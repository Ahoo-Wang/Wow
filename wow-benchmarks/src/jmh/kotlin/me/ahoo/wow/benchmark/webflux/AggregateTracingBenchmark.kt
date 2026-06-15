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

package me.ahoo.wow.benchmark.webflux

import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.serialization.deepCopy
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.route.state.AggregateTracingReplay
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import tools.jackson.databind.node.ObjectNode

@State(Scope.Thread)
open class AggregateTracingBenchmark {
    @Param("1", "10", "100")
    var eventCount: Int = 1

    @Param("10")
    var traceWindowSize: Int = 10

    // Setup prepares read-only inputs; benchmark rows choose which cost to measure.
    private lateinit var eventStreams: List<DomainEventStream>
    private lateinit var traceWindowEventStreams: List<DomainEventStream>
    private var traceWindowStartIndex: Int = 0
    private var traceWindowEmitHeadVersion: Int = 1
    private var traceWindowTailVersion: Int = 0
    private lateinit var statesToCopy: List<CartState>
    private lateinit var jsonStateSnapshots: List<ObjectNode>
    private lateinit var tracedHistory: List<StateEvent<ObjectNode>>
    private lateinit var stateEventState: CartState
    private lateinit var firstOperator: String
    private var firstEventTime: Long = 0
    private lateinit var tags: AbacTags
    private var deleted: Boolean = false

    @Setup(Level.Iteration)
    fun setup() {
        eventStreams = BenchmarkEvents.eventStreams(eventCount = eventCount)
        traceWindowStartIndex = (eventStreams.size - traceWindowSize).coerceAtLeast(0)
        traceWindowEmitHeadVersion = eventStreams[traceWindowStartIndex].version
        traceWindowTailVersion = eventStreams.last().version
        // The suffix-only row replays this window as a standalone sequence, so versions must be contiguous.
        traceWindowEventStreams = eventStreams.subList(traceWindowStartIndex, eventStreams.size)
            .mapIndexed { index, eventStream ->
                eventStream.withTraceVersion(version = index + 1)
            }
        val stateAggregate = newCartStateAggregate()
        val preparedStates = ArrayList<CartState>(eventCount)
        for (eventStream in eventStreams) {
            stateAggregate.onSourcing(eventStream)
            preparedStates.add(
                stateAggregate.state.deepCopy(BenchmarkAggregates.cartMetadata.state.aggregateType)
            )
        }
        statesToCopy = preparedStates
        jsonStateSnapshots = preparedStates.map {
            it.toJsonNode<ObjectNode>()
        }
        tracedHistory = AggregateTracingReplay.trace(
            stateAggregateMetadata = BenchmarkAggregates.cartMetadata.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = eventStreams,
        )
        stateEventState = preparedStates.last()
        firstOperator = stateAggregate.firstOperator
        firstEventTime = stateAggregate.firstEventTime
        tags = stateAggregate.tags
        deleted = stateAggregate.deleted
    }

    @Benchmark
    fun sourceCartHistoryOnly(blackhole: Blackhole) {
        val stateAggregate = newCartStateAggregate()
        for (eventStream in eventStreams) {
            stateAggregate.onSourcing(eventStream)
        }
        blackhole.consume(stateAggregate.state)
    }

    @Benchmark
    fun copyCartStateOnly(blackhole: Blackhole) {
        val copiedStates = ArrayList<CartState>(statesToCopy.size)
        for (state in statesToCopy) {
            copiedStates.add(
                state.deepCopy(BenchmarkAggregates.cartMetadata.state.aggregateType)
            )
        }
        blackhole.consume(copiedStates)
    }

    @Benchmark
    fun jsonSnapshotCartStateOnly(blackhole: Blackhole) {
        val snapshots = ArrayList<ObjectNode>(statesToCopy.size)
        for (state in statesToCopy) {
            snapshots.add(state.toJsonNode<ObjectNode>())
        }
        blackhole.consume(snapshots)
    }

    @Benchmark
    fun stateEventCreationOnly(blackhole: Blackhole) {
        val stateEvents = ArrayList<StateEvent<CartState>>(eventStreams.size)
        for (eventStream in eventStreams) {
            stateEvents.add(
                eventStream.toStateEvent(
                    state = stateEventState,
                    firstOperator = firstOperator,
                    firstEventTime = firstEventTime,
                    tags = tags,
                    deleted = deleted,
                )
            )
        }
        blackhole.consume(stateEvents)
    }

    @Benchmark
    fun jsonSnapshotStateEventCreationOnly(blackhole: Blackhole) {
        val stateEvents = ArrayList<StateEvent<ObjectNode>>(eventStreams.size)
        for (index in eventStreams.indices) {
            stateEvents.add(
                eventStreams[index].toStateEvent(
                    state = jsonStateSnapshots[index],
                    firstOperator = firstOperator,
                    firstEventTime = firstEventTime,
                    tags = tags,
                    deleted = deleted,
                )
            )
        }
        blackhole.consume(stateEvents)
    }

    @Benchmark
    fun serializeTracedCartHistoryOnly(blackhole: Blackhole) {
        blackhole.consume(tracedHistory.toJsonString())
    }

    @Benchmark
    fun traceAndSerializeCartHistory(blackhole: Blackhole) {
        val tracedStates = AggregateTracingReplay.trace(
            stateAggregateMetadata = BenchmarkAggregates.cartMetadata.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = eventStreams,
        )
        blackhole.consume(tracedStates.toJsonString())
    }

    @Benchmark
    fun suffixTraceWindowOnly(blackhole: Blackhole) {
        val tracedStates = AggregateTracingReplay.trace(
            stateAggregateMetadata = BenchmarkAggregates.cartMetadata.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = traceWindowEventStreams,
        )
        blackhole.consume(tracedStates)
    }

    @Benchmark
    fun prefixReplayWindowedTraceCartHistory(blackhole: Blackhole) {
        blackhole.consume(traceWindowedOutput())
    }

    @Benchmark
    fun prefixReplayWindowedTraceAndSerialize(blackhole: Blackhole) {
        blackhole.consume(traceWindowedOutputToJsonString())
    }

    @Benchmark
    fun directSerializeTraceCartHistory(blackhole: Blackhole) {
        blackhole.consume(traceFullOutputToJsonString())
    }

    @Benchmark
    fun traceCartHistory(blackhole: Blackhole) {
        val tracedStates = AggregateTracingReplay.trace(
            stateAggregateMetadata = BenchmarkAggregates.cartMetadata.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = eventStreams,
        )
        blackhole.consume(tracedStates)
    }

    private fun traceWindowedOutput(): List<StateEvent<ObjectNode>> {
        return AggregateTracingReplay.trace(
            stateAggregateMetadata = BenchmarkAggregates.cartMetadata.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = eventStreams,
            emitHeadVersion = traceWindowEmitHeadVersion,
            tailVersion = traceWindowTailVersion,
        ).collectList().block()!!
    }

    private fun traceWindowedOutputToJsonString(): String {
        return traceWindowedOutput().toJsonString()
    }

    private fun traceFullOutputToJsonString(): String {
        return traceOutputToJsonString(outputStartIndex = 0)
    }

    private fun traceOutputToJsonString(outputStartIndex: Int): String {
        val stateAggregate = newCartStateAggregate()
        val json = StringBuilder()
        json.append('[')
        var emitted = false
        for (index in eventStreams.indices) {
            val eventStream = eventStreams[index]
            stateAggregate.onSourcing(eventStream)
            if (index >= outputStartIndex) {
                if (emitted) {
                    json.append(',')
                } else {
                    emitted = true
                }
                json.append(
                    eventStream.toStateEvent(
                        state = stateAggregate.state.toJsonNode<ObjectNode>(),
                        firstOperator = stateAggregate.firstOperator,
                        firstEventTime = stateAggregate.firstEventTime,
                        tags = stateAggregate.tags,
                        deleted = stateAggregate.deleted,
                    ).toJsonString()
                )
            }
        }
        json.append(']')
        return json.toString()
    }

    private fun DomainEventStream.withTraceVersion(version: Int): DomainEventStream {
        return SimpleDomainEventStream(
            id = id,
            requestId = requestId,
            header = header.copy(),
            body = map { it.withTraceVersion(version) },
        )
    }

    private fun DomainEvent<*>.withTraceVersion(version: Int): DomainEvent<*> {
        return when (this) {
            is SimpleDomainEvent<*> -> copy(
                header = header.copy(),
                version = version,
            )

            else -> error("Unsupported benchmark event type: ${javaClass.name}")
        }
    }

    private fun newCartStateAggregate() = ConstructorStateAggregateFactory.create(
        BenchmarkAggregates.cartMetadata.state,
        eventStreams.first().aggregateId,
    )
}
