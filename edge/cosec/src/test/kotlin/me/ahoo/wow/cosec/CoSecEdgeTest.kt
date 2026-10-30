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

import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.util.concurrent.CopyOnWriteArrayList

/** [CoSecCallerResolver] and [CoSecCommandAuthorizer] behind the public routes, after CoSec's pipeline filter. */
class CoSecEdgeTest {
    private val headers = CopyOnWriteArrayList<Map<String, String>>()
    private val cosec = CoSecPipeline()
    private val node = CoSecNode(
        callers = CoSecCallerResolver(CoSecCallerOptions(crossScopeRoles = setOf("admin"))),
        cosec = cosec,
        options = CoSecRequestIds.addTo(CoSecRequestFacts.addTo(WowHttpOptions(requestFacts = setOf("X-Trace")))),
        transformers = listOf(
            object : CommandTransformer {
                override val input = CommandType("$CONTEXT.cart.OpenCart", OpenCart::class.java)

                override suspend fun transform(input: CommandInput): CommandTarget {
                    headers += input.headers
                    return CommandTarget(input.type, input.aggregateId, input.body, input.headers)
                }
            },
        ),
        authorizer = CoSecCommandAuthorizer(cosec.checker),
    )

    private fun token(value: String) = mapOf("Authorization" to "Bearer $value")

    private fun Pair<Int, JsonNode?>.assertProblem(status: Int, code: String) {
        first.assert().isEqualTo(status)
        second!!["code"].asString().assert().isEqualTo(code)
    }

    private fun Pair<Int, JsonNode?>.assertSucceeded(): JsonNode {
        first.assert().isEqualTo(200)
        return second!!
    }

    @Test
    fun `a request CoSec did not authenticate is answered 401`() {
        node.post("/shop/cart/open-cart").assertProblem(401, FrameworkCodes.AUTH_UNAUTHENTICATED)
    }

    @Test
    fun `the principal's tenant and id are the command's tenant and owner`() {
        val result = node.post("/shop/cart/open-cart", headers = token("alice;acme")).assertSucceeded()
        result["ref"]["tenantId"].asString().assert().isEqualTo("acme")
        result["status"].asString().assert().isEqualTo("succeeded")
    }

    @Test
    fun `CoSec-Space-Id selects a space CoSec validated, and CoSec refuses a space the principal is not in`() {
        val member = token("alice;acme;editor@s1,viewer@s2") + (CoSecParameters.SPACE_ID to "s2")
        node.post("/shop/cart/open-cart", headers = member).assertSucceeded()
        node.post("/shop/cart/open-cart", headers = member + (CoSecParameters.SPACE_ID to "s9")).first
            .assert().isEqualTo(403)
    }

    @Test
    fun `Wow-Scope is forbidden without the cross-scope role and honoured with it`() {
        val opened = node.post("/shop/cart/open-cart", headers = token("alice;acme")).assertSucceeded()
        val id = opened["ref"]["id"].asString()
        val scope = mapOf(WowHeaders.SCOPE to """owner="alice"""")
        node.post("/shop/cart/$id/add-item", """{"sku":"apple"}""", scope + token("bob;acme"))
            .assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
        node.post("/shop/cart/$id/add-item", """{"sku":"apple"}""", scope + token("root;acme;admin"))
            .assertSucceeded()["version"].asInt().assert().isEqualTo(2)
        node.post("/shop/cart/$id/add-item", """{"sku":"pear"}""", token("bob;acme"))
            .assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
    }

    @Test
    fun `CoSec decides the route and the authorizer the command, by one action, in the request's scope`() {
        val opened = node.post("/shop/cart/open-cart", headers = token("alice;acme")).assertSucceeded()
        val id = opened["ref"]["id"].asString()
        cosec.asked.clear()
        node.post("/shop/cart/$id/add-item", """{"sku":"apple"}""", token("alice;acme")).assertSucceeded()
        cosec.asked.map { it.toString() }.assert().isEqualTo(listOf("shop:AddItem cart/$id", "shop:AddItem cart/$id"))
    }

    @Test
    fun `the authorizer decides a command answered as an event stream with the request's security too`() {
        val opened = node.post("/shop/cart/open-cart", headers = token("alice;acme")).assertSucceeded()
        val id = opened["ref"]["id"].asString()
        // The route is permitted, the command is not: only the authorizer asks a second time.
        var asked = 0
        cosec.permits = { it.action != "shop:AddItem" || ++asked == 1 }
        val status = node.client.post().uri("/shop/cart/$id/add-item")
            .header("Authorization", "Bearer alice;acme")
            .header("Content-Type", "application/json")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("""{"sku":"apple"}""")
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        status.status.value().assert().isEqualTo(403)
        status.responseBody!!.assert().contains(FrameworkCodes.AUTH_FORBIDDEN)
        asked.assert().isEqualTo(2)
    }

    @Test
    fun `CoSec-Request-Id is a command's request id without Wow-Request-Id, which wins`() {
        val opened = node.post("/shop/cart/open-cart", headers = token("alice;acme")).assertSucceeded()
        val path = "/shop/cart/${opened["ref"]["id"].asString()}/add-item"
        val retried = token("alice;acme") + (CoSecRequestIds.HEADER to "gateway-1")
        node.post(path, """{"sku":"apple"}""", retried).assertSucceeded()["version"].asInt().assert().isEqualTo(2)
        // The gateway's retry carries the same header: the same request, not a second item.
        node.post(path, """{"sku":"apple"}""", retried).assertSucceeded()["version"].asInt().assert().isEqualTo(2)
        // Wow-Request-Id wins: a new one is a new request whatever CoSec-Request-Id says.
        node.post(path, """{"sku":"pear"}""", retried + (WowHeaders.REQUEST_ID to "client-1"))
            .assertSucceeded()["version"].asInt().assert().isEqualTo(3)
    }

    @Test
    fun `the CoSec request facts and the app of CoSec's scope reach the command beside the host's own`() {
        node.post(
            "/shop/cart/open-cart",
            headers = token("alice;acme") + mapOf(
                CoSecParameters.APP_ID to "shop-app",
                CoSecRequestFacts.DEVICE_ID to "device-1",
                "X-Trace" to "t-1",
                "X-Other" to "ignored",
                "app_id" to "forged",
            ),
        ).assertSucceeded()
        headers.single().assert().isEqualTo(
            mapOf(
                RequestFacts.APP_ID to "shop-app",
                CoSecRequestFacts.DEVICE_ID to "device-1",
                "X-Trace" to "t-1",
            ),
        )
    }

    @Test
    fun `a request in no app records none`() {
        node.post("/shop/cart/open-cart", headers = token("alice;acme") + ("app_id" to "forged")).assertSucceeded()
        headers.single().assert().isEmpty()
    }

    @Test
    fun `request facts already listed are kept as they are`() {
        val options = WowHttpOptions(requestFacts = CoSecRequestFacts.NAMES)
        CoSecRequestFacts.addTo(options).assert().isSameAs(options)
    }
}
