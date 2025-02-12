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
import me.ahoo.wow.openapi.route.AggregateRouteMetadata
import me.ahoo.wow.openapi.snapshot.LoadSnapshotRouteSpec
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.exception.toServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.getAggregateId
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
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
        val singleQuery = singleQuery {
            condition {
                tenantId(tenantId)
                id(id)
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
) : RouteHandlerFunctionFactory<LoadSnapshotRouteSpec> {
    override val supportedSpec: Class<LoadSnapshotRouteSpec>
        get() = LoadSnapshotRouteSpec::class.java

    override fun create(spec: LoadSnapshotRouteSpec): HandlerFunction<ServerResponse> {
        return LoadSnapshotHandlerFunction(spec.aggregateRouteMetadata, snapshotQueryHandler, exceptionHandler)
    }
}
