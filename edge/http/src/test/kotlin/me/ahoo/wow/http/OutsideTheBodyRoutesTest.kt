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
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.api.wire.WaitHeader
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.kernel.EventCodec
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

/**
 * What the public port reads of a request outside its body (#6501): every form a client sends is read as Wow reads
 * it or refused with a reason, never read as something else, or dropped.
 */
class OutsideTheBodyRoutesTest {
    private val node = HttpNode(
        options = WowHttpOptions(requestIdHeaders = listOf(WowHeaders.REQUEST_ID, "X-Request-Id")),
    )

    private fun Answer.refused(status: Int, code: String, vararg detail: String): Answer {
        this.status.assert().isEqualTo(status)
        body!!["code"].asString().assert().isEqualTo(code)
        detail.forEach { body["detail"].asString().assert().contains(it) }
        return this
    }

    /** A 400 `wow.query.invalid` whose one violation is [code] on the query parameter [parameter]. */
    private fun Answer.violation(parameter: String, code: String): Answer {
        refused(400, FrameworkCodes.QUERY_INVALID)
        body!!["violations"][0]["path"].asString().assert().isEqualTo(parameter)
        body["violations"][0]["code"].asString().assert().isEqualTo(code)
        return this
    }

    /** `POST [path]` as alice with [lines], each header given in as many lines as it has values. */
    private fun post(path: String, vararg lines: Pair<String, List<String>>, body: String = """{"sku":"apple"}""") =
        node.client.post().uri(URI.create(path)).headers { http ->
            http.set("X-User", "alice")
            lines.forEach { (name, values) -> http.addAll(name, values) }
        }.header("Content-Type", "application/json").bodyValue(body).answer()

    private fun getState(path: String, vararg headers: Pair<String, String>) =
        node.client.get().uri(URI.create(path)).headers { http ->
            http.set("X-User", "alice")
            headers.forEach { (name, value) -> http.set(name, value) }
        }.answer()

    private fun snapshot(id: String = "c-1") = runBlocking {
        val ref = AggregateType(CONTEXT, "cart").ref(TENANT, id)
        val cart = Cart().apply { items = 1 }
        node.snapshots.save(EventCodec().snapshotOf(cartModel, ref, cart, 1, Scope("alice"), 1L, 1L))
    }

    @Test
    fun `a Wow header given twice is refused, naming it, whatever a proxy appended`() {
        post("/shop/cart/c-1/add-item", WowHeaders.REQUEST_ID to listOf("r1", "r2"))
            .refused(400, FrameworkCodes.REQUEST_MALFORMED, WowHeaders.REQUEST_ID, "given 2 times")
        post("/shop/cart/c-1/add-item", WowHeaders.WAIT to listOf("committed", "soon"))
            .refused(400, FrameworkCodes.REQUEST_MALFORMED, WowHeaders.WAIT)
        post("/shop/cart/c-1/add-item", WowHeaders.WAIT_TIMEOUT to listOf("1s", "2s"))
            .refused(400, FrameworkCodes.REQUEST_MALFORMED, WowHeaders.WAIT_TIMEOUT)
        post("/shop/cart/c-1/add-item", WowHeaders.SPACE to listOf("s1", "s2"))
            .refused(400, FrameworkCodes.REQUEST_MALFORMED, WowHeaders.SPACE)
        // Given once, it is read.
        post("/shop/cart/c-1/add-item", WowHeaders.REQUEST_ID to listOf("r1")).status.assert().isEqualTo(200)
    }

    @Test
    fun `If-Match and Wow-Scope are read across their lines`() {
        post("/shop/cart/c-1/add-item").status.assert().isEqualTo(200)
        // Two lines naming two versions are refused as one line naming them is.
        post("/shop/cart/c-1/add-item", WowHeaders.IF_MATCH to listOf("\"99\"", "\"1\""))
            .refused(400, FrameworkCodes.REQUEST_MALFORMED, "1, 99")
        // A version in one line and its state's tag in another are one version.
        post("/shop/cart/c-1/add-item", WowHeaders.IF_MATCH to listOf("\"1\"", "\"1-0123456789abcdef\""))
            .body!!["version"].asInt().assert().isEqualTo(2)
        // The lines of Wow-Scope are one dictionary: a member given in two of them is given twice.
        post("/shop/cart/c-1/add-item", WowHeaders.SCOPE to listOf("owner=\"bob\"", "owner=\"carol\""))
            .refused(400, FrameworkCodes.COMMAND_MALFORMED, "owner twice")
        post("/shop/cart/c-1/add-item", WowHeaders.SCOPE to listOf("tenant=a, tenant=b"))
            .refused(400, FrameworkCodes.COMMAND_MALFORMED, "tenant twice")
        // Members in two lines are read together: here a scope alice may not act in.
        post("/shop/cart/c-1/add-item", WowHeaders.SCOPE to listOf("owner=\"bob\"", "space=\"s1\""))
            .refused(403, FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `an unknown Wow header is refused on the public port, naming it`() {
        post("/shop/cart/c-1/add-item", "Wow-Wiat" to listOf("committed"))
            .refused(400, FrameworkCodes.REQUEST_MALFORMED, "Wow-Wiat is not a header of Wow's")
        getState("/shop/cart/c-1/state", "wow-foo" to "1").refused(400, FrameworkCodes.REQUEST_MALFORMED, "wow-foo")
        // What Wow's own clients send is read: the development identity headers, a service's name, in any case.
        post(
            "/shop/cart/c-1/add-item",
            DevelopmentHeaders.USER to listOf("alice"),
            DevelopmentHeaders.TENANT to listOf(TENANT),
            DevelopmentHeaders.ROLES to listOf("a,b"),
            DevelopmentHeaders.OWNER to listOf("alice"),
            DevelopmentHeaders.APP to listOf("web"),
            WowHeaders.SERVICE to listOf("shop-web"),
            "wow-request-id" to listOf("r-9"),
            "X-Request-Id" to listOf("x-1"),
        ).status.assert().isEqualTo(200)
    }

    @Test
    fun `Wow-Call is refused on every public route, a query and a state read among them`() {
        snapshot()
        getState("/shop/cart/c-1/state", WowHeaders.CALL to "{}").refused(403, FrameworkCodes.AUTH_FORBIDDEN)
        getState("/shop/cart/c-1/state/trace", WowHeaders.CALL to "{}").refused(403, FrameworkCodes.AUTH_FORBIDDEN)
        post("/shop/cart/c-1/add-item", WowHeaders.CALL to listOf("{}")).refused(403, FrameworkCodes.AUTH_FORBIDDEN)
        val queries = QueryNode()
        queries.query("""{"kind":"count","filter":{"op":"match-all"}}""", headers = mapOf(WowHeaders.CALL to "{}"))
            .refused(403, FrameworkCodes.AUTH_FORBIDDEN)
        queries.query("""{"kind":"count","filter":{"op":"match-all"}}""").status.assert().isEqualTo(200)
    }

    @Test
    fun `a body's type is application json or an application json suffix alone`() {
        listOf("*/*", "application/*", "image/x+json", "text/json").forEach { type ->
            node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice").header("Content-Type", type)
                .bodyValue("""{"sku":"apple"}""").answer().status.assert().isEqualTo(415)
        }
        listOf("application/json", "application/json;charset=UTF-8", "application/vnd.shop+json").forEach { type ->
            node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice").header("Content-Type", type)
                .bodyValue("""{"sku":"apple"}""").answer().status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `a wait plan is read in time linear in its length, and refused past its bounds before a hop is read`() {
        // #6815: a hop left open, `accepted(`, white space and no `)`, backtracked about n³/6 times in the hop's pattern:
        // minutes of a request's thread at this length, where a linear read takes well under a millisecond.
        post("/shop/cart/c-1/add-item", WowHeaders.WAIT to listOf("soon")).refused(400, FrameworkCodes.WAIT_INVALID)
        val open = "accepted(" + " ".repeat(WaitHeader.MAX_LENGTH - "accepted(x".length) + "x"
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            post("/shop/cart/c-1/add-item", WowHeaders.WAIT to listOf(open))
                .refused(400, FrameworkCodes.WAIT_INVALID, "Not a hop")
        }
        post("/shop/cart/c-1/add-item", WowHeaders.WAIT to listOf(open + "x"))
            .refused(400, FrameworkCodes.WAIT_INVALID, "at most ${WaitHeader.MAX_LENGTH} characters")
        val hops = List(WaitHeader.MAX_HOPS + 1) { "committed" }.joinToString(">")
        post("/shop/cart/c-1/add-item", WowHeaders.WAIT to listOf(hops))
            .refused(400, FrameworkCodes.WAIT_INVALID, "at most ${WaitHeader.MAX_HOPS} hops")
    }

    @Test
    fun `a wait timeout has one form, of a millisecond or more`() {
        listOf("10 s", "PT0.0001S").forEach { timeout ->
            post("/shop/cart/c-1/add-item", WowHeaders.WAIT_TIMEOUT to listOf(timeout))
                .refused(400, FrameworkCodes.WAIT_INVALID, timeout)
        }
        // Past the node's maximum, it is the maximum: the client's bound on its wait.
        post("/shop/cart/c-1/add-item", WowHeaders.WAIT_TIMEOUT to listOf("P1D")).status.assert().isEqualTo(200)
    }

    @Test
    fun `a path segment with a matrix parameter that names none is refused, not routed without it`() {
        // #6816: Spring's path parse cuts `c-1;` and `c-1;=v` at `;` and lists no parameter, so they routed as c-1.
        val paths = listOf(
            "/shop/cart/c-1;/add-item",
            "/shop/cart/c-1;=v/add-item",
            "/shop/cart/c-1;;/add-item",
            "/shop/cart/c-1/add-item;",
        )
        paths.forEach { path -> post(path).refused(400, FrameworkCodes.REQUEST_MALFORMED, "matrix parameters") }
        node.commands.assert().isEmpty()
        // An encoded `;` is a character of the id, which the id rule refuses: not a matrix parameter.
        post("/shop/cart/a%3Bb/add-item").refused(400, FrameworkCodes.COMMAND_MALFORMED, "holds ';'")
    }

    @Test
    fun `an aggregate id no store keeps as given or no gateway carries is refused, and matrix parameters are`() {
        mapOf(
            "a%00b" to "control character",
            "a%07b" to "control character",
            "%20c-1" to "white space",
            "c-1%09" to "white space",
            "%2E" to "relative path segment",
            "%2E%2E" to "relative path segment",
            // #6594: what a gateway's path check refuses in a path, which no route by the id would reach behind it.
            "a%2Fb" to "holds '/'",
            "a%3Bb" to "holds ';'",
            "a%5Cb" to "holds '\\'",
            "%E8%B4%AD".repeat(171) to "171 characters, 513 bytes in UTF-8",
        ).forEach { (id, why) ->
            post("/shop/cart/$id/add-item").refused(400, FrameworkCodes.COMMAND_MALFORMED, why)
        }
        node.commands.assert().isEmpty()
        post("/shop/cart/c-1;x=1/add-item").refused(400, FrameworkCodes.REQUEST_MALFORMED, "matrix parameters")
        // Kept as they are: a unicode id is an id of its own, up to 512 bytes in UTF-8.
        post("/shop/cart/%E8%B4%AD/add-item").body!!["ref"]["id"].asString().assert().isEqualTo("购")
        post("/shop/cart/${"%E8%B4%AD".repeat(170)}/add-item").body!!["ref"]["id"].asString().assert()
            .isEqualTo("购".repeat(170))
    }

    @Test
    fun `a state read's parameters are ASCII digits, given once, and its own`() {
        snapshot()
        getState("/shop/cart/c-1/state?minVersion=1").status.assert().isEqualTo(200)
        listOf("%2B1", "001", "%D9%A1", "-1", "1.0").forEach { version ->
            getState("/shop/cart/c-1/state?version=$version").violation("version", QueryErrorCodes.INVALID_VALUE)
        }
        getState("/shop/cart/c-1/state?version=1&version=9").violation("version", QueryErrorCodes.INVALID_REQUEST)
            .body!!["detail"].asString().assert().contains("2 times")
        getState("/shop/cart/c-1/state?verison=1").violation("verison", QueryErrorCodes.INVALID_REQUEST)
        getState("/shop/cart/c-1/state/trace?form=1").violation("form", QueryErrorCodes.INVALID_REQUEST)
        getState("/shop/cart/c-1/state/trace?limit=010").violation("limit", QueryErrorCodes.INVALID_VALUE)
        getState("/shop/cart/c-1/state/trace?from=1&to=1&limit=1").status.assert().isEqualTo(404)
        // Kept: a cache condition that does not parse fails open.
        getState("/shop/cart/c-1/state", "If-None-Match" to "garbage").status.assert().isEqualTo(200)
    }

    @Test
    fun `a blank id on a state read is malformed, not a server error`() {
        getState("/shop/cart/%20/state").refused(400, FrameworkCodes.QUERY_MALFORMED, "not blank")
    }
}
