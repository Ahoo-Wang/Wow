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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlanner
import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.quoteIdentifier
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.sql.Connection
import java.sql.DriverManager

class ClickHouseExpansionIntegrationTest {
    @Test
    @Suppress("LongMethod")
    fun `should execute expansion SQL without losing nullable JSON semantics`() {
        val options = BiScriptOptions(
            database = DATABASE,
            consumerDatabase = "bi_it_consumer",
            cluster = CLUSTER,
            installation = "test",
            shard = "01",
            replica = "01",
            timezone = "UTC",
        )
        val plan = StateExpansionPlanner(options).plan(
            aggregateMetadata<ClickHouseExpansionAggregate, ClickHouseExpansionState>()
        )
        val rootView = plan.views.single { it.targetTableName.endsWith("_root") }
        val childView = plan.views.single { it.targetTableName.endsWith("_nullable_objects") }
        rootView.columns.count { it.targetName == "mixed" }.assert().isEqualTo(1)
        rootView.columns.none { it.targetName == "__raw__mixed" }.assert().isTrue()

        clickHouse().use { clickHouse ->
            clickHouse.start()
            DriverManager.getConnection(
                clickHouse.jdbcUrl,
                clickHouse.username,
                clickHouse.password,
            ).use { connection ->
                connection.createSourceTable(rootView.sourceTableName)
                ClickHouseScriptRenderer(options)
                    .renderExpansionStatements(plan)
                    .forEach { statement -> connection.executeSql(statement) }
                connection.insertStateRows(rootView.sourceTableName)

                val rootProjections = connection.queryRows(
                    sql = rootProjectionSql(rootView.targetTableName),
                    columns = ROOT_PROJECTION_COLUMNS,
                )
                rootProjections.assert().hasSize(STATE_ROWS.size)
                val rootRows = rootProjections.associateBy { it.required("row_id") }
                rootRows.assert().isEqualTo(EXPECTED_ROOT_ROWS)

                val childRows = connection.queryRows(
                    sql = childProjectionSql(childView.targetTableName),
                    columns = CHILD_PROJECTION_COLUMNS,
                )
                childRows.assert().containsExactlyElementsOf(EXPECTED_CHILD_ROWS)
            }
        }
    }

    private fun clickHouse(): ClickHouseContainer =
        ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).apply {
            withCopyFileToContainer(
                MountableFile.forClasspathResource(CLUSTER_CONFIG_RESOURCE),
                CLUSTER_CONFIG_PATH,
            )
        }

    private fun Connection.createSourceTable(table: String) {
        executeSql("CREATE DATABASE IF NOT EXISTS ${identifier(DATABASE)}")
        executeSql(
            """
                CREATE TABLE ${qualified(DATABASE, table)}
                (
                    ${identifier("id")} String,
                    ${identifier("aggregate_id")} String,
                    ${identifier("tenant_id")} String DEFAULT '',
                    ${identifier("owner_id")} String DEFAULT '',
                    ${identifier("space_id")} String DEFAULT '',
                    ${identifier("command_id")} String DEFAULT '',
                    ${identifier("request_id")} String DEFAULT '',
                    ${identifier("version")} UInt32 DEFAULT 0,
                    ${identifier("state")} String,
                    ${identifier("first_operator")} String DEFAULT '',
                    ${identifier("first_event_time")} DateTime('UTC') DEFAULT toDateTime(0, 'UTC'),
                    ${identifier("create_time")} DateTime('UTC') DEFAULT toDateTime(0, 'UTC'),
                    ${identifier("tags")} Map(String, Array(String)) DEFAULT map(),
                    ${identifier("deleted")} Bool DEFAULT false
                )
                ENGINE = MergeTree
                ORDER BY ${identifier("aggregate_id")}
            """.trimIndent()
        )
    }

    private fun Connection.insertStateRows(table: String) {
        prepareStatement(
            "INSERT INTO ${qualified(DATABASE, table)} " +
                "(${identifier("id")}, ${identifier("aggregate_id")}, ${identifier("state")}) VALUES (?, ?, ?)"
        ).use { statement ->
            STATE_ROWS.forEach { (id, state) ->
                statement.setString(1, id)
                statement.setString(2, id)
                statement.setString(3, state)
                statement.executeUpdate()
            }
        }
    }

    private fun Connection.executeSql(sql: String) {
        createStatement().use { statement -> statement.execute(sql) }
    }

    private fun Connection.queryRows(
        sql: String,
        columns: List<String>,
    ): List<Map<String, String>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        columns.associateWith { column ->
                            checkNotNull(resultSet.getString(column)) {
                                "Column [$column] unexpectedly returned SQL NULL."
                            }
                        }
                    )
                }
            }
        }
    }

    private fun rootProjectionSql(table: String): String =
        """
            SELECT
                ${identifier("__id")} AS row_id,
                toTypeName(${identifier("nullable_scalar")}) AS scalar_type,
                toJSONString(${identifier("nullable_scalar")}) AS scalar_json,
                ${identifier("__raw__nullable_scalar")} AS scalar_raw,
                toTypeName(${identifier("nullable_array")}) AS array_type,
                toJSONString(${identifier("nullable_array")}) AS array_json,
                ${identifier("__raw__nullable_array")} AS array_raw,
                toTypeName(${identifier("nullable_map")}) AS map_type,
                length(${identifier("nullable_map")}) AS map_size,
                toJSONString(${identifier("nullable_map")}['a']) AS map_a_json,
                toJSONString(${identifier("nullable_map")}['b']) AS map_b_json,
                ${identifier("__raw__nullable_map")} AS map_raw,
                toTypeName(${identifier("nullable_object__value")}) AS object_value_type,
                toJSONString(${identifier("nullable_object__value")}) AS object_value_json,
                ${identifier("__raw__nullable_object")} AS object_raw,
                ${identifier("__raw__nullable_objects")} AS object_array_raw,
                ${identifier("mixed")} AS mixed_raw
            FROM ${qualified(DATABASE, table)}
            ORDER BY row_id
        """.trimIndent()

    private fun childProjectionSql(table: String): String =
        """
            SELECT
                ${identifier("__id")} AS row_id,
                toTypeName(${identifier("nullable_objects__value")}) AS value_type,
                toJSONString(${identifier("nullable_objects__value")}) AS value_json,
                ${identifier("__raw__nullable_objects")} AS element_raw
            FROM ${qualified(DATABASE, table)}
            ORDER BY isNull(${identifier("nullable_objects__value")}), element_raw
        """.trimIndent()

    private fun Map<String, String>.required(name: String): String =
        checkNotNull(this[name]) { "Projection [$name] is missing." }

    private fun identifier(value: String): String = quoteIdentifier(value)

    private fun qualified(database: String, table: String): String =
        "${identifier(database)}.${identifier(table)}"

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val CLUSTER = "test_cluster"
        const val DATABASE = "bi_it"
        const val CLUSTER_CONFIG_RESOURCE = "clickhouse-test-cluster.xml"
        const val CLUSTER_CONFIG_PATH =
            "/etc/clickhouse-server/config.d/clickhouse-test-cluster.xml"

        val ROOT_PROJECTION_COLUMNS = listOf(
            "row_id",
            "scalar_type",
            "scalar_json",
            "scalar_raw",
            "array_type",
            "array_json",
            "array_raw",
            "map_type",
            "map_size",
            "map_a_json",
            "map_b_json",
            "map_raw",
            "object_value_type",
            "object_value_json",
            "object_raw",
            "object_array_raw",
            "mixed_raw",
        )
        val CHILD_PROJECTION_COLUMNS = listOf(
            "row_id",
            "value_type",
            "value_json",
            "element_raw",
        )

        val STATE_ROWS = linkedMapOf(
            "row-normal" to
                """{"id":"state-normal","nullableScalar":7,"nullableArray":[1,null,3],"nullableMap":{"a":1,"b":null},"nullableObject":{"value":11},"nullableObjects":[{"value":21},null],"mixed":{"string":"x","number":1,"bool":true,"nil":null,"object":{"x":1},"array":[1,"two"]}}""",
            "row-null" to
                """{"id":"state-null","nullableScalar":null,"nullableArray":null,"nullableMap":null,"nullableObject":null,"nullableObjects":null,"mixed":{"case":"null"}}""",
            "row-empty" to
                """{"id":"state-empty","nullableScalar":null,"nullableArray":[],"nullableMap":{},"nullableObject":{},"nullableObjects":[],"mixed":{}}""",
            "row-missing" to """{"id":"state-missing"}""",
        )

        val EXPECTED_ROOT_ROWS = mapOf(
            "row-normal" to rootRow(
                rowId = "row-normal",
                scalarJson = "7",
                scalarRaw = "7",
                arrayJson = "[1,null,3]",
                arrayRaw = "[1,null,3]",
                mapSize = "2",
                mapAJson = "1",
                mapBJson = "null",
                mapRaw = "{\"a\":1,\"b\":null}",
                objectValueJson = "11",
                objectRaw = "{\"value\":11}",
                objectArrayRaw = "[{\"value\":21},null]",
                mixedRaw =
                    "{\"string\":\"x\",\"number\":1,\"bool\":true,\"nil\":null," +
                        "\"object\":{\"x\":1},\"array\":[1,\"two\"]}",
            ),
            "row-null" to rootRow(
                rowId = "row-null",
                scalarJson = "null",
                scalarRaw = "null",
                arrayJson = "[]",
                arrayRaw = "null",
                mapSize = "0",
                mapAJson = "null",
                mapBJson = "null",
                mapRaw = "null",
                objectValueJson = "null",
                objectRaw = "null",
                objectArrayRaw = "null",
                mixedRaw = "{\"case\":\"null\"}",
            ),
            "row-empty" to rootRow(
                rowId = "row-empty",
                scalarJson = "null",
                scalarRaw = "null",
                arrayJson = "[]",
                arrayRaw = "[]",
                mapSize = "0",
                mapAJson = "null",
                mapBJson = "null",
                mapRaw = "{}",
                objectValueJson = "null",
                objectRaw = "{}",
                objectArrayRaw = "[]",
                mixedRaw = "{}",
            ),
            "row-missing" to rootRow(
                rowId = "row-missing",
                scalarJson = "null",
                scalarRaw = "",
                arrayJson = "[]",
                arrayRaw = "",
                mapSize = "0",
                mapAJson = "null",
                mapBJson = "null",
                mapRaw = "",
                objectValueJson = "null",
                objectRaw = "",
                objectArrayRaw = "",
                mixedRaw = "",
            ),
        )

        val EXPECTED_CHILD_ROWS = listOf(
            mapOf(
                "row_id" to "row-normal",
                "value_type" to "Nullable(Int32)",
                "value_json" to "21",
                "element_raw" to "{\"value\":21}",
            ),
            mapOf(
                "row_id" to "row-normal",
                "value_type" to "Nullable(Int32)",
                "value_json" to "null",
                "element_raw" to "null",
            ),
        )

        @Suppress("LongParameterList")
        fun rootRow(
            rowId: String,
            scalarJson: String,
            scalarRaw: String,
            arrayJson: String,
            arrayRaw: String,
            mapSize: String,
            mapAJson: String,
            mapBJson: String,
            mapRaw: String,
            objectValueJson: String,
            objectRaw: String,
            objectArrayRaw: String,
            mixedRaw: String,
        ): Map<String, String> = mapOf(
            "row_id" to rowId,
            "scalar_type" to "Nullable(Int32)",
            "scalar_json" to scalarJson,
            "scalar_raw" to scalarRaw,
            "array_type" to "Array(Nullable(Int32))",
            "array_json" to arrayJson,
            "array_raw" to arrayRaw,
            "map_type" to "Map(String, Nullable(Int32))",
            "map_size" to mapSize,
            "map_a_json" to mapAJson,
            "map_b_json" to mapBJson,
            "map_raw" to mapRaw,
            "object_value_type" to "Nullable(Int32)",
            "object_value_json" to objectValueJson,
            "object_raw" to objectRaw,
            "object_array_raw" to objectArrayRaw,
            "mixed_raw" to mixedRaw,
        )
    }
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
internal class ClickHouseExpansionAggregate(private val state: ClickHouseExpansionState)

internal class ClickHouseExpansionState(override val id: String) : Identifier {
    val nullableScalar: Int? = null
    val nullableArray: List<Int?>? = null
    val nullableMap: Map<String, Int?>? = null
    val nullableObject: ClickHouseNullableObject? = null
    val nullableObjects: List<ClickHouseNullableObject?>? = null
    val mixed: Map<String, Any> = emptyMap()
}

internal data class ClickHouseNullableObject(val value: Int)
