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

package me.ahoo.wow.webflux.route.state

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventStore.Companion.DEFAULT_TAIL_VERSION
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.context.WowWebRequestContext
import me.ahoo.wow.webflux.route.policy.TracingPolicy
import me.ahoo.wow.webflux.route.policy.TracingRequest
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class AggregateTracingHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val tracingPolicy: TracingPolicy
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return Mono.defer {
            val context = WowWebRequestContext.of(request, aggregateMetadata)
            val tracingRequest = tracingPolicy.request(request)
            trace(context, tracingRequest)
                .toServerResponse(request, exceptionHandler)
        }.onErrorResume {
            exceptionHandler.handle(request, it)
        }
    }

    private fun trace(
        context: WowWebRequestContext,
        tracingRequest: TracingRequest
    ): Flux<StateEvent<ObjectNode>> {
        val limit = tracingRequest.limit
        if (limit == null) {
            return eventStore
                .load(
                    aggregateId = context.aggregateId,
                    tailVersion = tracingRequest.tailVersion ?: DEFAULT_TAIL_VERSION,
                )
                .let { eventStreams ->
                    AggregateTracingReplay.trace(
                        stateAggregateMetadata = aggregateMetadata.state,
                        stateAggregateFactory = stateAggregateFactory,
                        eventStreams = eventStreams,
                        tracingRequest = tracingRequest,
                    )
                }
        }
        if (limit == 0) {
            return Flux.empty()
        }
        return eventStore.last(context.aggregateId)
            .map { it.version }
            .defaultIfEmpty(TracingPolicy.EMPTY_TAIL_VERSION)
            .flatMapMany { totalVersion ->
                val range = tracingRequest.toRange(totalVersion)
                if (range.tailVersion < range.emitHeadVersion) {
                    return@flatMapMany Flux.empty()
                }
                AggregateTracingReplay.trace(
                    stateAggregateMetadata = aggregateMetadata.state,
                    stateAggregateFactory = stateAggregateFactory,
                    eventStreams = eventStore.load(
                        aggregateId = context.aggregateId,
                        headVersion = range.replayHeadVersion,
                        tailVersion = range.tailVersion,
                    ),
                    tracingRequest = TracingRequest(
                        headVersion = range.emitHeadVersion,
                        tailVersion = range.tailVersion,
                        limit = null,
                    ),
                )
            }
    }
}

class AggregateTracingHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val tracingPolicy: TracingPolicy
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.State.AGGREGATE_TRACING) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return AggregateTracingHandlerFunction(
            aggregateMetadata,
            stateAggregateFactory,
            eventStore,
            exceptionHandler,
            tracingPolicy,
        )
    }
}
