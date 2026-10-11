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

import org.springframework.core.Ordered
import org.springframework.http.HttpMethod
import org.springframework.http.server.PathContainer
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

/**
 * The console is a single-page application: a page's own address ([AnalyticsProperties.consolePaths], such as
 * `/datasets/analytics.orders` or `/boards`), loaded or reloaded in the browser, is answered with the console's
 * `index.html`, which routes it in the browser. Runs before [AnalyticsAccessFilter], so the page is as public as the
 * console's other static files; what it then asks the server for needs the token.
 */
class ConsolePagesFilter(properties: AnalyticsProperties) : WebFilter, Ordered {
    private val pages = properties.consolePaths.map(PathPatternParser.defaultInstance::parse)

    override fun getOrder(): Int = ORDER

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val path = PathContainer.parsePath(request.path.pathWithinApplication().value())
        if (request.method != HttpMethod.GET || pages.none { it.matches(path) }) return chain.filter(exchange)
        return chain.filter(exchange.mutate().request { it.path(INDEX) }.build())
    }

    companion object {
        const val ORDER = AnalyticsAccessFilter.ORDER - 1
        const val INDEX = "/index.html"
    }
}
