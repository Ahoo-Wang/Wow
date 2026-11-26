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

package me.ahoo.wow.http

import kotlinx.coroutines.reactor.mono
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import org.springframework.http.server.PathContainer
import org.springframework.web.reactive.function.server.HandlerFilterFunction
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

/**
 * Refuses a path with matrix parameters (`/shop/cart/c-1;x=1/add-item`, 400 `wow.request.malformed`) before any route
 * handles it: Wow defines none, and the router would drop them. Any `;` in a segment counts, one that names no
 * parameter too (`c-1;`, `c-1;=v`), which the path's own parse lists none for and would route as `c-1` (#6816). Both
 * ports refuse them: the public one among what [PublicRequests] refuses, the internal port's call routes by this
 * filter ([callRoutes]). Its cost per request is one pass over the characters of the path's segments, which the router
 * has parsed already.
 */
internal class MatrixParameters(private val statuses: ErrorStatuses) :
    HandlerFilterFunction<ServerResponse, ServerResponse> {
    override fun filter(request: ServerRequest, next: HandlerFunction<ServerResponse>): Mono<ServerResponse> {
        val refusal = refusal(request) ?: return next.handle(request)
        return mono { refusal.respond() }
    }

    /** The problem of a path segment with matrix parameters, any `;` in its raw value, or null when it has none. */
    fun refusal(request: ServerRequest): Problem? {
        val segment = request.requestPath().pathWithinApplication().elements()
            .firstOrNull { it is PathContainer.PathSegment && ';' in it.value() } ?: return null
        return statuses.problemOf(
            FrameworkCodes.REQUEST_MALFORMED,
            "The path segment '${segment.value()}' has matrix parameters, which no route of Wow takes.",
        )
    }
}
