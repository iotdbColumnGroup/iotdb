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

package org.apache.iotdb.cluster.server.raft;

import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.utils.ClusterUtils;
import org.apache.iotdb.commons.concurrent.ThreadName;
import org.apache.iotdb.commons.service.ServiceType;
import org.apache.iotdb.commons.service.ThriftService;
import org.apache.iotdb.commons.service.ThriftServiceThread;

public class DataRaftHeartBeatService extends AbstractDataRaftService
    implements DataRaftHeartBeatServiceMBean {

  private DataRaftHeartBeatService() {}

  @Override
  public ThriftService getImplementation() {
    return DataRaftHeartBeatServiceHolder.INSTANCE;
  }

  @Override
  public ServiceType getID() {
    return ServiceType.CLUSTER_DATA_HEART_BEAT_RPC_SERVICE;
  }

  @Override
  public void initThriftServiceThread() throws IllegalAccessException {
    initThriftServiceThread(
        ThreadName.CLUSTER_DATA_HEARTBEAT_RPC_SERVICE.getName(),
        ThreadName.CLUSTER_DATA_HEARTBEAT_RPC_CLIENT.getName(),
        ThriftServiceThread.ServerType.HSHA);
  }

  @Override
  public int getBindPort() {
    return ClusterDescriptor.getInstance().getConfig().getInternalDataPort()
        + ClusterUtils.DATA_HEARTBEAT_PORT_OFFSET;
  }

  public static DataRaftHeartBeatService getInstance() {
    return DataRaftHeartBeatServiceHolder.INSTANCE;
  }

  private static class DataRaftHeartBeatServiceHolder {

    private static final DataRaftHeartBeatService INSTANCE = new DataRaftHeartBeatService();

    private DataRaftHeartBeatServiceHolder() {}
  }
}
