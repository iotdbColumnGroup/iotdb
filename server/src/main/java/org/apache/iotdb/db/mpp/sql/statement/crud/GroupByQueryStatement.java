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

package org.apache.iotdb.db.mpp.sql.statement.crud;

import org.apache.iotdb.db.mpp.common.header.ColumnHeader;
import org.apache.iotdb.db.mpp.common.header.DatasetHeader;
import org.apache.iotdb.db.mpp.sql.statement.StatementVisitor;
import org.apache.iotdb.db.mpp.sql.statement.component.GroupByTimeComponent;

import java.util.ArrayList;
import java.util.List;

public class GroupByQueryStatement extends AggregationQueryStatement {

  protected GroupByTimeComponent groupByTimeComponent;

  public GroupByQueryStatement() {
    super();
  }

  public GroupByQueryStatement(QueryStatement queryStatement) {
    super(queryStatement);
  }

  public GroupByTimeComponent getGroupByTimeComponent() {
    return groupByTimeComponent;
  }

  public void setGroupByTimeComponent(GroupByTimeComponent groupByTimeComponent) {
    this.groupByTimeComponent = groupByTimeComponent;
  }

  public DatasetHeader constructDatasetHeader() {
    List<ColumnHeader> columnHeaders = new ArrayList<>();
    // TODO: consider GROUP BY
    return new DatasetHeader(columnHeaders, false);
  }

  public <R, C> R accept(StatementVisitor<R, C> visitor, C context) {
    return visitor.visitGroupByQuery(this, context);
  }
}
