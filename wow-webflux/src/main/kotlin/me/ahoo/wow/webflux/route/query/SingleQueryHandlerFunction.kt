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

import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec
import me.ahoo.wow.query.filter.Contexts.writeRawRequest
import me.ahoo.wow.query.filter.QueryHandler
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class SingleQueryHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val queryHandler: QueryHandler<*>,
    private val rewriteRequestCondition: RewriteRequestCondition,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Mono<DynamicDocument>) -> Mono<DynamicDocument>
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return request.bodyToMono(SingleQuery::class.java)
            .flatMap {
                val query = rewriteRequestCondition.rewrite(aggregateMetadata, request, it)
                val result = queryHandler.dynamicSingle(aggregateMetadata, query)
                rewriteResult(result)
                    .writeRawRequest(request)
                    .throwNotFoundIfEmpty()
            }.toServerResponse(request, exceptionHandler)
    }
}

open class SingleQueryHandlerFunctionFactory<SPEC : AggregateRouteSpec>(
    override val supportedSpec: Class<SPEC>,
    private val queryHandler: QueryHandler<*>,
    private val rewriteRequestCondition: RewriteRequestCondition,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Mono<DynamicDocument>) -> Mono<DynamicDocument> = { it }
) : RouteHandlerFunctionFactory<SPEC> {
    override fun create(spec: SPEC): HandlerFunction<ServerResponse> {
        return SingleQueryHandlerFunction(
            aggregateMetadata = spec.aggregateMetadata,
            queryHandler = queryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler,
            rewriteResult = rewriteResult
        )
    }
}
