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

import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.aggregate.state.AggregateTracingRouteSpec
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.context.WowWebRequestContext
import me.ahoo.wow.webflux.route.policy.TracingPolicy
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
    constructor(
        aggregateMetadata: AggregateMetadata<*, *>,
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        exceptionHandler: RequestExceptionHandler
    ) : this(
        aggregateMetadata = aggregateMetadata,
        stateAggregateFactory = stateAggregateFactory,
        eventStore = eventStore,
        exceptionHandler = exceptionHandler,
        tracingPolicy = TracingPolicy(),
    )

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val context = WowWebRequestContext.of(request, aggregateMetadata)
        return eventStore
            .load(
                aggregateId = context.aggregateId,
            )
            // Temporary materialization is bounded to one aggregate history; tracing output streams the requested window.
            .collectList()
            .flatMapMany { eventStreams ->
                val totalVersion = eventStreams.lastOrNull()?.version ?: 0
                val range = tracingPolicy.range(request, totalVersion)
                aggregateMetadata.state.trace(
                    stateAggregateFactory = stateAggregateFactory,
                    eventStreams = eventStreams,
                    emitHeadVersion = range.emitHeadVersion,
                    tailVersion = range.tailVersion,
                )
            }.toServerResponse(request, exceptionHandler)
    }

    companion object {

        fun <S : Any> StateAggregateMetadata<S>.trace(
            stateAggregateFactory: StateAggregateFactory,
            eventStreams: List<DomainEventStream>
        ): List<StateEvent<ObjectNode>> {
            if (eventStreams.isEmpty()) {
                return listOf()
            }
            val stateAggregate = stateAggregateFactory.create(this, eventStreams.first().aggregateId)
            return eventStreams.map { eventStream ->
                stateAggregate.onSourcing(eventStream)
                eventStream.toStateEvent(
                    state = stateAggregate.state.toJsonNode<ObjectNode>(),
                    firstOperator = stateAggregate.firstOperator,
                    firstEventTime = stateAggregate.firstEventTime,
                    tags = stateAggregate.tags,
                    deleted = stateAggregate.deleted,
                )
            }
        }

        fun <S : Any> StateAggregateMetadata<S>.trace(
            stateAggregateFactory: StateAggregateFactory,
            eventStreams: List<DomainEventStream>,
            emitHeadVersion: Int,
            tailVersion: Int
        ): Flux<StateEvent<ObjectNode>> {
            require(emitHeadVersion > 0) {
                "emitHeadVersion must be greater than 0."
            }
            require(tailVersion >= 0) {
                "tailVersion must be greater than or equal to 0."
            }
            if (eventStreams.isEmpty() || tailVersion < emitHeadVersion) {
                return Flux.empty()
            }

            return Flux.defer {
                val stateAggregate = stateAggregateFactory.create(this, eventStreams.first().aggregateId)
                Flux.fromIterable(eventStreams)
                    .handle<StateEvent<ObjectNode>> { eventStream, sink ->
                        if (eventStream.version > tailVersion) {
                            return@handle
                        }
                        stateAggregate.onSourcing(eventStream)
                        if (eventStream.version >= emitHeadVersion) {
                            sink.next(eventStream.toStateEvent(stateAggregate))
                        }
                    }
            }
        }

        private fun <S : Any> DomainEventStream.toStateEvent(
            stateAggregate: StateAggregate<S>
        ): StateEvent<ObjectNode> {
            return toStateEvent(
                state = stateAggregate.state.toJsonNode<ObjectNode>(),
                firstOperator = stateAggregate.firstOperator,
                firstEventTime = stateAggregate.firstEventTime,
                tags = stateAggregate.tags,
                deleted = stateAggregate.deleted,
            )
        }
    }
}

class AggregateTracingHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val tracingPolicy: TracingPolicy
) : RouteHandlerFunctionFactory<AggregateTracingRouteSpec> {
    constructor(
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        exceptionHandler: RequestExceptionHandler
    ) : this(
        stateAggregateFactory = stateAggregateFactory,
        eventStore = eventStore,
        exceptionHandler = exceptionHandler,
        tracingPolicy = TracingPolicy(),
    )

    override val supportedSpec: Class<AggregateTracingRouteSpec>
        get() = AggregateTracingRouteSpec::class.java

    override fun create(spec: AggregateTracingRouteSpec): HandlerFunction<ServerResponse> {
        return AggregateTracingHandlerFunction(
            spec.aggregateMetadata,
            stateAggregateFactory,
            eventStore,
            exceptionHandler,
            tracingPolicy,
        )
    }
}
