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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

/**
 * Answers `GET …/schema` with the model's capability descriptor for the HTTP entry: what this model can be queried with
 * over HTTP, limits included, as the gateway describes it from its schema and entry policy. The descriptor's content-hash version is the ETag, so an unchanged descriptor answers a
 * matching `If-None-Match` with 304.
 */
internal class QuerySchemaHandlerFunction(
    private val queryGateway: QueryGateway<*>,
    private val exceptionHandler: RequestExceptionHandler,
    guard: HttpQueryGuard,
) : HandlerFunction<ServerResponse> {
    // The descriptor's limits and the rows the guard lets through come from the budget admission enforces.
    private val defaultListSize = guard.of(queryGateway).effectiveDefaultListSize

    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        Mono.defer { queryGateway.describe(QueryEntry.HTTP, defaultListSize) }
            .flatMap { descriptor ->
                val etag = "\"${descriptor.version}\""
                val matches = request.headers().header(HttpHeaders.IF_NONE_MATCH)
                    .flatMap { it.split(',') }.map { it.trim() }.any { it == etag || it == "*" }
                if (matches) {
                    ServerResponse.status(HttpStatus.NOT_MODIFIED).eTag(etag).build()
                } else {
                    ServerResponse.ok().eTag(etag).contentType(MediaType.APPLICATION_JSON).bodyValue(descriptor)
                }
            }
            .onErrorResume { exceptionHandler.handle(request, it) }
}
