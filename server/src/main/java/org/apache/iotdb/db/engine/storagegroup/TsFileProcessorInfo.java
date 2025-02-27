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
package org.apache.iotdb.db.engine.storagegroup;

import org.apache.iotdb.db.service.metrics.Metric;
import org.apache.iotdb.db.service.metrics.MetricsService;
import org.apache.iotdb.db.service.metrics.Tag;
import org.apache.iotdb.metrics.config.MetricConfigDescriptor;
import org.apache.iotdb.metrics.utils.MetricLevel;

/** The TsFileProcessorInfo records the memory cost of this TsFileProcessor. */
public class TsFileProcessorInfo {

  /** Once tspInfo updated, report to storageGroupInfo that this TSP belongs to. */
  private StorageGroupInfo storageGroupInfo;

  /** memory occupation of unsealed TsFileResource, ChunkMetadata, WAL */
  private long memCost;

  public TsFileProcessorInfo(StorageGroupInfo storageGroupInfo) {
    this.storageGroupInfo = storageGroupInfo;
    this.memCost = 0L;
  }

  /** called in each insert */
  public void addTSPMemCost(long cost) {
    memCost += cost;
    storageGroupInfo.addStorageGroupMemCost(cost);
    if (MetricConfigDescriptor.getInstance().getMetricConfig().getEnableMetric()) {
      MetricsService.getInstance()
          .getMetricManager()
          .getOrCreateGauge(
              Metric.MEM.toString(),
              MetricLevel.IMPORTANT,
              Tag.NAME.toString(),
              "chunkMetaData_" + storageGroupInfo.getDataRegion().getLogicalStorageGroupName())
          .incr(cost);
    }
  }

  /** called when meet exception */
  public void releaseTSPMemCost(long cost) {
    storageGroupInfo.releaseStorageGroupMemCost(cost);
    memCost -= cost;
    if (MetricConfigDescriptor.getInstance().getMetricConfig().getEnableMetric()) {
      MetricsService.getInstance()
          .getMetricManager()
          .getOrCreateGauge(
              Metric.MEM.toString(),
              MetricLevel.IMPORTANT,
              Tag.NAME.toString(),
              "chunkMetaData_" + storageGroupInfo.getDataRegion().getLogicalStorageGroupName())
          .decr(cost);
    }
  }

  /** called when closing TSP */
  public void clear() {
    storageGroupInfo.releaseStorageGroupMemCost(memCost);
    if (MetricConfigDescriptor.getInstance().getMetricConfig().getEnableMetric()) {
      MetricsService.getInstance()
          .getMetricManager()
          .getOrCreateGauge(
              Metric.MEM.toString(),
              MetricLevel.IMPORTANT,
              Tag.NAME.toString(),
              "chunkMetaData_" + storageGroupInfo.getDataRegion().getLogicalStorageGroupName())
          .decr(memCost);
    }
    memCost = 0L;
  }
}
