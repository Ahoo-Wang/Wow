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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.StateMaskingRoutesTest.Companion.CONFIDENTIAL
import me.ahoo.wow.http.StateMaskingRoutesTest.Companion.DISPLAYED
import me.ahoo.wow.http.StateMaskingRoutesTest.Companion.SECRET
import me.ahoo.wow.http.StateMaskingRoutesTest.Companion.sensitiveManifest
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * A service's read of a peer's `DISPLAY` values (#6509): `Wow-Reveal` on the internal port, for a read whose root is a
 * service's own, while the owner shares them (`wow.query.sensitivity.display-shared`).
 */
class PeerRevealTest {
    private fun node(displayShared: Boolean = true) =
        StateMaskingRoutesTest().opened(QueryNode(manifest = sensitiveManifest(), displayShared = displayShared))

    private fun routes(node: QueryNode): WebTestClient = WebTestClient.bindToRouterFunction(
        peerReadRoutes(
            node.http.manifest,
            node.http.gateway,
            DeclaredServices.resolver(TENANT),
            node.http.httpQueries,
            node.http.reads,
        ),
    ).build()

    /** The root of a read the service `basket-service` makes for itself, or for [operator] when given. */
    private fun root(operator: Operator = Operator.of(Caller.SERVICE_SUBJECT_PREFIX + SERVICE, TENANT)): String =
        CallHeaders.format(CallContext.of("c", TENANT, Scope.NONE, Causation("c", "c"), operator))

    private fun headers(reveal: String? = WowHeaders.REVEAL_DISPLAY, root: String = root()) =
        listOfNotNull(WowHeaders.SERVICE to SERVICE, WowHeaders.CALL to root, reveal?.let { WowHeaders.REVEAL to it })

    private fun WebTestClient.query(model: String, headers: List<Pair<String, String>>): Answer =
        post().uri("/shop/cart/$model/query")
            .headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }
            .header("Content-Type", "application/json")
            .bodyValue("""{"kind":"list","filter":{"op":"match-all"},"limit":10}""")
            .answer()

    private fun WebTestClient.read(path: String, headers: List<Pair<String, String>>): Answer =
        get().uri(path).headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }.answer()

    @Test
    fun `a service's own read that asks should get the DISPLAY values raw, every CONFIDENTIAL one masked`() {
        val routes = routes(node())
        routes.query("snapshot", headers()).body!![0]["state"]["note"].asString().assert().isEqualTo(SECRET)
        routes.read("/shop/cart/c-1/state", headers()).body!!["state"]["note"].asString().assert().isEqualTo(SECRET)
        // The CartOpened event's note is CONFIDENTIAL: masked whoever asks.
        routes.query("events", headers()).body!![0]["events"][0]["body"]["note"].asString().assert()
            .isEqualTo(CONFIDENTIAL)
        // A read that does not ask is masked.
        routes.query("snapshot", headers(reveal = null)).body!![0]["state"]["note"].asString().assert()
            .isEqualTo(DISPLAYED)
    }

    @Test
    fun `a state of the history should stay masked, whatever the read asks`() {
        val routes = routes(node())
        listOf("/shop/cart/c-1/state?version=1", "/shop/cart/c-1/state?at=2100-01-01T00:00:00Z").forEach { path ->
            routes.read(path, headers()).body!!["state"]["note"].asString().assert().describedAs(path)
                .isEqualTo(DISPLAYED)
        }
    }

    @Test
    fun `a read that asks on behalf of an end caller should be refused, whoever relays it`() {
        val answer = routes(node()).query("snapshot", headers(root = root(Operator.of("alice", TENANT))))
        answer.status.assert().isEqualTo(403)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        answer.body!!["detail"].asString().assert().contains("end caller")
    }

    @Test
    fun `an owner that shares no DISPLAY value should refuse every read that asks, naming the property`() {
        val routes = routes(node(displayShared = false))
        listOf(
            routes.query("snapshot", headers()),
            routes.read("/shop/cart/c-1/state", headers()),
        ).forEach { answer ->
            answer.status.assert().isEqualTo(403)
            answer.body!!["detail"].asString().assert().contains("wow.query.sensitivity.display-shared")
        }
        // A read that does not ask is answered, masked.
        routes.query("snapshot", headers(reveal = null)).body!![0]["state"]["note"].asString().assert()
            .isEqualTo(DISPLAYED)
    }

    @Test
    fun `a reveal of anything but the DISPLAY values should be an invalid query`() {
        val answer = routes(node()).query("snapshot", headers(reveal = "confidential"))
        answer.status.assert().isEqualTo(400)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.QUERY_INVALID)
    }

    @Test
    fun `the public port should refuse Wow-Reveal with a message of its own`() {
        val answer = node().http.post(
            "/shop/cart/snapshot/query",
            """{"kind":"list","filter":{"op":"match-all"}}""",
            "alice",
            mapOf(WowHeaders.REVEAL to WowHeaders.REVEAL_DISPLAY),
        )
        answer.status.assert().isEqualTo(403)
        answer.body!!["detail"].asString().assert().contains(WowHeaders.REVEAL, "internal port")
    }

    private companion object {
        const val SERVICE = "basket-service"
    }
}
