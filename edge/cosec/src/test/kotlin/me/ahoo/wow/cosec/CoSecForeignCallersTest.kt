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

package me.ahoo.wow.cosec

import me.ahoo.cosec.testkit.webflux.CoSecTestBindings
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.ops.OpsPaths
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.runtime.node.ParkedOps
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.databind.JsonNode

/**
 * CoSec's authorizer behind the public routes and the operator's routes, after CoSec's pipeline filter, beside a
 * `CallerResolver` of the application's (decision D485 ⑦): a caller of a request that is not the principal CoSec
 * authenticated for it, which CoSec cannot evaluate, is refused, a domain command and Wow's operational commands
 * alike. Through 10.0.0-beta.21 the authorizer abstained for it, and every command was allowed.
 */
@OptIn(ExperimentalWowApi::class, InternalWowApi::class)
class CoSecForeignCallersTest {
    private val cosec = CoSecPipeline()
    private val own = CoSecCallerResolver(CoSecCallerOptions(crossScopeRoles = setOf(OPERATOR)))

    /** An application's resolver that maps CoSec's principal to a subject of its own naming. */
    private val mapped = object : CallerResolver {
        override suspend fun resolve(request: ServerRequest): Caller? =
            own.resolve(request)?.let { it.copyWith(subject = "user:${it.subject}") }
    }

    /** An application's resolver that answers a request CoSec permitted without a credential as the anonymous caller. */
    private val anonymous = object : CallerResolver {
        override suspend fun resolve(request: ServerRequest): Caller? = own.resolve(request) ?: Caller.of("acme")
    }

    private fun node(callers: CallerResolver) =
        CoSecNode(callers = callers, cosec = cosec, authorizer = CoSecCommandAuthorizer(cosec.checker), rebuilds = true)

    private fun token(value: String) = mapOf("Authorization" to "Bearer $value")

    private fun Pair<Int, JsonNode?>.assertRefused(name: String) {
        first.assert().describedAs(second.toString()).isEqualTo(403)
        second!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        second!!["detail"].asString().assert().startsWith("CoSec cannot authorize $name")
    }

    @Test
    fun `a command of a caller the application mapped from CoSec's principal is refused`() {
        node(mapped).post("/shop/cart/open-cart", headers = token("alice;acme")).assertRefused("shop:OpenCart")
        // CoSec's pipeline permitted the route; the authorizer did not ask CoSec for a caller it cannot evaluate.
        cosec.asked.map { it.action }.assert().containsExactly("shop:OpenCart")
    }

    @Test
    fun `a command of the anonymous caller an application's resolver answers is refused`() {
        node(anonymous).post("/shop/cart/open-cart").assertRefused("shop:OpenCart")
    }

    @Test
    fun `CoSec's own caller is still decided by CoSec`() {
        val node = node(own)
        node.post("/shop/cart/open-cart", headers = token("alice;acme")).first.assert().isEqualTo(200)
        cosec.permits = { it.action != "shop:OpenCart" }
        node.post("/shop/cart/open-cart", headers = token("alice;acme")).second!!["detail"].asString()
            .assert().isEqualTo("CoSec does not permit shop:OpenCart on cart/(none).")
    }

    @Test
    fun `the snapshot rebuild, an operational command, of a mapped caller is refused`() {
        val rebuild = "/shop/cart/wow.snapshot.rebuild-snapshot"
        val ids = """{"ids":["c-1"]}"""
        val operator = token("root;acme;$OPERATOR")
        node(mapped).post(rebuild, ids, operator).assertRefused("shop:wow.snapshot.RebuildSnapshot")
        // CoSec's own caller reaches it, CoSec deciding.
        node(own).post(rebuild, ids, operator).first.assert().isEqualTo(200)
    }

    @Test
    fun `an operator's read of a parked entry, operational too, of a mapped caller is refused`() {
        fun read(callers: CallerResolver) = WebTestClient
            .bindToRouterFunction(
                parkedRoutes(ParkedOps.of(emptyList()), cartManifest(), CoSecCommandAuthorizer(cosec.checker), callers),
            )
            .webFilter<WebTestClient.RouterFunctionSpec>(CoSecTestBindings.filter(cosec.pipeline))
            .build()
            .post().uri("${OpsPaths.PARKED}/${OpsPaths.ENTRY}")
            .header("Authorization", "Bearer root;acme;$OPERATOR")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"subscription":"$CONTEXT.CartProjector.onAdded","context":"$CONTEXT","aggregate":"cart",""" +
                    """"tenantId":"acme","id":"c-1"}""",
            )
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        val refused = read(mapped)
        refused.status.value().assert().describedAs(refused.responseBody).isEqualTo(403)
        json.readTree(refused.responseBody)["detail"].asString().assert()
            .startsWith("CoSec cannot authorize $CONTEXT:${Ledger.READ} on ")
        // CoSec's own caller is authorized, and told the entry is not parked.
        read(own).status.value().assert().isEqualTo(404)
    }

    private companion object {
        const val OPERATOR = "ops.operator"
    }
}
