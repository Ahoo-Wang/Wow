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
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.aggregate.snapshot.RegenerateSnapshotRouteSpec
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import me.ahoo.wow.webflux.route.requireAggregateHandlerMetadata
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class RegenerateSnapshotHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository,
    private val exceptionHandler: RequestExceptionHandler
) : HandlerFunction<ServerResponse> {
    private val handler = RegenerateSnapshotHandler(
        aggregateMetadata = aggregateMetadata,
        stateAggregateFactory = stateAggregateFactory,
        eventStore = eventStore,
        snapshotRepository = snapshotRepository,
    )

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        val id = request.pathVariable(MessageRecords.ID)
        val aggregateId = aggregateMetadata.aggregateId(id = id, tenantId = tenantId)
        return handler.handle(aggregateId)
            .throwNotFoundIfEmpty()
            .then()
            .toServerResponse(request, exceptionHandler)
    }
}

class RegenerateSnapshotHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository,
    private val exceptionHandler: RequestExceptionHandler
) : RouteHandlerFunctionFactory<RegenerateSnapshotRouteSpec>, HttpRouteHandlerFunctionFactory {
    override val supportedSpec: Class<RegenerateSnapshotRouteSpec>
        get() = RegenerateSnapshotRouteSpec::class.java
    override val handlerKey: String
        get() = supportedSpec.name

    override fun create(spec: RegenerateSnapshotRouteSpec): HandlerFunction<ServerResponse> {
        return create(spec.aggregateMetadata)
    }

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        return create(metadata.requireAggregateHandlerMetadata(handlerKey).aggregateRouteMetadata.aggregateMetadata)
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return RegenerateSnapshotHandlerFunction(
            aggregateMetadata = aggregateMetadata,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            snapshotRepository = snapshotRepository,
            exceptionHandler = exceptionHandler,
        )
    }
}
