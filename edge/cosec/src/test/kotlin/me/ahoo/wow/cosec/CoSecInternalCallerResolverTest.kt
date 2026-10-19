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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.principal.RoleCapable
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosec.webflux.ReactiveRemoteIpResolver
import me.ahoo.cosec.webflux.ReactiveRequestParser
import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.caller.CallerResolver
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
import java.time.Instant

/**
 * CoSec's service principals on the internal port (decision D193), from their tokens verified without CoSec's filter
 * (review X-14).
 */
class CoSecInternalCallerResolverTest {
    private val secret = Algorithm.HMAC256("the-service-token-secret-of-at-least-256-bits")
    private val tokens = JwtTokenVerifier(secret)
    private val requests = ReactiveRequestParser(ReactiveRemoteIpResolver)
    private val callers = CoSecCallerResolver(CoSecCallerOptions(serviceRoles = setOf("service")))
    private val resolver = CoSecInternalCallerResolver(requests, tokens, callers)

    private fun token(
        subject: String,
        roles: List<String> = emptyList(),
        algorithm: Algorithm = secret,
        expiresAt: Instant = Instant.now().plusSeconds(600),
    ): String = JWT.create().withJWTId(
        "t-$subject"
    ).withSubject(subject).withClaim(RoleCapable.ROLE_KEY, roles).withExpiresAt(expiresAt)
        .sign(algorithm)

    private fun request(token: String?): ServerRequest {
        val request = MockServerHttpRequest.post("/shop/cart/c-1/add-item")
        token?.let { request.header("Authorization", "Bearer $it") }
        return ServerRequest.create(
            MockServerWebExchange.from(request),
            HandlerStrategies.withDefaults().messageReaders(),
        )
    }

    @Test
    fun `a service principal's token should be its service identity`() = runTest {
        resolver.resolve(request(token("basket", listOf("service"))))!!.service.assert().isTrue()
        resolver.resolve(request(token("mallory")))!!.service.assert().isFalse()
    }

    @Test
    fun `a forged service principal should be no caller, though an inject-mode parser takes it`() = runTest {
        val forged = token("basket", listOf("service"), Algorithm.HMAC256("a-key-the-service-never-issued-tokens-with"))
        // CoSec's inject mode trusts the gateway in front of the public port and checks no signature.
        val injected = InjectSecurityContextParser.parse(requests.parse(request(forged).exchange()))
        injected.principal.roles.assert().contains("service")
        resolver.resolve(request(forged)).assert().isNull()
    }

    @Test
    fun `an expired or missing token should be no service`() = runTest {
        resolver.resolve(request(token("basket", listOf("service"), expiresAt = Instant.now().minusSeconds(60))))
            .assert().isNull()
        resolver.resolve(request(null))?.service.assert().isNotEqualTo(true)
    }

    @Configuration(proxyBeanMethods = false)
    class Parsers {
        @Bean
        fun requestParser(): RequestParser<ServerWebExchange> = object : RequestParser<ServerWebExchange> {
            override fun parse(request: ServerWebExchange): Request = error("Not parsed here.")
        }

        // CoSec's inject mode: its parser is the application's SecurityContextParser bean.
        @Bean
        fun securityContextParser(): SecurityContextParser = InjectSecurityContextParser
    }

    @Configuration(proxyBeanMethods = false)
    class Tokens {
        @Bean
        fun tokenVerifier(): TokenVerifier = JwtTokenVerifier(Algorithm.HMAC256("the-service-token-secret"))
    }

    @Test
    fun `the internal resolver should be the host's when CoSec's request parser and token verifier are there`() {
        val runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoSecAutoConfiguration::class.java))
            .withPropertyValues("wow.cosec.service.roles=service")
        runner.run { context -> context.getBeansOfType(InternalCallerResolver::class.java).assert().isEmpty() }
        // Parsers alone, as in CoSec's inject mode without a verifier: no resolver that would trust the parser.
        runner.withUserConfiguration(Parsers::class.java)
            .run { context -> context.getBeansOfType(InternalCallerResolver::class.java).assert().isEmpty() }
        runner.withUserConfiguration(Parsers::class.java, Tokens::class.java).run { context ->
            context.getBean(InternalCallerResolver::class.java)
                .assert().isInstanceOf(CoSecInternalCallerResolver::class.java)
            // It is not a CallerResolver: the public port's resolver stays the only one (review X-13).
            context.getBeansOfType(CallerResolver::class.java).keys.assert().containsExactly("wowCoSecCallerResolver")
        }
    }
}
