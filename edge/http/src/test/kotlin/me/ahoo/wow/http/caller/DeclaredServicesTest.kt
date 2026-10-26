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
import me.ahoo.wow.api.wire.WowHeaders
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest

/** The service a request to the internal port names (decision D300): its service identity, no token. */
class DeclaredServicesTest {
    private fun request(service: String?): ServerRequest {
        val builder = MockServerHttpRequest.post("/shop/cart/c-1/add-item")
        service?.let { builder.header(WowHeaders.SERVICE, it) }
        return ServerRequest.create(
            MockServerWebExchange.from(builder),
            HandlerStrategies.withDefaults().messageReaders()
        )
    }

    @Test
    fun `a request that names a service is that service's identity`(): Unit = runBlocking {
        val basket = DeclaredServices.resolver().resolve(request(" basket-service "))!!
        basket.service.assert().isTrue()
        basket.subject.assert().isEqualTo("service:basket-service")
        basket.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
        DeclaredServices.serviceOf(basket).assert().isEqualTo("basket-service")
        DeclaredServices.resolver("t1").resolve(request("payment.v2_1"))!!.tenantId.assert().isEqualTo("t1")
    }

    @Test
    fun `a request that names none, or no service name, is no service`(): Unit = runBlocking {
        val resolver = DeclaredServices.resolver()
        resolver.resolve(request(null)).assert().isNull()
        resolver.resolve(request("")).assert().isNull()
        resolver.resolve(request("basket service")).assert().isNull()
        resolver.resolve(request("-basket")).assert().isNull()
        resolver.resolve(request("a".repeat(DeclaredServices.MAX_NAME_LENGTH + 1))).assert().isNull()
        DeclaredServices.isName("a".repeat(DeclaredServices.MAX_NAME_LENGTH)).assert().isTrue()
        assertThrownBy<IllegalArgumentException> { DeclaredServices.callerOf("basket service") }
    }
}
