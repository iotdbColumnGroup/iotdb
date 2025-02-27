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
package org.apache.iotdb.confignode.conf;

public class ConfigNodeConstant {

  // when running the program in IDE, we can not get the version info using
  // getImplementationVersion()
  public static final String VERSION =
      ConfigNodeConstant.class.getPackage().getImplementationVersion() != null
          ? ConfigNodeConstant.class.getPackage().getImplementationVersion()
          : "UNKNOWN";

  public static final String GLOBAL_NAME = "IoTDB ConfigNode";
  public static final String CONFIGNODE_CONF = "CONFIGNODE_CONF";
  public static final String CONFIGNODE_HOME = "CONFIGNODE_HOME";

  public static final String ENV_FILE_NAME = "confignode-env";
  public static final String CONF_NAME = "iotdb-confignode.properties";
  public static final String SPECIAL_CONF_NAME = "iotdb-confignode-special.properties";

  public static final String CONFIGNODE_PACKAGE = "org.apache.iotdb.confignode.service";
  public static final String JMX_TYPE = "type";
  public static final String CONFIGNODE_JMX_PORT = "confignode.jmx.port";

  public static final String DATA_DIR = "data";
  public static final String CONF_DIR = "conf";
  public static final String CONSENSUS_FOLDER = "consensus";

  public static final int MIN_SUPPORTED_JDK_VERSION = 8;

  private ConfigNodeConstant() {
    // empty constructor
  }
}
