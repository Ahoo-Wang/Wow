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

package me.ahoo.wow.example.eshop.ordering.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.OWNER
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.spring.boot.HostBackends
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * The ordering service in this JVM in its `postgres` and `clickhouse` profiles, as a deployment runs it, its command
 * transport in memory ([InMemoryOrderingTopics]): the orders are committed to PostgreSQL and served by its snapshot
 * queries, and the ClickHouse Sink writes the order stream to `eshop_ordering_order_state_flat`, over which
 * `META-INF/wow/dataset/eshop-ordering.sales.json` declares the sales dataset (decision D145). Both are read in the
 * caller's scope: a buyer reads their own orders, an employee every order of the tenant (decision D189).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderingAnalyticsTest {
    // region containers
    private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
    private val clickhouse = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).also { it.start() }
    private val context = SpringApplicationBuilder(OrderingServer::class.java, InMemoryOrderingTopics::class.java)
        .profiles("postgres", "clickhouse", "dev")
        .run(
            "--spring.main.banner-mode=off",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--wow.clickhouse.endpoint=${clickhouse.httpUrl}",
            "--wow.clickhouse.username=${clickhouse.username}",
            "--wow.clickhouse.password=${clickhouse.password}",
            "--wow.log.partitions=4",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--wow.internal.port=0",
            "--wow.internal.host=127.0.0.1",
            "--wow.cluster.drain-timeout=1s",
            "--wow.subscription.stop-timeout=2s",
            "--wow.wait.max-timeout=15s",
        )
    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    // endregion

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @AfterAll
    fun close() {
        try {
            context.close()
            http.shutdownNow()
        } finally {
            clickhouse.stop()
            postgres.stop()
        }
    }

    @Test
    @OptIn(ExperimentalWowApi::class) // the dataset queries are experimental (D216)
    fun `the orders should be served from PostgreSQL, and the sales dataset from ClickHouse`() {
        context.getBean(HostBackends::class.java).type.assert().isEqualTo("postgres")
        val queries = context.getBean(HttpQueries::class.java)
        queries.snapshots(EshopOrderingContract.Order.TYPE).assert().isNotNull()
        queries.datasets[AggregateType(EshopOrderingContract.NAME, "sales")].assert().isNotNull()
    }

    @Test
    fun `an order should be visible to its buyer only, and every order to an employee`() {
        val alice = buyer()
        val bob = buyer()
        val hers = listOf(submit(alice), submit(alice))
        val his = submit(bob)
        ids(post(ORDERS, MATCH_ALL, alice)).assert().containsExactlyInAnyOrderElementsOf(hers)
        ids(post(ORDERS, MATCH_ALL, bob)).assert().containsExactly(his)
        // Naming another buyer's order reads nothing: the caller's scope is ANDed with the query.
        ids(post(ORDERS, """{"kind":"list","filter":{"op":"ID","value":"$his"}}""", alice)).assert().isEmpty()
        post(
            ORDERS,
            """{"kind":"single","filter":{"op":"ID","value":"$his"}}""",
            alice
        ).first.assert().isEqualTo(NOT_FOUND)
        val all = ids(
            post(
                ORDERS,
                """{"kind":"list","filter":{"op":"IN","field":"aggregateId","values":${array(hers + his)}}}""",
                EMPLOYEE
            )
        )
        all.assert().containsExactlyInAnyOrderElementsOf(hers + his)
        // An order's events, for its buyer only.
        val events = post(ORDER_EVENTS, """{"kind":"list","filter":{"op":"ID","value":"$his"}}""", bob)
        events.first.assert().isEqualTo(OK)
        events.second.values().first()["events"][0]["name"].asString().assert().isEqualTo(
            "eshop-ordering.order.OrderSubmitted"
        )
        post(ORDER_EVENTS, """{"kind":"list","filter":{"op":"ID","value":"$his"}}""", alice).second.values().toList()
            .assert().isEmpty()
    }

    @Test
    fun `orders should reach the sales dataset through the Sink, each in its current state, in the caller's scope`() {
        val alice = buyer()
        val bob = buyer()
        val hers = listOf(submit(alice), submit(alice))
        val his = submit(bob)
        val ours = """{"kind":"list","filter":{"op":"IN","field":"aggregate_id","values":${array(hers + his)}},""" +
            """"sort":[{"field":"aggregate_id","direction":"ASC"}]}"""

        // The order process has each order await its stock validation: version 2 replaces version 1 in the flat view.
        val rows = awaitSales(ours, EMPLOYEE) { rows ->
            rows.size == 3 && rows.all { it["version"].asInt() == 2 && it["status"].asString() == "AWAITING_VALIDATION" }
        }
        rows.map { it["aggregate_id"].asString() }.assert().containsExactlyInAnyOrderElementsOf(hers + his)
        rows.single { it["aggregate_id"].asString() == his }.let { row ->
            row["id"].asString().assert().isEqualTo("eshop-ordering.order/${Caller.DEFAULT_TENANT}/$his@2")
            row["owner_id"].asString().assert().isEqualTo(bob.getValue(USER))
            row["totalAmount"].decimalValue().assert().isEqualByComparingTo("29.00")
        }

        // A buyer reads their own orders' rows only, even naming another's.
        sales(ours, alice).map { it["aggregate_id"].asString() }.assert().containsExactlyInAnyOrderElementsOf(hers)
        sales(ours, bob).map { it["aggregate_id"].asString() }.assert().containsExactly(his)
        sales(
            """{"kind":"list","filter":{"op":"EQ","field":"aggregate_id","value":"$his"}}""",
            alice
        ).assert().isEmpty()

        // An employee's revenue by buyer, over the tenant.
        val byBuyer = """{"kind":"aggregate","filter":{"op":"IN","field":"aggregate_id","values":${array(hers + his)}},""" +
            """"groupBy":[{"type":"TERMS","field":"owner_id","alias":"buyer"}],""" +
            """"metrics":[{"type":"COUNT","alias":"orders"},$REVENUE]}"""
        sales(
            byBuyer,
            EMPLOYEE
        ).associate { it["buyer"].asString() to (it["orders"].asLong() to it["revenue"].decimalValue().toInt()) }
            .assert().isEqualTo(
                mapOf(
                    alice.getValue(USER) to (2L to 58),
                    bob.getValue(USER) to (1L to 29),
                ),
            )
        val count = post(
            SALES,
            """{"kind":"count","filter":{"op":"IN","field":"aggregate_id","values":${array(hers)}}}""",
            alice
        )
        count.second["count"].asLong().assert().isEqualTo(2)
    }

    @Test
    fun `the sales dataset should be listed and described, with its version as ETag`() {
        val listed = get("/wow/dataset", EMPLOYEE)
        listed.first.assert().isEqualTo(OK)
        val entry = listed.second.values().single { it["name"].asString() == "eshop-ordering.sales" }
        entry["title"].asString().assert().isEqualTo("Sales")
        entry["path"].asString().assert().isEqualTo(SALES)

        // The descriptor compiles against the view, which the Sink creates before its first write.
        submit(buyer())
        val deadline = System.nanoTime() + AWAIT.toNanos()
        var described = get(SALES, EMPLOYEE)
        while (described.first != OK && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            described = get(SALES, EMPLOYEE)
        }
        described.first.assert().describedAs(described.second.toString()).isEqualTo(OK)
        described.second["model"].asString().assert().isEqualTo("DATASET")
        described.second["fields"].values().map { it["path"].asString() }.assert()
            .contains("aggregate_id", "owner_id", "status", "totalAmount", "commit_time")
        val tag = described.third
        tag.assert().isEqualTo("\"${described.second["version"].asString()}\"")
        get(SALES, EMPLOYEE, mapOf("If-None-Match" to tag!!)).first.assert().isEqualTo(NOT_MODIFIED)
        get("/wow/dataset/eshop-ordering/none/query", EMPLOYEE).first.assert().isEqualTo(NOT_FOUND)
    }

    /** A buyer of this test only. */
    private fun buyer(): Map<String, String> =
        ("buyer-" + UUID.randomUUID().toString().take(8)).let { mapOf(USER to it, OWNER to it) }

    /** Submits an order of 29.00 as [buyer]; its id. */
    private fun submit(buyer: Map<String, String>): String {
        val id = "order-" + UUID.randomUUID()
        val submitted = post(
            "/eshop-ordering/order/$id/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2},{"productId":"cap","unitPrice":12.00,"quantity":1}]}""",
            buyer,
        )
        submitted.first.assert().describedAs(submitted.second.toString()).isEqualTo(OK)
        return id
    }

    private fun ids(answer: Pair<Int, JsonNode>): List<String> {
        answer.first.assert().describedAs(answer.second.toString()).isEqualTo(OK)
        return answer.second.values().map { it["aggregateId"].asString() }
    }

    private fun sales(query: String, caller: Map<String, String>): List<JsonNode> {
        val answer = post(SALES, query, caller)
        answer.first.assert().describedAs(answer.second.toString()).isEqualTo(OK)
        return answer.second.values().toList()
    }

    /** Waits until [condition] holds of the rows [query] lists as [caller]: the Sink writes behind the commits. */
    private fun awaitSales(
        query: String,
        caller: Map<String, String>,
        condition: (List<JsonNode>) -> Boolean
    ): List<JsonNode> {
        val deadline = System.nanoTime() + AWAIT.toNanos()
        var last: Pair<Int, JsonNode>? = null
        while (System.nanoTime() < deadline) {
            // Until the Sink has created the view, the dataset's storage is unavailable.
            val answer = post(SALES, query, caller)
            last = answer
            if (answer.first == OK && condition(answer.second.values().toList())) return answer.second.values().toList()
            Thread.sleep(POLL.toMillis())
        }
        throw AssertionError("The sales dataset never matched; the last answer: ${last?.first} ${last?.second}")
    }

    private fun array(values: List<String>): String = values.joinToString(",", "[", "]") { "\"$it\"" }

    private fun post(path: String, body: String, headers: Map<String, String>): Pair<Int, JsonNode> {
        val request = HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
    }

    /** GETs [path]: the status, the body and the ETag. */
    private fun get(
        path: String,
        caller: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Triple<Int, JsonNode, String?> {
        val request = HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT).GET()
        (caller + headers).forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Triple(
            response.statusCode(),
            json.readTree(response.body().ifBlank { "{}" }),
            response.headers().firstValue("ETag").orElse(null),
        )
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val ORDERS = "/eshop-ordering/order/snapshot/query"
        const val ORDER_EVENTS = "/eshop-ordering/order/events/query"
        const val SALES = "/wow/dataset/eshop-ordering/sales/query"
        const val MATCH_ALL = """{"kind":"list","filter":{"op":"MATCH_ALL"}}"""
        const val REVENUE =
            """{"type":"NUMERIC","function":"SUM","expression":{"type":"FIELD","field":"totalAmount"},"alias":"revenue"}"""
        const val OK = 200
        const val NOT_MODIFIED = 304
        const val NOT_FOUND = 404
        val EMPLOYEE = mapOf(USER to "erin", ROLES to "employee")
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        val AWAIT: Duration = Duration.ofSeconds(60)
        val POLL: Duration = Duration.ofMillis(200)
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
