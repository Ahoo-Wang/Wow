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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.ops.OpsPaths
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.http.ops.subscriptionRoutes
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A service identity is taken on the internal port alone (#6662): a resolver that returns one for a request to the public
 * routes, the operator's routes or the subscription routes is answered 403, so no such request is trusted as a service
 * (skipping an access policy, an operational authorizer, or a revealed read); the internal port's call routes take it.
 */
class ServiceIdentityRoutesTest {
    private val service = Caller.ofService("basket-service", TENANT)

    /** An application's resolver that returns a service identity for every request. */
    private val asService = CallerResolver { service }

    private fun Answer.refused() {
        status.assert().withFailMessage { body.toString() }.isEqualTo(403)
        body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        body!!["detail"].asString().assert().contains("service:basket-service").contains("internal port alone")
    }

    @Test
    fun `the public routes refuse a service identity, its commands and reads alike`() {
        val node = HttpNode(callers = asService, rebuilds = true, authorizer = operatorsOnly)
        node.post("/shop/cart/c-1/add-item", """{"sku":"s-1","quantity":1}""").refused()
        node.post("/shop/cart/wow.snapshot.rebuild-snapshot", """{"ids":["c-1"]}""").refused()
        // The state reads and the queries share the routes' one authenticator.
        node.get("/shop/cart/c-1/state").refused()
        node.commands.assert().isEmpty()
    }

    @Test
    fun `the operator's routes and the subscription routes refuse a service identity`() {
        WebTestClient
            .bindToRouterFunction(
                parkedRoutes(ParkedOps.of(emptyList()), cartManifest(), CommandAuthorizer.ALLOW_ALL, asService),
            )
            .build()
            .post().uri("${OpsPaths.PARKED}/${OpsPaths.LIST}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .answer()
            .refused()
        val sampler = NodeSampler(mockk<ServiceNode>(relaxed = true), context = EmptyCoroutineContext)
        WebTestClient
            .bindToRouterFunction(subscriptionRoutes(sampler, cartManifest(), callerResolver = asService))
            .build()
            .get().uri(OpsPaths.SUBSCRIPTIONS)
            .answer()
            .refused()
    }

    @Test
    fun `the internal port's call routes take a service identity`() {
        val node = HttpNode()
        val call = CallHeaders.format(CallContext("call-1", TENANT, Scope.NONE, Causation("root-1", "basket/acme/b@1")))
        val answer = WebTestClient
            .bindToRouterFunction(
                callRoutes(
                    cartManifest(),
                    node.gateway,
                    asService,
                    permits = CallPermits(mapOf("basket-service" to listOf("AddItem"))),
                ),
            )
            .build()
            .post().uri("/shop/cart/c-1/add-item")
            .header(WowHeaders.CALL, call)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"sku":"s-1","quantity":1}""")
            .answer()
        answer.status.assert().withFailMessage { answer.body.toString() }.isEqualTo(200)
        node.commands.single().ref.id.assert().isEqualTo("c-1")
    }
}
