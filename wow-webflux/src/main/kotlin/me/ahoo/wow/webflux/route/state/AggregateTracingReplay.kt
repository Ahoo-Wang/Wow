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
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.webflux.route.policy.TracingRequest
import reactor.core.publisher.Flux
import tools.jackson.databind.node.ObjectNode
import java.util.ArrayDeque

internal object AggregateTracingReplay {

    fun <S : Any> trace(
        stateAggregateMetadata: StateAggregateMetadata<S>,
        stateAggregateFactory: StateAggregateFactory,
        eventStreams: Flux<DomainEventStream>,
        tracingRequest: TracingRequest
    ): Flux<StateEvent<ObjectNode>> {
        if (tracingRequest.hasLimit) {
            return traceTailLimit(
                stateAggregateMetadata = stateAggregateMetadata,
                stateAggregateFactory = stateAggregateFactory,
                eventStreams = eventStreams,
                tracingRequest = tracingRequest,
            )
        }
        return traceStreamingRange(
            stateAggregateMetadata = stateAggregateMetadata,
            stateAggregateFactory = stateAggregateFactory,
            eventStreams = eventStreams,
            tracingRequest = tracingRequest,
        )
    }

    private fun <S : Any> traceStreamingRange(
        stateAggregateMetadata: StateAggregateMetadata<S>,
        stateAggregateFactory: StateAggregateFactory,
        eventStreams: Flux<DomainEventStream>,
        tracingRequest: TracingRequest
    ): Flux<StateEvent<ObjectNode>> {
        return Flux.defer {
            val replayState = ReplayState(stateAggregateMetadata, stateAggregateFactory)
            eventStreams
                .takeUntilTail(tracingRequest.tailVersion)
                .handle<StateEvent<ObjectNode>> { eventStream, sink ->
                    val stateAggregate = replayState.source(eventStream)
                    if (eventStream.version >= tracingRequest.emitHeadVersion) {
                        sink.next(toStateEvent(eventStream, stateAggregate))
                    }
                }
        }
    }

    private fun <S : Any> traceTailLimit(
        stateAggregateMetadata: StateAggregateMetadata<S>,
        stateAggregateFactory: StateAggregateFactory,
        eventStreams: Flux<DomainEventStream>,
        tracingRequest: TracingRequest
    ): Flux<StateEvent<ObjectNode>> {
        val limit = tracingRequest.limit ?: return Flux.empty()
        if (limit == 0) {
            return Flux.empty()
        }
        return Flux.defer {
            val replayState = ReplayState(stateAggregateMetadata, stateAggregateFactory)
            val tailBuffer = ArrayDeque<StateEvent<ObjectNode>>(limit)
            eventStreams
                .takeUntilTail(tracingRequest.tailVersion)
                .doOnNext { eventStream ->
                    val stateAggregate = replayState.source(eventStream)
                    if (eventStream.version >= tracingRequest.emitHeadVersion) {
                        if (tailBuffer.size == limit) {
                            tailBuffer.removeFirst()
                        }
                        tailBuffer.addLast(toStateEvent(eventStream, stateAggregate))
                    }
                }
                .thenMany(Flux.defer { Flux.fromIterable(tailBuffer) })
        }
    }

    private fun Flux<DomainEventStream>.takeUntilTail(tailVersion: Int?): Flux<DomainEventStream> {
        return tailVersion?.let { tail ->
            takeWhile { it.version <= tail }
        } ?: this
    }

    private class ReplayState<S : Any>(
        private val stateAggregateMetadata: StateAggregateMetadata<S>,
        private val stateAggregateFactory: StateAggregateFactory
    ) {
        private var stateAggregate: StateAggregate<S>? = null

        fun source(eventStream: DomainEventStream): StateAggregate<S> {
            val aggregate = stateAggregate ?: stateAggregateFactory
                .create(stateAggregateMetadata, eventStream.aggregateId)
                .also {
                    stateAggregate = it
                }
            aggregate.onSourcing(eventStream)
            return aggregate
        }
    }

    internal fun <S : Any> toStateEvent(
        eventStream: DomainEventStream,
        stateAggregate: StateAggregate<S>
    ): StateEvent<ObjectNode> {
        return eventStream.toStateEvent(
            state = stateAggregate.state.toJsonNode<ObjectNode>(),
            firstOperator = stateAggregate.firstOperator,
            firstEventTime = stateAggregate.firstEventTime,
            tags = stateAggregate.tags,
            deleted = stateAggregate.deleted,
        )
    }
}
