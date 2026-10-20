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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.http.read.StateHandler
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.Test

/** `GET …/{id}/state?at=` and `GET …/{id}/state/trace` (decision D40). */
class HistoryRoutesTest {
    private val node = HttpNode()
    private val ref = AggregateType(CONTEXT, "cart").ref(TENANT, "c-1")

    /** Commits [body] to `add-item` at [at] ms as [user]. */
    private fun add(body: String, at: Long, user: String = "alice") {
        node.now = { at }
        node.post("/shop/cart/c-1/add-item", body, user).status.assert().isEqualTo(200)
    }

    /** Three items added by alice at 1000, 2000 and 3000 ms. */
    private fun history() {
        add("""{"sku":"apple"}""", 1000)
        add("""{"sku":"pear","quantity":2}""", 2000)
        add("""{"sku":"plum","quantity":3}""", 3000)
    }

    private fun at(instant: String, user: String? = "alice", headers: Map<String, String> = emptyMap()): Answer =
        node.client.get().uri("/shop/cart/c-1/state?at={at}", instant).headers { http ->
            user?.let { http.set("X-User", it) }
            headers.forEach { (name, value) -> http.set(name, value) }
        }.answer()

    @Test
    fun `a state at a time is the one after the last commit by then, its version in its ETag`() {
        history()
        val answer = at("1970-01-01T00:00:02.999Z")
        answer.status.assert().isEqualTo(200)
        answer.headers.eTag!!.matches(Regex("\"2-[0-9a-f]{16}\"")).assert().isTrue()
        answer.body!!["version"].asInt().assert().isEqualTo(2)
        answer.body!!["state"]["items"].asInt().assert().isEqualTo(3)
        answer.body!!["scope"]["ownerId"].asString().assert().isEqualTo("alice")
        // Exact boundaries: a commit is in the state from its millisecond on.
        at("1970-01-01T00:00:01Z").body!!["version"].asInt().assert().isEqualTo(1)
        at("1970-01-01T00:00:03Z").body!!["version"].asInt().assert().isEqualTo(3)
        // An offset names the same instant.
        at("1970-01-01T08:00:02+08:00").body!!["version"].asInt().assert().isEqualTo(2)
        at(
            "1970-01-01T00:00:02Z",
            headers = mapOf("If-None-Match" to answer.headers.eTag!!)
        ).status.assert().isEqualTo(304)
    }

    @Test
    fun `a state at a time is read from a snapshot committed by then`() {
        history()
        runBlocking {
            // A snapshot claiming more items than the log holds shows it is the one read.
            val state = Cart().apply { items = 30 }
            node.snapshots.save(EventCodec().snapshotOf(cartModel, ref, state, 2, Scope("alice"), 1000L, 2000L))
        }
        at("1970-01-01T00:00:03Z").body!!["state"]["items"].asInt().assert().isEqualTo(33)
        at("1970-01-01T00:00:01Z").body!!["state"]["items"].asInt().assert().isEqualTo(1)
    }

    @Test
    fun `a time before the first commit is 404, one that is not an instant 400`() {
        history()
        at("1970-01-01T00:00:00.999Z").assertCode(404, FrameworkCodes.NOT_FOUND)
        at("yesterday").assertCode(400, FrameworkErrors.QUERY_INVALID)
        at("1970-01-01T00:00:01").assertCode(400, FrameworkErrors.QUERY_INVALID)
        node.get(
            "/shop/cart/c-1/state?version=1&at=1970-01-01T00:00:01Z"
        ).assertCode(400, FrameworkErrors.QUERY_INVALID)
    }

    @Test
    fun `a state at a time outside the caller's scope is not found (Q-5)`() {
        history()
        at("1970-01-01T00:00:02Z", user = "bob").assertCode(404, FrameworkCodes.NOT_FOUND)
        at("1970-01-01T00:00:02Z", user = null).assertCode(401, FrameworkCodes.UNAUTHENTICATED)
    }

    @Test
    fun `a state at a time after a deletion is the deleted one`() {
        history()
        node.now = { 4000 }
        node.client.delete().uri("/shop/cart/c-1").header("X-User", "alice").answer().status.assert().isEqualTo(200)
        at("1970-01-01T00:00:03.5Z").body!!["deleted"].asBoolean().assert().isFalse()
        val deleted = at("1970-01-01T00:00:04Z").body!!
        deleted["deleted"].asBoolean().assert().isTrue()
        deleted["version"].asInt().assert().isEqualTo(4)
    }

    @Test
    fun `a trace gives each commit with its events and the state after it`() {
        history()
        val answer = node.get("/shop/cart/c-1/state/trace")
        answer.status.assert().isEqualTo(200)
        answer.contentType.assert().isEqualTo("application/json")
        val body = answer.body!!
        body["ref"]["id"].asString().assert().isEqualTo("c-1")
        val commits = body["commits"]
        commits.size().assert().isEqualTo(3)
        val second = commits[1]
        second["version"].asInt().assert().isEqualTo(2)
        second["commitTime"].asLong().assert().isEqualTo(2000L)
        second["commandType"].asString().assert().isEqualTo("shop.cart.AddItem")
        second["events"][0]["name"].asString().assert().isEqualTo("shop.cart.ItemAdded")
        second["events"][0]["revision"].asInt().assert().isEqualTo(1)
        second["events"][0]["body"]["sku"].asString().assert().isEqualTo("pear")
        second["state"]["items"].asInt().assert().isEqualTo(3)
        second["scope"]["ownerId"].asString().assert().isEqualTo("alice")
        second["deleted"].asBoolean().assert().isFalse()
        commits[2]["state"]["items"].asInt().assert().isEqualTo(6)
    }

    @Test
    fun `a trace takes a range and a limit`() {
        history()
        fun versions(query: String) =
            node.get("/shop/cart/c-1/state/trace?$query").body!!["commits"].values().map { it["version"].asInt() }
        versions("from=2").assert().isEqualTo(listOf(2, 3))
        versions("to=2").assert().isEqualTo(listOf(1, 2))
        versions("from=2&to=2").assert().isEqualTo(listOf(2))
        versions("limit=2").assert().isEqualTo(listOf(1, 2))
        versions("from=3&limit=${StateHandler.MAX_TRACE_LIMIT}").assert().isEqualTo(listOf(3))
    }

    @Test
    fun `a trace with no commit is 404, a parameter that is not one 400`() {
        history()
        node.get("/shop/cart/c-1/state/trace?from=4").assertCode(404, FrameworkCodes.NOT_FOUND)
        node.get("/shop/cart/c-2/state/trace").assertCode(404, FrameworkCodes.NOT_FOUND)
        listOf(
            "from=0",
            "from=one",
            "from=3&to=2",
            "to=0",
            "limit=0",
            "limit=${StateHandler.MAX_TRACE_LIMIT + 1}",
        ).forEach { query ->
            node.get("/shop/cart/c-1/state/trace?$query").assertCode(400, FrameworkErrors.QUERY_INVALID)
        }
    }

    @Test
    fun `a trace is read in the caller's scope, every commit`() {
        history()
        node.get("/shop/cart/c-1/state/trace", user = "bob").assertCode(404, FrameworkCodes.NOT_FOUND)
        node.get("/shop/cart/c-1/state/trace", user = null).assertCode(401, FrameworkCodes.UNAUTHENTICATED)
        node.now = { 4000 }
        node.post("/shop/cart/c-1/wow.lifecycle.transfer-owner", """{"ownerId":"bob"}""").status.assert().isEqualTo(200)
        // Bob holds the cart from version 4 on: its earlier commits stay alice's.
        node.get("/shop/cart/c-1/state/trace?from=4", user = "bob").status.assert().isEqualTo(200)
        node.get("/shop/cart/c-1/state/trace?from=3", user = "bob").assertCode(404, FrameworkCodes.NOT_FOUND)
        // Alice no longer sees the cart: she is checked on its latest state before its log is replayed (review R4-Q,
        // Q-1), so even the commits she held are not found for her.
        node.get("/shop/cart/c-1/state/trace?to=3").assertCode(404, FrameworkCodes.NOT_FOUND)
        node.get("/shop/cart/c-1/state/trace").assertCode(404, FrameworkCodes.NOT_FOUND)
        val trace = node.get("/shop/cart/c-1/state/trace?from=4", user = "bob").body!!["commits"][0]
        trace["events"][0]["name"].asString().assert().isEqualTo(Lifecycle.OWNER_TRANSFERRED)
        trace["events"][0]["body"]["ownerId"].asString().assert().isEqualTo("bob")
    }

    private fun Answer.assertCode(status: Int, code: String) {
        this.status.assert().isEqualTo(status)
        contentType.assert().isEqualTo("application/problem+json")
        body!!["code"].asString().assert().isEqualTo(code)
    }
}
