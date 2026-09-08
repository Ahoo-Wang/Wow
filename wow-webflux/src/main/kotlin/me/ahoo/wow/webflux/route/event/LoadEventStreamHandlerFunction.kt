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

package me.ahoo.wow.webflux.route.event

import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.BatchComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.withQueryScope
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.command.getOwnerId
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import me.ahoo.wow.webflux.route.query.QueryRequestScope
import me.ahoo.wow.webflux.route.toServerResponse
import me.ahoo.wow.webflux.route.writeRawRequest
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class LoadEventStreamHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val eventStreamQueryGateway: EventStreamQueryGateway,
    private val exceptionHandler: RequestExceptionHandler,
    private val queryRequestScope: QueryRequestScope = DefaultQueryRequestScope,
    private val guard: HttpQueryGuard = HttpQueryGuard(),
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        val ownerId = request.getOwnerId()
        val id = request.pathVariable(MessageRecords.ID)
        val headVersion = request.pathVariable(BatchComponent.PathVariable.HEAD_VERSION).toInt()
        val tailVersion = request.pathVariable(BatchComponent.PathVariable.TAIL_VERSION).toInt()
        val limit = tailVersion - headVersion + 1
        val scope = filter {
            tenantId(tenantId)
            if (!ownerId.isNullOrBlank()) {
                ownerId(ownerId)
            }
            MessageRecords.AGGREGATE_ID eq id
            MessageRecords.VERSION.between(headVersion, tailVersion)
        }.appendFilter(queryRequestScope.resolve(aggregateMetadata, request))
        val listQuery = ListQuery(MatchAllFilter, limit = limit)
        return guard.flux(QueryType.LIST, listQuery, request, scope) { eventStreamQueryGateway.dynamicList(listQuery) }
            .contextWrite { it.withQueryScope(scope) }
            .writeRawRequest(request)
            .toServerResponse(request, exceptionHandler)
    }
}

class LoadEventStreamHandlerFunctionFactory(
    private val eventStreamQueryGateway: (AggregateMetadata<*, *>) -> EventStreamQueryGateway,
    private val exceptionHandler: RequestExceptionHandler,
    private val queryRequestScope: QueryRequestScope = DefaultQueryRequestScope,
    private val guard: HttpQueryGuard = HttpQueryGuard(),
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Event.LOAD) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return LoadEventStreamHandlerFunction(
            aggregateMetadata,
            eventStreamQueryGateway(aggregateMetadata),
            exceptionHandler,
            queryRequestScope,
            guard,
        )
    }
}
