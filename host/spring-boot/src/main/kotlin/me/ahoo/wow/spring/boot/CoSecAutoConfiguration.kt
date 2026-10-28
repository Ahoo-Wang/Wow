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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.cosec.CoSecCallerOptions
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.cosec.CoSecServiceRouteAuthorizer
import me.ahoo.wow.cosec.CoSecRequestFacts
import me.ahoo.wow.cosec.CoSecRequestIds
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spi.config.configCheck
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

/**
 * Wires CoSec into the host when the application has wow-cosec and CoSec's WebFlux module: the host
 * wires its optional integrations as it wires Kafka, and wow-cosec keeps the Spring-free part, the resolvers and the
 * authorizer. It reads `wow.cosec.*` once, through [WowProperties.cosec], the keys' one reader and one default.
 *
 * - [CoSecCallerResolver] is the host's [CallerResolver] when CoSec is on (`cosec.enabled`, true unless set) and
 *   the application has none: the host's development callers ([DevelopmentCallerResolver]) step aside. Its options are `wow.tenant.default`, `wow.cosec.cross-scope.roles` /
 *   `wow.cosec.cross-scope.policies` and `wow.cosec.tenant-level.roles` / `wow.cosec.tenant-level.policies` (the
 *   tenant-level callers), comma-separated or indexed lists, and `wow.cosec.trust-injected-app` (false),
 *   whether a service in CoSec's inject mode records the application of the requests its gateway forwards
 * ([trustInjectedApp]). No CoSec principal is a service identity: a service names itself on the internal
 *   port.
 * - The CoSec request fact `CoSec-Device-Id` ([CoSecRequestFacts]) joins the edge's request-fact list
 *   (`wow.http.request-facts`); the application CoSec authorized a request in is the standard fact `app_id`.
 * - `CoSec-Request-Id` is the request id of a command without `Wow-Request-Id` ([CoSecRequestIds]), so
 *   a command a CoSec gateway sends again under the same header is answered as a duplicate.
 * - [CoSecCommandAuthorizer], CoSec's decision on a command over the application's policies and the host's default
 *   tenant, is one of the node's [CommandAuthorizer]s when `wow.cosec.authorizer.enabled` is true and the application
 *   has CoSec's `Authorization`: asked with the application's own authorizers, each of which must allow a command too.
 *   On the public port CoSec's WebFilter already authorizes the command routes by path and method.
 * - [CoSecServiceRouteAuthorizer], CoSec's decision on a call of a service route by the route's method and path, is
 *   one of the node's [me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer]s on the same condition.
 */
@AutoConfiguration(before = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [COSEC_MODULE, COSEC_WEBFLUX])
@EnableConfigurationProperties(WowProperties::class)
class CoSecAutoConfiguration {
    @Bean
    @Conditional(NoCallerResolver::class)
    @ConditionalOnProperty(COSEC_ENABLED, havingValue = "true", matchIfMissing = true)
    @InternalWowApi
    fun wowCoSecCallerResolver(properties: WowProperties, environment: Environment): CoSecCallerResolver =
        CoSecCallerResolver(callerOptions(properties, environment))

    @Bean
    @Conditional(CoSecAuthorizes::class)
    @InternalWowApi
    fun wowCoSecCommandAuthorizer(
        authorization: CoSecAuthorization,
        manifest: Manifest,
        properties: WowProperties,
    ): CoSecCommandAuthorizer = CoSecCommandAuthorizer(authorization, manifest, defaultTenant(properties))

    @Bean
    @Conditional(CoSecAuthorizes::class)
    @InternalWowApi
    fun wowCoSecServiceRouteAuthorizer(
        authorization: CoSecAuthorization,
        properties: WowProperties,
    ): CoSecServiceRouteAuthorizer = CoSecServiceRouteAuthorizer(authorization, defaultTenant(properties))

    /** No [CallerResolver] is defined yet: the application's own one wins over CoSec's. */
    internal class NoCallerResolver : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            context.beanFactory?.getBeanNamesForType(CallerResolver::class.java, true, false).isNullOrEmpty()
    }

    /**
     * `wow.cosec.authorizer.enabled` is true and the application has CoSec's `Authorization`, so
     * [wowCoSecCommandAuthorizer] is one of the node's authorizers. A condition runs before the properties bean is
     * bound: it binds them itself ([WowProperties.of]). An authorizer of the application's own roles that decides in
     * CoSec's place takes `ObjectProvider<CoSecCommandAuthorizer>` and steps aside when there is one: a condition of an
     * application's configuration is evaluated before this auto-configuration registers it.
     */
    internal class CoSecAuthorizes : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return WowProperties.of(context.environment).cosec.authorizer.enabled &&
                beans.getBeanNamesForType(CoSecAuthorization::class.java, true, false).isNotEmpty()
        }
    }

    companion object {
        const val AUTHORIZER_ENABLED: String = "wow.cosec.authorizer.enabled"
        const val TRUST_INJECTED_APP: String = "wow.cosec.trust-injected-app"

        /** CoSec's inject mode (`ReactiveInjectSecurityContextWebFilter`), off by default. */
        const val COSEC_INJECT_ENABLED: String = "cosec.inject.enabled"

        /** CoSec's own authorization filter, on by default. */
        const val COSEC_AUTHORIZATION_ENABLED: String = "cosec.authorization.enabled"

        /**
         * Adds the CoSec request facts and `CoSec-Request-Id`, the request id of a command without `Wow-Request-Id`
         * ([CoSecRequestIds]), to the edge's options, whoever defines them.
         */
        @Bean
        @JvmStatic
        fun wowCoSecRequestFacts(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is WowHttpOptions) CoSecRequestIds.addTo(CoSecRequestFacts.addTo(bean)) else bean
        }

        /** The caller options of [properties] (`wow.cosec.*`, `wow.tenant.default`). */
        internal fun callerOptions(properties: WowProperties, environment: Environment) =
            with(properties.cosec) {
                CoSecCallerOptions(
                    defaultTenant = defaultTenant(properties),
                    crossScopeRoles = crossScope.roles.names(),
                    crossScopePolicies = crossScope.policies.names(),
                    tenantRoles = tenantLevel.roles.names(),
                    tenantPolicies = tenantLevel.policies.names(),
                    trustInjectedApp = trustInjectedApp(properties, environment),
                )
            }

        /**
         * Whether the host trusts the application of a request CoSec's inject mode injected
         * ([TRUST_INJECTED_APP], off by default). It applies to that mode alone, CoSec's [COSEC_INJECT_ENABLED] with its
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

/** CoSec's switch: its resolver is the host's only while CoSec is on. */
internal const val COSEC_ENABLED = "cosec.enabled"

/** A class of wow-cosec, present only when the application has the module. */
internal const val COSEC_MODULE = "me.ahoo.wow.cosec.CoSecCallerResolver"

/** A class of CoSec's WebFlux module, which wow-cosec reads the resolved security context from. */
internal const val COSEC_WEBFLUX = "me.ahoo.cosec.webflux.ServerWebExchanges"
