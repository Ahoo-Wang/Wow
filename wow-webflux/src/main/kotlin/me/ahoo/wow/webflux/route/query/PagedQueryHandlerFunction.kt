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

import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.PAGED_QUERY_EXTRACTOR
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class PagedQueryHandlerFunction(
    aggregateMetadata: AggregateMetadata<*, *>,
    private val queryGateway: QueryGateway<*>,
    queryRequestScope: QueryRequestScope,
    exceptionHandler: RequestExceptionHandler,
    guard: HttpQueryGuard = HttpQueryGuard(),
    private val rewriteResult: (Mono<PagedList<ObjectNode>>) -> Mono<PagedList<ObjectNode>>
) : HandlerFunction<ServerResponse> {
    private val support = QueryHandlerSupport(aggregateMetadata, queryRequestScope, exceptionHandler, guard)

    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        support.mono(request, PAGED_QUERY_EXTRACTOR, HttpQueryGuard::check) {
            rewriteResult(queryGateway.dynamicPaged(it))
        }
}

open class PagedQueryHandlerFunctionFactory(
    handlerKey: String,
    queryGateway: (AggregateMetadata<*, *>) -> QueryGateway<*>,
    queryRequestScope: QueryRequestScope,
    exceptionHandler: RequestExceptionHandler,
    guard: HttpQueryGuard = HttpQueryGuard(),
    rewriteResult: (Mono<PagedList<ObjectNode>>) -> Mono<PagedList<ObjectNode>> = { it }
) : QueryHandlerFunctionFactorySupport<QueryGateway<*>>(
    handlerKey = handlerKey,
    queryGateway = queryGateway,
    handlerFunction = { aggregateMetadata, gateway ->
        PagedQueryHandlerFunction(
            aggregateMetadata,
            gateway,
            queryRequestScope,
            exceptionHandler,
            guard,
            rewriteResult,
        )
    },
)
