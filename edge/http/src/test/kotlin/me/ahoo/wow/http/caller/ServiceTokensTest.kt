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

package me.ahoo.wow.http.caller

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.caller.Caller
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest

/** The service identities a public port trusts by their static tokens (decision D193). */
class ServiceTokensTest {
    private val tokens = ServiceTokens(mapOf("basket" to listOf(OLD, NEW), "payment" to listOf(PAYMENT)))
    private val user = Caller(subject = "alice")
    private val delegate = CallerResolver { user }

    private fun request(authorization: String?): ServerRequest {
        val builder = MockServerHttpRequest.post("/shop/cart/c-1/add-item")
        authorization?.let { builder.header("Authorization", it) }
        return ServerRequest.create(MockServerWebExchange.from(builder), HandlerStrategies.withDefaults().messageReaders())
    }

    @Test
    fun `a trusted token should be its service's identity, each of its tokens while one is rotated`(): Unit =
        runBlocking {
            val resolver = tokens.resolver(delegate)
            val basket = resolver.resolve(request("Bearer $OLD"))!!
            basket.service.assert().isTrue()
            basket.subject.assert().isEqualTo("service:basket")
            basket.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
            resolver.resolve(request("bearer $NEW"))!!.subject.assert().isEqualTo("service:basket")
            resolver.resolve(request("Bearer $PAYMENT"))!!.subject.assert().isEqualTo("service:payment")
        }

    @Test
    fun `any other request should be the delegate's, never a service identity`(): Unit = runBlocking {
        val resolver = tokens.resolver(delegate)
        resolver.resolve(request(null)).assert().isSameAs(user)
        resolver.resolve(request("Basic $OLD")).assert().isSameAs(user)
        resolver.resolve(request("Bearer not-a-trusted-token-of-32-characters")).assert().isSameAs(user)
        tokens.serviceOf("short").assert().isNull()
    }

    @Test
    fun `no trusted token should leave the delegate as it is`() {
        val none = ServiceTokens(emptyMap())
        none.isEmpty.assert().isTrue()
        none.resolver(delegate).assert().isSameAs(delegate)
    }

    @Test
    fun `tokens should be long enough, named, given once and in a tenant`() {
        assertThrownBy<IllegalArgumentException> { ServiceTokens(mapOf("basket" to listOf("short"))) }
        assertThrownBy<IllegalArgumentException> { ServiceTokens(mapOf(" " to listOf(OLD))) }
        assertThrownBy<IllegalArgumentException> { ServiceTokens(mapOf("a" to listOf(OLD), "b" to listOf(OLD))) }
        assertThrownBy<IllegalArgumentException> { ServiceTokens(mapOf("a" to listOf(OLD)), " ") }
    }

    private companion object {
        const val OLD = "basket-old-token-0123456789abcdefgh"
        const val NEW = "basket-new-token-0123456789abcdefgh"
        const val PAYMENT = "payment-token-0123456789abcdefghijk"
    }
}
