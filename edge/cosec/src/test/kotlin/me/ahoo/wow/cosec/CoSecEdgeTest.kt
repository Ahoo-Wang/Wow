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
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.FrameworkCodes
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
        options = CoSecRequestFacts.addTo(WowHttpOptions(requestFacts = setOf("X-Trace"))),
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
        node.post("/shop/cart/open-cart").assertProblem(401, FrameworkCodes.UNAUTHENTICATED)
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
    fun `request facts already listed are kept as they are`() {
        val options = WowHttpOptions(requestFacts = CoSecRequestFacts.NAMES)
        CoSecRequestFacts.addTo(options).assert().isSameAs(options)
    }
}
