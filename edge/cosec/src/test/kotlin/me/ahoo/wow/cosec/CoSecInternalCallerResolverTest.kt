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

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.caller.InternalCallerResolver
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.server.ServerWebExchange
import java.lang.reflect.Proxy

/** CoSec's service principals on the internal port (decision D193), parsed from their tokens without CoSec's filter. */
class CoSecInternalCallerResolverTest {
    private val request: Request = Proxy.newProxyInstance(
        Request::class.java.classLoader,
        arrayOf(Request::class.java),
    ) { _, _, _ -> null } as Request

    private val requests = object : RequestParser<ServerWebExchange> {
        override fun parse(request: ServerWebExchange): Request = this@CoSecInternalCallerResolverTest.request
    }

    private fun parser(context: () -> SecurityContext) = object : SecurityContextParser {
        override fun parse(request: Request): SecurityContext = context()
    }

    private val serverRequest = ServerRequest.create(
        MockServerWebExchange.from(MockServerHttpRequest.post("/shop/cart/c-1/add-item")),
        HandlerStrategies.withDefaults().messageReaders(),
    )
    private val callers = CoSecCallerResolver(CoSecCallerOptions(serviceRoles = setOf("service")))

    @Test
    fun `a service principal's token should be its service identity`() = runTest {
        val resolver = CoSecInternalCallerResolver(
            requests,
            parser { securityContext("basket", roles = setOf("service")) },
            callers,
        )
        resolver.resolve(serverRequest)!!.service.assert().isTrue()
        val user = CoSecInternalCallerResolver(requests, parser { securityContext("mallory") }, callers)
        user.resolve(serverRequest)!!.service.assert().isFalse()
    }

    @Test
    fun `a token that does not parse should be no caller`() = runTest {
        val resolver = CoSecInternalCallerResolver(requests, parser { error("Expired.") }, callers)
        resolver.resolve(serverRequest).assert().isNull()
    }

    @Configuration(proxyBeanMethods = false)
    class Parsers {
        @Bean
        fun requestParser(): RequestParser<ServerWebExchange> = object : RequestParser<ServerWebExchange> {
            override fun parse(request: ServerWebExchange): Request = error("Not parsed here.")
        }

        @Bean
        fun securityContextParser(): SecurityContextParser = object : SecurityContextParser {
            override fun parse(request: Request): SecurityContext = error("Not parsed here.")
        }
    }

    @Test
    fun `the internal resolver should be the host's when CoSec's parsers are there`() {
        val runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoSecAutoConfiguration::class.java))
        runner.run { context -> context.getBeansOfType(InternalCallerResolver::class.java).assert().isEmpty() }
        runner.withUserConfiguration(Parsers::class.java).withPropertyValues("wow.cosec.service.roles=service")
            .run { context ->
                context.getBean(InternalCallerResolver::class.java)
                    .assert().isInstanceOf(CoSecInternalCallerResolver::class.java)
            }
    }
}
