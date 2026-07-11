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
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.quoteIdentifier
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.stringLiteral
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.sql.Connection
import java.sql.DriverManager
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.Year
import java.util.Date

class ClickHouseExpansionIntegrationTest {
    @Test
    @Suppress("LongMethod")
    fun `should execute complete generated DDL and preserve scalar wire values`() {
        val options = BiScriptOptions(
            database = DATABASE,
            consumerDatabase = "bi_it_consumer",
            cluster = CLUSTER,
            installation = "test",
            shard = "01",
            replica = "01",
            timezone = "UTC",
        )
        val namedAggregate = aggregateMetadata<ClickHouseExpansionAggregate, ClickHouseExpansionState>()
        val result = BiScriptGenerator(options).generate(setOf(namedAggregate))

        clickHouse().use { clickHouse ->
            clickHouse.start()
            DriverManager.getConnection(
                clickHouse.jdbcUrl,
                clickHouse.username,
                clickHouse.password,
            ).use { connection ->
                result.statements.forEach(connection::executeSql)
                connection.assertGeneratedObjects()
                connection.insertStateRows()

                val rootProjections = connection.queryRows(
                    sql = rootProjectionSql(ROOT_VIEW),
                    columns = ROOT_PROJECTION_COLUMNS,
                )
                rootProjections.assert().hasSize(STATE_ROWS.size)
                val rootRows = rootProjections.associateBy { it.required("row_id") }
                rootRows.assert().isEqualTo(EXPECTED_ROOT_ROWS)

                val childRows = connection.queryRows(
                    sql = childProjectionSql(CHILD_VIEW),
                    columns = CHILD_PROJECTION_COLUMNS,
                )
                childRows.assert().containsExactlyElementsOf(EXPECTED_CHILD_ROWS)

                connection.queryRows(
                    sql = scalarProjectionSql(ROOT_VIEW),
                    columns = SCALAR_PROJECTION_COLUMNS,
                ).single().assert().isEqualTo(EXPECTED_SCALAR_ROW)
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

    private fun Connection.assertGeneratedObjects() {
        val objects = queryRows(
            sql =
                "SELECT concat(database, '.', name) AS object_name FROM system.tables " +
                    "WHERE database IN (${literal(DATABASE)}, ${literal(CONSUMER_DATABASE)})",
            columns = listOf("object_name"),
        ).map { it.required("object_name") }

        objects.assert().contains(
            "$DATABASE.$COMMAND_TABLE",
            "$DATABASE.$STATE_TABLE",
            "$DATABASE.$STATE_LAST_TABLE",
            "$DATABASE.$ROOT_VIEW",
            "$DATABASE.$CHILD_VIEW",
            "$CONSUMER_DATABASE.${COMMAND_TABLE}_queue",
            "$CONSUMER_DATABASE.${COMMAND_TABLE}_consumer",
            "$CONSUMER_DATABASE.${STATE_TABLE}_queue",
            "$CONSUMER_DATABASE.${STATE_TABLE}_consumer",
            "$CONSUMER_DATABASE.${STATE_LAST_TABLE}_consumer",
        )
    }

    private fun Connection.insertStateRows() {
        prepareStatement(
            "INSERT INTO ${qualified(DATABASE, STATE_LAST_TABLE)} " +
                "(${identifier("id")}, ${identifier("context_name")}, ${identifier("aggregate_name")}, " +
                "${identifier("header")}, ${identifier("aggregate_id")}, ${identifier("tenant_id")}, " +
                "${identifier("owner_id")}, ${identifier("space_id")}, ${identifier("command_id")}, " +
                "${identifier("request_id")}, ${identifier("version")}, ${identifier("state")}, " +
                "${identifier("body")}, ${identifier("first_operator")}, ${identifier("first_event_time")}, " +
                "${identifier("create_time")}, ${identifier("tags")}, ${identifier("deleted")}) " +
                "VALUES (?, 'bi-integration-service', 'nullable', map(), ?, '', '', '', '', '', 1, ?, [], '', " +
                "toDateTime(0, 'UTC'), toDateTime(0, 'UTC'), map(), false)"
        ).use { statement ->
            STATE_ROWS.forEach { (id, state) ->
                statement.setString(1, id)
                statement.setString(2, id)
                statement.setString(3, state)
                statement.executeUpdate()
            }
        }
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
                ifNull(toJSONString(${identifier("nullable_scalar")}), 'null') AS scalar_json,
                ${identifier("__raw__nullable_scalar")} AS scalar_raw,
                toTypeName(${identifier("nullable_array")}) AS array_type,
                toJSONString(${identifier("nullable_array")}) AS array_json,
                ${identifier("__raw__nullable_array")} AS array_raw,
                toTypeName(${identifier("nullable_map")}) AS map_type,
                length(${identifier("nullable_map")}) AS map_size,
                ifNull(toJSONString(${identifier("nullable_map")}['a']), 'null') AS map_a_json,
                ifNull(toJSONString(${identifier("nullable_map")}['b']), 'null') AS map_b_json,
                ${identifier("__raw__nullable_map")} AS map_raw,
                toTypeName(${identifier("nullable_object__value")}) AS object_value_type,
                ifNull(toJSONString(${identifier("nullable_object__value")}), 'null') AS object_value_json,
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
                ifNull(toJSONString(${identifier("nullable_objects__value")}), 'null') AS value_json,
                ${identifier("__raw__nullable_objects")} AS element_raw
            FROM ${qualified(DATABASE, table)}
            ORDER BY isNull(${identifier("nullable_objects__value")}), element_raw
        """.trimIndent()

    private fun scalarProjectionSql(table: String): String =
        """
            SELECT
                ${identifier("big_decimal")} AS big_decimal_raw,
                ${identifier("date")} AS date_value,
                toString(${identifier("year")}) AS year_value,
                ${identifier("duration")} AS duration_value,
                ${identifier("instant")} AS instant_value,
                toString(isNaN(${identifier("special_double")})) AS double_is_nan,
                toString(isInfinite(${identifier("special_float")})) AS float_is_infinite,
                ${identifier("big_decimals")} AS big_decimals_raw,
                toJSONString(${identifier("dates")}) AS dates_json,
                toString(${identifier("years")}['negative']) AS negative_year,
                toJSONString(${identifier("durations")}) AS durations_json,
                ${identifier("instants")}['nano'] AS instant_map_value,
                toString(isNaN(${identifier("special_doubles")}[1])) AS array_nan,
                toString(isInfinite(${identifier("special_doubles")}[2])) AS array_positive_infinity,
                toString(isInfinite(${identifier("special_doubles")}[3])) AS array_negative_infinity,
                toString(${identifier("special_doubles")}[3] < 0) AS array_negative_sign
            FROM ${qualified(DATABASE, table)}
            WHERE ${identifier("__id")} = 'row-normal'
        """.trimIndent()

    private fun Map<String, String>.required(name: String): String =
        checkNotNull(this[name]) { "Projection [$name] is missing." }

    private fun identifier(value: String): String = quoteIdentifier(value)

    private fun literal(value: String): String = stringLiteral(value)

    private fun qualified(database: String, table: String): String =
        "${identifier(database)}.${identifier(table)}"

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val CLUSTER = "test_cluster"
        const val DATABASE = "bi_it"
        const val CONSUMER_DATABASE = "bi_it_consumer"
        const val COMMAND_TABLE = "bi_it_nullable_command"
        const val STATE_TABLE = "bi_it_nullable_state"
        const val STATE_LAST_TABLE = "bi_it_nullable_state_last"
        const val ROOT_VIEW = "bi_it_nullable_state_last_root"
        const val CHILD_VIEW = "bi_it_nullable_state_last_root_nullable_objects"
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
        val SCALAR_PROJECTION_COLUMNS = listOf(
            "big_decimal_raw",
            "date_value",
            "year_value",
            "duration_value",
            "instant_value",
            "double_is_nan",
            "float_is_infinite",
            "big_decimals_raw",
            "dates_json",
            "negative_year",
            "durations_json",
            "instant_map_value",
            "array_nan",
            "array_positive_infinity",
            "array_negative_infinity",
            "array_negative_sign",
        )

        val HIGH_PRECISION_DECIMAL = BigDecimal("123456789012345678901234567890.12345678901234567890")
        val NEGATIVE_DATE = Date(-1)
        val NEGATIVE_YEAR = Year.of(-1)
        val NANO_DURATION = Duration.ofSeconds(1, 123456789)
        val NANO_INSTANT = Instant.ofEpochSecond(1, 123456789)
        val BIG_DECIMALS = listOf(HIGH_PRECISION_DECIMAL, BigDecimal("1E+100"))
        val DATES = listOf(NEGATIVE_DATE, Date(0))
        val DURATIONS = listOf(NANO_DURATION, Duration.ofSeconds(-1, 500000000))
        val SPECIAL_DOUBLES = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

        val STATE_ROWS = linkedMapOf(
            "row-normal" to JsonSerializer.writeValueAsString(
                linkedMapOf(
                    "id" to "state-normal",
                    "nullableScalar" to 7,
                    "nullableArray" to listOf(1, null, 3),
                    "nullableMap" to linkedMapOf("a" to 1, "b" to null),
                    "nullableObject" to ClickHouseNullableObject(11),
                    "nullableObjects" to listOf(ClickHouseNullableObject(21), null),
                    "mixed" to linkedMapOf(
                        "string" to "x",
                        "number" to 1,
                        "bool" to true,
                        "nil" to null,
                        "object" to mapOf("x" to 1),
                        "array" to listOf(1, "two"),
                    ),
                    "bigDecimal" to HIGH_PRECISION_DECIMAL,
                    "date" to NEGATIVE_DATE,
                    "year" to NEGATIVE_YEAR,
                    "duration" to NANO_DURATION,
                    "instant" to NANO_INSTANT,
                    "specialDouble" to Double.NaN,
                    "specialFloat" to Float.POSITIVE_INFINITY,
                    "bigDecimals" to BIG_DECIMALS,
                    "dates" to DATES,
                    "years" to mapOf("negative" to NEGATIVE_YEAR),
                    "durations" to DURATIONS,
                    "instants" to mapOf("nano" to NANO_INSTANT),
                    "specialDoubles" to SPECIAL_DOUBLES,
                )
            ),
            "row-null" to JsonSerializer.writeValueAsString(
                linkedMapOf(
                    "id" to "state-null",
                    "nullableScalar" to null,
                    "nullableArray" to null,
                    "nullableMap" to null,
                    "nullableObject" to null,
                    "nullableObjects" to null,
                    "mixed" to mapOf("case" to "null"),
                )
            ),
            "row-empty" to JsonSerializer.writeValueAsString(
                linkedMapOf(
                    "id" to "state-empty",
                    "nullableScalar" to null,
                    "nullableArray" to emptyList<Int>(),
                    "nullableMap" to emptyMap<String, Int>(),
                    "nullableObject" to emptyMap<String, Int>(),
                    "nullableObjects" to emptyList<ClickHouseNullableObject>(),
                    "mixed" to emptyMap<String, Any>(),
                )
            ),
            "row-missing" to JsonSerializer.writeValueAsString(mapOf("id" to "state-missing")),
        )

        val EXPECTED_SCALAR_ROW = mapOf(
            "big_decimal_raw" to JsonSerializer.writeValueAsString(HIGH_PRECISION_DECIMAL),
            "date_value" to serializedText(NEGATIVE_DATE),
            "year_value" to "-1",
            "duration_value" to serializedText(NANO_DURATION),
            "instant_value" to serializedText(NANO_INSTANT),
            "double_is_nan" to "1",
            "float_is_infinite" to "1",
            "big_decimals_raw" to JsonSerializer.writeValueAsString(BIG_DECIMALS),
            "dates_json" to JsonSerializer.writeValueAsString(DATES),
            "negative_year" to "-1",
            "durations_json" to JsonSerializer.writeValueAsString(DURATIONS),
            "instant_map_value" to serializedText(NANO_INSTANT),
            "array_nan" to "1",
            "array_positive_infinity" to "1",
            "array_negative_infinity" to "1",
            "array_negative_sign" to "1",
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

        fun serializedText(value: Any): String =
            JsonSerializer.readTree(JsonSerializer.writeValueAsString(value)).asString()
    }
}

private fun Connection.executeSql(sql: String) {
    createStatement().use { statement -> statement.execute(sql) }
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
    val bigDecimal: BigDecimal = BigDecimal.ZERO
    val date: Date = Date(0)
    val year: Year = Year.of(1970)
    val duration: Duration = Duration.ZERO
    val instant: Instant = Instant.EPOCH
    val specialDouble: Double = 0.0
    val specialFloat: Float = 0f
    val bigDecimals: List<BigDecimal> = emptyList()
    val dates: List<Date> = emptyList()
    val years: Map<String, Year> = emptyMap()
    val durations: List<Duration> = emptyList()
    val instants: Map<String, Instant> = emptyMap()
    val specialDoubles: List<Double> = emptyList()
}

internal data class ClickHouseNullableObject(val value: Int)
