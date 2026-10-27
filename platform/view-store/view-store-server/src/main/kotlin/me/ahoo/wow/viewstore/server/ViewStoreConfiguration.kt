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

import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.server.system.ConfiguredSystemViews
import me.ahoo.wow.viewstore.server.system.StoredSystemViews
import me.ahoo.wow.viewstore.server.system.SystemViewRoutes
import me.ahoo.wow.viewstore.server.system.ViewStoreProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
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
 * Its callers: CoSec's, the user, its roles and the application CoSec authorized the request in, which the edge's
 * resolver reads from the security context CoSec put on the request, which the `cosec` profile has CoSec's inject mode
 * parse from the token the gateway verified and forwarded. In development without CoSec, the host's, from the `Wow-*`
 * identity headers any client sets (`DevelopmentCallerResolver`; a user acts as an owner with `Wow-Owner`, in the
 * application `Wow-App` names); a request without a user is the anonymous caller, which reads the reserved owners'
 * views. Either is behind the view store's reserved owners ([ReservedScopeCallers]). A node
 * outside development without CoSec does not start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ViewStoreProperties::class)
class ViewStoreConfiguration {
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
        /**
         * Puts CoSec's callers, or the host's in development ([DevelopmentCallerResolver]), behind the view store's
         * reserved owners ([ReservedScopeCallers]).
         */
        @Bean
        @JvmStatic
        fun reservedScopeCallers(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is CoSecCallerResolver || bean is DevelopmentCallerResolver) {
                    ReservedScopeCallers(bean as CallerResolver)
                } else {
                    bean
                }
        }
    }
}
