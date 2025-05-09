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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.ddl.catalog.create;

import org.apache.hadoop.hive.ql.ddl.DDLDesc;
import org.apache.hadoop.hive.ql.plan.Explain;

import java.io.Serializable;

/**
 * DDL task description for CREATE CATALOG commands.
 */
@Explain(displayName = "Create CATALOG", explainLevels = { Explain.Level.USER, Explain.Level.DEFAULT, Explain.Level.EXTENDED })
public class CreateCatalogDesc implements DDLDesc, Serializable {
  private static final long serialVersionUID = 1L;

  private final String catalogName;
  private final String comment;
  private final String locationUri;
  private final boolean ifNotExists;

  public CreateCatalogDesc(String catalogName, String comment, String locationUri, boolean ifNotExists) {
    this.catalogName = catalogName;
    this.comment = comment;
    this.locationUri = locationUri;
    this.ifNotExists = ifNotExists;
  }

  @Explain(displayName="name", explainLevels = { Explain.Level.USER, Explain.Level.DEFAULT, Explain.Level.EXTENDED })
  public String getName() {
    return catalogName;
  }

  @Explain(displayName="comment")
  public String getComment() {
    return comment;
  }

  @Explain(displayName="locationUri")
  public String getLocationUri() {
    return locationUri;
  }

  @Explain(displayName="if not exists", displayOnlyOnTrue = true)
  public boolean isIfNotExists() {
    return ifNotExists;
  }
}
