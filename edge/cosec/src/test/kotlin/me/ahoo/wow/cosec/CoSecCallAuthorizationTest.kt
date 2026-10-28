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

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.util.concurrent.CopyOnWriteArrayList
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

/**
 * With CoSec, a service's synchronous call on the internal port is authorized by CoSec's policies as its service
 * principal (decision D226, review A3-2), whether or not `wow.cosec.authorizer.enabled` is set: the internal port runs
 * no WebFilter, and the host's default authorizer allows all.
 */
class CoSecCallAuthorizationTest {
    /** The paths CoSec was asked about, and by whom; it allows the service principal's `add-item` alone. */
    private val asked = CopyOnWriteArrayList<String>()
    private val authorization = CoSecAuthorization { request, context ->
        asked += "${context.principal.id} ${request.path}"
        val allowed = "writer" in context.principal.roles && request.path.endsWith("add-item")
        Mono.just(if (allowed) AuthorizeResult.ALLOW else AuthorizeResult.deny("Not this service's."))
    }

    private val cosec = CoSecCommandAuthorizer(authorization, cartManifest(), Caller.DEFAULT_TENANT)

    /** CoSec's decision on the service identities' calls; no objection to any other caller's command. */
    private val calls = CommandAuthorizer { caller, action ->
        if (caller.service) cosec.authorize(caller, action) else Authorization.Allowed
    }
    private val node = CoSecNode(
        callers = CoSecCallerResolver(CoSecCallerOptions()),
        authorizer = CommandAuthorizer.allOf(listOf(calls, CommandAuthorizer.ALLOW_ALL)),
    )
    private val service = Caller(
        subject = "basket",
        service = true,
        attributes = mapOf(CoSecCallerResolver.ROLES to listOf("service", "writer")),
    )
    private val internal = node.calls(CallerResolver { service })

    private fun WebTestClient.call(path: String, body: String): Pair<Int, String?> {
        val call = CallHeaders.format(CallContext("call-${path.hashCode()}", "t-1", Scope("alice"), ROOT))
        val result = post().uri(path)
            .header(WowHeaders.CALL, call)
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        val code = result.responseBody?.let { json.readTree(it)["code"]?.asString() }
        return result.status.value() to code
    }

    @Test
    fun `a service's call should be authorized by CoSec as its service principal`() {
        // CoSec allows the principal's add-item: the gateway decides it, on a cart that does not exist.
        internal.call("/shop/cart/c-1/add-item", """{"sku":"mug"}""").second.assert()
            .isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        // And denies its open-cart, though the host's own authorizer allows all.
        internal.call("/shop/cart/open-cart", """{"note":"hi"}""").assert()
            .isEqualTo(403 to FrameworkCodes.AUTH_FORBIDDEN)
        asked.assert().hasSize(2)
        asked.first().assert().startsWith("basket /shop/cart/c-1/").endsWith("add-item")
        asked.last().assert().startsWith("basket /shop/cart/").endsWith("open-cart")
    }

    @Test
    fun `a caller that is no service should stay the host's authorizer's`() {
        node.post("/shop/cart/open-cart", headers = mapOf("X-User" to "alice")).first.assert().isEqualTo(200)
        asked.assert().isEmpty()
    }

    private companion object {
        val ROOT = Causation("root-1", "basket/t-1/b-1@1")
    }
}
