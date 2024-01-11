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
package me.ahoo.wow.tck.eventsourcing

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.metrics.LoggingMeterRegistryInitializer
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test

/**
 * Provides tests for verifying `EventStore` specification rules.
 *
 * @author ahoo wang
 */
@ExtendWith(LoggingMeterRegistryInitializer::class)
abstract class EventStoreSpec {
    val namedAggregate = EventStoreSpec::class.java.requiredNamedAggregate()
    lateinit var eventStore: EventStore

    @BeforeEach
    open fun setup() {
        eventStore = createEventStore().metrizable()
    }

    protected abstract fun createEventStore(): EventStore

    protected fun generateEventStream(aggregateId: AggregateId): DomainEventStream {
        return generateEventStream(aggregateId, eventCount = 10)
    }

    protected fun generateEventStream(): DomainEventStream {
        return generateEventStream(namedAggregate.aggregateId())
    }

    @Test
    fun appendEventStream() {
        val eventStream = generateEventStream()
        assertThat(eventStream.count(), equalTo(eventStream.size))
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        eventStore.load(eventStream.aggregateId)
            .test()
            .expectNextMatches {
                assertThat(it.aggregateId, equalTo(eventStream.aggregateId))
                assertThat(it.version, equalTo(eventStream.version))
                assertThat(it.size, equalTo(eventStream.size))
                true
            }
            .verifyComplete()
    }

    @Test
    fun appendEventStreamWhenDuplicateAggregateId() {
        val eventStore = createEventStore().metrizable()
        val aggregateId = namedAggregate.aggregateId()
        val eventStream = generateEventStream(aggregateId)
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        val conflictingStream = generateEventStream(aggregateId)
        eventStore.append(conflictingStream)
            .test()
            .expectErrorMatches {
                assertThat(
                    it,
                    instanceOf(
                        DuplicateAggregateIdException::class.java,
                    ),
                )
                val conflictException = it as DuplicateAggregateIdException
                assertThat(conflictException.eventStream, equalTo(conflictingStream))
                true
            }
            .verify()
        eventStore.load(aggregateId)
            .test()
            .consumeNextWith {
                assertThat(
                    it.size,
                    equalTo(eventStream.size),
                )
            }
            .verifyComplete()
    }

    @Test
    fun appendEventStreamWhenEventVersionConflict() {
        val aggregateId = namedAggregate.aggregateId()
        val eventStream =
            MockAggregateCreated(GlobalIdGenerator.generateAsString())
                .toDomainEventStream(
                    GivenInitializationCommand(aggregateId),
                    Version.UNINITIALIZED_VERSION,
                )
        eventStore.append(eventStream)
            .test()
            .verifyComplete()

        val changeStream =
            MockAggregateCreated(GlobalIdGenerator.generateAsString())
                .toDomainEventStream(
                    GivenInitializationCommand(aggregateId),
                    Version.UNINITIALIZED_VERSION + 1,
                )
        eventStore.append(changeStream)
            .test()
            .verifyComplete()
        val conflictingStream =
            MockAggregateCreated(GlobalIdGenerator.generateAsString())
                .toDomainEventStream(
                    GivenInitializationCommand(aggregateId),
                    Version.UNINITIALIZED_VERSION + 1,
                )
        eventStore.append(conflictingStream)
            .test()
            .expectErrorMatches {
                assertThat(
                    it,
                    instanceOf(
                        EventVersionConflictException::class.java,
                    ),
                )
                val conflictException = it as EventVersionConflictException
                assertThat(conflictException.eventStream, equalTo(conflictingStream))
                true
            }
            .verify()
    }

    @Test
    open fun appendEventStreamWhenDuplicateRequestIdException() {
        val requestId = GlobalIdGenerator.generateAsString()
        val aggregateId = namedAggregate.aggregateId()
        val eventStream =
            MockAggregateCreated(GlobalIdGenerator.generateAsString()).toDomainEventStream(
                GivenInitializationCommand(aggregateId, requestId = requestId),
                Version.UNINITIALIZED_VERSION,
            )
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        val conflictingStream =
            MockAggregateCreated(GlobalIdGenerator.generateAsString()).toDomainEventStream(
                GivenInitializationCommand(aggregateId, requestId = requestId),
                Version.UNINITIALIZED_VERSION + 1,
            )
        eventStore.append(conflictingStream)
            .test()
            .expectErrorMatches {
                assertThat(
                    it,
                    instanceOf(
                        DuplicateRequestIdException::class.java,
                    ),
                )
                val duplicateRequestIdException = it as DuplicateRequestIdException
                assertThat(duplicateRequestIdException.requestId, equalTo(conflictingStream.requestId))
                true
            }
            .verify()
        eventStore.load(aggregateId)
            .test()
            .consumeNextWith {
                assertThat(
                    it.size,
                    equalTo(eventStream.size),
                )
            }
            .verifyComplete()
    }

    @Test
    fun appendEventStreamWhenParallel() {
        val eventStore = createEventStore().metrizable()
        Flux.range(0, TIMES)
            .parallel(DEFAULT_PARALLELISM)
            .runOn(Schedulers.parallel())
            .flatMap { eventStore.append(generateEventStream()) }
            .sequential()
            .test()
            .expectSubscription()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun loadEventStreamWhenParallel() {
        val eventStore = createEventStore().metrizable()
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        Flux.range(0, TIMES)
            .parallel(DEFAULT_PARALLELISM)
            .runOn(Schedulers.parallel())
            .flatMap { eventStore.load(eventStream.aggregateId) }
            .sequential()
            .test()
            .expectSubscription()
            .expectNextCount(TIMES.toLong())
            .verifyComplete()
    }

    @Test
    fun loadEventStreamWhenNotFound() {
        val eventStore = createEventStore().metrizable()
        eventStore.load(namedAggregate.aggregateId())
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun loadEventStreamGivenHeadVersion() {
        val eventStore = createEventStore().metrizable()
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        val headVersion = 1
        eventStore.load(eventStream.aggregateId, headVersion)
            .test()
            .expectNextMatches { actualStream: DomainEventStream ->
                assertThat(actualStream.aggregateId, equalTo(eventStream.aggregateId))
                assertThat(actualStream.version, equalTo(1))
                assertThat(actualStream.size, equalTo(10))
                true
            }
            .verifyComplete()
    }

    @Test
    open fun loadEventStreamGivenWrongVersion() {
        val eventStore = createEventStore().metrizable()
        val eventStream = generateEventStream()
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            eventStore.load(
                eventStream.aggregateId,
                -1,
            )
        }
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            eventStore.load(
                eventStream.aggregateId,
                5,
                4,
            )
        }
    }

    @Test
    open fun tailCursorId() {
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        eventStore.archiveAggregateId(eventStream.aggregateId)
            .test()
            .verifyComplete()
        eventStore.tailCursorId(namedAggregate)
            .test()
            .expectNext(eventStream.aggregateId.id)
            .verifyComplete()
    }

    @Test
    open fun archiveAggregateId() {
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        eventStore.archiveAggregateId(eventStream.aggregateId, AggregateIdScanner.FIRST_CURSOR_ID)
            .test()
            .verifyComplete()
    }

    @Test
    open fun scanAggregateId() {
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        val eventStream1 = generateEventStream()
        eventStore.append(eventStream1)
            .test()
            .verifyComplete()
        eventStore.scanAggregateId(eventStream.aggregateId, cursorId = eventStream.aggregateId.id, limit = 1)
            .test()
            .expectNextCount(1)
            .verifyComplete()
        eventStore.scanAggregateId(eventStream.aggregateId, cursorId = eventStream1.aggregateId.id, limit = 1)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    companion object {
        const val TIMES = 4000
        const val DEFAULT_PARALLELISM = 16
    }
}
