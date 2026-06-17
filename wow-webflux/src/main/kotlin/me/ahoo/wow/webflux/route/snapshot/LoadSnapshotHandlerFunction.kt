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

package me.ahoo.wow.webflux.route.snapshot

import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.command.getAggregateId
import me.ahoo.wow.webflux.route.command.getOwnerId
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class LoadSnapshotHandlerFunction(
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    private val snapshotQueryHandler: SnapshotQueryHandler,
    private val exceptionHandler: RequestExceptionHandler
) : HandlerFunction<ServerResponse> {
    private val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        val id = requireNotNull(request.getAggregateId(aggregateRouteMetadata.owner))
        val ownerId = request.getOwnerId()
        val singleQuery = singleQuery {
            condition {
                tenantId(tenantId)
                id(id)
                if (!ownerId.isNullOrBlank()) {
                    ownerId(ownerId)
                }
            }
        }
        return snapshotQueryHandler.dynamicSingle(aggregateMetadata, singleQuery)
            .throwNotFoundIfEmpty()
            .toServerResponse(request, exceptionHandler)
    }
}

class LoadSnapshotHandlerFunctionFactory(
    private val snapshotQueryHandler: SnapshotQueryHandler,
    private val exceptionHandler: RequestExceptionHandler
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Snapshot.LOAD) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateRouteMetadata(metadata))
    }

    private fun create(aggregateRouteMetadata: AggregateRouteMetadata<*>): HandlerFunction<ServerResponse> {
        return LoadSnapshotHandlerFunction(aggregateRouteMetadata, snapshotQueryHandler, exceptionHandler)
    }
}
