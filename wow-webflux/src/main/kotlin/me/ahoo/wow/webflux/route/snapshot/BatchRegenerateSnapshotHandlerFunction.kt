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
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.aggregate.snapshot.BatchRegenerateSnapshotRouteSpec
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.exception.onErrorMapBatchTaskException
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.toBatchResult
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class BatchRegenerateSnapshotHandlerFunction(
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
        val afterId = request.pathVariable(RoutePaths.BATCH_AFTER_ID)
        val limit = request.pathVariable(RoutePaths.BATCH_LIMIT).toInt()
        return snapshotRepository.scanAggregateId(
            namedAggregate = aggregateMetadata.namedAggregate,
            afterId = afterId,
            limit = limit,
        ).flatMapSequential { aggregateId ->
            handler.handle(aggregateId).thenReturn(aggregateId).onErrorMapBatchTaskException(aggregateId)
        }.toBatchResult(afterId).toServerResponse(request, exceptionHandler)
    }
}

class BatchRegenerateSnapshotHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository,
    private val exceptionHandler: RequestExceptionHandler
) : RouteHandlerFunctionFactory<BatchRegenerateSnapshotRouteSpec> {
    override val supportedSpec: Class<BatchRegenerateSnapshotRouteSpec>
        get() = BatchRegenerateSnapshotRouteSpec::class.java

    override fun create(spec: BatchRegenerateSnapshotRouteSpec): HandlerFunction<ServerResponse> {
        return BatchRegenerateSnapshotHandlerFunction(
            aggregateMetadata = spec.aggregateMetadata,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            snapshotRepository = snapshotRepository,
            exceptionHandler = exceptionHandler,
        )
    }
}
