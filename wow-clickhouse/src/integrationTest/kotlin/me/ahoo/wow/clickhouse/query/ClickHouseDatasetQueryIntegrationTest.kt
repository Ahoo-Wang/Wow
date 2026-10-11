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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.aggregate
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.paged
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.query.schema.QuerySchemaDeclarationBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.node.ObjectNode
import java.time.ZoneId

/**
 * S0 spike: an order × customer wide table queried through the unchanged query core. Each test compares the Wow
 * result with hand-written SQL and records both latencies in the report printed at the end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClickHouseDatasetQueryIntegrationTest {
    private lateinit var clickHouse: ClickHouseContainer
    private lateinit var client: Client
    private lateinit var executor: ClickHouseExecutor
    private lateinit var dataset: ClickHouseDataset
    private lateinit var schema: QueryModelSchema
    private val report = mutableListOf<String>()

    @BeforeAll
    fun setUp() {
        clickHouse = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE))
        clickHouse.start()
        client = Client.Builder()
            .addEndpoint(clickHouse.httpUrl)
            .setUsername(clickHouse.username)
            .setPassword(clickHouse.password)
            .useAsyncRequests(true)
            .build()
        executor = ClickHouseExecutor(client)
        FIXTURE.forEach { raw(it) }
        dataset = ClickHouseDataset("order_customer_wide", "bi_db", "order_customer_wide", DECLARATION, executor)
        schema = dataset.schemaProvider.schema().block()!!
    }

    @AfterAll
    fun tearDown() {
        println(report.joinToString("\n", "\n==== S0 report ($ROWS rows) ====\n", "\n"))
        if (::client.isInitialized) client.close()
        if (::clickHouse.isInitialized) clickHouse.close()
    }

    @Test
    @Order(1)
    fun `top cities by GMV with distinct customers, p95 and a derived AOV`() {
        val query = aggregation {
            filter {
                "tenant_id" eq "t1"
                "status" isIn listOf("PAID", "SHIPPED")
            }
            terms("city", "city")
            count("orders")
            sum("amount", "gmv")
            distinctCount("customer_id", "customers")
            percentile("amount", 95.0, "p95")
            derived("aov") { ref("gmv") / ref("orders") }
            sort { "gmv".desc() }
            limit(3)
        }
        val rows = timed("terms + count/sum/distinct/p95/derived, top 3") { aggregate(query) }
        val expected = timedRaw(
            "SELECT city, count() AS orders, sum(amount) AS gmv, uniqExact(customer_id) AS customers, " +
                "quantileTDigest(0.95)(toFloat64(amount)) AS p95, gmv / orders AS aov " +
                "FROM bi_db.order_customer_wide WHERE tenant_id = 't1' AND status IN ('PAID', 'SHIPPED') " +
                "GROUP BY city ORDER BY gmv DESC, city ASC LIMIT 3",
        )
        rows.map { it.get("city").asString() }.assert().isEqualTo(expected.map { it.get("city").asString() })
        rows.zip(expected).forEach { (actual, raw) ->
            actual.get("orders").asLong().assert().isEqualTo(raw.get("orders").asLong())
            actual.get("gmv").decimalValue().assert().isEqualByComparingTo(raw.get("gmv").decimalValue())
            actual.get("customers").asLong().assert().isEqualTo(raw.get("customers").asLong())
            actual.get("aov").asDouble().assert()
                .isCloseTo(raw.get("gmv").asDouble() / raw.get("orders").asDouble(), org.assertj.core.data.Offset.offset(1e-6))
            actual.get("p95").isNumber.assert().isTrue()
        }
    }

    @Test
    @Order(2)
    fun `monthly GMV in Asia Shanghai keys buckets by local month start`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val query = aggregation {
            dateHistogram("created_at", AggregationDateUnit.MONTH, "month", zone)
            sum("amount", "gmv")
            count("paid") { "paid_at".isNotNull() }
            limit(24)
        }
        val rows = timed("date histogram MONTH Asia/Shanghai + metric filter") { aggregate(query) }
        val expected = timedRaw(
            "SELECT toInt64(toUnixTimestamp(toDateTime(toStartOfMonth(toTimeZone(created_at, 'Asia/Shanghai')), " +
                "'Asia/Shanghai'))) * 1000 AS month, sum(amount) AS gmv, countIf(isNotNull(paid_at)) AS paid " +
                "FROM bi_db.order_customer_wide GROUP BY month ORDER BY month LIMIT 24",
        )
        rows.size.assert().isEqualTo(expected.size)
        rows.first().get("month").asLong().assert()
            .isEqualTo(java.time.LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli())
        rows.zip(expected).forEach { (actual, raw) ->
            actual.get("month").asLong().assert().isEqualTo(raw.get("month").asLong())
            actual.get("paid").asLong().assert().isEqualTo(raw.get("paid").asLong())
        }
    }

    @Test
    @Order(3)
    fun `weekday by category with HAVING computed by the core`() {
        val query = aggregation {
            datePart("created_at", AggregationDatePart.DAY_OF_WEEK, "weekday", ZoneId.of("Asia/Shanghai"))
            terms("category", "category")
            count("orders")
            avg("discount", "avgDiscount")
            having { "orders" gt 30_000.0 }
            sort { "orders".desc() }
            limit(100)
        }
        val rows = timed("date part x terms + residual HAVING + metric sort") { aggregate(query) }
        rows.isNotEmpty().assert().isTrue()
        rows.all { it.get("orders").asLong() > 30_000 }.assert().isTrue()
        rows.zipWithNext().all { (a, b) -> a.get("orders").asLong() >= b.get("orders").asLong() }.assert().isTrue()
    }

    @Test
    @Order(4)
    fun `summary over no records keeps the spec's empty values`() {
        val query = aggregation {
            filter { "city" eq "Nowhere" }
            count("orders")
            sum("amount", "gmv")
            distinctCount("customer_id", "customers")
            percentile("amount", 50.0, "median")
            any("city", "anyCity")
            first("order_id", "firstOrder", orderBy = "created_at")
        }
        val row = aggregate(query).single()
        report += "empty summary: $row"
        row.get("orders").asLong().assert().isEqualTo(0)
        row.get("customers").asLong().assert().isEqualTo(0)
        row.get("gmv").isNull.assert().isTrue()
        row.get("median").isNull.assert().isTrue()
        row.get("anyCity").isNull.assert().isTrue()
        row.get("firstOrder").isNull.assert().isTrue()
    }

    @Test
    @Order(5)
    fun `paged records with filter, projection, sort and total`() {
        val query = pagedQuery {
            filter {
                "city" eq "Hangzhou"
                "customer_name".startsWithText("customer 12", StringComparison.CASE_INSENSITIVE)
            }
            projection { include("order_id", "customer_name", "amount", "created_at") }
            sort { "amount".desc() }
            pagination {
                index(2)
                size(20)
            }
        }
        val page = timed("paged: eq + startsWith ignoreCase + sort + total") { paged(query) }
        val total = timedRaw(
            "SELECT count() AS total FROM bi_db.order_customer_wide " +
                "WHERE city = 'Hangzhou' AND startsWith(lowerUTF8(customer_name), 'customer 12')",
        ).single().get("total").asLong()
        report += "paged total=${page.total} rows=${page.list.size} first=${page.list.firstOrNull()}"
        page.total.assert().isEqualTo(total)
    }

    @Test
    @Order(6)
    fun `null comparison on a non-nullable column is rejected before any I-O`() {
        val query = aggregation {
            filter { "city".isNull() }
            count("orders")
        }
        val error = assertThrows<Exception> { QueryAdmission.Trusted.aggregate(query, schema) }
        report += "IS_NULL on non-Nullable column: ${error.javaClass.simpleName}: ${error.message}"
        val nullable = aggregation {
            filter { "discount".isNull() }
            count("orders")
        }
        aggregate(nullable).single().get("orders").asLong().assert().isGreaterThan(0)
    }

    @Test
    @Order(7)
    fun `the capability descriptor view-engine reads is derived for a dataset`() {
        val descriptor = schema.describe(budget = null, defaultListSize = 100, timeZone = ZoneId.of("UTC"))
        val json = JsonSerializer.writeValueAsString(descriptor)
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/s0-dataset-descriptor.json"), json)
        report += "descriptor: ${json.length} chars, written to build/s0-dataset-descriptor.json"
        json.contains("customer_level").assert().isTrue()
    }

    private fun aggregate(query: AggregationQuery): List<ObjectNode> =
        dataset.backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).collectList().block()!!

    private fun paged(query: IPagedQuery) =
        dataset.backend.paged(QueryAdmission.Trusted.paged(query, schema)).block()!!

    private fun raw(sql: String): List<ObjectNode> =
        executor.rows(ClickHouseStatement(sql, emptyMap())).collectList().block()!!

    private fun <T> timed(label: String, block: () -> T): T {
        block() // warm up
        val samples = (1..SAMPLES).map {
            val started = System.nanoTime()
            block()
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        report += "wow  %-55s median %7.1f ms  p90 %7.1f ms".format(label, samples[SAMPLES / 2], samples[SAMPLES * 9 / 10])
        return block()
    }

    private fun timedRaw(sql: String): List<ObjectNode> = timed("raw  ${sql.take(48)}…") { raw(sql) }

    companion object {
        private const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        private const val SAMPLES = 10
        private val ROWS: Long = System.getenv("WOW_CLICKHOUSE_SPIKE_ROWS")?.toLong() ?: 1_000_000

        private val FIXTURE = listOf(
            "CREATE DATABASE bi_db",
            """
            CREATE TABLE bi_db.order_customer_wide (
                order_id String,
                tenant_id LowCardinality(String),
                customer_id String,
                customer_name String,
                customer_level LowCardinality(String),
                city LowCardinality(String),
                category LowCardinality(String),
                status Enum8('CREATED' = 1, 'PAID' = 2, 'SHIPPED' = 3, 'CANCELLED' = 4),
                amount Decimal(18, 2),
                quantity UInt32,
                discount Nullable(Float64),
                created_at DateTime64(3, 'UTC'),
                paid_at Nullable(DateTime64(3, 'UTC'))
            ) ENGINE = MergeTree ORDER BY (tenant_id, created_at, order_id)
            """.trimIndent(),
            """
            INSERT INTO bi_db.order_customer_wide
            SELECT
                concat('o', toString(number)),
                if(number % 10 = 0, 't2', 't1'),
                concat('c', toString(number % 50000)),
                concat('Customer ', toString(number % 50000)),
                ['GOLD', 'SILVER', 'BRONZE'][number % 3 + 1],
                ['Shanghai', 'Beijing', 'Shenzhen', 'Hangzhou', 'Chengdu'][number % 5 + 1],
                ['Book', 'Food', 'Toy', 'Phone'][intDiv(number, 7) % 4 + 1],
                CAST(['CREATED', 'PAID', 'SHIPPED', 'CANCELLED'][number % 4 + 1] AS Enum8('CREATED' = 1, 'PAID' = 2, 'SHIPPED' = 3, 'CANCELLED' = 4)),
                toDecimal64(1 + (number % 10000) / 100, 2),
                toUInt32(number % 5 + 1),
                if(number % 7 = 0, NULL, (number % 30) / 100),
                toDateTime64('2025-01-01 00:00:00', 3, 'UTC') + toIntervalSecond(number * 31),
                if(number % 4 IN (1, 2), toDateTime64('2025-01-01 00:00:00', 3, 'UTC') + toIntervalSecond(number * 31 + number % 3600), NULL)
            FROM numbers($ROWS)
            """.trimIndent(),
        )

        private val DECLARATION = QuerySchemaDeclarationBuilder().apply {
            listOf("order_id", "tenant_id", "customer_id", "customer_name", "customer_level", "city", "category")
                .forEach { field(it) { types(QueryValueType.STRING); nullable(false) } }
            field("status") {
                types(QueryValueType.STRING)
                nullable(false)
                listOf("CREATED", "PAID", "SHIPPED", "CANCELLED").forEach(::enumValue)
            }
            field("amount") { types(QueryValueType.DECIMAL); nullable(false) }
            field("quantity") { types(QueryValueType.INTEGER); nullable(false) }
            field("discount") { types(QueryValueType.DECIMAL); nullable(true) }
            field("created_at") { types(QueryValueType.STRING); nullable(false); semantic(Temporal.Date) }
            field("paid_at") { types(QueryValueType.STRING); nullable(true); semantic(Temporal.Date) }
        }.build()
    }
}
