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

package me.ahoo.wow.spring.boot.security

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.cosec.CoSecActions
import me.ahoo.wow.cosec.CoSecCallerOptions
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.cosec.CoSecFilterCodes
import me.ahoo.wow.cosec.CoSecProblemRenderer
import me.ahoo.wow.cosec.CoSecRequestIds
import me.ahoo.wow.cosec.CoSecServiceRouteAuthorizer
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spring.boot.WowAutoConfiguration
import me.ahoo.wow.spring.boot.WowProperties
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.SmartInitializingSingleton
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
import org.springframework.util.ClassUtils

/**
 * Wires CoSec into the host when the application has wow-cosec and CoSec's WebFlux module: the host
 * wires its optional integrations as it wires Kafka, and wow-cosec keeps the Spring-free part, the resolver, the
 * authorizers and the route map. It reads `wow.cosec.*` once, through [WowProperties.cosec], the keys' one reader and
 * one default.
 *
 * - [CoSecCallerResolver] is the host's [CallerResolver] when CoSec is on (`cosec.enabled`, true unless set) and
 *   the application has none: the host's development callers ([DevelopmentCallerResolver]) step aside. Its options are
 *   `wow.tenant.default`, `wow.cosec.cross-scope.roles` and `wow.cosec.tenant-level.roles` (the tenant-level
 *   callers), comma-separated or indexed lists. The caller's app and space are those of the scope CoSec resolved, behind a CoSec gateway
 *   (`wow.cosec.mode=gateway`) the ones the gateway forwarded. No CoSec principal is a service identity: a service
 *   names itself on the internal port.
 * - The app of the request's scope is the standard fact `app_id`. CoSec's device id, `CoSec-Device-Id`, a
 *   client's persistent identifier, is recorded only when the application lists it in `wow.http.request-facts`, as
 *   any header: Wow adds no personal data of its own to a command.
 * - With CoSec on, `CoSec-Request-Id` is the request id of a command without `Wow-Request-Id` ([CoSecRequestIds]),
 *   so a command a CoSec gateway sends again under the same header is answered as a duplicate. With it off, Wow
 *   reads no header of CoSec's.
 * - Where CoSec decides the requests itself, the route map CoSec's pipeline reads is the Manifest's command and
 *   service routes ([CoSecRouteMapAutoConfiguration]).
 * - [CoSecCommandAuthorizer] and [CoSecServiceRouteAuthorizer], CoSec's decision on a command and on a service route
 *   call, are the node's authorizers when `wow.cosec.authorizer.enabled` is true and CoSec decides here: an
 *   auto-configuration of their own after CoSec's pipeline, which defines the checker they ask
 *   ([CoSecAuthorizersAutoConfiguration]).
 *
 * Experimental as wow-cosec is in 10.0: the class and its constant may change within 10.x until a 10.x minor release
 * drops the marker.
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

    /**
     * Writes CoSec's refusals as Wow's problems when `wow.cosec.problems` is `wow` and the application declares no
     * `ProblemRenderer` of its own, which wins: CoSec takes one renderer and refuses to start with two.
     */
    @Bean
    @ConditionalOnProperty(PROBLEMS, havingValue = "wow")
    @ConditionalOnMissingBean(type = [PROBLEM_RENDERER])
    @InternalWowApi
    fun wowCoSecProblemRenderer(): CoSecProblemRenderer = CoSecProblemRenderer()

    /** Says at startup who writes CoSec's refusals when `wow.cosec.problems` is `wow`: Wow, or the application. */
    @Bean
    @ConditionalOnProperty(PROBLEMS, havingValue = "wow")
    @InternalWowApi
    fun wowCoSecProblemsNotice(beans: ListableBeanFactory): SmartInitializingSingleton = SmartInitializingSingleton {
        val type = ClassUtils.resolveClassName(PROBLEM_RENDERER, beans.javaClass.classLoader)
        val renderers = beans.getBeanNamesForType(type, true, false).toList()
        if (renderers.any { beans.getType(it) == CoSecProblemRenderer::class.java }) {
            logger.info { "CoSec's refusals are written as Wow's problems ($PROBLEMS=wow)." }
        } else {
            logger.info {
                "$PROBLEMS is wow, but the application declares its own ProblemRenderer ${renderers.joinToString()}: " +
                    "it writes CoSec's refusals, not Wow."
            }
        }
    }

    /** No [CallerResolver] is defined yet: the application's own one wins over CoSec's. */
    internal class NoCallerResolver : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            context.beanFactory?.getBeanNamesForType(CallerResolver::class.java, true, false).isNullOrEmpty()
    }

    companion object {
        const val AUTHORIZER_ENABLED: String = "wow.cosec.authorizer.enabled"

        /** How the body of CoSec's refusals is written ([CoSecProperties.problems]). */
        const val PROBLEMS: String = "wow.cosec.problems"

        /** CoSec's renderer of a refusal's body, by name: its type is CoSec's experimental API. */
        private const val PROBLEM_RENDERER: String = "me.ahoo.cosec.runtime.http.ProblemRenderer"

        /**
         * Adds `CoSec-Request-Id`, the request id of a command without `Wow-Request-Id` ([CoSecRequestIds]), and the
         * codes CoSec answers before any route, 429 and 503 ([CoSecFilterCodes]), which the OpenAPI document lists on
         * every operation, to the edge's options, whoever defines them, while CoSec is on (`cosec.enabled`, true unless
         * set).
         */
        @Bean
        @JvmStatic
        @ConditionalOnProperty(COSEC_ENABLED, havingValue = "true", matchIfMissing = true)
        @InternalWowApi
        fun wowCoSecRequestIds(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is WowHttpOptions) CoSecFilterCodes.addTo(CoSecRequestIds.addTo(bean)) else bean
        }

        /** The caller options of [properties] (`wow.cosec.*`, `wow.tenant.default`). */
        internal fun callerOptions(properties: WowProperties) =
            with(properties.cosec) {
                CoSecCallerOptions(
                    defaultTenant = defaultTenant(properties),
                    crossScopeRoles = crossScope.roles.names(),
                    tenantRoles = tenantLevel.roles.names(),
                )
            }

        /** `wow.tenant.default`, the default tenant when it is blank. */
        internal fun defaultTenant(properties: WowProperties): String =
            properties.tenant.default.takeIf { it.isNotBlank() } ?: Caller.DEFAULT_TENANT

        /** The names of a list property, trimmed, blanks dropped (`key=a,b,` or `key[0]=a`). */
        private fun Set<String>.names(): Set<String> =
            map(String::trim).filter(String::isNotEmpty).toCollection(LinkedHashSet())
    }
}

/**
 * [CoSecCommandAuthorizer] and [CoSecServiceRouteAuthorizer], CoSec's decision on a command and on a service route call
 * by the same actions, as the caller of its request, among the node's authorizers when `wow.cosec.authorizer.enabled`
 * is true and CoSec decides here (its `AuthorizationChecker`): asked with the application's own authorizers, each of
 * which must allow a command too. On the public port CoSec's pipeline already decides the command routes. A
 * request-less command (a saga's, a timer's, a scheduled job's) gets no objection from them; a caller of a request that
 * is not the principal CoSec authenticated for it is denied. Where the switch is true and they cannot be wired, the
 * node refuses to start ([CoSecAuthorizerSwitch]).
 *
 * After CoSec's pipeline, which defines the checker: their condition looks for it among the beans registered so far,
 * and [CoSecAutoConfiguration], before the host's own configuration, comes before CoSec's (#6849). The node takes them
 * when it is built, once every configuration has registered its beans. Experimental as wow-cosec is.
 */
@ExperimentalWowApi
@AutoConfiguration(after = [WowAutoConfiguration::class], afterName = [COSEC_PIPELINE_AUTO_CONFIGURATION])
@ConditionalOnClass(name = [COSEC_MODULE, COSEC_WEBFLUX])
@EnableConfigurationProperties(WowProperties::class)
class CoSecAuthorizersAutoConfiguration {
    @Bean
    @Conditional(CoSecAuthorizes::class)
    @InternalWowApi
    fun wowCoSecCommandAuthorizer(checker: AuthorizationChecker, properties: WowProperties): CoSecCommandAuthorizer =
        CoSecCommandAuthorizer(checker, CoSecAutoConfiguration.defaultTenant(properties))

    @Bean
    @Conditional(CoSecAuthorizes::class)
    @InternalWowApi
    fun wowCoSecServiceRouteAuthorizer(
        checker: AuthorizationChecker,
        properties: WowProperties,
    ): CoSecServiceRouteAuthorizer = CoSecServiceRouteAuthorizer(
        checker,
        CoSecAutoConfiguration.defaultTenant(properties)
    )

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
    "me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration"

/** A class of wow-cosec, present only when the application has the module. */
internal const val COSEC_MODULE = "me.ahoo.wow.cosec.CoSecCallerResolver"

/** A class of CoSec's WebFlux module, which wow-cosec reads the resolved security context from. */
internal const val COSEC_WEBFLUX = "me.ahoo.cosec.webflux.ServerWebExchanges"

private val logger = KotlinLogging.logger {}
