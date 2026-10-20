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

package me.ahoo.wow.compensation.server.dashboard

import org.springframework.boot.autoconfigure.web.WebProperties
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.server.ServerWebExchange
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Controller
class DashboardConfiguration(private val webProperties: WebProperties, private val resourceLoader: ResourceLoader) {
    companion object {
        const val HOME_FILE = "index.html"
        const val EXECUTIONS_NAV = "/executions"
        const val EVENTS_NAV = "/events"
        const val BOARDS_NAV = "/boards"
        const val EXECUTION_FAILED_VIEW_PREFIX = "system:execution-failed:"
    }

    private val indexResource by lazy {
        val location = webProperties.resources.staticLocations.first()
        val resourcePath = "${location.removeSuffix("/")}/$HOME_FILE"
        val resource = resourceLoader.getResource(resourcePath)
        check(resource.exists()) { "$HOME_FILE not found in $resourcePath" }
        resource
    }

    /**
     * The console's four places, answered with its entry point so a link or a refresh opens them: the overview,
     * the failed executions, the event stream and the boards.
     */
    @GetMapping(
        *[
            "/",
            EXECUTIONS_NAV,
            EVENTS_NAV,
            BOARDS_NAV,
        ],
    )
    fun home(): ResponseEntity<Resource> {
        return ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.TEXT_HTML)
            .body(indexResource)
    }

    // compat(wow<9.2): alert links sent by 9.1 name the 9.1 console's pages.
    /**
     * The 9.1 console's pages, each now the failed executions' system view of the same name. 9.1 sent links to them
     * (`/to-retry?id=…`) in alerts, which are stored outside and still clicked, so each redirects (302) to that view
     * with the same execution open. The view comes from the matched route, never from the request path, and the
     * location is relative (`executions?…`), so it resolves under whatever context path the console is served at.
     */
    @GetMapping(
        *[
            "/active",
            "/to-retry",
            "/executing",
            "/next-retry",
            "/non-retryable",
            "/succeeded",
            "/unrecoverable",
        ],
    )
    fun legacyNav(exchange: ServerWebExchange, @RequestParam(required = false) id: String?): ResponseEntity<Void> {
        val pattern = exchange.getAttribute<Any>(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
        val view = LEGACY_VIEWS[pattern] ?: return ResponseEntity.notFound().build()
        val location = buildString {
            append(EXECUTIONS_NAV.removePrefix("/"))
            append("?view=")
            append(URLEncoder.encode(EXECUTION_FAILED_VIEW_PREFIX + view, StandardCharsets.UTF_8))
            if (!id.isNullOrBlank()) {
                append("&id=")
                append(URLEncoder.encode(id, StandardCharsets.UTF_8))
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build()
    }
}

/** compat(wow<9.2): each 9.1 console page and the failed-execution system view it became. */
private val LEGACY_VIEWS = listOf(
    "active",
    "to-retry",
    "executing",
    "next-retry",
    "non-retryable",
    "succeeded",
    "unrecoverable",
).associateBy { "/$it" }
