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
import me.ahoo.wow.example.shop.domain.order.Order
import me.ahoo.wow.example.shop.domain.order.OrderRejections
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/** The order through the running shop server (memory backend), placed with the server's stub services. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderEndToEndTest {
    private val shop = startShop()

    @AfterAll
    fun close() {
        shop.close()
    }

    /** Places an order of [items] (`productId` to `price` and `quantity`) as [user], under [id] when given. */
    private fun createOrder(
        user: String = ALICE,
        id: String? = null,
        items: String = TWO_APPLES_AND_A_PEAR,
        headers: Map<String, String> = emptyMap(),
    ): Answer {
        val path = if (id == null) "/example/order/create-order" else "/example/order/$id/create-order"
        return shop.post(path, """{"items":$items,"address":$ADDRESS}""", user, headers)
    }

    private fun placed(): String {
        val created = createOrder()
        created.status.assert().isEqualTo(200)
        return created.json["ref"]["id"].asString()
    }

    private fun state(id: String, user: String = ALICE) = shop.get("/example/order/$id/state", user)

    @Test
    fun `placing an order should answer its total and create it with a server-generated id`() {
        val created = createOrder()
        created.status.assert().isEqualTo(200)
        created.json["status"].asString().assert().isEqualTo("SUCCEEDED")
        created.json["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
        val id = created.json["ref"]["id"].asString()
        id.assert().isNotBlank()

        val state = state(id)
        state.headers.eTag.assert().isEqualTo("\"1\"")
        state.json["state"]["status"].asString().assert().isEqualTo("CREATED")
        state.json["state"]["totalAmount"].decimalValue().assert().isEqualByComparingTo(TOTAL)
        state.json["state"]["items"].values().map { it["id"].asString() }.assert().containsExactly("$id-1", "$id-2")
    }

    @Test
    fun `an order for more than the stock should be rejected with the domain's 422`() {
        val rejected = createOrder(items = """[{"productId":"durian","price":30.00,"quantity":2}]""")
            .assertProblem(422, OrderRejections.INSUFFICIENT_INVENTORY)
        rejected.json["result"]["status"].asString().assert().isEqualTo("REJECTED")
        rejected.json["result"]["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        createOrder(items = """[{"productId":"apple","price":2.00,"quantity":1}]""")
            .assertProblem(422, OrderRejections.PRICE_CHANGED)
        createOrder(items = "[]").assertProblem(422, OrderRejections.NO_ITEMS)
    }

    @Test
    fun `an order should be paid, shipped and received, each step reading its own write`() {
        val id = placed()
        shop.post("/example/order/$id/pay-order", """{"paymentId":"payment-1","amount":9.00}""", ALICE)
            .json["version"].asInt().assert().isEqualTo(2)
        state(id).json["state"]["status"].asString().assert().isEqualTo("PAID")
        shop.post("/example/order/$id/ship-order", user = ALICE).status.assert().isEqualTo(200)
        state(id).json["state"]["status"].asString().assert().isEqualTo("SHIPPED")
        shop.post("/example/order/$id/receipt-order", user = ALICE).status.assert().isEqualTo(200)
        val received = state(id)
        received.json["state"]["status"].asString().assert().isEqualTo("RECEIVED")
        received.headers.eTag.assert().isEqualTo("\"4\"")
        // An earlier version stays readable.
        shop.get("/example/order/$id/state?version=2", ALICE).json["state"]["status"].asString()
            .assert().isEqualTo("PAID")
    }

    @Test
    fun `a command the order's status does not allow should be a 409`() {
        val id = placed()
        shop.post("/example/order/$id/ship-order", user = ALICE).assertProblem(409, OrderRejections.WRONG_STATUS)
    }

    @Test
    fun `If-Match should set the expected version, a mismatch answered 412`() {
        val id = placed()
        val change = """{"shippingAddress":$ADDRESS}"""
        shop.post("/example/order/$id/change-address", change, ALICE, mapOf(WowHeaders.IF_MATCH to "\"5\""))
            .assertProblem(412, FrameworkCodes.VERSION_MISMATCH)
        shop.post("/example/order/$id/change-address", change, ALICE, mapOf(WowHeaders.IF_MATCH to "\"1\""))
            .json["version"].asInt().assert().isEqualTo(2)
    }

    @Test
    fun `an explicit Wow-Wait committed should end at COMMITTED`() {
        val created = createOrder(headers = mapOf(WowHeaders.WAIT to "committed"))
        created.status.assert().isEqualTo(200)
        created.json["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        created.json["hop"]["subscription"].isNull.assert().isTrue()
        // DecideScope.putResult: the total the stub pricing service confirmed, answered by the commit, not persisted.
        created.json["results"][Order.TOTAL_AMOUNT_RESULT].decimalValue().assert().isEqualByComparingTo(TOTAL)
    }

    @Test
    fun `Wow-Wait accepted should answer 202 and run the command detached`() {
        val id = UUID.randomUUID().toString()
        val accepted = createOrder(id = id, headers = mapOf(WowHeaders.WAIT to "accepted"))
        accepted.status.assert().isEqualTo(202)
        accepted.json["hop"]["stage"].asString().assert().isEqualTo("ACCEPTED")
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (state(id).status != 200 && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        state(id).json["state"]["status"].asString().assert().isEqualTo("CREATED")
    }

    @Test
    fun `an event stream should send one event per hop until the wait ends`() {
        val events = shop.client.post().uri("/example/order/create-order")
            .header(HeaderCallers.USER, ALICE)
            .header(WowHeaders.WAIT, "committed > snapshot")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"items":$TWO_APPLES_AND_A_PEAR,"address":$ADDRESS}""")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(SSE)
            .responseBody
            .collectList()
            .block(Duration.ofSeconds(10))!!
        events.map { it.id() }.assert().containsExactly("0", "1")
        events.map { it.event() }.assert().containsExactly("committed", "handled")
        val results = events.map { json.readTree(it.data()) }
        results.map { it["status"].asString() }.assert().containsExactly("SUCCEEDED", "SUCCEEDED")
        results.last()["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
        results.first()["results"][Order.TOTAL_AMOUNT_RESULT].decimalValue().assert().isEqualByComparingTo(TOTAL)
    }

    @Test
    fun `an event stream refused at admission should be a problem`() {
        shop.client.post().uri("/example/order/create-order")
            .header(HeaderCallers.USER, ALICE)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"items":"none"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `the framework's problems should be reachable through the order`() {
        val id = UUID.randomUUID().toString()
        // No such order yet.
        shop.post("/example/order/$id/pay-order", """{"paymentId":"p","amount":1}""", ALICE)
            .assertProblem(404, FrameworkCodes.NOT_FOUND)
        createOrder(id = id).status.assert().isEqualTo(200)
        // Placed twice.
        createOrder(id = id).assertProblem(409, FrameworkCodes.EXISTS)
        // A wait the order cannot reach: it has no subscription of that name.
        shop.post("/example/order/$id/ship-order", user = ALICE, headers = mapOf(WowHeaders.WAIT to "handled(nope)"))
            .assertProblem(400, FrameworkCodes.WAIT_INVALID)
        // Another scope, asked for by a caller that may not act across scopes.
        shop.post("/example/order/$id/ship-order", user = ALICE, headers = mapOf(WowHeaders.SCOPE to "owner=\"bob\""))
            .assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `a request id should deduplicate a retry and refuse another command`() {
        val requestId = UUID.randomUUID().toString()
        val first = createOrder(headers = mapOf(WowHeaders.REQUEST_ID to requestId))
        first.status.assert().isEqualTo(200)
        val id = first.json["ref"]["id"].asString()
        val retry = createOrder(id = id, headers = mapOf(WowHeaders.REQUEST_ID to requestId))
        retry.status.assert().isEqualTo(200)
        retry.json["duplicate"].asBoolean().assert().isTrue()
        retry.json["version"].asInt().assert().isEqualTo(1)
        shop.post(
            "/example/order/$id/pay-order",
            """{"paymentId":"p","amount":1}""",
            ALICE,
            mapOf(WowHeaders.REQUEST_ID to requestId),
        ).assertProblem(409, FrameworkCodes.REQUEST_ID_REUSED)
    }

    private companion object {
        const val ALICE = "alice"
        const val POLL_MILLIS = 50L
        val TOTAL = BigDecimal("9.00")
        const val TWO_APPLES_AND_A_PEAR =
            """[{"productId":"apple","price":2.50,"quantity":2},{"productId":"pear","price":4.00,"quantity":1}]"""
        const val ADDRESS =
            """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1 Century Avenue"}"""
        val SSE = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }
}
