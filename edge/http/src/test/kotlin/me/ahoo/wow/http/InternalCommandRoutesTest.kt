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
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * An internal command (`@InternalCommand`, decision D388): the public port serves no route of it, and answers one as it
 * answers any unknown path, while the internal port's call routes send it as any command; its OpenAPI document leaves
 * it out.
 */
class InternalCommandRoutesTest {
    private val manifest = cartManifest(internal = setOf("Checkout"))
    private val node = HttpNode(manifest = manifest)
    private val calls = callRoutes(manifest, node.gateway, DeclaredServices.resolver(TENANT))
    private val internal: WebTestClient = WebTestClient.bindToRouterFunction(calls).build()

    private fun call(path: String, body: String): Answer = internal.post().uri(path)
        .header(WowHeaders.SERVICE, SERVICE)
        .header(
            WowHeaders.CALL,
            CallHeaders.format(
                CallContext(
                    "call-1",
                    TENANT,
                    Scope("alice"),
                    Causation("root-1", "basket/acme/alice@2"),
                    Operator("alice", TENANT),
                ),
            ),
        )
        .header("Content-Type", "application/json")
        .bodyValue(body)
        .answer()

    @Test
    fun `the public port should answer an internal command's route as an unknown path`() {
        node.post("/shop/cart/cart-1/add-item", """{"sku":"mug"}""").status.assert().isEqualTo(200)
        val internal = node.post("/shop/cart/cart-1/checkout", "{}")
        val unknown = node.post("/shop/cart/cart-1/no-such-command", "{}")
        internal.status.assert().isEqualTo(404)
        internal.contentType.assert().isEqualTo(unknown.contentType)
        internal.body!!.propertyNames().assert().isEqualTo(unknown.body!!.propertyNames())
        internal.body!!["detail"].asString().assert().isEqualTo("No route POST /shop/cart/cart-1/checkout.")
        node.commands.map { it.body }.assert().containsExactly(AddItem("mug"))
    }

    @Test
    fun `the internal port should send an internal command a service calls`() {
        node.post("/shop/cart/cart-1/add-item", """{"sku":"mug"}""").status.assert().isEqualTo(200)
        call("/shop/cart/cart-1/checkout", "{}").status.assert().isEqualTo(200)
        node.commands.map { it.body }.assert().containsExactly(AddItem("mug"), Checkout())
    }

    @Test
    fun `the public port's OpenAPI document should leave an internal command out`() {
        val paths = node.get(WowPaths.OPENAPI).body!!["paths"].propertyNames()
        paths.assert().contains("/shop/cart/{id}/add-item").doesNotContain("/shop/cart/{id}/checkout")
    }

    private companion object {
        const val SERVICE = "basket-service"
    }
}
