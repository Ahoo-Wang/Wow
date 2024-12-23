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

import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.query.filter.Contexts.writeRawRequest
import me.ahoo.wow.query.filter.QueryHandler
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.exception.toServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class PagedQueryHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val queryHandler: QueryHandler<*, *>,
    private val exceptionHandler: RequestExceptionHandler
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantId(aggregateMetadata)
        return request.bodyToMono(PagedQuery::class.java)
            .flatMap {
                val pagedQuery = if (tenantId == null) it else it.appendTenantId(tenantId)
                queryHandler.dynamicPaged(aggregateMetadata, pagedQuery)
                    .writeRawRequest(request)
            }.toServerResponse(request, exceptionHandler)
    }
}

open class PagedQueryHandlerFunctionFactory<SPEC : AggregateRouteSpec>(
    override val supportedSpec: Class<SPEC>,
    private val queryHandler: QueryHandler<*, *>,
    private val exceptionHandler: RequestExceptionHandler
) : RouteHandlerFunctionFactory<SPEC> {
    override fun create(spec: SPEC): HandlerFunction<ServerResponse> {
        return PagedQueryHandlerFunction(
            aggregateMetadata = spec.aggregateMetadata,
            queryHandler = queryHandler,
            exceptionHandler = exceptionHandler
        )
    }
}
