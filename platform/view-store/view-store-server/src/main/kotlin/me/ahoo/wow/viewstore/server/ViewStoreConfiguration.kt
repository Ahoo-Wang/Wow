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
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.server.system.ConfiguredSystemViews
import me.ahoo.wow.viewstore.server.system.StoredSystemViews
import me.ahoo.wow.viewstore.server.system.SystemViewRoutes
import me.ahoo.wow.viewstore.server.system.ViewStoreProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
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
 * any user, tenant and role there. Otherwise CoSec's resolver when `wow-cosec` is on the class path, and without it no
 * caller at all ([REFUSED]): every request is answered 401, never served on a client's word.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ViewStoreProperties::class)
class ViewStoreConfiguration {
    @Bean
    @ConditionalOnProperty(TRUST_IDENTITY_HEADERS, havingValue = "true")
    fun viewStoreCallers(): CallerResolver {
        logger.warn {
            "$TRUST_IDENTITY_HEADERS=true: the view store takes its callers from the X-User, X-Tenant and X-Role " +
                "headers, which any client sets. For local runs, demos and tests only, never a deployment."
        }
        return ViewStoreCallers()
    }

    @Bean
    @ConditionalOnProperty(TRUST_IDENTITY_HEADERS, havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingClass(COSEC_CALLER_RESOLVER)
    fun viewStoreRefusedCallers(): CallerResolver {
        logger.warn {
            "The view store has no identity provider: every request is answered 401. Put wow-cosec on the class " +
                "path, or set $TRUST_IDENTITY_HEADERS=true for a local run."
        }
        return REFUSED
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
    fun sharedBoardReferences(queries: ObjectProvider<HttpQueries>): SharedBoardReferences =
        QueriedBoardReferences { queries.getObject() }

    /** `wow.view-store.system-views`, checked at startup. */
    @Bean
    fun configuredSystemViews(properties: ViewStoreProperties): ConfiguredSystemViews =
        ConfiguredSystemViews(properties.systemViews)

    @Bean
    fun storedSystemViews(configured: ConfiguredSystemViews, queries: ObjectProvider<HttpQueries>): StoredSystemViews =
        StoredSystemViews(configured) { queries.getIfAvailable { HttpQueries.NONE } }

    /** Before Wow's routes, whose catch-all answers every other path under `/view-store` with a 404. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun systemViewRoutes(views: StoredSystemViews, callers: CallerResolver): RouterFunction<ServerResponse> =
        SystemViewRoutes(views, callers).routes()

    companion object {
        /** The switch that takes the callers from the request headers ([ViewStoreCallers]): off by default. */
        const val TRUST_IDENTITY_HEADERS: String = "wow.platform.trust-identity-headers"

        /** CoSec's caller resolver, which serves the view store when `wow-cosec` is on the class path. */
        const val COSEC_CALLER_RESOLVER: String = "me.ahoo.wow.cosec.CoSecCallerResolver"

        /** No caller for any request: each is answered 401. */
        @JvmField
        val REFUSED: CallerResolver = CallerResolver { null }

        private val logger = KotlinLogging.logger {}
    }
}
