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

package me.ahoo.wow.viewstore.server

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.spring.boot.DevelopmentMode
import me.ahoo.wow.spring.boot.WowProperties
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.server.system.ConfiguredSystemViews
import me.ahoo.wow.viewstore.server.system.StoredSystemViews
import me.ahoo.wow.viewstore.server.system.SystemViewRoutes
import me.ahoo.wow.viewstore.server.system.ViewStoreProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * The view store's beans: its callers, its write rules, its derived ids, its query policy, the board lookup and the
 * system views.
 *
 * Its callers: with `wow.platform.trust-identity-headers=true` ([TRUST_IDENTITY_HEADERS]), the callers the request
 * headers name ([ViewStoreCallers]), for local runs, demos and tests only, with a warning at startup: any client names
 * any user, tenant and role there. Otherwise CoSec's: the edge's resolver reads the user, its roles and the application
 * CoSec authorized the request in (decisions D232, D236) from the security context CoSec put on the request, which the
 * `cosec` profile has CoSec's inject mode parse from the token the gateway verified and forwarded (decision D296),
 * behind the view store's reserved
 * owners ([ReservedScopeCallers]); without CoSec (`cosec.enabled=false`, the default profile) no request has one, and
 * each is answered 401, never served on a client's word. The header switch is refused outside development
 * ([DevelopmentMode], review S-9).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ViewStoreProperties::class)
class ViewStoreConfiguration {
    @Bean
    @ConditionalOnProperty(TRUST_IDENTITY_HEADERS, havingValue = "true")
    fun viewStoreCallers(development: DevelopmentMode): CallerResolver {
        development.require(TRUST_IDENTITY_HEADERS, true, "takes the callers from headers any client sets")
        logger.warn {
            "$TRUST_IDENTITY_HEADERS=true: the view store takes its callers from the X-User, X-Tenant and X-Role " +
                "headers, which any client sets: for local runs, demos and tests only (${development.reason})."
        }
        return ViewStoreCallers()
    }

    @Bean
    fun viewStoreAuthorizer(): CommandAuthorizer = ViewStoreAuthorizer()

    @Bean
    fun preferencesTarget(): CommandTransformer = PreferencesTarget()

    @Bean
    fun wordingTarget(): CommandTransformer = WordingTarget()

    @Bean
    fun wordingClearTarget(): CommandTransformer = WordingClearTarget()

    @Bean
    fun viewStoreQueryPolicy(): QueryPolicy = ViewStoreQueryPolicy()

    /** Looks the queries up on use: they are built with the node, which this binding is part of. */
    @Bean
    fun sharedBoardReferences(queries: ObjectProvider<QueryApi>): SharedBoardReferences =
        QueriedBoardReferences { queries.getObject() }

    /** `wow.view-store.system-views`, checked at startup. */
    @Bean
    fun configuredSystemViews(properties: ViewStoreProperties): ConfiguredSystemViews =
        ConfiguredSystemViews(properties.systemViews)

    @Bean
    fun storedSystemViews(configured: ConfiguredSystemViews, queries: ObjectProvider<QueryApi>): StoredSystemViews =
        StoredSystemViews(configured) { queries.ifAvailable }

    /** Before Wow's routes, whose catch-all answers every other path under `/view-store` with a 404. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun systemViewRoutes(views: StoredSystemViews, callers: CallerResolver): RouterFunction<ServerResponse> =
        SystemViewRoutes(views, callers).routes()

    companion object {
        /** The switch that takes the callers from the request headers ([ViewStoreCallers]): the host's key. */
        @OptIn(InternalWowApi::class)
        const val TRUST_IDENTITY_HEADERS: String = WowProperties.TRUST_IDENTITY_HEADERS

        /** Puts CoSec's callers behind the view store's reserved owners ([ReservedScopeCallers]). */
        @Bean
        @JvmStatic
        fun reservedScopeCoSecCallers(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is CoSecCallerResolver) ReservedScopeCallers(bean) else bean
        }

        private val logger = KotlinLogging.logger {}
    }
}
