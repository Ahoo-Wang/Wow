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
import me.ahoo.wow.example.shop.domain.cart.MAX_CART_ITEM_SIZE
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders

/**
 * The cart through the running shop server (memory backend): a cart's id is its customer's user id, and the caller
 * comes from the `X-User` header.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CartEndToEndTest {
    private val shop = startShop()

    @AfterAll
    fun close() {
        shop.close()
    }

    private fun addItem(cart: String, user: String?, body: String = """{"productId":"apple","quantity":2}""") =
        shop.post("/example/cart/$cart/add-cart-item", body, user)

    @Test
    fun `the owner's first item should create the cart, and the default wait read its own write`() {
        val added = addItem("alice", "alice")
        added.status.assert().isEqualTo(200)
        added.json["status"].asString().assert().isEqualTo("SUCCEEDED")
        // No Wow-Wait: the default plan waits until the snapshot holds the commit (decision D69).
        added.json["hop"]["stage"].asString().assert().isEqualTo("HANDLED")
        added.json["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
        added.json["version"].asInt().assert().isEqualTo(1)
        added.json["ref"]["id"].asString().assert().isEqualTo("alice")

        val state = shop.get("/example/cart/alice/state", "alice")
        state.status.assert().isEqualTo(200)
        state.headers.eTag.assert().isEqualTo("\"1\"")
        state.json["version"].asInt().assert().isEqualTo(1)
        state.json["scope"]["ownerId"].asString().assert().isEqualTo("alice")
        state.json["deleted"].asBoolean().assert().isFalse()
        state.json["state"]["items"][0]["productId"].asString().assert().isEqualTo("apple")
        state.json["state"]["items"][0]["quantity"].asInt().assert().isEqualTo(2)

        // The tag answers a conditional read.
        shop.get("/example/cart/alice/state", "alice", mapOf(HttpHeaders.IF_NONE_MATCH to "\"1\"")).status
            .assert().isEqualTo(304)
    }

    @Test
    fun `another owner should be refused by the kernel's scope guard once the cart exists`() {
        addItem("bob", "bob").status.assert().isEqualTo(200)
        addItem("bob", "mallory").assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
            .json["result"]["status"].asString().assert().isEqualTo("REJECTED")
        shop.post("/example/cart/bob/remove-cart-item", """{"productIds":["apple"]}""", "mallory")
            .assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
        shop.get("/example/cart/bob/state", "mallory").assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
    }

    @Test
    fun `a cart created for another customer should be refused by the domain with its own status`() {
        addItem("dave", "mallory").assertProblem(403, CartRejections.NOT_OWNER)
        // Without an id the server generates one, which is no customer's user id.
        shop.post("/example/cart/add-cart-item", """{"productId":"apple"}""", "mallory")
            .assertProblem(403, CartRejections.NOT_OWNER)
    }

    @Test
    fun `a body breaking the Jakarta constraints should be invalid`() {
        val invalid = addItem("erin", "erin", """{"productId":"","quantity":0}""")
            .assertProblem(400, FrameworkCodes.INVALID)
        invalid.json["violations"].values().map { it["path"].asString() }.assert().containsExactlyInAnyOrder(
            "productId",
            "quantity",
        )
        invalid.json["result"]["hop"]["stage"].asString().assert().isEqualTo("ACCEPTED")
    }

    @Test
    fun `a body that is not the command should be malformed`() {
        addItem("erin", "erin", """{"quantity":1}""").assertProblem(400, FrameworkCodes.MALFORMED)
        addItem("erin", "erin", "not json").assertProblem(400, FrameworkCodes.MALFORMED)
    }

    @Test
    fun `a request without credentials should be unauthenticated`() {
        addItem("frank", null).assertProblem(401, FrameworkCodes.UNAUTHENTICATED)
            .json.has("result").assert().isFalse()
        shop.get("/example/cart/frank/state").assertProblem(401, FrameworkCodes.UNAUTHENTICATED)
    }

    @Test
    fun `a full cart should refuse a new product with the domain's code`() {
        repeat(MAX_CART_ITEM_SIZE) { index ->
            addItem("grace", "grace", """{"productId":"product-$index"}""").status.assert().isEqualTo(200)
        }
        val full = addItem("grace", "grace").assertProblem(422, CartRejections.FULL)
        full.json["result"]["status"].asString().assert().isEqualTo("REJECTED")
        full.json["result"]["error"]["code"].asString().assert().isEqualTo(CartRejections.FULL)
        // More of a product the cart holds is still taken.
        addItem("grace", "grace", """{"productId":"product-0"}""").status.assert().isEqualTo(200)
    }

    @Test
    fun `the other cart commands should change the cart, or be refused by the domain`() {
        addItem("heidi", "heidi").status.assert().isEqualTo(200)
        shop.post("/example/cart/heidi/change-quantity", """{"productId":"apple","quantity":5}""", "heidi")
            .status.assert().isEqualTo(200)
        shop.post("/example/cart/heidi/change-quantity", """{"productId":"kiwi","quantity":5}""", "heidi")
            .assertProblem(422, CartRejections.ITEM_NOT_FOUND)
        shop.post("/example/cart/heidi/remove-cart-item", """{"productIds":["apple"]}""", "heidi")
            .json["version"].asInt().assert().isEqualTo(3)
        shop.get("/example/cart/heidi/state", "heidi").json["state"]["items"].size().assert().isEqualTo(0)
        // A cart that was never created.
        shop.post("/example/cart/ivan/remove-cart-item", """{"productIds":["apple"]}""", "ivan")
            .assertProblem(404, FrameworkCodes.NOT_FOUND)
        shop.get("/example/cart/ivan/state", "ivan").assertProblem(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `a path no route serves should be a 404 problem`() {
        val missing = shop.post("/example/cart/alice/check-out", "{}", "alice")
        missing.status.assert().isEqualTo(404)
        missing.json["type"].asString().assert().isEqualTo("about:blank")
    }
}
