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
package org.apache.iotdb.db.qp.physical;

import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.qp.Planner;
import org.apache.iotdb.db.qp.executor.PlanExecutor;
import org.apache.iotdb.db.qp.physical.crud.InsertMultiTabletsPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.qp.physical.crud.QueryPlan;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.tsfile.exception.filter.QueryFilterOptimizationException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.apache.iotdb.tsfile.utils.Binary;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InsertTabletMultiPlanTest extends InsertTabletPlanTest {

  private final Planner processor = new Planner();

  @Test
  public void testInsertMultiTabletPlan()
      throws QueryProcessException, MetadataException, InterruptedException,
          QueryFilterOptimizationException, StorageEngineException, IOException {
    long[] times = new long[] {110L, 111L, 112L, 113L};
    List<Integer> dataTypes = new ArrayList<>();
    dataTypes.add(TSDataType.DOUBLE.ordinal());
    dataTypes.add(TSDataType.FLOAT.ordinal());
    dataTypes.add(TSDataType.INT64.ordinal());
    dataTypes.add(TSDataType.INT32.ordinal());
    dataTypes.add(TSDataType.BOOLEAN.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());

    Object[] columns = new Object[6];
    columns[0] = new double[4];
    columns[1] = new float[4];
    columns[2] = new long[4];
    columns[3] = new int[4];
    columns[4] = new boolean[4];
    columns[5] = new Binary[4];

    for (int r = 0; r < 4; r++) {
      ((double[]) columns[0])[r] = 1.0;
      ((float[]) columns[1])[r] = 2;
      ((long[]) columns[2])[r] = 10000;
      ((int[]) columns[3])[r] = 100;
      ((boolean[]) columns[4])[r] = false;
      ((Binary[]) columns[5])[r] = new Binary("hh" + r);
    }

    List<InsertTabletPlan> insertTabletPlanList = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      InsertTabletPlan tabletPlan =
          new InsertTabletPlan(
              new PartialPath("root.multi.d" + i),
              new String[] {"s1", "s2", "s3", "s4", "s5", "s6"},
              dataTypes);
      tabletPlan.setTimes(times);
      tabletPlan.setColumns(columns);
      tabletPlan.setRowCount(times.length);
      insertTabletPlanList.add(tabletPlan);
    }
    PlanExecutor executor = new PlanExecutor();

    InsertMultiTabletsPlan insertMultiTabletsPlan =
        new InsertMultiTabletsPlan(insertTabletPlanList);

    executor.insertTablet(insertMultiTabletsPlan);
    QueryPlan queryPlan =
        (QueryPlan) processor.parseSQLToPhysicalPlan("select * from root.multi.**");
    QueryDataSet dataSet = executor.processQuery(queryPlan, EnvironmentUtils.TEST_QUERY_CONTEXT);
    Assert.assertEquals(60, dataSet.getPaths().size());
    while (dataSet.hasNext()) {
      RowRecord record = dataSet.next();
      Assert.assertEquals(60, record.getFields().size());
    }
  }

  @Test
  public void testInsertMultiTabletPlanParallel()
      throws QueryProcessException, MetadataException, StorageEngineException, IOException,
          InterruptedException, QueryFilterOptimizationException {
    long[] times =
        new long[] {
          110L, 111L, 112L, 113L, 110L, 111L, 112L, 113L, 110L, 111L, 112L, 113L, 110L, 111L, 112L,
        };
    List<Integer> dataTypes = new ArrayList<>();
    dataTypes.add(TSDataType.DOUBLE.ordinal());
    dataTypes.add(TSDataType.FLOAT.ordinal());
    dataTypes.add(TSDataType.INT64.ordinal());
    dataTypes.add(TSDataType.INT32.ordinal());
    dataTypes.add(TSDataType.BOOLEAN.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());

    Object[] columns = new Object[16];
    int size = (times).length;
    columns[0] = new double[size];
    columns[1] = new float[size];
    columns[2] = new long[size];
    columns[3] = new int[size];
    columns[4] = new boolean[size];
    columns[5] = new Binary[size];
    columns[6] = new Binary[size];
    columns[7] = new Binary[size];
    columns[8] = new Binary[size];
    columns[9] = new Binary[size];
    columns[10] = new Binary[size];
    columns[11] = new Binary[size];
    columns[12] = new Binary[size];
    columns[13] = new Binary[size];
    columns[14] = new Binary[size];
    columns[15] = new Binary[size];

    for (int r = 0; r < size; r++) {
      ((double[]) columns[0])[r] = 1.0;
      ((float[]) columns[1])[r] = 2;
      ((long[]) columns[2])[r] = 10000;
      ((int[]) columns[3])[r] = 100;
      ((boolean[]) columns[4])[r] = false;
      ((Binary[]) columns[5])[r] = new Binary("hh" + r);
      ((Binary[]) columns[6])[r] = new Binary("hh" + r);
      ((Binary[]) columns[7])[r] = new Binary("hh" + r);
      ((Binary[]) columns[8])[r] = new Binary("hh" + r);
      ((Binary[]) columns[9])[r] = new Binary("hh" + r);
      ((Binary[]) columns[10])[r] = new Binary("hh" + r);
      ((Binary[]) columns[11])[r] = new Binary("hh" + r);
      ((Binary[]) columns[12])[r] = new Binary("hh" + r);
      ((Binary[]) columns[13])[r] = new Binary("hh" + r);
      ((Binary[]) columns[14])[r] = new Binary("hh" + r);
      ((Binary[]) columns[15])[r] = new Binary("hh" + r);
    }

    List<InsertTabletPlan> insertTabletPlanList = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      InsertTabletPlan tabletPlan =
          new InsertTabletPlan(
              new PartialPath("root.multi" + i / 5 + ".d" + i),
              new String[] {
                "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "s12", "s13",
                "s14", "s15", "s16"
              },
              dataTypes);
      tabletPlan.setTimes(times);
      tabletPlan.setColumns(columns);
      tabletPlan.setRowCount(times.length);
      insertTabletPlanList.add(tabletPlan);
    }
    PlanExecutor executor = new PlanExecutor();

    InsertMultiTabletsPlan insertMultiTabletsPlan =
        new InsertMultiTabletsPlan(insertTabletPlanList);
    Assert.assertTrue(insertMultiTabletsPlan.isEnableMultiThreading());
    executor.insertTablet(insertMultiTabletsPlan);

    QueryPlan queryPlan = (QueryPlan) processor.parseSQLToPhysicalPlan("select * from root.**");
    QueryDataSet dataSet = executor.processQuery(queryPlan, EnvironmentUtils.TEST_QUERY_CONTEXT);
    Assert.assertEquals(160, dataSet.getPaths().size());
    while (dataSet.hasNext()) {
      RowRecord record = dataSet.next();
      Assert.assertEquals(160, record.getFields().size());
    }
  }

  @Test
  public void testHugeInsertMultiTabletPlan()
      throws QueryProcessException, MetadataException, StorageEngineException, IOException,
          InterruptedException, QueryFilterOptimizationException {
    // run this test case, can throw write_process_npe
    long[] times = new long[10000];
    for (int i = 0; i < times.length; i++) {
      times[i] = i;
    }
    List<Integer> dataTypes = new ArrayList<>();
    dataTypes.add(TSDataType.DOUBLE.ordinal());
    dataTypes.add(TSDataType.FLOAT.ordinal());
    dataTypes.add(TSDataType.INT64.ordinal());
    dataTypes.add(TSDataType.INT32.ordinal());
    dataTypes.add(TSDataType.BOOLEAN.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());
    dataTypes.add(TSDataType.TEXT.ordinal());

    Object[] columns = new Object[16];
    int size = (times).length;
    columns[0] = new double[size];
    columns[1] = new float[size];
    columns[2] = new long[size];
    columns[3] = new int[size];
    columns[4] = new boolean[size];
    columns[5] = new Binary[size];
    columns[6] = new Binary[size];
    columns[7] = new Binary[size];
    columns[8] = new Binary[size];
    columns[9] = new Binary[size];
    columns[10] = new Binary[size];
    columns[11] = new Binary[size];
    columns[12] = new Binary[size];
    columns[13] = new Binary[size];
    columns[14] = new Binary[size];
    columns[15] = new Binary[size];

    for (int r = 0; r < size; r++) {
      ((double[]) columns[0])[r] = 1.0;
      ((float[]) columns[1])[r] = 2;
      ((long[]) columns[2])[r] = 10000;
      ((int[]) columns[3])[r] = 100;
      ((boolean[]) columns[4])[r] = false;
      ((Binary[]) columns[5])[r] = new Binary("hh" + r);
      ((Binary[]) columns[6])[r] = new Binary("hh" + r);
      ((Binary[]) columns[7])[r] = new Binary("hh" + r);
      ((Binary[]) columns[8])[r] = new Binary("hh" + r);
      ((Binary[]) columns[9])[r] = new Binary("hh" + r);
      ((Binary[]) columns[10])[r] = new Binary("hh" + r);
      ((Binary[]) columns[11])[r] = new Binary("hh" + r);
      ((Binary[]) columns[12])[r] = new Binary("hh" + r);
      ((Binary[]) columns[13])[r] = new Binary("hh" + r);
      ((Binary[]) columns[14])[r] = new Binary("hh" + r);
      ((Binary[]) columns[15])[r] = new Binary("hh" + r);
    }

    List<InsertTabletPlan> insertTabletPlanList = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      InsertTabletPlan tabletPlan =
          new InsertTabletPlan(
              new PartialPath("root.multi" + i / 20 + ".d" + i),
              new String[] {
                "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "s12", "s13",
                "s14", "s15", "s16"
              },
              dataTypes);
      tabletPlan.setTimes(times);
      tabletPlan.setColumns(columns);
      tabletPlan.setRowCount(times.length);
      insertTabletPlanList.add(tabletPlan);
    }
    PlanExecutor executor = new PlanExecutor();

    InsertMultiTabletsPlan insertMultiTabletsPlan =
        new InsertMultiTabletsPlan(insertTabletPlanList);

    executor.insertTablet(insertMultiTabletsPlan);

    for (int i = 0; i < 1000; i++) {
      QueryPlan queryPlan =
          (QueryPlan)
              processor.parseSQLToPhysicalPlan("select * from root.multi" + i / 20 + ".d" + i);
      QueryDataSet dataSet = executor.processQuery(queryPlan, EnvironmentUtils.TEST_QUERY_CONTEXT);
      Assert.assertEquals(16, dataSet.getPaths().size());
      while (dataSet.hasNext()) {
        RowRecord record = dataSet.next();
        Assert.assertEquals(16, record.getFields().size());
      }
    }
  }
}
