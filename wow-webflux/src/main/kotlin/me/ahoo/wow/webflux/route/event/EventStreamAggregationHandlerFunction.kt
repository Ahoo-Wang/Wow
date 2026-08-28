/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)]
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

package me.ahoo.wow.webflux.route.event

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.filter.Contexts.writeRawRequest
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.AGGREGATION_QUERY_EXTRACTOR
import me.ahoo.wow.webflux.route.query.RewriteRequestFilter
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class EventStreamAggregationHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val queryGateway: EventStreamQueryGateway,
    private val rewriteRequestFilter: RewriteRequestFilter,
    private val exceptionHandler: RequestExceptionHandler,
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        request.body(AGGREGATION_QUERY_EXTRACTOR)
            .flatMapMany { query ->
                queryGateway.aggregate(
                    aggregateMetadata,
                    rewriteRequestFilter.rewrite(aggregateMetadata, request, query),
                )
            }
            .writeRawRequest(request)
            .toServerResponse(request, exceptionHandler)
}

class EventStreamAggregationHandlerFunctionFactory(
    private val eventStreamQueryGateway: EventStreamQueryGateway,
    private val rewriteRequestFilter: RewriteRequestFilter,
    private val exceptionHandler: RequestExceptionHandler,
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Event.AGGREGATION) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate,
    ): HandlerFunction<ServerResponse> = EventStreamAggregationHandlerFunction(
        aggregateMetadata = aggregateMetadata(metadata),
        queryGateway = eventStreamQueryGateway,
        rewriteRequestFilter = rewriteRequestFilter,
        exceptionHandler = exceptionHandler,
    )
}
