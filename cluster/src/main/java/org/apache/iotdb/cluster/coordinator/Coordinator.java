/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.coordinator;

import org.apache.iotdb.cluster.ClusterIoTDB;
import org.apache.iotdb.cluster.client.async.AsyncDataClient;
import org.apache.iotdb.cluster.client.sync.SyncDataClient;
import org.apache.iotdb.cluster.config.ClusterConstant;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.exception.ChangeMembershipException;
import org.apache.iotdb.cluster.exception.CheckConsistencyException;
import org.apache.iotdb.cluster.exception.UnknownLogTypeException;
import org.apache.iotdb.cluster.exception.UnsupportedPlanException;
import org.apache.iotdb.cluster.log.Log;
import org.apache.iotdb.cluster.metadata.CSchemaProcessor;
import org.apache.iotdb.cluster.partition.PartitionGroup;
import org.apache.iotdb.cluster.query.ClusterPlanRouter;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.RaftNode;
import org.apache.iotdb.cluster.server.member.MetaGroupMember;
import org.apache.iotdb.cluster.server.monitor.Timer;
import org.apache.iotdb.cluster.utils.PartitionUtils;
import org.apache.iotdb.cluster.utils.StatusUtils;
import org.apache.iotdb.common.rpc.thrift.EndPoint;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.commons.utils.TestOnly;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.PathNotExistException;
import org.apache.iotdb.db.exception.metadata.StorageGroupNotSetException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.qp.physical.BatchPlan;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertMultiTabletsPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertRowsPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateAlignedTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateMultiTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.DeleteTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.SetTemplatePlan;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.TSStatusCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/** Coordinator of client non-query request */
public class Coordinator {

  private static final Logger logger = LoggerFactory.getLogger(Coordinator.class);

  private MetaGroupMember metaGroupMember;

  private String name;
  private Node thisNode;
  /** router calculates the partition groups that a partitioned plan should be sent to */
  private ClusterPlanRouter router;

  private static final String MSG_MULTIPLE_ERROR =
      "The following errors occurred when executing "
          + "the query, please retry or contact the DBA: ";

  @TestOnly
  public Coordinator(MetaGroupMember metaGroupMember) {
    linkMetaGroupMember(metaGroupMember);
  }

  public Coordinator() {}

  public void linkMetaGroupMember(MetaGroupMember metaGroupMember) {
    this.metaGroupMember = metaGroupMember;
    if (metaGroupMember.getCoordinator() != null && metaGroupMember.getCoordinator() != this) {
      logger.warn("MetadataGroupMember linked inconsistent Coordinator, will correct it.");
      metaGroupMember.setCoordinator(this);
    }
    this.name = metaGroupMember.getName();
    this.thisNode = metaGroupMember.getThisNode();
  }

  public void setRouter(ClusterPlanRouter router) {
    this.router = router;
  }

  /**
   * Execute a non-query plan. According to the type of the plan, the plan will be executed on all
   * nodes (like timeseries deletion) or the nodes that belong to certain groups (like data
   * ingestion).
   *
   * @param plan a non-query plan.
   */
  public TSStatus executeNonQueryPlan(PhysicalPlan plan) {
    TSStatus result;
    long startTime = Timer.Statistic.COORDINATOR_EXECUTE_NON_QUERY.getOperationStartTime();
    if (PartitionUtils.isLocalNonQueryPlan(plan)) {
      // run locally
      result = executeNonQueryLocally(plan);
    } else if (PartitionUtils.isGlobalMetaPlan(plan)) {
      // forward the plan to all meta group nodes
      result = metaGroupMember.processNonPartitionedMetaPlan(plan);
    } else if (PartitionUtils.isGlobalDataPlan(plan)) {
      // forward the plan to all data group nodes
      result = processNonPartitionedDataPlan(plan);
    } else {
      // split the plan and forward them to some PartitionGroups
      try {
        result = processPartitionedPlan(plan);
      } catch (UnsupportedPlanException e) {
        return StatusUtils.getStatus(StatusUtils.UNSUPPORTED_OPERATION, e.getMessage());
      }
    }
    Timer.Statistic.COORDINATOR_EXECUTE_NON_QUERY.calOperationCostTimeFromStart(startTime);
    return result;
  }

  /** execute a non-query plan that is not necessary to be executed on other nodes. */
  private TSStatus executeNonQueryLocally(PhysicalPlan plan) {
    boolean execRet;
    try {
      execRet = metaGroupMember.getLocalExecutor().processNonQuery(plan);
    } catch (QueryProcessException e) {
      if (e.getErrorCode() != TSStatusCode.INTERNAL_SERVER_ERROR.getStatusCode()) {
        logger.debug("meet error while processing non-query. ", e);
      } else {
        logger.warn("meet error while processing non-query. ", e);
      }
      return RpcUtils.getStatus(e.getErrorCode(), e.getMessage());
    } catch (Exception e) {
      logger.error("{}: server Internal Error: ", IoTDBConstant.GLOBAL_DB_NAME, e);
      return RpcUtils.getStatus(TSStatusCode.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    return execRet
        ? RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS, "Execute successfully")
        : RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR);
  }

  /**
   * A non-partitioned plan (like DeleteData) should be executed on all data group nodes, so the
   * DataGroupLeader should take the responsible to make sure that every node receives the plan.
   * Thus the plan will be processed locally only by the DataGroupLeader and forwarded by non-leader
   * nodes.
   */
  private TSStatus processNonPartitionedDataPlan(PhysicalPlan plan) {
    try {
      if (plan instanceof DeleteTimeSeriesPlan) {
        // as delete related plans may have abstract paths (paths with wildcards), we convert
        // them to full paths so the executor nodes will not need to query the metadata holders,
        // eliminating the risk that when they are querying the metadata holders, the timeseries
        // has already been deleted
        ((CSchemaProcessor) IoTDB.schemaProcessor).convertToFullPaths(plan);
      } else {
        // function convertToFullPaths has already sync leader
        metaGroupMember.syncLeaderWithConsistencyCheck(true);
      }
    } catch (PathNotExistException e) {
      if (plan.getPaths().isEmpty()) {
        // only reports an error when there is no matching path
        return StatusUtils.getStatus(StatusUtils.TIMESERIES_NOT_EXIST_ERROR, e.getMessage());
      }
    } catch (CheckConsistencyException e) {
      logger.debug(
          "Forwarding global data plan {} to meta leader {}", plan, metaGroupMember.getLeader());
      metaGroupMember.waitLeader();
      return metaGroupMember.forwardPlan(plan, metaGroupMember.getLeader(), null);
    }
    try {
      createSchemaIfNecessary(plan);
    } catch (MetadataException | CheckConsistencyException e) {
      logger.error("{}: Cannot find storage groups for {}", name, plan);
      return StatusUtils.NO_STORAGE_GROUP;
    }
    List<PartitionGroup> globalGroups = metaGroupMember.getPartitionTable().getGlobalGroups();
    logger.debug("Forwarding global data plan {} to {} groups", plan, globalGroups.size());
    return forwardPlan(globalGroups, plan);
  }

  public void createSchemaIfNecessary(PhysicalPlan plan)
      throws MetadataException, CheckConsistencyException {
    if (plan instanceof SetTemplatePlan) {
      try {
        IoTDB.schemaProcessor.getBelongedStorageGroup(
            new PartialPath(((SetTemplatePlan) plan).getPrefixPath()));
      } catch (IllegalPathException e) {
        // the plan has been checked
      } catch (StorageGroupNotSetException e) {
        ((CSchemaProcessor) IoTDB.schemaProcessor).createSchema(plan);
      }
    }
  }

  /**
   * A partitioned plan (like batch insertion) will be split into several sub-plans, each belongs to
   * a data group. And these sub-plans will be sent to and executed on the corresponding groups
   * separately.
   */
  public TSStatus processPartitionedPlan(PhysicalPlan plan) throws UnsupportedPlanException {
    logger.debug("{}: Received a partitioned plan {}", name, plan);
    if (metaGroupMember.getPartitionTable() == null) {
      logger.debug("{}: Partition table is not ready", name);
      return StatusUtils.PARTITION_TABLE_NOT_READY;
    }

    if (!checkPrivilegeForBatchExecution(plan)) {
      return concludeFinalStatus(
          plan, plan.getPaths().size(), true, false, false, null, Collections.emptyList());
    }

    // split the plan into sub-plans that each only involve one data group
    Map<PhysicalPlan, PartitionGroup> planGroupMap;
    try {
      planGroupMap = splitPlan(plan);
    } catch (CheckConsistencyException checkConsistencyException) {
      return StatusUtils.getStatus(
          StatusUtils.CONSISTENCY_FAILURE, checkConsistencyException.getMessage());
    }

    // the storage group is not found locally
    if (planGroupMap == null || planGroupMap.isEmpty()) {
      if ((plan instanceof InsertPlan
              || plan instanceof CreateTimeSeriesPlan
              || plan instanceof CreateAlignedTimeSeriesPlan
              || plan instanceof CreateMultiTimeSeriesPlan)
          && ClusterDescriptor.getInstance().getConfig().isEnableAutoCreateSchema()) {

        logger.debug("{}: No associated storage group found for {}, auto-creating", name, plan);
        try {
          ((CSchemaProcessor) IoTDB.schemaProcessor).createSchema(plan);
          return processPartitionedPlan(plan);
        } catch (MetadataException | CheckConsistencyException e) {
          logger.error(
              String.format("Failed to set storage group or create timeseries, because %s", e));
        }
      }
      logger.error("{}: Cannot find storage groups for {}", name, plan);
      return StatusUtils.NO_STORAGE_GROUP;
    }
    logger.debug("{}: The data groups of {} are {}", name, plan, planGroupMap);
    return forwardPlan(planGroupMap, plan);
  }

  /**
   * check if batch execution plan has privilege on any sg
   *
   * @param plan
   * @return
   */
  private boolean checkPrivilegeForBatchExecution(PhysicalPlan plan) {
    if (plan instanceof BatchPlan) {
      return ((BatchPlan) plan).getResults().size() != plan.getPaths().size();
    } else {
      return true;
    }
  }

  /**
   * Forward a plan to all DataGroupMember groups. Only when all nodes time out, will a TIME_OUT be
   * returned. The error messages from each group (if any) will be compacted into one string.
   *
   * @param partitionGroups
   * @param plan
   */
  private TSStatus forwardPlan(List<PartitionGroup> partitionGroups, PhysicalPlan plan) {
    // the error codes from the groups that cannot execute the plan
    TSStatus status;
    List<String> errorCodePartitionGroups = new ArrayList<>();
    for (PartitionGroup partitionGroup : partitionGroups) {
      if (partitionGroup.contains(thisNode)) {
        // the query should be handled by a group the local node is in, handle it with in the group
        status =
            metaGroupMember
                .getLocalDataMember(partitionGroup.getHeader())
                .executeNonQueryPlan(plan);
        logger.debug(
            "Execute {} in a local group of {} with status {}",
            plan,
            partitionGroup.getHeader(),
            status);
      } else {
        // forward the query to the group that should handle it
        status = forwardPlan(plan, partitionGroup);
        logger.debug(
            "Forward {} to a remote group of {} with status {}",
            plan,
            partitionGroup.getHeader(),
            status);
      }
      if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()
          && !(plan instanceof SetTemplatePlan
              && status.getCode() == TSStatusCode.DUPLICATED_TEMPLATE.getStatusCode())
          && !(plan instanceof DeleteTimeSeriesPlan
              && status.getCode() == TSStatusCode.TIMESERIES_NOT_EXIST.getStatusCode())) {
        // execution failed, record the error message
        errorCodePartitionGroups.add(
            String.format(
                "[%s@%s:%s]", status.getCode(), partitionGroup.getHeader(), status.getMessage()));
      }
    }
    if (errorCodePartitionGroups.isEmpty()) {
      status = StatusUtils.OK;
    } else {
      status =
          StatusUtils.getStatus(
              StatusUtils.EXECUTE_STATEMENT_ERROR, MSG_MULTIPLE_ERROR + errorCodePartitionGroups);
    }
    logger.debug("{}: executed {} with answer {}", name, plan, status);
    return status;
  }

  public void sendLogToAllDataGroups(Log log) throws ChangeMembershipException {
    if (logger.isDebugEnabled()) {
      logger.debug("Send log {} to all data groups: start", log);
    }

    Map<PhysicalPlan, PartitionGroup> planGroupMap = router.splitAndRouteChangeMembershipLog(log);
    List<String> errorCodePartitionGroups = new CopyOnWriteArrayList<>();
    CountDownLatch counter = new CountDownLatch(planGroupMap.size());
    for (Map.Entry<PhysicalPlan, PartitionGroup> entry : planGroupMap.entrySet()) {
      metaGroupMember
          .getAppendLogThreadPool()
          .submit(() -> forwardChangeMembershipPlan(log, entry, errorCodePartitionGroups, counter));
    }
    try {
      counter.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChangeMembershipException(
          String.format("Can not wait all data groups to apply %s", log));
    }
    if (!errorCodePartitionGroups.isEmpty()) {
      throw new ChangeMembershipException(
          String.format("Apply %s failed with status {%s}", log, errorCodePartitionGroups));
    }
    if (logger.isDebugEnabled()) {
      logger.debug("Send log {} to all data groups: end", log);
    }
  }

  private void forwardChangeMembershipPlan(
      Log log,
      Map.Entry<PhysicalPlan, PartitionGroup> entry,
      List<String> errorCodePartitionGroups,
      CountDownLatch counter) {
    int retryTime = 0;
    long startTime = System.currentTimeMillis();
    try {
      while (true) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Send change membership log {} to data group {}, retry time: {}",
              log,
              entry.getValue(),
              retryTime);
        }
        try {
          TSStatus status = forwardToSingleGroup(entry);
          if (status.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
            if (logger.isDebugEnabled()) {
              logger.debug(
                  "Success to send change membership log {} to data group {}",
                  log,
                  entry.getValue());
            }
            return;
          }
          long cost = System.currentTimeMillis() - startTime;
          if (cost > ClusterDescriptor.getInstance().getConfig().getWriteOperationTimeoutMS()) {
            errorCodePartitionGroups.add(
                String.format(
                    "Forward change membership log %s to data group %s", log, entry.getValue()));
            return;
          }
          Thread.sleep(ClusterConstant.RETRY_WAIT_TIME_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          errorCodePartitionGroups.add(e.getMessage());
          return;
        }
        retryTime++;
      }
    } finally {
      counter.countDown();
    }
  }

  /** split a plan into several sub-plans, each belongs to only one data group. */
  private Map<PhysicalPlan, PartitionGroup> splitPlan(PhysicalPlan plan)
      throws UnsupportedPlanException, CheckConsistencyException {
    Map<PhysicalPlan, PartitionGroup> planGroupMap = null;
    try {
      planGroupMap = router.splitAndRoutePlan(plan);
    } catch (StorageGroupNotSetException e) {
      // synchronize with the leader to see if this node has unpulled storage groups
      metaGroupMember.syncLeaderWithConsistencyCheck(true);
      try {
        planGroupMap = router.splitAndRoutePlan(plan);
      } catch (MetadataException | UnknownLogTypeException ex) {
        // ignore
      }
    } catch (MetadataException | UnknownLogTypeException e) {
      logger.error("Cannot route plan {}", plan, e);
    }
    logger.debug("route plan {} with partitionGroup {}", plan, planGroupMap);
    return planGroupMap;
  }

  /**
   * Forward plans to the DataGroupMember of one node in the corresponding group. Only when all
   * nodes time out, will a TIME_OUT be returned.
   *
   * @param planGroupMap sub-plan -> belong data group pairs
   */
  private TSStatus forwardPlan(Map<PhysicalPlan, PartitionGroup> planGroupMap, PhysicalPlan plan) {
    // the error codes from the groups that cannot execute the plan
    TSStatus status;
    // need to create substatus for multiPlan

    // InsertTabletPlan, InsertMultiTabletsPlan, InsertRowsPlan and CreateMultiTimeSeriesPlan
    // contains many rows,
    // each will correspond to a TSStatus as its execution result,
    // as the plan is split and the sub-plans may have interleaving ranges,
    // we must assure that each TSStatus is placed to the right position
    // e.g., an InsertTabletPlan contains 3 rows, row1 and row3 belong to NodeA and row2
    // belongs to NodeB, when NodeA returns a success while NodeB returns a failure, the
    // failure and success should be placed into proper positions in TSStatus.subStatus
    if (plan instanceof InsertMultiTabletsPlan
        || plan instanceof CreateMultiTimeSeriesPlan
        || plan instanceof InsertRowsPlan) {
      status = forwardMultiSubPlan(planGroupMap, plan);
    } else if (planGroupMap.size() == 1) {
      status = forwardToSingleGroup(planGroupMap.entrySet().iterator().next());
    } else {
      status = forwardToMultipleGroup(planGroupMap);
    }
    if (status.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && status.isSetRedirectNode()) {
      status.setCode(TSStatusCode.NEED_REDIRECTION.getStatusCode());
    }
    logger.debug("{}: executed {} with answer {}", name, plan, status);
    return status;
  }

  private TSStatus forwardToSingleGroup(Map.Entry<PhysicalPlan, PartitionGroup> entry) {
    TSStatus result;
    if (entry.getValue().contains(thisNode)) {
      // the query should be handled by a group the local node is in, handle it with in the group
      long startTime =
          Timer.Statistic.META_GROUP_MEMBER_EXECUTE_NON_QUERY_IN_LOCAL_GROUP
              .getOperationStartTime();
      result =
          metaGroupMember
              .getLocalDataMember(entry.getValue().getHeader())
              .executeNonQueryPlan(entry.getKey());
      logger.debug(
          "Execute {} in a local group of {}, {}",
          entry.getKey(),
          entry.getValue().getHeader(),
          result);
      Timer.Statistic.META_GROUP_MEMBER_EXECUTE_NON_QUERY_IN_LOCAL_GROUP
          .calOperationCostTimeFromStart(startTime);
    } else {
      // forward the query to the group that should handle it
      long startTime =
          Timer.Statistic.META_GROUP_MEMBER_EXECUTE_NON_QUERY_IN_REMOTE_GROUP
              .getOperationStartTime();
      logger.debug(
          "Forward {} to a remote group of {}", entry.getKey(), entry.getValue().getHeader());
      result = forwardPlan(entry.getKey(), entry.getValue());
      Timer.Statistic.META_GROUP_MEMBER_EXECUTE_NON_QUERY_IN_REMOTE_GROUP
          .calOperationCostTimeFromStart(startTime);
    }
    return result;
  }

  /**
   * forward each sub-plan to its corresponding data group, if some groups goes wrong, the error
   * messages from each group will be compacted into one string.
   *
   * @param planGroupMap sub-plan -> data group pairs
   */
  private TSStatus forwardToMultipleGroup(Map<PhysicalPlan, PartitionGroup> planGroupMap) {
    List<String> errorCodePartitionGroups = new ArrayList<>();
    TSStatus tmpStatus;
    boolean allRedirect = true;
    EndPoint endPoint = null;
    for (Map.Entry<PhysicalPlan, PartitionGroup> entry : planGroupMap.entrySet()) {
      tmpStatus = forwardToSingleGroup(entry);
      if (tmpStatus.isSetRedirectNode()) {
        endPoint = tmpStatus.getRedirectNode();
      } else {
        allRedirect = false;
      }
      if (tmpStatus.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        // execution failed, record the error message
        errorCodePartitionGroups.add(
            String.format(
                "[%s@%s:%s]",
                tmpStatus.getCode(), entry.getValue().getHeader(), tmpStatus.getMessage()));
      }
    }
    TSStatus status;
    if (errorCodePartitionGroups.isEmpty()) {
      if (allRedirect) {
        status = StatusUtils.getStatus(TSStatusCode.NEED_REDIRECTION, endPoint);
      } else {
        status = StatusUtils.OK;
      }
    } else {
      status =
          StatusUtils.getStatus(
              StatusUtils.EXECUTE_STATEMENT_ERROR, MSG_MULTIPLE_ERROR + errorCodePartitionGroups);
    }
    return status;
  }

  /**
   * Forward each sub-plan to its belonging data group, and combine responses from the groups.
   *
   * @param planGroupMap sub-plan -> data group pairs
   */
  @SuppressWarnings("squid:S3776") // Suppress high Cognitive Complexity warning
  private TSStatus forwardMultiSubPlan(
      Map<PhysicalPlan, PartitionGroup> planGroupMap, PhysicalPlan parentPlan) {
    List<String> errorCodePartitionGroups = new ArrayList<>();
    TSStatus tmpStatus;
    TSStatus[] subStatus = null;
    boolean noFailure = true;
    boolean isBatchFailure = false;
    boolean isBatchRedirect = false;
    int totalRowNum = parentPlan.getPaths().size();
    // send sub-plans to each belonging data group and collect results
    for (Map.Entry<PhysicalPlan, PartitionGroup> entry : planGroupMap.entrySet()) {
      tmpStatus = forwardToSingleGroup(entry);
      logger.debug("{}: from {},{},{}", name, entry.getKey(), entry.getValue(), tmpStatus);
      noFailure = (tmpStatus.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) && noFailure;
      isBatchFailure =
          (tmpStatus.getCode() == TSStatusCode.MULTIPLE_ERROR.getStatusCode()) || isBatchFailure;
      if (tmpStatus.getCode() == TSStatusCode.MULTIPLE_ERROR.getStatusCode()
          || tmpStatus.isSetRedirectNode() && !(parentPlan instanceof CreateMultiTimeSeriesPlan)) {
        if (parentPlan instanceof InsertMultiTabletsPlan) {
          // the subStatus is the two-dimensional array,
          // The first dimension is the number of InsertTabletPlans,
          // and the second dimension is the number of rows per InsertTabletPlan
          totalRowNum = ((InsertMultiTabletsPlan) parentPlan).getTabletsSize();
        } else if (parentPlan instanceof CreateMultiTimeSeriesPlan) {
          totalRowNum = parentPlan.getPaths().size();
        } else if (parentPlan instanceof InsertRowsPlan) {
          totalRowNum = ((InsertRowsPlan) parentPlan).getRowCount();
        }

        if (subStatus == null) {
          subStatus = new TSStatus[totalRowNum];
          Arrays.fill(subStatus, RpcUtils.SUCCESS_STATUS);
        }
        // set the status from one group to the proper positions of the overall status
        if (parentPlan instanceof InsertMultiTabletsPlan) {
          InsertMultiTabletsPlan tmpMultiTabletPlan = ((InsertMultiTabletsPlan) entry.getKey());
          for (int i = 0; i < tmpMultiTabletPlan.getInsertTabletPlanList().size(); i++) {
            InsertTabletPlan tmpInsertTabletPlan = tmpMultiTabletPlan.getInsertTabletPlan(i);
            int parentIndex = tmpMultiTabletPlan.getParentIndex(i);
            int parentPlanRowCount = ((InsertMultiTabletsPlan) parentPlan).getRowCount(parentIndex);
            if (tmpStatus.getCode() == TSStatusCode.MULTIPLE_ERROR.getStatusCode()) {
              subStatus[parentIndex] = tmpStatus.subStatus.get(i);
              if (tmpStatus.subStatus.get(i).getCode()
                  == TSStatusCode.MULTIPLE_ERROR.getStatusCode()) {
                if (subStatus[parentIndex].subStatus == null) {
                  TSStatus[] tmpSubTsStatus = new TSStatus[parentPlanRowCount];
                  Arrays.fill(tmpSubTsStatus, RpcUtils.SUCCESS_STATUS);
                  subStatus[parentIndex].subStatus = Arrays.asList(tmpSubTsStatus);
                }
                TSStatus[] reorderTsStatus =
                    subStatus[parentIndex].subStatus.toArray(new TSStatus[] {});

                PartitionUtils.reordering(
                    tmpInsertTabletPlan,
                    reorderTsStatus,
                    tmpStatus.subStatus.get(i).subStatus.toArray(new TSStatus[] {}));
                subStatus[parentIndex].subStatus = Arrays.asList(reorderTsStatus);
              }
              if (tmpStatus.isSetRedirectNode()) {
                if (tmpStatus.isSetRedirectNode()
                    && tmpInsertTabletPlan.getMaxTime()
                        == ((InsertMultiTabletsPlan) parentPlan)
                            .getInsertTabletPlan(parentIndex)
                            .getMaxTime()) {
                  subStatus[parentIndex].setRedirectNode(tmpStatus.redirectNode);
                  isBatchRedirect = true;
                }
              }
            } else if (tmpStatus.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
              if (tmpStatus.isSetRedirectNode()
                  && tmpInsertTabletPlan.getMaxTime()
                      == ((InsertMultiTabletsPlan) parentPlan)
                          .getInsertTabletPlan(parentIndex)
                          .getMaxTime()) {
                subStatus[parentIndex] =
                    StatusUtils.getStatus(RpcUtils.SUCCESS_STATUS, tmpStatus.redirectNode);
                isBatchRedirect = true;
              }
            }
          }
        } else if (parentPlan instanceof CreateMultiTimeSeriesPlan) {
          CreateMultiTimeSeriesPlan subPlan = (CreateMultiTimeSeriesPlan) entry.getKey();
          for (int i = 0; i < subPlan.getIndexes().size(); i++) {
            subStatus[subPlan.getIndexes().get(i)] = tmpStatus.subStatus.get(i);
          }
        } else if (parentPlan instanceof InsertRowsPlan) {
          InsertRowsPlan subPlan = (InsertRowsPlan) entry.getKey();
          if (tmpStatus.getCode() == TSStatusCode.MULTIPLE_ERROR.getStatusCode()) {
            for (int i = 0; i < subPlan.getInsertRowPlanIndexList().size(); i++) {
              subStatus[subPlan.getInsertRowPlanIndexList().get(i)] = tmpStatus.subStatus.get(i);
              if (tmpStatus.isSetRedirectNode()) {
                subStatus[subPlan.getInsertRowPlanIndexList().get(i)].setRedirectNode(
                    tmpStatus.getRedirectNode());
                isBatchRedirect = true;
              }
            }
          } else if (tmpStatus.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
            if (tmpStatus.isSetRedirectNode()) {
              isBatchRedirect = true;
              TSStatus redirectStatus =
                  StatusUtils.getStatus(RpcUtils.SUCCESS_STATUS, tmpStatus.getRedirectNode());
              for (int i = 0; i < subPlan.getInsertRowPlanIndexList().size(); i++) {
                subStatus[subPlan.getInsertRowPlanIndexList().get(i)] = redirectStatus;
              }
            }
          }
        }
      }

      if (tmpStatus.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        // execution failed, record the error message
        errorCodePartitionGroups.add(
            String.format(
                "[%s@%s:%s:%s]",
                tmpStatus.getCode(),
                entry.getValue().getHeader(),
                tmpStatus.getMessage(),
                tmpStatus.subStatus));
      }
    }
    return concludeFinalStatus(
        parentPlan,
        totalRowNum,
        noFailure,
        isBatchRedirect,
        isBatchFailure,
        subStatus,
        errorCodePartitionGroups);
  }

  private TSStatus concludeFinalStatus(
      PhysicalPlan parentPlan,
      int totalRowNum,
      boolean noFailure,
      boolean isBatchRedirect,
      boolean isBatchFailure,
      TSStatus[] subStatus,
      List<String> errorCodePartitionGroups) {
    if (parentPlan instanceof InsertMultiTabletsPlan
        && !((InsertMultiTabletsPlan) parentPlan).getResults().isEmpty()) {
      if (subStatus == null) {
        subStatus = new TSStatus[totalRowNum];
        Arrays.fill(subStatus, RpcUtils.SUCCESS_STATUS);
      }
      noFailure = false;
      isBatchFailure = true;
      for (Map.Entry<Integer, TSStatus> integerTSStatusEntry :
          ((InsertMultiTabletsPlan) parentPlan).getResults().entrySet()) {
        subStatus[integerTSStatusEntry.getKey()] = integerTSStatusEntry.getValue();
      }
    }

    if (parentPlan instanceof CreateMultiTimeSeriesPlan
        && !((CreateMultiTimeSeriesPlan) parentPlan).getResults().isEmpty()) {
      if (subStatus == null) {
        subStatus = new TSStatus[totalRowNum];
        Arrays.fill(subStatus, RpcUtils.SUCCESS_STATUS);
      }
      noFailure = false;
      isBatchFailure = true;
      for (Map.Entry<Integer, TSStatus> integerTSStatusEntry :
          ((CreateMultiTimeSeriesPlan) parentPlan).getResults().entrySet()) {
        subStatus[integerTSStatusEntry.getKey()] = integerTSStatusEntry.getValue();
      }
    }

    if (parentPlan instanceof InsertRowsPlan
        && !((InsertRowsPlan) parentPlan).getResults().isEmpty()) {
      if (subStatus == null) {
        subStatus = new TSStatus[totalRowNum];
        Arrays.fill(subStatus, RpcUtils.SUCCESS_STATUS);
      }
      noFailure = false;
      isBatchFailure = true;
      for (Map.Entry<Integer, TSStatus> integerTSStatusEntry :
          ((InsertRowsPlan) parentPlan).getResults().entrySet()) {
        subStatus[integerTSStatusEntry.getKey()] = integerTSStatusEntry.getValue();
      }
    }

    TSStatus status;
    if (noFailure) {
      if (isBatchRedirect) {
        status = RpcUtils.getStatus(Arrays.asList(subStatus));
        status.setCode(TSStatusCode.NEED_REDIRECTION.getStatusCode());
      } else {
        status = StatusUtils.OK;
      }
    } else if (isBatchFailure) {
      status = RpcUtils.getStatus(Arrays.asList(subStatus));
    } else {
      status =
          StatusUtils.getStatus(
              StatusUtils.EXECUTE_STATEMENT_ERROR, MSG_MULTIPLE_ERROR + errorCodePartitionGroups);
    }
    return status;
  }

  /**
   * Forward a plan to the DataGroupMember of one node in the group. Only when all nodes time out,
   * will a TIME_OUT be returned.
   */
  private TSStatus forwardPlan(PhysicalPlan plan, PartitionGroup group) {
    for (Node node : group) {
      TSStatus status;
      try {
        // only data plans are partitioned, so it must be processed by its data server instead of
        // meta server
        if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
          status = forwardDataPlanAsync(plan, node, group.getHeader());
        } else {
          status = forwardDataPlanSync(plan, node, group.getHeader());
        }
      } catch (IOException e) {
        status = StatusUtils.getStatus(StatusUtils.EXECUTE_STATEMENT_ERROR, e.getMessage());
      }
      if (!StatusUtils.TIME_OUT.equals(status)) {
        if (!status.isSetRedirectNode()) {
          status.setRedirectNode(new EndPoint(node.getClientIp(), node.getClientPort()));
        }
        return status;
      } else {
        logger.warn("Forward {} to {} timed out", plan, node);
      }
    }
    logger.warn("Forward {} to {} timed out", plan, group);
    return StatusUtils.TIME_OUT;
  }

  /**
   * Forward a non-query plan to the data port of "receiver"
   *
   * @param plan a non-query plan
   * @param header to determine which DataGroupMember of "receiver" will process the request.
   * @return a TSStatus indicating if the forwarding is successful.
   */
  private TSStatus forwardDataPlanAsync(PhysicalPlan plan, Node receiver, RaftNode header)
      throws IOException {
    AsyncDataClient client =
        ClusterIoTDB.getInstance()
            .getAsyncDataClient(receiver, ClusterConstant.getWriteOperationTimeoutMS());
    return this.metaGroupMember.forwardPlanAsync(plan, receiver, header, client);
  }

  private TSStatus forwardDataPlanSync(PhysicalPlan plan, Node receiver, RaftNode header)
      throws IOException {
    SyncDataClient client =
        ClusterIoTDB.getInstance()
            .getSyncDataClient(receiver, ClusterConstant.getWriteOperationTimeoutMS());
    return this.metaGroupMember.forwardPlanSync(plan, receiver, header, client);
  }

  public Node getThisNode() {
    return thisNode;
  }
}
