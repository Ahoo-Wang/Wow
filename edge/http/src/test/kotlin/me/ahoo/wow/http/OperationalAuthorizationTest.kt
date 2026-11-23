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
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.ops.OpsPaths
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.node.ParkedOps
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Wow's operational commands on the public port and the operator's routes (#6662): an authorizer of another concern,
 * which allows what it does not know, no longer opens them to every caller; only an authorizer that decides them
 * ([CommandAuthorizer.decidesOperational]) does.
 */
class OperationalAuthorizationTest {
    private val rebuild = "/shop/cart/wow.snapshot.rebuild-snapshot"
    private val ids = """{"ids":["c-1"]}"""

    @Test
    fun `an authorizer of another concern does not open the snapshot rebuild to any caller`() {
        val node = HttpNode(rebuilds = true, authorizer = otherConcern)
        val anonymous = HttpNode(rebuilds = true, authorizer = otherConcern, callers = CallerResolver.ANONYMOUS)
        listOf(
            anonymous.post(rebuild, ids, user = null),
            node.post(rebuild, ids, user = "alice"),
            node.post(rebuild, ids, user = OPERATOR, headers = mapOf("X-Tenant-Level" to "true")),
        ).forEach { refused ->
            refused.status.assert().withFailMessage { refused.body.toString() }.isEqualTo(403)
            refused.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
            refused.body!!["detail"].asString().assert().contains("CommandAuthorizer.operational")
        }
        // Its own concern still holds, and the cart's commands pass as before.
        node.post("/shop/cart/c-1/add-item", """{"sku":"s-1","quantity":1}""").status.assert().isEqualTo(200)
        node.post("/shop/cart/forbidden/add-item", """{"sku":"s-1","quantity":1}""").status.assert().isEqualTo(403)
    }

    @Test
    fun `beside an authorizer that decides them, the operational one is asked`() {
        val node = HttpNode(rebuilds = true, authorizer = CommandAuthorizer.allOf(listOf(otherConcern, operatorsOnly)))
        node.post(rebuild, ids, user = OPERATOR).status.assert().isEqualTo(200)
        val buyer = node.post(rebuild, ids, user = "alice")
        buyer.status.assert().isEqualTo(403)
        buyer.body!!["detail"].asString().assert().contains("Only an operator sends wow.snapshot.RebuildSnapshot")
    }

    @Test
    fun `an authorizer of another concern does not open the operator's routes`() {
        fun list(authorizer: CommandAuthorizer) = WebTestClient
            .bindToRouterFunction(parkedRoutes(ParkedOps.of(emptyList()), cartManifest(), authorizer, headerCallers))
            .build()
            .post().uri("${OpsPaths.PARKED}/${OpsPaths.LIST}")
            .header("X-User", OPERATOR)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .answer()
        val refused = list(otherConcern)
        refused.status.assert().isEqualTo(403)
        refused.body!!["detail"].asString().assert().contains("no CommandAuthorizer of the service decides")
        list(operatorsOnly).status.assert().isEqualTo(200)
    }
}
