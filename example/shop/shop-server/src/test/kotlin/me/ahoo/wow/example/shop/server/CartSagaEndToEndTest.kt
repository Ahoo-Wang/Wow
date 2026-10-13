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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.WowHeaders
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The cart saga through the running shop server (memory backend): the host registers the subscription the compiler
 * generated for `CartSaga`, and an order placed from the cart removes its products from the owner's cart. A wait
 * chain follows the order's commit through the saga's hop (decision D62).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CartSagaEndToEndTest {
    private val shop = startShop()

    @AfterAll
    fun close() {
        shop.close()
    }

    private fun fill(user: String) {
        listOf("apple", "pear", "durian").forEach { product ->
            shop.post("/example/cart/$user/add-cart-item", """{"productId":"$product"}""", user)
                .status.assert().isEqualTo(200)
        }
    }

    private fun order(user: String, wait: String, fromCart: Boolean = true): Answer = shop.post(
        "/example/order/create-order",
        """{"items":$APPLE_AND_PEAR,"address":$ADDRESS,"fromCart":$fromCart}""",
        user,
        mapOf(WowHeaders.WAIT to wait),
    )

    private fun cartProducts(user: String): List<String> =
        shop.get("/example/cart/$user/state", user).json["state"]["items"].values().map { it["productId"].asString() }

    @Test
    fun `an order from the cart should complete the wait at the saga's hop and empty the ordered products`() {
        fill("grace")
        val placed = order("grace", "committed > handled(example/$SAGA)")
        placed.status.assert().isEqualTo(200)
        placed.json["status"].asString().assert().isEqualTo("SUCCEEDED")
        placed.json["hop"]["stage"].asString().assert().isEqualTo("HANDLED")
        placed.json["hop"]["subscription"].asString().assert().isEqualTo(SAGA)
        placed.json["function"]["kind"].asString().assert().isEqualTo("SAGA")
        placed.json["derived"].values().toList().assert().hasSize(1)

        // The saga's hop is answered once its command is committed: the cart's fourth version, after the three items,
        // reads emptied at once from the log; its snapshot follows (the next test waits for it).
        val removed = shop.get("/example/cart/grace/state?version=4", "grace")
        removed.status.assert().isEqualTo(200)
        removed.json["state"]["items"].values().map { it["productId"].asString() }.assert().containsExactly("durian")
    }

    @Test
    fun `a chain through the saga to the cart's snapshot should read the emptied cart at once`() {
        fill("heidi")
        val placed = order("heidi", "committed > handled(example/$SAGA) > snapshot")
        placed.status.assert().isEqualTo(200)
        placed.json["status"].asString().assert().isEqualTo("SUCCEEDED")
        placed.json["ref"]["id"].asString().assert().isEqualTo("heidi")
        placed.json["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
        cartProducts("heidi").assert().containsExactly("durian")
    }

    @Test
    fun `an order not placed from the cart should derive nothing and leave the cart as it is`() {
        fill("ivan")
        val placed = order("ivan", "committed > handled(example/$SAGA) > committed", fromCart = false)
        placed.status.assert().isEqualTo(200)
        placed.json["hop"]["subscription"].asString().assert().isEqualTo(SAGA)
        placed.json["derived"].values().toList().assert().isEmpty()
        cartProducts("ivan").assert().containsExactly("apple", "pear", "durian")
    }

    @Test
    fun `an order from a deleted cart should end the wait at the saga's hop with the rejection, parking nothing`() {
        fill("judy")
        shop.delete("/example/cart/judy", "judy").status.assert().isEqualTo(200)
        val placed = order("judy", "committed > handled(example/$SAGA) > committed")
        // Decision D153: the cart's refusal is the outcome of the saga's hop, handed to CartSaga.onRemoveRejected.
        placed.assertProblem(410, "wow.aggregate.deleted")
        val result = placed.json["result"]
        result["status"].asString().assert().isEqualTo("REJECTED")
        result["hop"]["subscription"].asString().assert().isEqualTo(SAGA)
        result["derived"].values().toList().assert().hasSize(1)
        // The saga parks the order before it signals the hop, so a park would be in the ledger by now.
        val ref = result["ref"]
        val placedOrder = AggregateType("example", "order").ref(ref["tenantId"].asString(), ref["id"].asString())
        val ledger = shop.node.context("example").backends.ledger
        runBlocking { ledger.isParked(SAGA, placedOrder) }.assert().isFalse()
        shop.get("/example/order/${placedOrder.id}/state", "judy").status.assert().isEqualTo(200)
    }

    private companion object {
        const val SAGA = "CartSaga.onOrderCreated"
        const val APPLE_AND_PEAR =
            """[{"productId":"apple","price":2.50,"quantity":1},{"productId":"pear","price":4.00,"quantity":1}]"""
        const val ADDRESS =
            """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1 Century Avenue"}"""
    }
}
