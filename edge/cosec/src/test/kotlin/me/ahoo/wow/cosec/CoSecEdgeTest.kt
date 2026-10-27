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

import me.ahoo.cosec.api.context.request.AppIdCapable
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
import tools.jackson.databind.JsonNode
import java.util.concurrent.CopyOnWriteArrayList

/** [CoSecCallerResolver] behind the public routes, after a stand-in of CoSec's WebFilter ([coSecFilter]). */
class CoSecEdgeTest {
    private val headers = CopyOnWriteArrayList<Map<String, String>>()
    private val node = CoSecNode(
        callers = CoSecCallerResolver(CoSecCallerOptions(crossScopeRoles = setOf("admin"))),
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
    )

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
        val result = node.post("/shop/cart/open-cart", headers = mapOf("X-User" to "alice")).assertSucceeded()
        result["ref"]["tenantId"].asString().assert().isEqualTo("acme")
        result["status"].asString().assert().isEqualTo("SUCCEEDED")
    }

    @Test
    fun `Wow-Space selects a space the principal belongs to, and is forbidden otherwise`() {
        val member = mapOf("X-User" to "alice", "X-Roles" to "editor@s1,viewer@s2", WowHeaders.SPACE to "s2")
        node.post("/shop/cart/open-cart", headers = member).assertSucceeded()
        node.post("/shop/cart/open-cart", headers = member + (WowHeaders.SPACE to "s9"))
            .assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `Wow-Scope is forbidden without the cross-scope role and honoured with it`() {
        val opened = node.post("/shop/cart/open-cart", headers = mapOf("X-User" to "alice")).assertSucceeded()
        val id = opened["ref"]["id"].asString()
        val scope = mapOf(WowHeaders.SCOPE to """owner="alice"""")
        node.post("/shop/cart/$id/add-item", """{"sku":"apple"}""", scope + ("X-User" to "bob"))
            .assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
        val admin = scope + mapOf("X-User" to "root", "X-Roles" to "admin")
        node.post("/shop/cart/$id/add-item", """{"sku":"apple"}""", admin)
            .assertSucceeded()["version"].asInt().assert().isEqualTo(2)
        node.post("/shop/cart/$id/add-item", """{"sku":"pear"}""", mapOf("X-User" to "bob"))
            .assertProblem(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
    }

    @Test
    fun `CoSec-Request-Id is a command's request id without Wow-Request-Id, which wins`() {
        val opened = node.post("/shop/cart/open-cart", headers = mapOf("X-User" to "alice")).assertSucceeded()
        val path = "/shop/cart/${opened["ref"]["id"].asString()}/add-item"
        val retried = mapOf("X-User" to "alice", CoSecRequestIds.HEADER to "gateway-1")
        node.post(path, """{"sku":"apple"}""", retried).assertSucceeded()["version"].asInt().assert().isEqualTo(2)
        // The gateway's retry carries the same header: the same request, not a second item.
        node.post(path, """{"sku":"apple"}""", retried).assertSucceeded()["version"].asInt().assert().isEqualTo(2)
        // Wow-Request-Id wins: a new one is a new request whatever CoSec-Request-Id says.
        node.post(path, """{"sku":"pear"}""", retried + (WowHeaders.REQUEST_ID to "client-1"))
            .assertSucceeded()["version"].asInt().assert().isEqualTo(3)
    }

    @Test
    fun `the CoSec request facts and the application CoSec authorized reach the command beside the host's own`() {
        node.post(
            "/shop/cart/open-cart",
            headers = mapOf(
                "X-User" to "alice",
                "X-Granted" to "app-role",
                AppIdCapable.APP_ID_KEY to "shop-app",
                CoSecRequestFacts.DEVICE_ID to "device-1",
                "X-Trace" to "t-1",
                "X-Other" to "ignored",
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
    fun `a CoSec-App-Id CoSec did not authorize the request in is not recorded`() {
        node.post(
            "/shop/cart/open-cart",
            headers = mapOf("X-User" to "root", AppIdCapable.APP_ID_KEY to "shop-app", "app_id" to "forged"),
        ).assertSucceeded()
        headers.single().assert().isEmpty()
    }

    @Test
    fun `the application of an injected request is not recorded unless the host trusts it`() {
        val injected = mapOf("X-User" to "alice", "X-Granted" to "injected", AppIdCapable.APP_ID_KEY to "shop-app")
        node.post("/shop/cart/open-cart", headers = injected).assertSucceeded()
        headers.single().assert().isEmpty()
    }

    @Test
    fun `a host that trusts injected applications records the one the gateway forwarded, and no other`() {
        val recorded = CopyOnWriteArrayList<Map<String, String>>()
        val trusting = CoSecNode(
            callers = CoSecCallerResolver(CoSecCallerOptions(trustInjectedApp = true)),
            transformers = listOf(
                object : CommandTransformer {
                    override val input = CommandType("$CONTEXT.cart.OpenCart", OpenCart::class.java)

                    override suspend fun transform(input: CommandInput): CommandTarget {
                        recorded += input.headers
                        return CommandTarget(input.type, input.aggregateId, input.body, input.headers)
                    }
                },
            ),
        )
        val injected = mapOf("X-User" to "alice", "X-Granted" to "injected")
        trusting.post("/shop/cart/open-cart", headers = injected + (AppIdCapable.APP_ID_KEY to " shop-app "))
            .assertSucceeded()
        trusting.post("/shop/cart/open-cart", headers = injected + (AppIdCapable.APP_ID_KEY to " ")).assertSucceeded()
        trusting.post("/shop/cart/open-cart", headers = injected + ("app_id" to "forged")).assertSucceeded()
        // A request CoSec authorized here still needs the application's role, whatever the option.
        trusting.post(
            "/shop/cart/open-cart",
            headers = mapOf("X-User" to "root", AppIdCapable.APP_ID_KEY to "shop-app"),
        ).assertSucceeded()
        trusting.post(
            "/shop/cart/open-cart",
            headers = mapOf("X-User" to "bob", "X-Granted" to "app-role", AppIdCapable.APP_ID_KEY to "console"),
        ).assertSucceeded()
        recorded.toList().assert().isEqualTo(
            listOf(
                mapOf(RequestFacts.APP_ID to "shop-app"),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                mapOf(RequestFacts.APP_ID to "console"),
            ),
        )
    }

    @Test
    fun `request facts already listed are kept as they are`() {
        val options = WowHttpOptions(requestFacts = CoSecRequestFacts.NAMES)
        CoSecRequestFacts.addTo(options).assert().isSameAs(options)
    }
}
