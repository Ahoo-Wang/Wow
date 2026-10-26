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

package me.ahoo.wow.spring.boot

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosec.webflux.ServerWebExchanges
import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.CoSecCallerOptions
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.cosec.CoSecInternalCallerResolver
import me.ahoo.wow.cosec.CoSecRequestFacts
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallAuthorizer
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.InternalCallerResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

class CoSecAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecAutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    class Application {
        @Bean
        fun callerResolver(): CallerResolver = CallerResolver.ANONYMOUS

        @Bean
        fun authorizer(): CommandAuthorizer = CommandAuthorizer.ALLOW_ALL
    }

    @Configuration(proxyBeanMethods = false)
    class CoSec {
        @Bean
        fun authorization(): CoSecAuthorization = CoSecAuthorization { _, _ -> Mono.just(AuthorizeResult.ALLOW) }

        @Bean
        fun httpOptions(): WowHttpOptions = WowHttpOptions(requestFacts = setOf("X-Trace"))
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(CoSecAutoConfiguration::class)
    class Imported

    @Test
    fun `the CoSec resolver is the host's caller resolver, configured from wow properties`() {
        runner.withPropertyValues(
            "wow.tenant.default=home",
            "wow.cosec.cross-scope.roles[0]=admin",
            "wow.cosec.cross-scope.roles[1]=ops",
            "wow.cosec.cross-scope.policies=p1, p2,",
            "wow.cosec.tenant-level.roles=employee",
            "wow.cosec.tenant-level.policies[0]=p3",
            "wow.cosec.service.roles=service",
            "wow.cosec.service.policies[0]=p4",
        ).run { context ->
            context.getBeansOfType(CallerResolver::class.java).values.single().assert()
                .isInstanceOf(CoSecCallerResolver::class.java)
            CoSecAutoConfiguration.callerOptions(
                context.getBean(WowProperties::class.java),
                context.environment
            ).assert().isEqualTo(
                CoSecCallerOptions(
                    "home",
                    setOf("admin", "ops"),
                    setOf("p1", "p2"),
                    tenantRoles = setOf("employee"),
                    tenantPolicies = setOf("p3"),
                    serviceRoles = setOf("service"),
                    servicePolicies = setOf("p4"),
                ),
            )
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `the injected application is trusted only when the host turns it on, in CoSec's inject mode`() {
        val injectMode = arrayOf("cosec.inject.enabled=true", "cosec.authorization.enabled=false")
        runner.withPropertyValues(*injectMode).run { context ->
            CoSecAutoConfiguration.callerOptions(
                context.getBean(WowProperties::class.java),
                context.environment
            ).trustInjectedApp.assert().isFalse()
        }
        runner.withPropertyValues("wow.cosec.trust-injected-app=true", *injectMode).run { context ->
            CoSecAutoConfiguration.callerOptions(
                context.getBean(WowProperties::class.java),
                context.environment
            ).trustInjectedApp.assert().isTrue()
            // Never on the internal port, whose callers are services.
            CoSecAutoConfiguration.callerOptions(
                context.getBean(WowProperties::class.java),
                context.environment,
                injectedApps = false
            ).trustInjectedApp.assert()
                .isFalse()
        }
    }

    @Test
    fun `trusting injected applications outside CoSec's inject mode fails the startup`() {
        listOf(
            arrayOf("wow.cosec.trust-injected-app=true"),
            arrayOf("wow.cosec.trust-injected-app=true", "cosec.inject.enabled=true"),
            arrayOf("wow.cosec.trust-injected-app=true", "cosec.authorization.enabled=false"),
        ).forEach { properties ->
            runner.withPropertyValues(*properties).run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { it.cause }
                    .any { it.message.orEmpty().contains("wow.cosec.trust-injected-app") }.assert().isTrue()
            }
        }
    }

    @Test
    fun `the application's own resolver and authorizer win`() {
        runner.withUserConfiguration(Application::class.java, CoSec::class.java)
            .withPropertyValues("wow.cosec.authorizer.enabled=true")
            .run { context ->
                context.getBean(CallerResolver::class.java).assert().isSameAs(CallerResolver.ANONYMOUS)
                context.getBean(CommandAuthorizer::class.java).assert().isSameAs(CommandAuthorizer.ALLOW_ALL)
            }
    }

    @Test
    fun `the CoSec authorizer is opt-in and needs CoSec's authorization`() {
        runner.withUserConfiguration(CoSec::class.java).run { context ->
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
        runner.withPropertyValues("wow.cosec.authorizer.enabled=true").run { context ->
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
        runner.withUserConfiguration(CoSec::class.java)
            .withPropertyValues("wow.cosec.authorizer.enabled=true")
            .run { context ->
                context.getBean(CommandAuthorizer::class.java).assert().isInstanceOf(CoSecCommandAuthorizer::class.java)
            }
    }

    @Test
    fun `the CoSec request facts join the edge's options`() {
        runner.withUserConfiguration(CoSec::class.java).run { context ->
            context.getBean(WowHttpOptions::class.java).requestFacts.assert()
                .containsExactlyInAnyOrder("X-Trace", CoSecRequestFacts.DEVICE_ID)
        }
    }

    @Test
    fun `the host imports the configuration before its own, only with CoSec on the classpath`() {
        val loader = CoSecAutoConfiguration::class.java.classLoader
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, loader).candidates
        candidates.assert().contains(CoSecAutoConfiguration::class.java.name)
        val annotation = CoSecAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)
        annotation.before.toList().assert().containsExactly(WowAutoConfiguration::class)
        ApplicationContextRunner().withUserConfiguration(Imported::class.java).run { context ->
            context.getBeansOfType(CoSecCallerResolver::class.java).assert().hasSize(1)
        }
        ApplicationContextRunner().withUserConfiguration(Imported::class.java)
            .withClassLoader(FilteredClassLoader(ServerWebExchanges::class.java))
            .run { context -> context.getBeansOfType(CallerResolver::class.java).assert().isEmpty() }
    }

    @Configuration(proxyBeanMethods = false)
    class CallCoSec {
        @Bean
        fun authorization(): CoSecAuthorization = CoSecAuthorization { _, _ -> Mono.just(AuthorizeResult.ALLOW) }
    }

    @Configuration(proxyBeanMethods = false)
    class OwnCallAuthorizer {
        @Bean
        fun callAuthorizer(): CallAuthorizer = OWN_CALLS
    }

    @Test
    fun `the CoSec call authorizer should be there with CoSec's authorization, whatever the authorizer flag`() {
        val runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoSecAutoConfiguration::class.java))
        runner.run { context -> context.getBeansOfType(CallAuthorizer::class.java).assert().isEmpty() }
        runner.withUserConfiguration(CallCoSec::class.java).run { context ->
            context.getBeansOfType(CallAuthorizer::class.java).assert().hasSize(1)
            // The host's own authorizer stays opt-in.
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
        runner.withUserConfiguration(CallCoSec::class.java)
            .withPropertyValues("wow.cosec.authorizer.enabled=false")
            .run { context -> context.getBeansOfType(CallAuthorizer::class.java).assert().hasSize(1) }
        runner.withUserConfiguration(CallCoSec::class.java, OwnCallAuthorizer::class.java).run { context ->
            context.getBean(CallAuthorizer::class.java).assert().isSameAs(OWN_CALLS)
        }
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

    private companion object {
        val OWN_CALLS = CallAuthorizer { _, _ -> me.ahoo.wow.runtime.admission.Authorization.Allowed }
    }
}
