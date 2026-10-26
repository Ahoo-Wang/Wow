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

import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.wow.api.caller.Caller
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
import me.ahoo.wow.spi.config.configCheck
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.web.server.ServerWebExchange

/**
 * Wires CoSec into the host when the application has wow-cosec and CoSec's WebFlux module (review R5.C-12): the host
 * wires its optional integrations as it wires Kafka, and wow-cosec keeps the Spring-free part, the resolvers and the
 * authorizer. It reads `wow.cosec.*` once, through [WowProperties.cosec], the keys' one reader and one default.
 *
 * - [CoSecCallerResolver] is the host's [CallerResolver] unless the application has one: the host's anonymous
 *   default steps aside. Its options are `wow.tenant.default`, `wow.cosec.cross-scope.roles` /
 *   `wow.cosec.cross-scope.policies` and `wow.cosec.tenant-level.roles` / `wow.cosec.tenant-level.policies` (the
 *   tenant-level callers, decision D189), `wow.cosec.service.roles` / `wow.cosec.service.policies` (the service
 *   identities, decision D193), comma-separated or indexed lists, and `wow.cosec.trust-injected-app` (false), whether
 *   a service in CoSec's inject mode records the application of the requests its gateway forwards (decision D236 ②,
 *   [trustInjectedApp]).
 * - [CoSecInternalCallerResolver] is the host's [InternalCallerResolver], the callers of the internal port's call
 *   routes, when the application has CoSec's request parser and [TokenVerifier] and no internal resolver of its own: it
 *   verifies each service principal's token itself, in every CoSec mode (review X-14). It is not a [CallerResolver],
 *   so the public port's resolver stays the only one (review X-13).
 * - The CoSec request fact `CoSec-Device-Id` ([CoSecRequestFacts]) joins the edge's request-fact list
 *   (`wow.http.request-facts`); the application CoSec authorized a request in is the standard fact `app_id` (D232).
 * - [CoSecCommandAuthorizer] is the host's [CommandAuthorizer] when `wow.cosec.authorizer.enabled` is true, the
 *   application has CoSec's `Authorization` and no authorizer of its own. On the public port CoSec's WebFilter
 *   already authorizes the command routes by path and method.
 * - [CoSecCommandAuthorizer] is also the [CallAuthorizer] of the service identities' commands, their synchronous calls
 *   on the internal port, whenever the application has CoSec's `Authorization` and no call authorizer of its own,
 *   whatever `wow.cosec.authorizer.enabled` says (decision D226, review A3-2): the internal port runs no WebFilter, so
 *   a call is authorized as its service principal by CoSec's policies, never by the host's default that allows all.
 */
@AutoConfiguration(before = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [COSEC_MODULE, COSEC_WEBFLUX])
@EnableConfigurationProperties(WowProperties::class)
class CoSecAutoConfiguration {
    @Bean
    @Conditional(NoCallerResolver::class)
    fun wowCoSecCallerResolver(properties: WowProperties, environment: Environment): CoSecCallerResolver =
        CoSecCallerResolver(callerOptions(properties, environment))

    /**
     * The callers of the internal port's call routes (decision D193): CoSec's service principals, from their tokens
     * verified by CoSec's [TokenVerifier], since the internal port runs no CoSec filter; never by the application's
     * [me.ahoo.cosec.context.SecurityContextParser], which in CoSec's inject mode does not verify a token's signature
     * (review X-14).
     */
    @Bean
    @Conditional(InternalCallersAvailable::class)
    fun wowCoSecInternalCallerResolver(
        requests: RequestParser<ServerWebExchange>,
        tokens: TokenVerifier,
        properties: WowProperties,
        environment: Environment,
    ): CoSecInternalCallerResolver =
        CoSecInternalCallerResolver(
            requests,
            tokens,
            // A service's call is never an injected request: it has the application its Wow-Call carries (D236).
            CoSecCallerResolver(callerOptions(properties, environment, injectedApps = false)),
        )

    /** The authorizer of the services' calls (decision D226): CoSec's policies, as the service principal. */
    @Bean
    @Conditional(CallAuthorizerAvailable::class)
    fun wowCoSecCallAuthorizer(authorization: CoSecAuthorization, properties: WowProperties): CallAuthorizer {
        val authorizer = CoSecCommandAuthorizer(authorization, defaultTenant(properties))
        return CallAuthorizer(authorizer::authorize)
    }

    @Bean
    @Conditional(AuthorizerEnabled::class)
    fun wowCoSecCommandAuthorizer(
        authorization: CoSecAuthorization,
        properties: WowProperties
    ): CoSecCommandAuthorizer =
        CoSecCommandAuthorizer(authorization, defaultTenant(properties))

    /** No [CallerResolver] is defined yet: the application's own one wins over CoSec's. */
    internal class NoCallerResolver : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            context.beanFactory?.getBeanNamesForType(CallerResolver::class.java, true, false).isNullOrEmpty()
    }

    /**
     * The application has CoSec's request parser and token verifier, and no
     * [me.ahoo.wow.http.caller.InternalCallerResolver] of its own.
     */
    internal class InternalCallersAvailable : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return beans.getBeanNamesForType(RequestParser::class.java, true, false).isNotEmpty() &&
                beans.getBeanNamesForType(TokenVerifier::class.java, true, false).isNotEmpty() &&
                beans.getBeanNamesForType(InternalCallerResolver::class.java, true, false).isEmpty()
        }
    }

    /** The application has CoSec's `Authorization` and no [CallAuthorizer] of its own. */
    internal class CallAuthorizerAvailable : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return beans.getBeanNamesForType(CoSecAuthorization::class.java, true, false).isNotEmpty() &&
                beans.getBeanNamesForType(CallAuthorizer::class.java, true, false).isEmpty()
        }
    }

    /**
     * `wow.cosec.authorizer.enabled` is true, the application has CoSec's `Authorization` and no authorizer. A
     * condition reads the environment before the properties are bound, with the bound properties' default (false).
     */
    internal class AuthorizerEnabled : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            val enabled = WowProperties.CoSec.Authorizer().enabled
            return context.environment.getProperty(AUTHORIZER_ENABLED, Boolean::class.java, enabled) &&
                beans.getBeanNamesForType(CoSecAuthorization::class.java, true, false).isNotEmpty() &&
                beans.getBeanNamesForType(CommandAuthorizer::class.java, true, false).isEmpty()
        }
    }

    companion object {
        const val AUTHORIZER_ENABLED: String = "wow.cosec.authorizer.enabled"
        const val TRUST_INJECTED_APP: String = "wow.cosec.trust-injected-app"

        /** CoSec's inject mode (`ReactiveInjectSecurityContextWebFilter`), off by default. */
        const val COSEC_INJECT_ENABLED: String = "cosec.inject.enabled"

        /** CoSec's own authorization filter, on by default. */
        const val COSEC_AUTHORIZATION_ENABLED: String = "cosec.authorization.enabled"

        /** Adds the CoSec request facts to the edge's options, whoever defines them. */
        @Bean
        @JvmStatic
        fun wowCoSecRequestFacts(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is WowHttpOptions) CoSecRequestFacts.addTo(bean) else bean
        }

        /** The caller options of [properties] (`wow.cosec.*`, `wow.tenant.default`). */
        internal fun callerOptions(properties: WowProperties, environment: Environment, injectedApps: Boolean = true) =
            with(properties.cosec) {
                CoSecCallerOptions(
                    defaultTenant = defaultTenant(properties),
                    crossScopeRoles = crossScope.roles.names(),
                    crossScopePolicies = crossScope.policies.names(),
                    tenantRoles = tenantLevel.roles.names(),
                    tenantPolicies = tenantLevel.policies.names(),
                    serviceRoles = service.roles.names(),
                    servicePolicies = service.policies.names(),
                    trustInjectedApp = injectedApps && trustInjectedApp(properties, environment),
                )
            }

        /**
         * Whether the host trusts the application of a request CoSec's inject mode injected (decision D236 ②,
         * [TRUST_INJECTED_APP], off by default). It applies to that mode alone, CoSec's [COSEC_INJECT_ENABLED] with its
         * own authorization off ([COSEC_AUTHORIZATION_ENABLED]): a service that authorizes its requests itself records
         * the application CoSec authorized them in, never the header a client sent. Turned on in any other mode, it
         * fails the startup rather than be ignored.
         */
        internal fun trustInjectedApp(properties: WowProperties, environment: Environment): Boolean {
            if (!properties.cosec.trustInjectedApp) {
                return false
            }
            val injectMode = environment.getProperty(COSEC_INJECT_ENABLED, Boolean::class.java, false) &&
                !environment.getProperty(COSEC_AUTHORIZATION_ENABLED, Boolean::class.java, true)
            configCheck(injectMode, TRUST_INJECTED_APP, true, "Turn it off, or run behind a CoSec gateway.") {
                "$TRUST_INJECTED_APP applies to CoSec's inject mode alone, behind a CoSec gateway " +
                    "($COSEC_INJECT_ENABLED=true, $COSEC_AUTHORIZATION_ENABLED=false)."
            }
            return true
        }

        /** `wow.tenant.default`, the default tenant when it is blank. */
        private fun defaultTenant(properties: WowProperties): String =
            properties.tenant.default.takeIf { it.isNotBlank() } ?: Caller.DEFAULT_TENANT

        /** The names of a list property, trimmed, blanks dropped (`key=a,b,` or `key[0]=a`). */
        private fun Set<String>.names(): Set<String> =
            map(String::trim).filter(String::isNotEmpty).toCollection(LinkedHashSet())
    }
}

/** A class of wow-cosec, present only when the application has the module. */
internal const val COSEC_MODULE = "me.ahoo.wow.cosec.CoSecCallerResolver"

/** A class of CoSec's WebFlux module, which wow-cosec reads the resolved security context from. */
internal const val COSEC_WEBFLUX = "me.ahoo.cosec.webflux.ServerWebExchanges"
