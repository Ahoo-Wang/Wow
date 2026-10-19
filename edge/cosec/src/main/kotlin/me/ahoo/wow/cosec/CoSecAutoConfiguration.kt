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

import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallAuthorizer
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.InternalCallerResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.web.server.ServerWebExchange
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

/**
 * Wires CoSec into the Wow host when CoSec is on the classpath. The host lists it in
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; its ordering (before the host's
 * `WowAutoConfiguration`) and its classpath condition are in `META-INF/spring-autoconfigure-metadata.properties`, so
 * that this module imports no Spring Boot type (only the host does).
 *
 * - [CoSecCallerResolver] is the host's [CallerResolver] unless the application has one: the host's anonymous
 *   default steps aside. Its options are `wow.tenant.default`, `wow.cosec.cross-scope.roles` /
 *   `wow.cosec.cross-scope.policies` and `wow.cosec.tenant-level.roles` / `wow.cosec.tenant-level.policies` (the
 *   tenant-level callers, decision D189), `wow.cosec.service.roles` / `wow.cosec.service.policies` (the service
 *   identities, decision D193), comma-separated or indexed lists.
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
@Configuration(proxyBeanMethods = false)
class CoSecAutoConfiguration {
    @Bean
    @Conditional(NoCallerResolver::class)
    fun wowCoSecCallerResolver(environment: Environment): CoSecCallerResolver =
        CoSecCallerResolver(callerOptions(environment))

    /**
     * The callers of the internal port's call routes (decision D193): CoSec's service principals, parsed from their
     * tokens by CoSec's own parsers, since the internal port runs no CoSec filter.
     */
    @Bean
    @Conditional(InternalCallersAvailable::class)
    fun wowCoSecInternalCallerResolver(
        requests: RequestParser<ServerWebExchange>,
        contexts: SecurityContextParser,
        environment: Environment,
    ): CoSecInternalCallerResolver =
        CoSecInternalCallerResolver(requests, contexts, CoSecCallerResolver(callerOptions(environment)))

    /** The authorizer of the services' calls (decision D226): CoSec's policies, as the service principal. */
    @Bean
    @Conditional(CallAuthorizerAvailable::class)
    fun wowCoSecCallAuthorizer(authorization: CoSecAuthorization, environment: Environment): CallAuthorizer {
        val authorizer = CoSecCommandAuthorizer(authorization, defaultTenant(environment))
        return CallAuthorizer(authorizer::authorize)
    }

    @Bean
    @Conditional(AuthorizerEnabled::class)
    fun wowCoSecCommandAuthorizer(authorization: CoSecAuthorization, environment: Environment): CoSecCommandAuthorizer =
        CoSecCommandAuthorizer(authorization, defaultTenant(environment))

    /** No [CallerResolver] is defined yet: the application's own one wins over CoSec's. */
    internal class NoCallerResolver : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            context.beanFactory?.getBeanNamesForType(CallerResolver::class.java, true, false).isNullOrEmpty()
    }

    /**
     * The application has CoSec's request and security-context parsers, and no
     * [me.ahoo.wow.http.caller.InternalCallerResolver] of its own.
     */
    internal class InternalCallersAvailable : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return beans.getBeanNamesForType(RequestParser::class.java, true, false).isNotEmpty() &&
                beans.getBeanNamesForType(SecurityContextParser::class.java, true, false).isNotEmpty() &&
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

    /** `wow.cosec.authorizer.enabled` is true, the application has CoSec's `Authorization` and no authorizer. */
    internal class AuthorizerEnabled : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return context.environment.getProperty(AUTHORIZER_ENABLED, Boolean::class.java, false) &&
                beans.getBeanNamesForType(CoSecAuthorization::class.java, true, false).isNotEmpty() &&
                beans.getBeanNamesForType(CommandAuthorizer::class.java, true, false).isEmpty()
        }
    }

    companion object {
        const val PREFIX: String = "wow.cosec"
        const val CROSS_SCOPE_ROLES: String = "$PREFIX.cross-scope.roles"
        const val CROSS_SCOPE_POLICIES: String = "$PREFIX.cross-scope.policies"
        const val TENANT_ROLES: String = "$PREFIX.tenant-level.roles"
        const val TENANT_POLICIES: String = "$PREFIX.tenant-level.policies"
        const val SERVICE_ROLES: String = "$PREFIX.service.roles"
        const val SERVICE_POLICIES: String = "$PREFIX.service.policies"
        const val AUTHORIZER_ENABLED: String = "$PREFIX.authorizer.enabled"
        const val DEFAULT_TENANT: String = "wow.tenant.default"

        /** Adds the CoSec request facts to the edge's options, whoever defines them. */
        @Bean
        @JvmStatic
        fun wowCoSecRequestFacts(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is WowHttpOptions) CoSecRequestFacts.addTo(bean) else bean
        }

        internal fun callerOptions(environment: Environment) = CoSecCallerOptions(
            defaultTenant = defaultTenant(environment),
            crossScopeRoles = environment.list(CROSS_SCOPE_ROLES),
            crossScopePolicies = environment.list(CROSS_SCOPE_POLICIES),
            tenantRoles = environment.list(TENANT_ROLES),
            tenantPolicies = environment.list(TENANT_POLICIES),
            serviceRoles = environment.list(SERVICE_ROLES),
            servicePolicies = environment.list(SERVICE_POLICIES),
        )

        private fun defaultTenant(environment: Environment): String =
            environment.getProperty(DEFAULT_TENANT)?.takeIf { it.isNotBlank() } ?: Caller.DEFAULT_TENANT

        /** A list property, as `key=a,b` or as `key[0]=a`, `key[1]=b` (a YAML list). */
        internal fun Environment.list(key: String): Set<String> {
            val indexed = generateSequence(0) { it + 1 }.map { getProperty("$key[$it]") }.takeWhile { it != null }
            return (indexed.filterNotNull() + getProperty(key)?.split(',').orEmpty())
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toCollection(LinkedHashSet())
        }
    }
}
