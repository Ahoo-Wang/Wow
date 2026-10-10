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
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.rest.RouteVariables
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.policy.BatchExecutionPolicy
import me.ahoo.wow.webflux.route.toBatchResult
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class BatchRegenerateSnapshotHandlerFunction(
    aggregateMetadata: AggregateMetadata<*, *>,
    stateAggregateFactory: StateAggregateFactory,
    eventStore: EventStore,
    snapshotStore: SnapshotStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val batchExecutionPolicy: BatchExecutionPolicy
) : HandlerFunction<ServerResponse> {
    private val handler = RegenerateSnapshotHandler(
        aggregateMetadata = aggregateMetadata,
        stateAggregateFactory = stateAggregateFactory,
        eventStore = eventStore,
        snapshotStore = snapshotStore,
    )

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val afterId = request.pathVariable(RouteVariables.BATCH_AFTER_ID)
        val limit = request.pathVariable(RouteVariables.BATCH_LIMIT).toInt()
        return handler.regenerate(afterId, limit, batchExecutionPolicy)
            .toBatchResult(afterId, request, exceptionHandler)
            .toServerResponse(request, exceptionHandler)
    }
}

class BatchRegenerateSnapshotHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotStore: SnapshotStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val batchExecutionPolicy: BatchExecutionPolicy
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Snapshot.BATCH_REGENERATE) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return BatchRegenerateSnapshotHandlerFunction(
            aggregateMetadata = aggregateMetadata(metadata),
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            snapshotStore = snapshotStore,
            exceptionHandler = exceptionHandler,
            batchExecutionPolicy = batchExecutionPolicy,
        )
    }
}
