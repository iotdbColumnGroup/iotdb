/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// this file is generated by openapi generator

package org.apache.iotdb.openapi.gen.handler.factories;

import org.apache.iotdb.openapi.gen.handler.PingApiService;
import org.apache.iotdb.openapi.gen.handler.impl.PingApiServiceImpl;

@javax.annotation.Generated(value = "org.openapitools.codegen.languages.JavaJerseyServerCodegen", date = "2021-01-14T20:28:23.313+08:00[Asia/Shanghai]")
public class PingApiServiceFactory {
    private static final PingApiService service = new PingApiServiceImpl();

    public static PingApiService getPingApi() {
        return service;
    }
}
