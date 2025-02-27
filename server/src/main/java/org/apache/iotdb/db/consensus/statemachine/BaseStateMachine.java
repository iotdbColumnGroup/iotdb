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

package org.apache.iotdb.db.consensus.statemachine;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.consensus.common.DataSet;
import org.apache.iotdb.consensus.common.request.ByteBufferConsensusRequest;
import org.apache.iotdb.consensus.common.request.IConsensusRequest;
import org.apache.iotdb.consensus.statemachine.IStateMachine;
import org.apache.iotdb.db.mpp.sql.planner.plan.FragmentInstance;
import org.apache.iotdb.rpc.TSStatusCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseStateMachine implements IStateMachine {

  private static final Logger logger = LoggerFactory.getLogger(BaseStateMachine.class);

  @Override
  public TSStatus write(IConsensusRequest request) {
    try {
      return write(getFragmentInstance(request));
    } catch (IllegalArgumentException e) {
      logger.error(e.getMessage());
      return new TSStatus(TSStatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
    }
  }

  protected abstract TSStatus write(FragmentInstance fragmentInstance);

  @Override
  public DataSet read(IConsensusRequest request) {
    try {
      return read(getFragmentInstance(request));
    } catch (IllegalArgumentException e) {
      logger.error(e.getMessage());
      return null;
    }
  }

  protected abstract DataSet read(FragmentInstance fragmentInstance);

  private FragmentInstance getFragmentInstance(IConsensusRequest request) {
    FragmentInstance instance;
    if (request instanceof ByteBufferConsensusRequest) {
      instance =
          FragmentInstance.deserializeFrom(((ByteBufferConsensusRequest) request).getContent());
    } else if (request instanceof FragmentInstance) {
      instance = (FragmentInstance) request;
    } else {
      logger.error("Unexpected IConsensusRequest : {}", request);
      throw new IllegalArgumentException("Unexpected IConsensusRequest!");
    }
    return instance;
  }
}
