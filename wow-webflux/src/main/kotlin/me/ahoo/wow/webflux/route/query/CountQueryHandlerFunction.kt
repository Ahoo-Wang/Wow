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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.filter.Contexts.writeRawRequest
import me.ahoo.wow.query.filter.QueryHandler
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.CONDITION_EXTRACTOR
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class CountQueryHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val queryHandler: QueryHandler<*>,
    private val documentKind: QueryDocumentKind?,
    private val rewriteRequestCondition: RewriteRequestCondition,
    private val exceptionHandler: RequestExceptionHandler,
) : HandlerFunction<ServerResponse> {

    constructor(
        aggregateMetadata: AggregateMetadata<*, *>,
        queryHandler: QueryHandler<*>,
        rewriteRequestCondition: RewriteRequestCondition,
        exceptionHandler: RequestExceptionHandler,
    ) : this(
        aggregateMetadata,
        queryHandler,
        queryHandler.queryDocumentKind(),
        rewriteRequestCondition,
        exceptionHandler,
    )

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return request.body(CONDITION_EXTRACTOR)
            .flatMap {
                val query = rewriteRequestCondition.rewrite(aggregateMetadata, request, it)
                queryHandler.count(aggregateMetadata, query)
                    .writeRawRequest(request)
                    .writeQueryWebTransport(
                        request,
                        aggregateMetadata,
                        documentKind,
                        QueryType.COUNT,
                    )
            }.toServerResponse(request, exceptionHandler)
    }
}

open class CountQueryHandlerFunctionFactory(
    handlerKey: String,
    private val queryHandler: QueryHandler<*>,
    private val documentKind: QueryDocumentKind?,
    private val rewriteRequestCondition: RewriteRequestCondition,
    private val exceptionHandler: RequestExceptionHandler
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    constructor(
        handlerKey: String,
        queryHandler: QueryHandler<*>,
        rewriteRequestCondition: RewriteRequestCondition,
        exceptionHandler: RequestExceptionHandler,
    ) : this(
        handlerKey,
        queryHandler,
        queryHandler.queryDocumentKind(),
        rewriteRequestCondition,
        exceptionHandler,
    )

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return CountQueryHandlerFunction(
            aggregateMetadata = aggregateMetadata,
            queryHandler = queryHandler,
            documentKind = documentKind,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        )
    }
}
