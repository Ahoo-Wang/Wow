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
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.runtime.snapshot.SnapshotStatus
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * The snapshot rebuild route of a context (decision D172), sent through the gateway like any command, and the
 * `Wow-Rebuilding` header of the snapshot queries and descriptors while their snapshots are being rebuilt.
 */
class SnapshotRebuildRoutesTest {
    @Test
    fun `a rebuild request of an aggregate type's snapshots is a command of the rebuild aggregate`() {
        val node = HttpNode(rebuilds = true)
        val requested = node.post("/shop/cart/wow.snapshot.rebuild", """{"ids":["c-1","c-2"]}""")
        requested.status.assert().withFailMessage { requested.body.toString() }.isEqualTo(200)
        requested.body!!["ref"]["id"].asString().assert().isEqualTo("cart")
        val unknown = node.post("/shop/order/wow.snapshot.rebuild", """{"ids":["o-1"]}""")
        unknown.status.assert().isEqualTo(404)
        node.post("/shop/cart/wow.snapshot.rebuild", """{"ids":[]}""").status.assert().isEqualTo(400)
    }

    @Test
    fun `a service calls the rebuild route on the internal port when its permits list it (R2_3-5)`() {
        val node = HttpNode(rebuilds = true)
        val tokens = DeclaredServices.resolver(TENANT)
        val call = CallHeaders.format(CallContext("call-1", TENANT, Scope.NONE, Causation("root-1", "ops/acme/r@1")))
        fun rebuild(permits: CallPermits): Answer = WebTestClient
            .bindToRouterFunction(
                callRoutes(cartManifest(), node.gateway, tokens, permits = permits)
            )
            .build()
            .post().uri("/shop/cart/wow.snapshot.rebuild")
            .header(WowHeaders.SERVICE, "ops-service")
            .header(WowHeaders.CALL, call)
            .header("Content-Type", "application/json")
            .bodyValue("""{"ids":["c-1"]}""")
            .answer()
        val unlisted = rebuild(CallPermits(emptyMap()))
        unlisted.status.assert().isEqualTo(403)
        unlisted.body!!["code"].asString().assert().isEqualTo(CallPermits.COMMAND_FORBIDDEN)
        val listed = rebuild(CallPermits(mapOf("ops-service" to listOf(SnapshotRebuilds.REBUILD))))
        listed.status.assert().withFailMessage { listed.body.toString() }.isEqualTo(200)
        listed.body!!["ref"]["id"].asString().assert().isEqualTo("cart")
    }

    @Test
    fun `a context that keeps no snapshots has no rebuild route`() {
        val manifest = cartManifest().let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(
                        aggregates = context.aggregates.map { aggregate ->
                            aggregate.copyWith(
                                snapshot = SnapshotManifest(false),
                                commands = aggregate.commands.filterNot { it.name == SnapshotRebuilds.REBUILD },
                            )
                        },
                    )
                },
            )
        }
        HttpNode(manifest = manifest).post("/shop/cart/wow.snapshot.rebuild", """{"ids":["c-1"]}""")
            .status.assert().isEqualTo(404)
    }

    @Test
    fun `a snapshot query answers Wow-Rebuilding while its snapshots are being rebuilt`() {
        var rebuilding = true
        val node = QueryNode(snapshotStatus = SnapshotStatus { rebuilding })
        val list = """{"kind":"list","filter":{"op":"match-all"}}"""
        val path = "/shop/cart/snapshot/query"
        node.http.post(path, list).headers.getFirst(WowHeaders.REBUILDING).assert().isEqualTo("true")
        node.http.post(path, """{"kind":"count","filter":{"op":"match-all"}}""")
            .headers.getFirst(WowHeaders.REBUILDING).assert().isEqualTo("true")
        node.http.get(path).headers.getFirst(WowHeaders.REBUILDING).assert().isEqualTo("true")
        node.http.post("/shop/cart/events/query", list).headers.getFirst(WowHeaders.REBUILDING).assert().isNull()
        rebuilding = false
        node.http.post(path, list).headers.getFirst(WowHeaders.REBUILDING).assert().isNull()
        node.http.get(path).headers.getFirst(WowHeaders.REBUILDING).assert().isNull()
    }
}
