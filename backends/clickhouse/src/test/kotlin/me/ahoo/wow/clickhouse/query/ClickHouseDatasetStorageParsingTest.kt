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

package me.ahoo.wow.clickhouse.query

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.ServerException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.DATASET
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.TABLE
import me.ahoo.wow.clickhouse.sink.ClickHouseColumn
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetBuilder
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** The storage binding, the column types, the declaration check and the executor's helpers, without a server. */
class ClickHouseDatasetStorageParsingTest {
    @Test
    fun `a column's family and nullability should come from its type`() {
        mapOf(
            "String" to (ClickHouseTypeFamily.STRING to false),
            "UUID" to (ClickHouseTypeFamily.STRING to false),
            "FixedString(3)" to (ClickHouseTypeFamily.STRING to false),
            "LowCardinality(Nullable(String))" to (ClickHouseTypeFamily.STRING to true),
            "Enum8('A' = 1)" to (ClickHouseTypeFamily.ENUM to false),
            "UInt64" to (ClickHouseTypeFamily.INTEGER to false),
            "Nullable(Float64)" to (ClickHouseTypeFamily.FLOAT to true),
            "Decimal(18, 2)" to (ClickHouseTypeFamily.DECIMAL to false),
            "Bool" to (ClickHouseTypeFamily.BOOLEAN to false),
            "Date32" to (ClickHouseTypeFamily.DATE to false),
            "DateTime64(3, 'UTC')" to (ClickHouseTypeFamily.DATE_TIME to false),
            "Array(String)" to (ClickHouseTypeFamily.STRING_ARRAY to false),
            "Array(LowCardinality(String))" to (ClickHouseTypeFamily.STRING_ARRAY to false),
            "Array(FixedString(2))" to (ClickHouseTypeFamily.STRING_ARRAY to false),
            "Array(Nullable(String))" to (ClickHouseTypeFamily.UNSUPPORTED to false),
            "Array(Int64)" to (ClickHouseTypeFamily.UNSUPPORTED to false),
            "Array(String" to (ClickHouseTypeFamily.UNSUPPORTED to false),
            "Map(String, String)" to (ClickHouseTypeFamily.UNSUPPORTED to false),
        ).forEach { (type, expected) ->
            val column = TypedColumn(ClickHouseColumn("c", type))
            (column.family to column.nullable).assert().describedAs(type).isEqualTo(expected)
        }
        column("DateTime64(3, 'UTC')").parameterType.assert().isEqualTo("Int64")
        column("Enum8('A' = 1)").parameterType.assert().isEqualTo("String")
        column("Array(String)").parameterType.assert().isEqualTo("String")
        column("Nullable(Decimal(18, 2))").parameterType.assert().isEqualTo("Decimal(18, 2)")
        column("Array(String)").array.assert().isTrue()
        column("String").array.assert().isFalse()
        column("String").toString().assert().isEqualTo("TypedColumn(c String)")
    }

    @Test
    fun `only engines that merge rows should support FINAL`() {
        fun table(engine: String) = ClickHouseTable("db", "t", engine, emptyList())
        table("ReplicatedReplacingMergeTree").supportsFinal.assert().isTrue()
        table("SharedCollapsingMergeTree").supportsFinal.assert().isTrue()
        table("MergeTree").supportsFinal.assert().isFalse()
        table("View").supportsFinal.assert().isFalse()
        table("t").qualifiedName.assert().isEqualTo("`db`.`t`")
        table("View").toString().assert().isEqualTo("ClickHouseTable(db.t, View)")
        quoteIdentifier("a`b").assert().isEqualTo("`a\\`b`")
        assertThrows<RuntimeException> { table("MergeTree").column("missing") }
    }

    @Test
    fun `the storage binding should name a table and how to deduplicate`() {
        ClickHouseDatasetStorage.of(dataset("""{"table":"bi.orders","deduplicate":"FINAL"}"""), "default")
            .assert().isEqualTo(ClickHouseDatasetStorage("bi", "orders", ClickHouseDatasetStorage.Deduplicate.FINAL))
        ClickHouseDatasetStorage.of(dataset("""{"table":"orders"}"""), "analytics")
            .assert().isEqualTo(ClickHouseDatasetStorage("analytics", "orders"))
        listOf(
            """{}""",
            """{"table":""}""",
            """{"table":"a.b.c"}""",
            """{"table":"a."}""",
            """{"table":"orders","deduplicate":"LATEST"}""",
            """{"table":"orders","deduplicate":1}""",
            """{"table":1}""",
            """{"table":"orders","engine":"x"}""",
        ).forEach { storage ->
            assertThrows<IllegalArgumentException>(storage) { ClickHouseDatasetStorage.of(dataset(storage), "default") }
        }
        // The factory refuses such a binding when it binds the dataset.
        assertThrows<IllegalArgumentException> { factory().create(dataset("""{}""")) }
    }

    @Test
    fun `the options should leave the execution time to each query's budget`() {
        ClickHouseDatasetOptions().database.assert().isEqualTo("default")
        assertThrows<IllegalArgumentException> { ClickHouseDatasetOptions(database = " ") }
        assertThrows<IllegalArgumentException> { ClickHouseDatasetOptions(metadataTtl = Duration.ofSeconds(-1)) }
        assertThrows<IllegalArgumentException> {
            ClickHouseDatasetOptions(
                settings = mapOf("max_execution_time" to "5")
            )
        }
        ClickHouseDatasetOptions(settings = mapOf("max_memory_usage" to "1")).settings.assert().hasSize(1)
    }

    @Test
    fun `a timeout should become max_execution_time in whole seconds, and the server's timeout the core's`() {
        ClickHouseExecutor.timeoutSettings(null).assert().isEmpty()
        ClickHouseExecutor.timeoutSettings(Duration.ofMillis(10)).assert()
            .containsEntry("max_execution_time", "1").containsEntry("timeout_overflow_mode", "throw")
        ClickHouseExecutor.timeoutSettings(Duration.ofMillis(2500)).assert().containsEntry("max_execution_time", "3")
        ClickHouseExecutor.timeoutSettings(Duration.ofSeconds(30)).assert().containsEntry("max_execution_time", "30")

        val timeout = Duration.ofSeconds(1)
        val server = ServerException(ClickHouseExecutor.TIMEOUT_EXCEEDED, "Timeout exceeded", 500, "q")
        ClickHouseExecutor.translate(
            RuntimeException(server),
            timeout
        ).assert().isInstanceOf(QueryTimeoutException::class.java)
        ClickHouseExecutor.translate(server, null).assert().isSameAs(server)
        val other = ServerException(60, "Unknown table", 404, "q")
        ClickHouseExecutor.translate(other, timeout).assert().isSameAs(other)
        val streamed = ClickHouseStreamException("Code: 159. DB::Exception: Timeout exceeded. (TIMEOUT_EXCEEDED)")
        streamed.code.assert().isEqualTo(159)
        ClickHouseExecutor.translate(streamed, timeout).assert().isInstanceOf(QueryTimeoutException::class.java)
        ClickHouseStreamException("garbage").code.assert().isNull()
    }

    @Test
    fun `sql constants and time zones should be rendered from validated values`() {
        1.5.sqlLiteral().assert().isEqualTo("toFloat64(1.5)")
        assertThrows<IllegalStateException> { Double.NaN.sqlLiteral() }
        "Asia/Shanghai".sqlTimeZone().assert().isEqualTo("'Asia/Shanghai'")
        "Z".sqlTimeZone().assert().isEqualTo("'UTC'")
        "UTC".sqlTimeZone().assert().isEqualTo("'UTC'")
        "+08:00".sqlTimeZone().assert().isEqualTo("'Etc/GMT-8'")
        "-05:00".sqlTimeZone().assert().isEqualTo("'Etc/GMT+5'")
        assertThrows<RuntimeException> { "+05:30".sqlTimeZone() }
        val parameters = SqlParameters()
        parameters.bind("x", "String").assert().isEqualTo("{p0:String}")
        parameters.bind(1, "Int64").assert().isEqualTo("{p1:Int64}")
        val statement = parameters.statement("SELECT 1")
        statement.parameters.assert().containsEntry("p0", "x").containsEntry("p1", 1)
        statement.toString().assert().isEqualTo("SELECT 1")
    }

    // One table of every way a declaration can contradict the table reads best as a single list.
    @Suppress("LongMethod")
    @Test
    fun `the table should confirm every declared field`() {
        val invalid = mapOf<String, QueryDatasetBuilder.() -> Unit>(
            "has no column for" to { field("missing") { types(QueryValueType.STRING) } },
            "declares a nested field" to {
                field("note") {
                    kind(QueryValueKind.OBJECT)
                    property("name") { types(QueryValueType.STRING) }
                }
            },
            "unsupported type" to { field("attrs") { types(QueryValueType.STRING) } },
            "is scalar" to {
                field("note") {
                    kind(QueryValueKind.ARRAY)
                    items { types(QueryValueType.STRING) }
                }
            },
            "as an array of strings" to { field("tags") { types(QueryValueType.STRING) } },
            "keys on the array column" to {
                owner("tags")
                field("tags") {
                    kind(QueryValueKind.ARRAY)
                    items { types(QueryValueType.STRING) }
                }
            },
            "holds" to { field("amount") { types(QueryValueType.STRING) } },
            "TEMPORAL_DATE" to { field("day") { types(QueryValueType.STRING) } },
            "non-null, but its column is Nullable" to {
                field("note") {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            },
        )
        invalid.forEach { (message, block) ->
            val dataset = QueryDataset.of(DATASET.name) {
                tenant("tenant_id")
                field("tenant_id") {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
                block()
            }
            assertThrows<QuerySchemaValidationException>(message) { ClickHouseQueryFixtures.schema(dataset) }
                .message.assert().contains(message)
        }
        val nullableTenant = QueryDataset.of(DATASET.name) {
            tenant("note")
            field("note") { types(QueryValueType.STRING) }
        }
        assertThrows<QuerySchemaValidationException> { ClickHouseQueryFixtures.schema(nullableTenant) }
            .message.assert().contains("keys on the Nullable column")
        val final = ClickHouseDatasetStorage("bi", "orders", ClickHouseDatasetStorage.Deduplicate.FINAL)
        assertThrows<QuerySchemaValidationException> { ClickHouseQueryFixtures.schema(DATASET, final) }
            .message.assert().contains("FINAL")
        val failing = ClickHouseQueryFixtures.adapter { throw IllegalStateException("down") }
        assertThrows<QuerySchemaUnavailableException> { ClickHouseQueryFixtures.schema(failing) }
        val unavailable = ClickHouseQueryFixtures.adapter { throw QuerySchemaUnavailableException("gone") }
        assertThrows<QuerySchemaUnavailableException> { ClickHouseQueryFixtures.schema(unavailable) }.message
            .assert().isEqualTo("gone")
    }

    @Test
    fun `the schema should grant each column what its type proves`() {
        val schema = ClickHouseQueryFixtures.SCHEMA
        schema.model.assert().isEqualTo(QueryModel.DATASET)
        fun capabilities(name: String) = checkNotNull(schema.field(QueryField(name))).capabilities
        capabilities("city").assert().contains(
            me.ahoo.wow.query.schema.QueryCapability.LITERAL_MATCH,
            me.ahoo.wow.query.schema.QueryCapability.CURSOR_SORT,
            me.ahoo.wow.query.schema.QueryCapability.AGGREGATE_TERMS,
        )
        capabilities("note").assert().contains(me.ahoo.wow.query.schema.QueryCapability.PRESENCE)
            .doesNotContain(me.ahoo.wow.query.schema.QueryCapability.CURSOR_SORT)
        capabilities("amount").assert().contains(me.ahoo.wow.query.schema.QueryCapability.AGGREGATE_NUMERIC)
            .doesNotContain(me.ahoo.wow.query.schema.QueryCapability.AGGREGATE_TERMS)
        capabilities("paid").assert().contains(me.ahoo.wow.query.schema.QueryCapability.AGGREGATE_TERMS)
        capabilities("created_at").assert().contains(me.ahoo.wow.query.schema.QueryCapability.AGGREGATE_TEMPORAL)
        capabilities("epoch_ms").assert().contains(me.ahoo.wow.query.schema.QueryCapability.AGGREGATE_TEMPORAL)
        schema.field(QueryField("attrs")).assert().isNull()
    }

    @Test
    fun `the metadata should be read again only once its time to live has passed`() = runBlocking {
        var now = 0L
        var reads = 0
        val metadata = ClickHouseTableMetadata(Duration.ofNanos(10), { now }) {
            reads++
            TABLE
        }
        metadata.table().assert().isSameAs(TABLE)
        metadata.table()
        reads.assert().isEqualTo(1)
        now = 10
        metadata.table()
        reads.assert().isEqualTo(2)
        metadata.refresh()
        reads.assert().isEqualTo(3)
        val backend = factory().create(DATASET).backend
        backend.aggregateType.assert().isEqualTo(DATASET.name)
        backend.toString().assert().isEqualTo("ClickHouseDatasetQueryBackend(bi.orders on bi.orders)")
        Unit
    }

    private fun column(type: String) = TypedColumn(ClickHouseColumn("c", type))

    private fun factory() = ClickHouseDatasetQueryBackendFactory(
        Client.Builder().addEndpoint("http://localhost:1").setUsername("default").setPassword("").build(),
    )

    private fun dataset(storage: String) = QueryDataset.of(AggregateType("tck", "orders")) {
        tenant("tenant_id")
        field("tenant_id") { types(QueryValueType.STRING) }
        storage(WowJson.mapper.readTree(storage) as tools.jackson.databind.node.ObjectNode)
    }
}
