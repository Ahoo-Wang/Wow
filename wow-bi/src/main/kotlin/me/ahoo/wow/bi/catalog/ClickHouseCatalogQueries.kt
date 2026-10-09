/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.bi.catalog

/*
 * The SQL the catalog reader runs against system.tables / system.columns, and the columns it reads back.
 * Placeholders are replaced with parameterised predicates by catalogScopeQuery.
 */

internal const val DATABASE_TABLES_PREDICATE = "__DATABASE_TABLES_PREDICATE__"
internal const val CONSUMER_DATABASE_TABLES_PREDICATE = "__CONSUMER_DATABASE_TABLES_PREDICATE__"
internal const val TABLES_PREDICATE = "__TABLES_PREDICATE__"
internal const val EXPECTED_DEFINITIONS_EXPRESSION = "__EXPECTED_DEFINITIONS_EXPRESSION__"

internal val NODE_COLUMNS = listOf("host_name", "tcp_port")
internal val CATALOG_COLUMNS = listOf(
    "database",
    "name",
    "engine",
    "engine_full",
    "create_table_query",
    "as_select",
    "comment",
    "partition_key",
    "sorting_key",
)
internal val EXPECTED_QUERY_COLUMNS = listOf("database", "name", "canonical_select")
internal val OBJECT_KEY_COLUMNS = listOf("database", "name")
internal val COLUMN_COLUMNS = listOf("database", "table", "name", "type", "position")

internal val STANDALONE_CATALOG_DISCOVERY_QUERY: String = """
    SELECT database, name
    FROM system.tables
    WHERE database IN ({database:String}, {consumerDatabase:String})
      AND (startsWith(comment, {ownershipPrefix:String})
           OR (database = {database:String} AND $DATABASE_TABLES_PREDICATE)
           OR (database = {consumerDatabase:String}
               AND $CONSUMER_DATABASE_TABLES_PREDICATE))
""".trimIndent()

internal val STANDALONE_SCOPED_CATALOG_QUERY: String = """
    SELECT database, name, engine, engine_full, create_table_query,
           formatQuerySingleLineOrNull(as_select) AS as_select,
           comment, partition_key, sorting_key
    FROM system.tables
    WHERE (database = {database:String} AND $DATABASE_TABLES_PREDICATE)
       OR (database = {consumerDatabase:String} AND $CONSUMER_DATABASE_TABLES_PREDICATE)
    SETTINGS show_table_uuid_in_table_create_query_if_not_nil = 0
""".trimIndent()

internal val CLUSTER_NODES_QUERY: String = """
    SELECT hostName() AS host_name, tcpPort() AS tcp_port
    FROM clusterAllReplicas({cluster:String}, system.one)
    SETTINGS skip_unavailable_shards = 0,
             show_table_uuid_in_table_create_query_if_not_nil = 0
""".trimIndent()

internal val CLUSTER_CATALOG_DISCOVERY_QUERY: String = """
    SELECT DISTINCT database, name
    FROM clusterAllReplicas({cluster:String}, system.tables)
    WHERE database IN ({database:String}, {consumerDatabase:String})
      AND (startsWith(comment, {ownershipPrefix:String})
           OR (database = {database:String} AND $DATABASE_TABLES_PREDICATE)
           OR (database = {consumerDatabase:String}
               AND $CONSUMER_DATABASE_TABLES_PREDICATE))
    SETTINGS skip_unavailable_shards = 0,
             max_parallel_replicas = 1
""".trimIndent()

internal val CLUSTER_SCOPED_CATALOG_QUERY: String = """
    SELECT hostName() AS host_name,
           tcpPort() AS tcp_port,
           database,
           name,
           engine,
           engine_full,
           create_table_query,
           formatQuerySingleLineOrNull(as_select) AS as_select,
           comment,
           partition_key,
           sorting_key
    FROM clusterAllReplicas({cluster:String}, system.tables)
    WHERE (database = {database:String} AND $DATABASE_TABLES_PREDICATE)
       OR (database = {consumerDatabase:String} AND $CONSUMER_DATABASE_TABLES_PREDICATE)
    SETTINGS skip_unavailable_shards = 0,
             show_table_uuid_in_table_create_query_if_not_nil = 0
""".trimIndent()

internal val STANDALONE_COLUMNS_QUERY: String = """
    SELECT database, table, name, type, position
    FROM system.columns
    WHERE database = {database:String}
      AND $TABLES_PREDICATE
""".trimIndent()

internal val CLUSTER_COLUMNS_QUERY: String = """
    SELECT hostName() AS host_name,
           tcpPort() AS tcp_port,
           database,
           table,
           name,
           type,
           position
    FROM clusterAllReplicas({cluster:String}, system.columns)
    WHERE database = {database:String}
      AND $TABLES_PREDICATE
    SETTINGS skip_unavailable_shards = 0,
             max_parallel_replicas = 1
""".trimIndent()

internal val EXPECTED_QUERY_CANONICALIZATION_QUERY: String = """
    SELECT JSONExtractString(definition, 'database') AS database,
           JSONExtractString(definition, 'name') AS name,
           formatQuerySingleLineOrNull(JSONExtractString(definition, 'selectSql')) AS canonical_select
    FROM (
        SELECT arrayJoin($EXPECTED_DEFINITIONS_EXPRESSION) AS definition
    )
""".trimIndent()
