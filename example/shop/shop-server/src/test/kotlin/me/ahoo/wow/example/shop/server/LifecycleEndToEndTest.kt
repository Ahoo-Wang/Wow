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
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** The lifecycle routes the Manifest lists for the shop's aggregates (decision D113), on an order. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecycleEndToEndTest {
    private val shop = startShop()

    @AfterAll
    fun close() {
        shop.close()
    }

    private fun placed(user: String): String {
        val created = shop.post(
            "/example/order/create-order",
            """{"items":[{"productId":"pear","price":4.00,"quantity":1}],"address":$ADDRESS}""",
            user,
        )
        created.status.assert().isEqualTo(200)
        return created.json["ref"]["id"].asString()
    }

    private fun pay(id: String, user: String) =
        shop.post("/example/order/$id/pay-order", """{"paymentId":"payment-1","amount":4.00}""", user)

    @Test
    fun `a deleted order should refuse the next command until it is restored`() {
        val id = placed(ALICE)
        val deleted = shop.delete("/example/order/$id", ALICE)
        deleted.status.assert().isEqualTo(200)
        deleted.json["version"].asInt().assert().isEqualTo(2)
        shop.get("/example/order/$id/state", ALICE).json["deleted"].asBoolean().assert().isTrue()

        pay(id, ALICE).assertProblem(410, FrameworkCodes.DELETED)

        shop.post("/example/order/$id/wow.lifecycle.restore-aggregate", user = ALICE).status.assert().isEqualTo(200)
        shop.get("/example/order/$id/state", ALICE).json["deleted"].asBoolean().assert().isFalse()
        pay(id, ALICE).status.assert().isEqualTo(200)
    }

    @Test
    fun `a transferred order should be the new owner's`() {
        val id = placed(ALICE)
        shop.post("/example/order/$id/wow.lifecycle.transfer-owner", """{"ownerId":"$BOB"}""", ALICE)
            .status.assert().isEqualTo(200)
        shop.get("/example/order/$id/state", BOB).json["scope"]["ownerId"].asString().assert().isEqualTo(BOB)

        pay(id, ALICE).assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
        shop.get("/example/order/$id/state", ALICE).assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
        pay(id, BOB).status.assert().isEqualTo(200)
    }

    @Test
    fun `tags and spaces should be applied through their routes`() {
        val id = placed(ALICE)
        shop.post("/example/order/$id/wow.lifecycle.apply-tags", """{"tags":{"channel":["web"]}}""", ALICE)
            .status.assert().isEqualTo(200)
        shop.get("/example/order/$id/state", ALICE).json["tags"]["channel"][0].asString().assert().isEqualTo("web")
        shop.post("/example/order/$id/wow.lifecycle.transfer-space", """{"spaceId":"shop-1"}""", ALICE)
            .status.assert().isEqualTo(200)
        shop.get("/example/order/$id/state", ALICE).json["scope"]["spaceId"].asString().assert().isEqualTo("shop-1")
    }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
        const val ADDRESS =
            """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1 Century Avenue"}"""
    }
}
