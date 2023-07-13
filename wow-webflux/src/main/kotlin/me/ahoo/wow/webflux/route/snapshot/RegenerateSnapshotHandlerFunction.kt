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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.snapshot.RegenerateSnapshotRouteSpec
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.asServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class RegenerateSnapshotHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository,
    private val exceptionHandler: ExceptionHandler
) : HandlerFunction<ServerResponse> {
    private val handler = RegenerateSnapshotHandler(
        aggregateMetadata = aggregateMetadata,
        stateAggregateFactory = stateAggregateFactory,
        eventStore = eventStore,
        snapshotRepository = snapshotRepository,
    )

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantId(aggregateMetadata)
        val id = request.pathVariable(RoutePaths.ID_KEY)
        val aggregateId = aggregateMetadata.asAggregateId(id = id, tenantId = tenantId)
        return handler.handle(aggregateId)
            .throwNotFoundIfEmpty()
            .then()
            .asServerResponse(exceptionHandler)
    }
}

class RegenerateSnapshotHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository,
    private val exceptionHandler: ExceptionHandler
) : RouteHandlerFunctionFactory<RegenerateSnapshotRouteSpec> {
    override val supportedSpec: Class<RegenerateSnapshotRouteSpec>
        get() = RegenerateSnapshotRouteSpec::class.java

    override fun create(spec: RegenerateSnapshotRouteSpec): HandlerFunction<ServerResponse> {
        return RegenerateSnapshotHandlerFunction(
            aggregateMetadata = spec.aggregateMetadata,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            snapshotRepository = snapshotRepository,
            exceptionHandler = exceptionHandler,
        )
    }
}
