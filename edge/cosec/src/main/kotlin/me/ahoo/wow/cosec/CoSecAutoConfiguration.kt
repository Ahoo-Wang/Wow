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

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

/**
 * Wires CoSec into the Wow host when CoSec is on the classpath. The host lists it in
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; its ordering (before the host's
 * `WowAutoConfiguration`) and its classpath condition are in `META-INF/spring-autoconfigure-metadata.properties`, so
 * that this module imports no Spring Boot type (only the host does).
 *
 * - [CoSecCallerResolver] is the host's [CallerResolver] unless the application has one: the host's anonymous
 *   default steps aside. Its options are `wow.tenant.default` and `wow.cosec.cross-scope.roles` /
 *   `wow.cosec.cross-scope.policies` (comma-separated or indexed lists).
 * - The CoSec request facts ([CoSecRequestFacts]) join the edge's request-fact list (`wow.http.request-facts`).
 * - [CoSecCommandAuthorizer] is the host's [CommandAuthorizer] when `wow.cosec.authorizer.enabled` is true, the
 *   application has CoSec's `Authorization` and no authorizer of its own. On the public port CoSec's WebFilter
 *   already authorizes the command routes by path and method.
 */
@Configuration(proxyBeanMethods = false)
class CoSecAutoConfiguration {
    @Bean
    @Conditional(NoCallerResolver::class)
    fun wowCoSecCallerResolver(environment: Environment): CoSecCallerResolver =
        CoSecCallerResolver(callerOptions(environment))

    @Bean
    @Conditional(AuthorizerEnabled::class)
    fun wowCoSecCommandAuthorizer(authorization: CoSecAuthorization, environment: Environment): CoSecCommandAuthorizer =
        CoSecCommandAuthorizer(authorization, defaultTenant(environment))

    /** No [CallerResolver] is defined yet: the application's own one wins over CoSec's. */
    internal class NoCallerResolver : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            context.beanFactory?.getBeanNamesForType(CallerResolver::class.java, true, false).isNullOrEmpty()
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
