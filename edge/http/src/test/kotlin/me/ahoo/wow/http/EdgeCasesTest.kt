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

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.ServerRequest

/** The edges of the public routes: defaults, unreadable headers and the less common forms of the standard ones. */
class EdgeCasesTest {
    private val node = HttpNode()
    private val ref = AggregateType(CONTEXT, "cart").ref(TENANT, "c-1")

    private fun Answer.assertStatus(status: Int, code: String? = null): Answer {
        this.status.assert().isEqualTo(status)
        if (code != null) {
            body!!["code"].asString().assert().isEqualTo(code)
        }
        return this
    }

    private fun snapshot(scope: Scope) = runBlocking {
        node.snapshots.save(EventCodec().snapshotOf(cartModel, ref, Cart().apply { items = 1 }, 1, scope, 1L, 1L))
    }

    @Test
    fun `the defaults serve the anonymous caller and read no state`() {
        val client = WebTestClient.bindToRouterFunction(wowRoutes(cartManifest(), node.gateway)).build()
        client.post().uri("/shop/cart/c-1/add-item").header("Content-Type", "application/json")
            .bodyValue("""{"sku":"apple"}""").answer()
            .assertStatus(200).body!!["ref"]["tenantId"].asString().assert().isEqualTo(Caller.DEFAULT_TENANT)
        client.get().uri("/shop/cart/c-1/state").answer().assertStatus(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `the anonymous resolver resolves every request to the anonymous caller`(): Unit = runBlocking {
        val request = mockk<ServerRequest>()
        CallerResolver.ANONYMOUS.resolve(request).assert().isEqualTo(Caller.ANONYMOUS)
        CallerResolver.ANONYMOUS.resolve(request).assert().isEqualTo(Caller.ANONYMOUS)
        CallerResolver.ANONYMOUS.toString().assert().isEqualTo("CallerResolver.ANONYMOUS")
    }

    @Test
    fun `an empty JSON body is an empty object`() {
        node.client.post().uri("/shop/cart/open-cart").header("X-User", "alice")
            .header("Content-Type", "application/json").bodyValue("").answer()
            .assertStatus(200).body!!["version"].asInt().assert().isEqualTo(1)
    }

    @Test
    fun `If-Match takes a bare version and refuses a negative one`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertStatus(200)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.IF_MATCH to "1"))
            .assertStatus(200).body!!["version"].asInt().assert().isEqualTo(2)
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", headers = mapOf(WowHeaders.IF_MATCH to "\"-1\""))
            .assertStatus(400, FrameworkCodes.MALFORMED).body!!.has("result").assert().isFalse()
    }

    @Test
    fun `a Content-Type or an Accept that does not parse is refused`() {
        node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice")
            .header("Content-Type", "json").answer()
            .assertStatus(415).body!!["detail"].asString().assert().isEqualTo("Content-Type does not parse.")
        node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice").header("Accept", "json")
            .header("Content-Type", "application/json").bodyValue("""{"sku":"apple"}""").answer()
            .assertStatus(406).body!!["detail"].asString().assert().isEqualTo("Accept does not parse.")
    }

    @Test
    fun `a state read answers JSON only`() {
        snapshot(Scope("alice"))
        node.get("/shop/cart/c-1/state", headers = mapOf("Accept" to "text/event-stream")).assertStatus(406)
        node.get("/shop/cart/c-1/state", headers = mapOf("Accept" to "application/*")).assertStatus(200)
    }

    @Test
    fun `If-None-Match takes a list, weak tags and any`() {
        snapshot(Scope("alice"))
        val current = node.get("/shop/cart/c-1/state").headers.eTag!!
        listOf("*", "W/$current", "\"7\", $current").forEach { tag ->
            node.get("/shop/cart/c-1/state", headers = mapOf("If-None-Match" to tag)).assertStatus(304)
                .headers.eTag.assert().isEqualTo(current)
        }
        node.get("/shop/cart/c-1/state", headers = mapOf("If-None-Match" to "\"7\", W/\"8\"")).assertStatus(200)
    }

    @Test
    fun `a reader in a space reads only the states of that space`() {
        val inHome = mapOf("X-Spaces" to "home", WowHeaders.SPACE to "home")
        snapshot(Scope("alice"))
        node.get("/shop/cart/c-1/state", headers = inHome).assertStatus(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `a reader in a space reads the state of its space`() {
        runBlocking {
            node.snapshots.save(EventCodec().snapshotOf(cartModel, ref, Cart(), 2, Scope("alice", "home"), 1L, 2L))
        }
        node.get("/shop/cart/c-1/state", headers = mapOf("X-Spaces" to "home", WowHeaders.SPACE to "home"))
            .assertStatus(200).body!!["scope"]["spaceId"].asString().assert().isEqualTo("home")
        // A reader that names no space reads it too: Scope.NONE fields are not checked.
        node.get("/shop/cart/c-1/state").assertStatus(200)
        node.get("/shop/cart/c-1/state", user = "bob").assertStatus(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `an SSE request refused at admission with If-Match is a problem`() {
        node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice")
            .header("Accept", "text/event-stream").header(WowHeaders.IF_MATCH, "\"0\"")
            .header("Content-Type", "application/json").bodyValue("""{"sku":1""").answer()
            .assertStatus(400, FrameworkCodes.MALFORMED)
    }
}
