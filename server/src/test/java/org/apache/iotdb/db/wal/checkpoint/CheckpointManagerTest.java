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
package org.apache.iotdb.db.wal.checkpoint;

import org.apache.iotdb.db.engine.memtable.PrimitiveMemTable;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.db.wal.io.CheckpointReader;
import org.apache.iotdb.db.wal.io.CheckpointWriter;
import org.apache.iotdb.db.wal.recover.CheckpointRecoverUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class CheckpointManagerTest {
  private static final String identifier = String.valueOf(Integer.MAX_VALUE);
  private static final String logDirectory = "wal-test";
  private CheckpointManager checkpointManager;

  @Before
  public void setUp() throws Exception {
    EnvironmentUtils.cleanDir(logDirectory);
    checkpointManager = new CheckpointManager(identifier, logDirectory);
  }

  @After
  public void tearDown() throws Exception {
    checkpointManager.close();
    EnvironmentUtils.cleanDir(logDirectory);
  }

  @Test
  public void testNewFile() {
    Checkpoint initCheckpoint =
        new Checkpoint(CheckpointType.GLOBAL_MEMORY_TABLE_INFO, Collections.emptyList());
    List<Checkpoint> expectedCheckpoints = Collections.singletonList(initCheckpoint);
    CheckpointReader checkpointReader =
        new CheckpointReader(
            new File(logDirectory + File.separator + CheckpointWriter.getLogFileName(0)));
    List<Checkpoint> actualCheckpoints = checkpointReader.readAll();
    assertEquals(expectedCheckpoints, actualCheckpoints);
  }

  @Test
  public void testConcurrentWrite() throws Exception {
    // start write threads to write concurrently
    int threadsNum = 5;
    ExecutorService executorService = Executors.newFixedThreadPool(threadsNum);
    List<Future<Void>> futures = new ArrayList<>();
    Map<Integer, MemTableInfo> expectedMemTableId2Info = new ConcurrentHashMap<>();
    Map<Integer, Integer> versionId2memTableId = new ConcurrentHashMap<>();
    // create 10 memTables, and flush the first 5 of them
    int memTablesNum = 10;
    for (int i = 0; i < memTablesNum; ++i) {
      int versionId = i;
      Callable<Void> writeTask =
          () -> {
            String tsFilePath = logDirectory + File.separator + versionId + ".tsfile";
            MemTableInfo memTableInfo =
                new MemTableInfo(new PrimitiveMemTable(), tsFilePath, versionId);
            versionId2memTableId.put(versionId, memTableInfo.getMemTableId());
            checkpointManager.makeCreateMemTableCP(memTableInfo);
            if (versionId < memTablesNum / 2) {
              checkpointManager.makeFlushMemTableCP(versionId2memTableId.get(versionId));
            } else {
              expectedMemTableId2Info.put(memTableInfo.getMemTableId(), memTableInfo);
            }
            return null;
          };
      Future<Void> future = executorService.submit(writeTask);
      futures.add(future);
    }
    // wait until all write tasks are done
    for (Future<Void> future : futures) {
      future.get();
    }
    // check first valid version id
    assertEquals(memTablesNum / 2, checkpointManager.getFirstValidWALVersionId());
    // recover info from checkpoint file
    Map<Integer, MemTableInfo> actualMemTableId2Info =
        CheckpointRecoverUtils.recoverMemTableInfo(new File(logDirectory));
    assertEquals(expectedMemTableId2Info, actualMemTableId2Info);
  }

  @Test
  public void testTriggerLogRoller() {
    // create memTables until reach LOG_SIZE_LIMIT, and flush the first 5 of them
    int size = 0;
    int versionId = 0;
    Map<Integer, MemTableInfo> expectedMemTableId2Info = new HashMap<>();
    Map<Integer, Integer> versionId2memTableId = new HashMap<>();
    while (size < CheckpointManager.LOG_SIZE_LIMIT) {
      ++versionId;
      String tsFilePath = logDirectory + File.separator + versionId + ".tsfile";
      MemTableInfo memTableInfo = new MemTableInfo(new PrimitiveMemTable(), tsFilePath, versionId);
      versionId2memTableId.put(versionId, memTableInfo.getMemTableId());
      Checkpoint checkpoint =
          new Checkpoint(
              CheckpointType.CREATE_MEMORY_TABLE, Collections.singletonList(memTableInfo));
      size += checkpoint.serializedSize();
      checkpointManager.makeCreateMemTableCP(memTableInfo);
      if (versionId < 5) {
        checkpoint =
            new Checkpoint(
                CheckpointType.FLUSH_MEMORY_TABLE, Collections.singletonList(memTableInfo));
        size += checkpoint.serializedSize();
        checkpointManager.makeFlushMemTableCP(versionId2memTableId.get(versionId));
      } else {
        expectedMemTableId2Info.put(memTableInfo.getMemTableId(), memTableInfo);
      }
    }
    // check first valid version id
    assertEquals(5, checkpointManager.getFirstValidWALVersionId());
    // check checkpoint files
    assertFalse(
        new File(logDirectory + File.separator + CheckpointWriter.getLogFileName(0)).exists());
    assertTrue(
        new File(logDirectory + File.separator + CheckpointWriter.getLogFileName(1)).exists());
    // recover info from checkpoint file
    Map<Integer, MemTableInfo> actualMemTableId2Info =
        CheckpointRecoverUtils.recoverMemTableInfo(new File(logDirectory));
    assertEquals(expectedMemTableId2Info, actualMemTableId2Info);
  }
}
