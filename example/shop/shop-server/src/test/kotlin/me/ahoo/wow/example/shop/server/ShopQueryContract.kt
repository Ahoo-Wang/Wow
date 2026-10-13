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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.example.shop.testing.MemorySnapshotQueries
import me.ahoo.wow.http.WowHeaders
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.time.Duration
import java.util.UUID
import tools.jackson.databind.JsonNode

/**
 * The shop's orders and carts queried over HTTP (decisions D127, D129–D131, D150), the same contract on every backend
 * the shop runs on: the descriptor and its ETag, the caller's scope, bounded counts, cursor paging, the event stream of
 * a list, and the events model.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ShopQueryContract {
    /** The shop server the queries run on. */
    protected abstract val shop: ShopNode

    @AfterAll
    fun closeShop() {
        shop.close()
        afterClose()
    }

    /** What else the test stops once the shop is closed, such as its database. */
    protected open fun afterClose() = Unit

    /** A user of this test only, so the orders it places are its own. */
    private fun user(): String = "u-" + UUID.randomUUID().toString().take(8)

    /** Places an order as [user] and returns its id. */
    private fun placeOrder(user: String): String {
        val created = shop.post(
            "/example/order/create-order",
            """{"items":[{"productId":"apple","price":2.50,"quantity":2}],"address":$ADDRESS}""",
            user,
        )
        created.status.assert().isEqualTo(200)
        return created.json["ref"]["id"].asString()
    }

    private fun query(path: String, body: String, user: String?): Answer = shop.post(path, body, user)

    private fun ids(answer: Answer): List<String> {
        answer.status.assert().isEqualTo(200)
        val rows = if (answer.json.isArray) answer.json else answer.json["list"]
        return rows.values().map { it["aggregateId"].asString() }
    }

    @Test
    fun `the descriptor should be answered with its version as ETag, and 304 when it is named`() {
        listOf(ORDERS, CARTS, ORDER_EVENTS).forEach { path ->
            val described = shop.get(path, user())
            described.status.assert().isEqualTo(200)
            val tag = described.headers.eTag
            tag.assert().isEqualTo("\"${described.json["version"].asString()}\"")
            shop.get(path, user(), mapOf(HttpHeaders.IF_NONE_MATCH to tag!!)).status.assert().isEqualTo(304)
        }
        val orders = shop.get(ORDERS, user()).json
        orders["model"].asString().assert().isEqualTo("SNAPSHOT")
        orders["fields"].values().map { it["path"].asString() }.assert().contains("aggregateId", "ownerId", "state")
    }

    @Test
    fun `an order should be visible to its owner only`() {
        val alice = user()
        val bob = user()
        val mine = listOf(placeOrder(alice), placeOrder(alice))
        val theirs = placeOrder(bob)
        val listed = query(ORDERS, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", alice)
        ids(listed).assert().containsExactlyInAnyOrderElementsOf(mine)
        listed.json.values().map { it["ownerId"].asString() }.assert().containsOnly(alice)
        ids(query(ORDERS, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", bob)).assert().containsExactly(theirs)
        // Naming another owner's order reads nothing: the caller's scope is ANDed with the query.
        ids(query(ORDERS, """{"kind":"list","filter":{"op":"ID","value":"$theirs"}}""", alice)).assert().isEmpty()
        query(ORDERS, """{"kind":"single","filter":{"op":"ID","value":"$theirs"}}""", alice).status.assert()
            .isEqualTo(404)
        query(ORDERS, """{"kind":"single","filter":{"op":"ID","value":"${mine[0]}"}}""", alice)
            .json["state"]["status"].asString().assert().isEqualTo("CREATED")
        // Without credentials, no query.
        query(ORDERS, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", null).status.assert().isEqualTo(401)
    }

    @Test
    fun `a count should be bounded and say whether it is exact`() {
        val alice = user()
        repeat(3) { placeOrder(alice) }
        val bounded = query(ORDERS, """{"kind":"count","filter":{"op":"MATCH_ALL"},"limit":2}""", alice)
        bounded.status.assert().isEqualTo(200)
        bounded.json["count"].asLong().assert().isEqualTo(2)
        bounded.json["exact"].asBoolean().assert().isFalse()
        val exact = query(ORDERS, """{"kind":"count","filter":{"op":"MATCH_ALL"}}""", alice)
        exact.json["count"].asLong().assert().isEqualTo(3)
        exact.json["exact"].asBoolean().assert().isTrue()
        val paged = query(
            ORDERS,
            """{"kind":"paged","filter":{"op":"MATCH_ALL"},"pagination":{"index":1,"size":2}}""",
            alice
        )
        paged.json["total"].asLong().assert().isEqualTo(3)
        paged.json["list"].values().assert().hasSize(2)
    }

    @Test
    fun `a cursor should page through the caller's orders once`() {
        val alice = user()
        val placed = (1..5).map { placeOrder(alice) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val after = cursor?.let { ""","cursor":"$it"""" }.orEmpty()
            val page = query(
                ORDERS,
                """{"kind":"cursor","filter":{"op":"MATCH_ALL"},"sort":[{"field":"lastEventTime","direction":"DESC"}],"size":2$after}""",
                alice,
            )
            seen += ids(page)
            cursor = page.json["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        seen.assert().hasSize(5).containsExactlyInAnyOrderElementsOf(placed)
    }

    @Test
    fun `a list should stream as server-sent events`() {
        val alice = user()
        val placed = (1..3).map { placeOrder(alice) }
        val body = shop.client.post().uri(ORDERS)
            .header(HeaderCallers.USER, alice)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String::class.java)
            .returnResult().responseBody.orEmpty()
        val events = body.split("\n\n").filter { it.isNotBlank() }
        events.map { block -> block.lines().first { it.startsWith("event:") }.removePrefix("event:").trim() }
            .assert().containsOnly("row")
        events.map { block ->
            val data = block.lines().filter { it.startsWith("data:") }.joinToString("") { it.removePrefix("data:") }
            json.readTree(data)["aggregateId"].asString()
        }.assert().containsExactlyInAnyOrderElementsOf(placed)
    }

    @Test
    fun `a cart should be queried by its owner`() {
        val alice = user()
        shop.post("/example/cart/$alice/add-cart-item", """{"productId":"apple","quantity":2}""", alice)
            .status.assert().isEqualTo(200)
        val listed = query(CARTS, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", alice)
        ids(listed).assert().containsExactly(alice)
        listed.json[0]["state"]["items"][0]["productId"].asString().assert().isEqualTo("apple")
        listed.json[0]["version"].asInt().assert().isEqualTo(1)
        ids(query(CARTS, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", user())).assert().isEmpty()
    }

    @Test
    fun `an order's events should be read by version, for its owner only`() {
        val alice = user()
        val id = placeOrder(alice)
        val events = query(ORDER_EVENTS, """{"kind":"list","filter":{"op":"ID","value":"$id"}}""", alice)
        events.status.assert().isEqualTo(200)
        events.json.values().map { it["version"].asInt() }.assert().containsExactly(1)
        events.json[0]["events"][0]["name"].asString().assert().isEqualTo("example.order.OrderCreated")
        query(ORDER_EVENTS, """{"kind":"list","filter":{"op":"ID","value":"$id"}}""", user()).json.values()
            .assert().isEmpty()
        query(ORDER_EVENTS, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", alice)
            .assertProblem(400, "wow.query.invalid")
    }

    /**
     * The audit trail of decision D163, end to end: the caller's operator is recorded by every commit of its HTTP
     * commands, by the command a saga derives from them (which has no caller) and, for a cross-scope caller, with its own
     * tenant and the cross-scope mark; the snapshot keeps the last operator and the creator, and a snapshot query
     * filters by them.
     */
    @Test
    fun `an audit should follow the operator from the HTTP command through the event stream to the snapshot query`() {
        val alice = user()
        val admin = user()
        listOf("apple", "pear", "durian").forEach { product ->
            shop.post("/example/cart/$alice/add-cart-item", """{"productId":"$product"}""", alice)
                .status.assert().isEqualTo(200)
        }
        // CartSaga removes the ordered products from alice's cart: a derived command, of alice's operator.
        val placed = shop.post(
            "/example/order/create-order",
            """{"items":$APPLE_AND_PEAR,"address":$ADDRESS,"fromCart":true}""",
            alice,
            mapOf(WowHeaders.WAIT to "committed > handled(example/CartSaga.onOrderCreated)"),
        )
        placed.status.assert().describedAs(placed.body.toString()).isEqualTo(200)
        placed.json["derived"].values().toList().assert().hasSize(1)
        val order = placed.json["ref"]["id"].asString()

        // The order's event stream: the HTTP command's operator, alice in her own tenant.
        val orderEvents = query(ORDER_EVENTS, """{"kind":"list","filter":{"op":"ID","value":"$order"}}""", alice)
        orderEvents.status.assert().isEqualTo(200)
        orderEvents.json[0]["operator"].assertOperator(alice)

        // The saga's command committed the cart's fourth version in alice's name, though no one called it.
        fun cart() = query(CARTS, """{"kind":"single","filter":{"op":"ID","value":"$alice"}}""", alice)
        eventually { cart().json?.get("version")?.asInt() == 4 }
        val cart = cart()
        cart.json["state"]["items"].values().map { it["productId"].asString() }.assert().containsExactly("durian")
        cart.json["operator"].assertOperator(alice)
        cart.json["firstOperator"].assertOperator(alice)

        // An administrator acting in alice's scope through Wow-Scope: its own tenant, marked as acting elsewhere.
        shop.post(
            "/example/cart/$alice/add-cart-item",
            """{"productId":"fig"}""",
            admin,
            mapOf(HeaderCallers.ADMIN to "true", WowHeaders.SCOPE to "owner=\"$alice\"", WowHeaders.WAIT to "snapshot"),
        ).status.assert().isEqualTo(200)

        // The snapshot query filters by the operator and the creator.
        fun carts(filter: String) = ids(query(CARTS, """{"kind":"list","filter":$filter}""", alice))
        carts("""{"op":"EQ","field":"operator.id","value":"$admin"}""").assert().containsExactly(alice)
        carts("""{"op":"EQ","field":"operator.crossScope","value":true}""").assert().containsExactly(alice)
        carts("""{"op":"EQ","field":"firstOperator.id","value":"$alice"}""").assert().containsExactly(alice)
        carts("""{"op":"EQ","field":"operator.id","value":"$alice"}""").assert().isEmpty()
        val latest = query(CARTS, """{"kind":"single","filter":{"op":"ID","value":"$alice"}}""", alice).json
        latest["operator"].assertOperator(admin, crossScope = true)
        latest["firstOperator"].assertOperator(alice)
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in 30 seconds." }
            Thread.sleep(50)
        }
    }

    private fun JsonNode.assertOperator(id: String, crossScope: Boolean = false) {
        this["id"].asString().assert().isEqualTo(id)
        this["tenantId"].asString().assert().isEqualTo(Caller.DEFAULT_TENANT)
        this["crossScope"].asBoolean().assert().isEqualTo(crossScope)
    }

    @Test
    fun `a query the model refuses should be wow-query-invalid, one it cannot read wow-query-malformed`() {
        val alice = user()
        query(ORDERS, """{"kind":"list","filter":{"op":"EQ","field":"state.nope","value":1}}""", alice)
            .assertProblem(400, "wow.query.invalid")
            .json["violations"][0]["code"].asString().assert().isEqualTo("UNKNOWN_FIELD")
        query(
            ORDERS,
            """{"kind":"lists","filter":{"op":"MATCH_ALL"}}""",
            alice
        ).assertProblem(400, "wow.query.malformed")
    }

    protected companion object {
        const val ORDERS = "/example/order/snapshot/query"
        const val CARTS = "/example/cart/snapshot/query"
        const val ORDER_EVENTS = "/example/order/events/query"
        const val APPLE_AND_PEAR =
            """[{"productId":"apple","price":2.50,"quantity":1},{"productId":"pear","price":4.00,"quantity":1}]"""
        const val ADDRESS =
            """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1 Century Avenue"}"""
    }
}

/** The contract on the memory backend, its snapshots in the in-memory reference query backend. */
class ShopQueryEndToEndTest : ShopQueryContract() {
    override val shop: ShopNode = startShop(sources = listOf(MemorySnapshotQueries::class.java))
}
