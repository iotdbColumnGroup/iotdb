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

package org.apache.iotdb.rpc;

import org.apache.iotdb.common.rpc.thrift.EndPoint;

import java.io.IOException;
import java.util.Map;

public class RedirectException extends IOException {

  private final EndPoint endPoint;

  private final Map<String, EndPoint> deviceEndPointMap;

  public RedirectException(EndPoint endPoint) {
    super("later request in same group will be redirected to " + endPoint.toString());
    this.endPoint = endPoint;
    this.deviceEndPointMap = null;
  }

  public RedirectException(Map<String, EndPoint> deviceEndPointMap) {
    super("later request in same group will be redirected to " + deviceEndPointMap);
    this.endPoint = null;
    this.deviceEndPointMap = deviceEndPointMap;
  }

  public EndPoint getEndPoint() {
    return this.endPoint;
  }

  public Map<String, EndPoint> getDeviceEndPointMap() {
    return deviceEndPointMap;
  }
}
