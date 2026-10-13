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

package me.ahoo.wow.example.shop.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.spring.boot.BackendKind
import me.ahoo.wow.spring.boot.HostBackends
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The V10 analytics end to end (decision D145), on PostgreSQL and ClickHouse: the shop commits its orders to
 * PostgreSQL, the ClickHouse Sink writes the order stream to `example_order_state_flat`, and the dataset that
 * `META-INF/wow/dataset/example.orders.json` declares over that view is queried on the dataset route,
 * `/wow/dataset/example/orders/query`, in the caller's tenant and scope.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopClickHouseDatasetTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
    private val clickhouse = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).also { it.start() }
    private val shop = startShop(
        "spring.datasource.url" to postgres.jdbcUrl,
        "spring.datasource.username" to postgres.username,
        "spring.datasource.password" to postgres.password,
        "wow.clickhouse.endpoint" to clickhouse.httpUrl,
        "wow.clickhouse.username" to clickhouse.username,
        "wow.clickhouse.password" to clickhouse.password,
        profiles = listOf(PostgresDataSource.PROFILE),
    )

    @AfterAll
    fun close() {
        shop.close()
        clickhouse.stop()
        postgres.stop()
    }

    /** A tenant of this test only. */
    private fun tenant(): String = "t-" + UUID.randomUUID().toString().take(8)

    private fun caller(tenant: String) = mapOf(HeaderCallers.TENANT to tenant)

    /** A caller of [tenant] across its owners: `Wow-Scope` names the tenant alone. */
    private fun wholeTenant(tenant: String) =
        mapOf(HeaderCallers.TENANT to tenant, HeaderCallers.ADMIN to "yes", WowHeaders.SCOPE to "tenant=\"$tenant\"")

    private fun placeOrder(user: String, tenant: String): String {
        val created = shop.post(
            "/example/order/create-order",
            """{"items":[{"productId":"apple","price":2.50,"quantity":2}],"address":$ADDRESS}""",
            user,
            caller(tenant),
        )
        created.status.assert().isEqualTo(200)
        return created.json["ref"]["id"].asString()
    }

    private fun query(body: String, user: String, headers: Map<String, String>): Answer =
        shop.post(ORDERS, body, user, headers)

    /** The rows of [answer], a list. */
    private fun rows(answer: Answer): List<JsonNode> {
        answer.status.assert().isEqualTo(200)
        return answer.json.values().toList()
    }

    /** Waits until [condition] holds of the rows [body] lists as [user] with [headers]: the Sink writes behind. */
    private fun awaitRows(
        body: String,
        user: String,
        headers: Map<String, String>,
        condition: (List<JsonNode>) -> Boolean,
    ): List<JsonNode> {
        val deadline = System.nanoTime() + AWAIT_NANOS
        var last: Answer? = null
        while (System.nanoTime() < deadline) {
            // Until the Sink has created the view, the dataset's storage is unavailable.
            val answer = query(body, user, headers)
            last = answer
            if (answer.status == 200 && condition(answer.json.values().toList())) return rows(answer)
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("The dataset never matched; the last answer: ${last?.status} ${last?.body}")
    }

    @Test
    fun `orders should reach the dataset through the Sink, and be queried in the caller's tenant and scope`() {
        val east = tenant()
        val west = tenant()
        val aliceOrders = listOf(placeOrder(ALICE, east), placeOrder(ALICE, east))
        val bobOrder = placeOrder(BOB, east)
        val westOrder = placeOrder(ALICE, west)
        shop.post(
            "/example/order/${aliceOrders[0]}/pay-order",
            """{"paymentId":"payment-1","amount":5.00}""",
            ALICE,
            caller(east),
        ).status.assert().isEqualTo(200)

        // The paid order's current state, version 2, replaces version 1 in the flat view.
        val mine = awaitRows(ALL, ALICE, caller(east)) { rows ->
            rows.size == 2 && rows.any { it["aggregate_id"].asString() == aliceOrders[0] && it["version"].asInt() == 2 }
        }
        mine.map { it["aggregate_id"].asString() }.assert().containsExactlyInAnyOrderElementsOf(aliceOrders)
        mine.map { it["tenant_id"].asString() }.assert().containsOnly(east)
        mine.map { it["owner_id"].asString() }.assert().containsOnly(ALICE)
        mine.single { it["aggregate_id"].asString() == aliceOrders[0] }["id"].asString().assert()
            .isEqualTo("example.order/$east/${aliceOrders[0]}@2")

        // Bob sees his only, and Alice in the other tenant hers there only.
        rows(query(ALL, BOB, caller(east))).map { it["aggregate_id"].asString() }.assert().containsExactly(bobOrder)
        awaitRows(ALL, ALICE, caller(west)) { it.size == 1 }
            .map { it["aggregate_id"].asString() }.assert().containsExactly(westOrder)
        // Naming another tenant's row reads nothing: the caller's tenant is ANDed with the query.
        rows(
            query(
                """{"kind":"list","filter":{"op":"EQ","field":"aggregate_id","value":"$westOrder"}}""",
                ALICE,
                caller(east),
            ),
        ).assert().isEmpty()

        // An aggregation over the whole tenant: the orders and their commits by owner.
        val byOwner = query(
            """{"kind":"aggregate","filter":{"op":"MATCH_ALL"},""" +
                """"groupBy":[{"type":"TERMS","field":"owner_id","alias":"owner"}],""" +
                """"metrics":[{"type":"COUNT","alias":"orders"},""" +
                """{"type":"NUMERIC","function":"SUM","expression":{"type":"FIELD","field":"version"},"alias":"commits"}]}""",
            ALICE,
            wholeTenant(east),
        )
        rows(byOwner).associate { it["owner"].asString() to (it["orders"].asLong() to it["commits"].asLong()) }
            .assert().isEqualTo(mapOf(ALICE to (2L to 3L), BOB to (1L to 1L)))

        val counted = query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""", ALICE, wholeTenant(east))
        counted.json["count"].asLong().assert().isEqualTo(3)
        query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""", ALICE, wholeTenant(west)).json["count"].asLong()
            .assert().isEqualTo(1)

        // A keyset walk by commit time.
        val page = query(
            """{"kind":"cursor","filter":{"op":"MATCH_ALL"},"sort":[{"field":"commit_time","direction":"ASC"}],"size":2}""",
            ALICE,
            wholeTenant(east),
        )
        page.status.assert().isEqualTo(200)
        page.json["list"].values().toList().assert().hasSize(2)
        page.json["nextCursor"].isString.assert().isTrue()
    }

    @Test
    fun `the dataset should be listed and described`() {
        shop.context.getBean(HostBackends::class.java).kind.assert().isEqualTo(BackendKind.POSTGRES)
        shop.context.getBean(HttpQueries::class.java).datasets[AggregateType("example", "orders")].assert().isNotNull()
        val listed = shop.get("/wow/dataset", ALICE)
        listed.status.assert().isEqualTo(200)
        val entry = listed.json.values().single { it["name"].asString() == "example.orders" }
        entry["title"].asString().assert().isEqualTo("Orders")
        entry["path"].asString().assert().isEqualTo(ORDERS)

        // The descriptor compiles against the view, which the Sink creates before its first write.
        placeOrder(ALICE, tenant())
        val deadline = System.nanoTime() + AWAIT_NANOS
        var described = shop.get(ORDERS, ALICE)
        while (described.status != 200 && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            described = shop.get(ORDERS, ALICE)
        }
        described.status.assert().isEqualTo(200)
        described.json["model"].asString().assert().isEqualTo("DATASET")
        described.json["fields"].values().map { it["path"].asString() }.assert()
            .contains("aggregate_id", "tenant_id", "owner_id", "version", "commit_time")
        val tag = described.headers.eTag
        tag.assert().isEqualTo("\"${described.json["version"].asString()}\"")
        shop.get(ORDERS, ALICE, mapOf(HttpHeaders.IF_NONE_MATCH to tag!!)).status.assert().isEqualTo(304)
        shop.get("/wow/dataset/example/none/query", ALICE).status.assert().isEqualTo(404)
        shop.get("/wow/dataset", null).status.assert().isEqualTo(401)
    }

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val ORDERS = "/wow/dataset/example/orders/query"
        const val ALL = """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"aggregate_id","direction":"ASC"}]}"""
        const val ALICE = "alice"
        const val BOB = "bob"
        const val ADDRESS =
            """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1"}"""
        const val AWAIT_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 200L
    }
}
