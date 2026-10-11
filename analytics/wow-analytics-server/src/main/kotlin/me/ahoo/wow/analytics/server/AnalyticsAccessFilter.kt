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

package me.ahoo.wow.analytics.server

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.webflux.ServerWebExchanges
import me.ahoo.wow.viewstore.ViewStoreService
import org.springframework.core.Ordered
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

/**
 * Who may reach what (analytics design §4.2), after CoSec has put the request's principal in the exchange: the health
 * probes and [AnalyticsProperties.publicPaths] are open; every other path needs an authenticated principal (401
 * otherwise); a change to the dataset registry needs [AnalyticsProperties.adminRole] too, and a view store path the
 * view store's gateway rules ([ViewStoreAccess]), each 403 otherwise. A view store request carries the service's own
 * application ([AnalyticsProperties.appId]). What an authenticated principal reads is narrowed further by the query
 * gateway: its tenant and ABAC tags.
 */
class AnalyticsAccessFilter(private val properties: AnalyticsProperties) : WebFilter, Ordered {
    private val publicPaths = (properties.publicPaths + HEALTH_PATHS).map(PathPatternParser.defaultInstance::parse)
    private val registry = PathPatternParser.defaultInstance.parse(REGISTRY_PATHS)
    private val viewStore = PathPatternParser.defaultInstance.parse(VIEW_STORE_PATHS)
    private val viewStoreAccess = ViewStoreAccess(properties.sharedViewRole, properties.systemViewRole)

    override fun getOrder(): Int = ORDER

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val path = PathContainer.parsePath(request.path.pathWithinApplication().value())
        if (request.method == HttpMethod.OPTIONS || publicPaths.any { it.matches(path) }) return chain.filter(exchange)
        val principal = with(ServerWebExchanges) { exchange.getSecurityContext() }?.principal
        if (principal == null || !principal.authenticated) return exchange.reject(HttpStatus.UNAUTHORIZED)
        if (registry.matches(path) && request.method != HttpMethod.GET && !principal.isAdmin()) {
            return exchange.reject(HttpStatus.FORBIDDEN)
        }
        if (viewStore.matches(path)) {
            viewStoreAccess.decide(request.method, path, principal)?.let { return exchange.reject(it) }
            return chain.filter(exchange.withApplication())
        }
        return chain.filter(exchange)
    }

    private fun CoSecPrincipal.isAdmin(): Boolean = properties.adminRole in roles

    private fun ServerWebExchange.withApplication(): ServerWebExchange = mutate()
        .request { request -> request.headers { it.set(ViewStoreService.APP_ID_HEADER, properties.appId) } }
        .build()

    private fun ServerWebExchange.reject(status: HttpStatus): Mono<Void> {
        response.statusCode = status
        return response.setComplete()
    }

    companion object {
        /** After CoSec's security context filter (`Ordered.HIGHEST_PRECEDENCE + 10`), before every route. */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 20
        private val HEALTH_PATHS = listOf("/actuator/health", "/actuator/health/**")
        private const val REGISTRY_PATHS = "/dataset-registry/**"
        private const val VIEW_STORE_PATHS = "/view-store/**"
    }
}
