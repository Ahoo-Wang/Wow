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

import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.route.RouteMap
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.cosec.CoSecActions
import me.ahoo.wow.cosec.CoSecCallerOptions
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.cosec.CoSecRequestFacts
import me.ahoo.wow.cosec.CoSecRequestIds
import me.ahoo.wow.cosec.CoSecServiceRouteAuthorizer
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.manifest.Manifest
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Wires CoSec into the host when the application has wow-cosec and CoSec's WebFlux module: the host
 * wires its optional integrations as it wires Kafka, and wow-cosec keeps the Spring-free part, the resolver, the
 * authorizers and the route map. It reads `wow.cosec.*` once, through [WowProperties.cosec], the keys' one reader and
 * one default.
 *
 * - [CoSecCallerResolver] is the host's [CallerResolver] when CoSec is on (`cosec.enabled`, true unless set) and
 *   the application has none: the host's development callers ([DevelopmentCallerResolver]) step aside. Its options are
 *   `wow.tenant.default`, `wow.cosec.cross-scope.roles` / `wow.cosec.cross-scope.policies` and
 *   `wow.cosec.tenant-level.roles` / `wow.cosec.tenant-level.policies` (the tenant-level callers), comma-separated or
 *   indexed lists. The caller's app and space are those of the scope CoSec resolved, behind a CoSec gateway
 *   (`wow.cosec.mode=gateway`) the ones the gateway forwarded. No CoSec principal is a service identity: a service
 *   names itself on the internal port.
 * - The CoSec request fact `CoSec-Device-Id` ([CoSecRequestFacts]) joins the edge's request-fact list
 *   (`wow.http.request-facts`); the app of the request's scope is the standard fact `app_id`.
 * - `CoSec-Request-Id` is the request id of a command without `Wow-Request-Id` ([CoSecRequestIds]), so
 *   a command a CoSec gateway sends again under the same header is answered as a duplicate.
 * - Where CoSec decides the requests itself, the route map CoSec's pipeline reads is the Manifest's command and
 *   service routes ([CoSecRouteMapAutoConfiguration]).
 * - [CoSecCommandAuthorizer] and [CoSecServiceRouteAuthorizer], CoSec's decision on a command and on a service route
 *   call by the same actions, as the caller of its request, are among the node's authorizers when
 *   `wow.cosec.authorizer.enabled` is true and CoSec decides here (its `AuthorizationChecker`): asked with the
 *   application's own authorizers, each of which must allow a command too. On the public port CoSec's pipeline already
 *   decides the command routes. A request-less command (a saga's, a timer's, a scheduled job's) gets no objection
 *   from them.
 *
 * Experimental as wow-cosec is, which follows CoSec 8's pre-release: the class and its constant may change within 10.x.
 */
@ExperimentalWowApi
@AutoConfiguration(before = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [COSEC_MODULE, COSEC_WEBFLUX])
@EnableConfigurationProperties(WowProperties::class)
class CoSecAutoConfiguration {
    @Bean
    @Conditional(NoCallerResolver::class)
    @ConditionalOnProperty(COSEC_ENABLED, havingValue = "true", matchIfMissing = true)
    @InternalWowApi
    fun wowCoSecCallerResolver(properties: WowProperties): CoSecCallerResolver =
        CoSecCallerResolver(callerOptions(properties))

    @Bean
    @Conditional(CoSecAuthorizes::class)
    @InternalWowApi
    fun wowCoSecCommandAuthorizer(checker: AuthorizationChecker, properties: WowProperties): CoSecCommandAuthorizer =
        CoSecCommandAuthorizer(checker, defaultTenant(properties))

    @Bean
    @Conditional(CoSecAuthorizes::class)
    @InternalWowApi
    fun wowCoSecServiceRouteAuthorizer(
        checker: AuthorizationChecker,
        properties: WowProperties,
    ): CoSecServiceRouteAuthorizer = CoSecServiceRouteAuthorizer(checker, defaultTenant(properties))

    /** No [CallerResolver] is defined yet: the application's own one wins over CoSec's. */
    internal class NoCallerResolver : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            context.beanFactory?.getBeanNamesForType(CallerResolver::class.java, true, false).isNullOrEmpty()
    }

    /**
     * `wow.cosec.authorizer.enabled` is true and CoSec decides here (its `AuthorizationChecker`, which a service
     * behind a CoSec gateway has none of), so [wowCoSecCommandAuthorizer] is one of the node's authorizers. A
     * condition runs before the properties bean is bound: it binds them itself ([WowProperties.of]). An authorizer of
     * the application's own roles that decides in CoSec's place takes `ObjectProvider<CoSecCommandAuthorizer>` and
     * steps aside when there is one: a condition of an application's configuration is evaluated before this
     * auto-configuration registers it.
     */
    internal class CoSecAuthorizes : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return WowProperties.of(context.environment).cosec.authorizer.enabled &&
                beans.getBeanNamesForType(AuthorizationChecker::class.java, true, false).isNotEmpty()
        }
    }

    companion object {
        const val AUTHORIZER_ENABLED: String = "wow.cosec.authorizer.enabled"

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
        internal fun callerOptions(properties: WowProperties) =
            with(properties.cosec) {
                CoSecCallerOptions(
                    defaultTenant = defaultTenant(properties),
                    crossScopeRoles = crossScope.roles.names(),
                    crossScopePolicies = crossScope.policies.names(),
                    tenantRoles = tenantLevel.roles.names(),
                    tenantPolicies = tenantLevel.policies.names(),
                )
            }

        /** `wow.tenant.default`, the default tenant when it is blank. */
        private fun defaultTenant(properties: WowProperties): String =
            properties.tenant.default.takeIf { it.isNotBlank() } ?: Caller.DEFAULT_TENANT

        /** The names of a list property, trimmed, blanks dropped (`key=a,b,` or `key[0]=a`). */
        private fun Set<String>.names(): Set<String> =
            map(String::trim).filter(String::isNotEmpty).toCollection(LinkedHashSet())
    }
}

/**
 * The Manifest's command and service routes as the route map CoSec's pipeline reads ([CoSecActions.routes]), where
 * CoSec decides the requests itself (`cosec.pipeline.mode` `enforce`, its default): a command is `<context>:<command>`
 * on `<aggregate>/<id>`, a service route `<context>:<route>` on `route/<route>`, the names [CoSecCommandAuthorizer]
 * and [CoSecServiceRouteAuthorizer] decide them by. It comes after the host's configuration, which defines the
 * Manifest, and before CoSec's pipeline, whose route map file it takes the place of; an application that maps routes
 * of its own declares its `RouteMap`. Experimental as wow-cosec is.
 */
@ExperimentalWowApi
@AutoConfiguration(after = [WowAutoConfiguration::class], beforeName = [COSEC_PIPELINE_AUTO_CONFIGURATION])
@ConditionalOnClass(name = [COSEC_MODULE, COSEC_WEBFLUX])
@ConditionalOnProperty(COSEC_ENABLED, havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(COSEC_PIPELINE_MODE, havingValue = "enforce", matchIfMissing = true)
class CoSecRouteMapAutoConfiguration {
    @Bean
    @ConditionalOnBean(Manifest::class)
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowCoSecRouteMap(manifest: Manifest): RouteMap = RouteMap.of(
        CoSecActions.routes(manifest).routes.map { RouteMap.Located(it, "the Manifest's routes") }
    )
}

/** CoSec's switch: its resolver is the host's only while CoSec is on. */
internal const val COSEC_ENABLED = "cosec.enabled"

/** How CoSec's pipeline runs: `enforce`, deciding the requests itself (its default), or `gateway`. */
internal const val COSEC_PIPELINE_MODE = "cosec.pipeline.mode"

/** CoSec's pipeline configuration, whose route map the Manifest's routes take the place of. */
internal const val COSEC_PIPELINE_AUTO_CONFIGURATION =
    "me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration"

/** A class of wow-cosec, present only when the application has the module. */
internal const val COSEC_MODULE = "me.ahoo.wow.cosec.CoSecCallerResolver"

/** A class of CoSec's WebFlux module, which wow-cosec reads the resolved security context from. */
internal const val COSEC_WEBFLUX = "me.ahoo.cosec.webflux.ServerWebExchanges"
