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

import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.CURSOR_QUERY_EXTRACTOR
import me.ahoo.wow.webflux.route.toServerResponse
import me.ahoo.wow.webflux.route.writeRawRequest
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class CursorQueryHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val queryGateway: QueryGateway<*>,
    private val rewriteRequestFilter: RewriteRequestFilter,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Mono<CursorPage<ObjectNode>>) -> Mono<CursorPage<ObjectNode>> = { it },
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return request.body(CURSOR_QUERY_EXTRACTOR)
            .flatMap {
                val query = rewriteRequestFilter.rewrite(aggregateMetadata, request, it)
                rewriteResult(queryGateway.dynamicCursor(query))
                    .writeRawRequest(request)
            }.toServerResponse(request, exceptionHandler)
    }
}

open class CursorQueryHandlerFunctionFactory(
    handlerKey: String,
    private val queryGateway: (AggregateMetadata<*, *>) -> QueryGateway<*>,
    private val rewriteRequestFilter: RewriteRequestFilter,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Mono<CursorPage<ObjectNode>>) -> Mono<CursorPage<ObjectNode>> = { it },
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate,
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return CursorQueryHandlerFunction(
            aggregateMetadata = aggregateMetadata,
            queryGateway = queryGateway(aggregateMetadata),
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler,
            rewriteResult = rewriteResult,
        )
    }
}
