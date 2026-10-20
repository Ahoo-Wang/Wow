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

package me.ahoo.wow.http

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.Test

/**
 * A row the application's query policies hide from the snapshot queries is hidden from the reads by id too (review
 * A3-11): the state, a version of it, its trace and its events. The policy here hides a cart of two items from every
 * caller but the admin.
 */
class StateVisibilityRoutesTest {
    private val hideTwoItems = QueryPolicy { caller, _ ->
        if (caller.subject == "admin") MatchAllFilter else NotEqualFilter(QueryField("state.items"), 2)
    }
    private val node = QueryNode(policies = listOf(hideTwoItems))

    private fun cart(id: String, quantity: Int, node: QueryNode = this.node) {
        node.http.post(
            "/shop/cart/$id/add-item",
            """{"sku":"apple","quantity":$quantity}""",
            user = "admin",
            headers = mapOf("X-Tenant-Level" to "true")
        )
            .status.assert().isEqualTo(200)
        runBlocking {
            val state = Cart().apply { items = quantity }
            node.http.snapshots.save(
                EventCodec().snapshotOf(cartModel, cartType.ref(TENANT, id), state, 1, Scope.NONE, 1L, 1L)
            )
        }
    }

    private fun Answer.assertNotFound() {
        status.assert().describedAs(body.toString()).isEqualTo(404)
        body!!["code"].asString().assert().isEqualTo(FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `a state the policies hide is not found, by id, version, time and trace`() {
        cart("c-one", 1)
        cart("c-two", 2)
        val user = "alice"
        val tenantLevel = mapOf("X-Tenant-Level" to "true")
        node.http.get("/shop/cart/c-one/state", user, tenantLevel).status.assert().isEqualTo(200)
        node.http.get("/shop/cart/c-one/state?version=1", user, tenantLevel).status.assert().isEqualTo(200)
        node.http.get("/shop/cart/c-one/state/trace", user, tenantLevel).status.assert().isEqualTo(200)

        node.http.streamsRead.set(0)
        node.http.get("/shop/cart/c-two/state", user, tenantLevel).assertNotFound()
        node.http.get("/shop/cart/c-two/state?version=1", user, tenantLevel).assertNotFound()
        node.http.get("/shop/cart/c-two/state?at=2100-01-01T00:00:00Z", user, tenantLevel).assertNotFound()
        node.http.get("/shop/cart/c-two/state/trace", user, tenantLevel).assertNotFound()
        // The policies refuse the caller on the snapshot, before the log is replayed (review R4-Q, Q-1).
        node.http.streamsRead.get().assert().isZero()
        // The caller the policy lets through reads it.
        node.http.get("/shop/cart/c-two/state", "admin", tenantLevel).status.assert().isEqualTo(200)
        node.http.get("/shop/cart/c-two/state/trace", "admin", tenantLevel).status.assert().isEqualTo(200)
    }

    @Test
    fun `the events of an aggregate the policies hide are not returned`() {
        cart("c-one", 1)
        cart("c-two", 2)
        val tenantLevel = mapOf("X-Tenant-Level" to "true")
        fun events(id: String, user: String) = node.query(
            """{"kind":"list","filter":{"op":"EQ","field":"aggregateId","value":"$id"}}""",
            user,
            QueryRoutes.EVENTS,
            tenantLevel,
        )
        events("c-one", "alice").body!!.size().assert().isEqualTo(1)
        events("c-two", "alice").body!!.size().assert().isEqualTo(0)
        events("c-two", "admin").body!!.size().assert().isEqualTo(1)
    }

    @Test
    fun `before its first snapshot an aggregate's events follow the same rule, on the state its log holds`() {
        val tenantLevel = mapOf("X-Tenant-Level" to "true")
        listOf("c-one" to 1, "c-two" to 2).forEach { (id, quantity) ->
            node.http.post("/shop/cart/$id/add-item", """{"sku":"apple","quantity":$quantity}""", "admin", tenantLevel)
                .status.assert().isEqualTo(200)
        }
        fun events(id: String, user: String) = node.query(
            """{"kind":"list","filter":{"op":"EQ","field":"aggregateId","value":"$id"}}""",
            user,
            QueryRoutes.EVENTS,
            tenantLevel,
        )
        // No snapshot is written: the state the log holds decides (review R2.3-6), for the state read too (D246).
        node.http.get("/shop/cart/c-two/state", "admin", tenantLevel).status.assert().isEqualTo(200)
        node.http.get("/shop/cart/c-two/state", "alice", tenantLevel).assertNotFound()
        node.http.get("/shop/cart/c-one/state", "alice", tenantLevel).status.assert().isEqualTo(200)
        events("c-one", "alice").body!!.size().assert().isEqualTo(1)
        events("c-two", "alice").body!!.size().assert().isEqualTo(0)
        events("c-two", "admin").body!!.size().assert().isEqualTo(1)
        // An aggregate with no commit has no streams, for any caller.
        events("c-none", "admin").body!!.size().assert().isEqualTo(0)
    }

    @Test
    fun `without snapshot queries the policies still hide the state and the events`() {
        // Review R3.3-3: the cart's snapshots are not queried, so its policies are evaluated on its state alone.
        val unqueried = QueryNode(policies = listOf(hideTwoItems), snapshots = { null })
        cart("c-one", 1, unqueried)
        cart("c-two", 2, unqueried)
        val tenantLevel = mapOf("X-Tenant-Level" to "true")
        fun events(id: String, user: String) = unqueried.query(
            """{"kind":"list","filter":{"op":"EQ","field":"aggregateId","value":"$id"}}""",
            user,
            QueryRoutes.EVENTS,
            tenantLevel,
        )
        unqueried.http.get("/shop/cart/c-one/state", "alice", tenantLevel).status.assert().isEqualTo(200)
        unqueried.http.get("/shop/cart/c-two/state", "alice", tenantLevel).assertNotFound()
        unqueried.http.get("/shop/cart/c-two/state", "admin", tenantLevel).status.assert().isEqualTo(200)
        events("c-one", "alice").body!!.size().assert().isEqualTo(1)
        events("c-two", "alice").body!!.size().assert().isEqualTo(0)
        events("c-two", "admin").body!!.size().assert().isEqualTo(1)
        unqueried.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""", "admin", headers = tenantLevel)
            .status.assert().isEqualTo(404)
    }
}
