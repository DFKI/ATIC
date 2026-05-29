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

package org.apache.jena.tdb2;

import org.apache.jena.tdb2.graph.TestDatasetGraphTDB2;
import org.apache.jena.tdb2.graph.TestGraphOverDatasetTDB2;
import org.apache.jena.tdb2.graph.TestGraphViewSwitchable;
import org.apache.jena.tdb2.graph.TestGraphsTDB2_A;
import org.apache.jena.tdb2.graph.TestGraphsTDB2_B;
import org.apache.jena.tdb2.graph.TestPrefixMappingTDB2;
import org.apache.jena.tdb2.solver.TestSolverTDB;
import org.apache.jena.tdb2.store.TestDatasetTDB;
import org.apache.jena.tdb2.store.TestDynamicDatasetTDB;
import org.apache.jena.tdb2.store.TestGraphNamedTDB;
import org.apache.jena.tdb2.store.TestGraphTDB;
import org.apache.jena.tdb2.store.TestQueryExecTDB;
import org.apache.jena.tdb2.store.TestTransactionLifecycleTDB;
import org.apache.jena.tdb2.store.TestTransactions;
import org.apache.jena.tdb2.store.TestVisibilityOfChanges;
import org.apache.jena.tdb2.store.Test_SPARQL_TDB;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
    TestDatasetGraphTDB2.class
    , TestGraphOverDatasetTDB2.class
    , TestGraphViewSwitchable.class
    , TestDynamicDatasetTDB.class
    , TestGraphNamedTDB.class
    , TestGraphTDB.class
    , TestQueryExecTDB.class
    , Test_SPARQL_TDB.class
    , TestTransactionLifecycleTDB.class
    , TestVisibilityOfChanges.class //solved with URI resource and buffersize=1
    , TestSolverTDB.class
    , TestTransactions.class
    , TestGraphsTDB2_B.class
    , TestPrefixMappingTDB2.class
    , TestDatasetTDB.class
    , TestGraphsTDB2_A.class
        
    //TODO
    //, TestTransPromoteTDB.class
        
})
public class TS_Atic
{
    
}
