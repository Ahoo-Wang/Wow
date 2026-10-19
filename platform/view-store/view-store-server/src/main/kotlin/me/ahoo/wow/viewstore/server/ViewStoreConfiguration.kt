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
import me.ahoo.cosec.context.DefaultSecurityContextParser
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.wow.cosec.CoSecCallerResolver
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
 * `cosec` profile has it verify from the request's token ([securityContextParser]), behind the view store's reserved
 * owners ([ReservedScopeCallers]); without CoSec (`cosec.enabled=false`, the default profile) no request has one, and
 * each is answered 401, never served on a client's word.
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

    /**
     * CoSec's inject mode with each token verified (the `cosec` profile): CoSec's own parser of that mode trusts any
     * well-formed token, as a gateway in front of the service would have verified it, so here the service verifies
     * the token's signature itself (`cosec.jwt.secret`), on the internal port too, whose service principals the edge
     * parses with this parser (decision D226).
     */
    @Bean
    @ConditionalOnProperty(name = [COSEC_ENABLED, COSEC_INJECT_ENABLED], havingValue = "true")
    fun securityContextParser(tokenVerifier: TokenVerifier): SecurityContextParser =
        DefaultSecurityContextParser(tokenVerifier, false)

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

        /** Whether CoSec runs at all; the default profile turns it off, the `cosec` profile on. */
        const val COSEC_ENABLED: String = "cosec.enabled"

        /** CoSec's inject mode: the security context from the request's token, no authorization of CoSec's own. */
        const val COSEC_INJECT_ENABLED: String = "cosec.inject.enabled"

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
