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
import me.ahoo.wow.example.shop.domain.cart.CartRejections
import me.ahoo.wow.example.shop.domain.order.OrderRejections
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.node.Routing
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import tools.jackson.databind.JsonNode

/** The shop server as `application.yaml` configures it, and the Manifest it serves at `/wow/manifest`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopServerTest {
    private val shop = startShop()

    @AfterAll
    fun close() {
        shop.close()
    }

    @Test
    fun `the server should run one node of the shop on the memory backend`() {
        val node = shop.node
        node.service.assert().isEqualTo(SERVICE)
        node.isReady.assert().isTrue()
        node.contexts.keys.assert().containsExactly("example")
        node.options.routing.assert().isEqualTo(Routing.LOCAL)
        node.address.assert().isEqualTo("127.0.0.1:${shop.internalPort}")
    }

    @Test
    fun `the server should not start without a command transport for the order saga`() {
        val failure = runCatching {
            SpringApplicationBuilder(ShopServer::class.java).run(
                "--spring.main.banner-mode=off",
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--wow.internal.port=0",
                "--wow.internal.host=127.0.0.1",
            ).close()
        }.exceptionOrNull()
        generateSequence(failure) { it.cause }.last().message.assert()
            .contains("has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers)")
            .contains("example/OrderSaga.onOrderCreated sends commands to inventory")
    }

    @Test
    fun `the manifest should list both aggregates with their routes, errors and lifecycle`() {
        val manifest = shop.get("/wow/manifest", "alice")
        manifest.status.assert().isEqualTo(200)
        manifest.json["service"]["name"].asString().assert().isEqualTo(SERVICE)
        val context = manifest.json["contexts"].values().single()
        context["name"].asString().assert().isEqualTo("example")
        val aggregates = context["aggregates"].values().associateBy { it["name"].asString() }
        aggregates.keys.assert().containsExactly("cart", "order")

        aggregates.getValue("cart").routes().assert().containsExactlyInAnyOrder(
            "POST /example/cart/{id}/add-cart-item",
            "POST /example/cart/add-cart-item",
            "POST /example/cart/{id}/change-quantity",
            "POST /example/cart/{id}/remove-cart-item",
        )
        aggregates.getValue("order").routes().assert().containsExactlyInAnyOrder(
            "POST /example/order/{id}/create-order",
            "POST /example/order/create-order",
            "POST /example/order/{id}/change-address",
            "POST /example/order/{id}/pay-order",
            "POST /example/order/{id}/ship-order",
            "POST /example/order/{id}/receipt-order",
            "POST /example/order/{id}/cancel-order",
        )
        aggregates.values.forEach { aggregate ->
            aggregate["lifecycle"].values().map { it.asString() }.assert().containsExactly(
                "wow.lifecycle.ApplyTags",
                "wow.lifecycle.DeleteAggregate",
                "wow.lifecycle.RestoreAggregate",
                "wow.lifecycle.TransferOwner",
                "wow.lifecycle.TransferSpace",
            )
        }

        context["errors"].statuses().assert().containsAllEntriesOf(
            mapOf(
                CartRejections.FULL to 422,
                CartRejections.NOT_OWNER to 403,
                OrderRejections.PRICE_CHANGED to 422,
                OrderRejections.WRONG_STATUS to 409,
            ),
        )
        manifest.json["errors"].statuses().assert().containsAllEntriesOf(
            mapOf(
                FrameworkCodes.UNAUTHENTICATED to 401,
                FrameworkCodes.AGGREGATE_FORBIDDEN to 403,
                FrameworkCodes.INVALID to 400,
                FrameworkCodes.DELETED to 410,
                FrameworkCodes.WAIT_TIMEOUT to 504,
            ),
        )
    }

    @Test
    fun `the manifest should list the query models, the published streams, the saga and the query errors`() {
        val manifest = shop.get("/wow/manifest", "alice").json
        val context = manifest["contexts"].values().single()
        val order = context["aggregates"].values().single { it["name"].asString() == "order" }
        order["queries"].values().map { it["model"].asString() }.assert().containsExactly("snapshot", "events")
        order["queries"].values().first()["routes"].values()
            .map { "${it["method"].asString()} ${it["path"].asString()}" }
            .assert().containsExactly("POST /example/order/snapshot/query", "GET /example/order/snapshot/query")
        context["published"].values().map { "${it["aggregate"].asString()} ${it["topic"].asString()}" }
            .assert().containsExactly("cart wow.example.cart.state", "order wow.example.order.state")
        context["subscriptions"].values().map { it["name"].asString() }.assert().containsExactly(
            "CartSaga.onOrderCreated",
            "CartSaga.onRemoveRejected",
            "OrderSaga.onOrderCreated",
            "OrderSaga.onReserveRejected",
        )
        val (saga, rejections) = context["subscriptions"].values().toList()
        saga["name"].asString().assert().isEqualTo("CartSaga.onOrderCreated")
        rejections["name"].asString().assert().isEqualTo("CartSaga.onRemoveRejected")
        rejections["rejected"].values().map { it.asString() }.assert().containsExactly("example.cart.RemoveCartItem")
        saga["derives"].values().map { it.asString() }.assert().containsExactly("example.cart.RemoveCartItem")
        saga["start"].asString().assert().isEqualTo("END")
        manifest["errors"].statuses().assert().containsAllEntriesOf(
            mapOf(
                "wow.query.malformed" to 400,
                "wow.query.invalid" to 400,
                "wow.query.failed" to 500,
                "wow.query.timeout" to 503,
            ),
        )
    }

    @Test
    fun `the manifest should carry the state schemas of both aggregates`() {
        val manifest = shop.get("/wow/manifest", "alice").json
        val context = manifest["contexts"].values().single()
        val defs = manifest["\$defs"]
        context["aggregates"].values().map { it["state"]["schema"].asString() }.assert().containsExactly(
            "#/\$defs/me.ahoo.wow.example.shop.domain.cart.CartState",
            "#/\$defs/me.ahoo.wow.example.shop.domain.order.OrderState",
        )
        val cart = defs["me.ahoo.wow.example.shop.domain.cart.CartState"]
        cart["type"].asString().assert().isEqualTo("object")
        cart["properties"]["items"]["type"].asString().assert().isEqualTo("array")
        val order = defs["me.ahoo.wow.example.shop.domain.order.OrderState"]
        order["properties"]["status"]["enum"].values().map { it.asString() }.assert()
            .containsExactly("CREATED", "PAID", "SHIPPED", "RECEIVED", "CANCELLED")
        order["properties"]["address"].assert().isNotNull()
        // The schemas the states refer to are carried with them, so every reference resolves.
        defs["me.ahoo.wow.example.shop.api.order.OrderItem"]["type"].asString().assert().isEqualTo("object")
        defs["me.ahoo.wow.example.shop.api.order.ShippingAddress"]["type"].asString().assert().isEqualTo("object")
    }

    @Test
    fun `the manifest should need credentials`() {
        shop.get("/wow/manifest").assertProblem(401, FrameworkCodes.UNAUTHENTICATED)
    }

    private fun JsonNode.routes(): List<String> = this["commands"].values().flatMap { command ->
        command["routes"].values().map { "${it["method"].asString()} ${it["path"].asString()}" }
    }

    private fun JsonNode.statuses(): Map<String, Int> = values().associate { it["code"].asString() to it["status"].asInt() }

    private companion object {
        const val SERVICE = "shop-service"
    }
}
