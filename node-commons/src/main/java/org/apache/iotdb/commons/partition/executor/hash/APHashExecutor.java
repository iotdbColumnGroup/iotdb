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
package org.apache.iotdb.commons.partition.executor.hash;

import org.apache.iotdb.commons.partition.SeriesPartitionSlot;
import org.apache.iotdb.commons.partition.executor.SeriesPartitionExecutor;

public class APHashExecutor extends SeriesPartitionExecutor {

  public APHashExecutor(int deviceGroupCount) {
    super(deviceGroupCount);
  }

  @Override
  public SeriesPartitionSlot getSeriesPartitionSlot(String device) {
    int hash = 0;

    for (int i = 0; i < device.length(); i++) {
      if ((i & 1) == 0) {
        hash ^= ((hash << 7) ^ (int) device.charAt(i) ^ (hash >> 3));
      } else {
        hash ^= (~((hash << 11) ^ (int) device.charAt(i) ^ (hash >> 5)));
      }
    }
    hash &= Integer.MAX_VALUE;

    return new SeriesPartitionSlot(hash % seriesPartitionSlotNum);
  }
}
