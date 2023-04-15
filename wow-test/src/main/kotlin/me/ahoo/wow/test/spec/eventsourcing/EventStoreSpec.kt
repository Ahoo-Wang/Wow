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
package me.ahoo.wow.test.spec.eventsourcing

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.configuration.asRequiredNamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.eventsourcing.RequestIdIdempotencyException
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import me.ahoo.wow.test.spec.eventsourcing.MockDomainEventStreams.generateEventStream
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import reactor.test.StepVerifier

/**
 * Provides tests for verifying `EventStore` specification rules.
 *
 * @author ahoo wang
 */
abstract class EventStoreSpec {
    val namedAggregate = EventStoreSpec::class.java.asRequiredNamedAggregate()
    lateinit var eventStore: EventStore

    @BeforeEach
    fun setup() {
        eventStore = createEventStore()
    }

    protected abstract fun createEventStore(): EventStore

    protected fun generateEventStream(aggregateId: AggregateId): DomainEventStream {
        return generateEventStream(aggregateId, eventCount = 10)
    }

    protected fun generateEventStream(): DomainEventStream {
        return generateEventStream(namedAggregate.asAggregateId())
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
        val eventStore = createEventStore()
        val aggregateId = namedAggregate.asAggregateId()
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
        val aggregateId = namedAggregate.asAggregateId()
        val eventStream =
            Created().asDomainEventStream(
                GivenInitializationCommand(aggregateId),
                Version.INITIAL_VERSION,
            )
        eventStore.append(eventStream)
            .test()
            .verifyComplete()

        val changeStream =
            Changed().asDomainEventStream(
                GivenInitializationCommand(aggregateId),
                Version.INITIAL_VERSION + 1,
            )
        eventStore.append(changeStream)
            .test()
            .verifyComplete()
        val conflictingStream =
            Changed().asDomainEventStream(
                GivenInitializationCommand(aggregateId),
                Version.INITIAL_VERSION + 1,
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
    fun givenDuplicateRequestIdWhenAppendExpectRequestIdIdempotencyException() {
        val requestId = GlobalIdGenerator.generateAsString()
        val aggregateId = namedAggregate.asAggregateId()
        val eventStream =
            Created().asDomainEventStream(
                GivenInitializationCommand(aggregateId, requestId = requestId),
                Version.INITIAL_VERSION,
            )
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        val conflictingStream =
            Created().asDomainEventStream(
                GivenInitializationCommand(aggregateId, requestId = requestId),
                Version.INITIAL_VERSION + 1,
            )
        eventStore.append(conflictingStream)
            .test()
            .expectErrorMatches {
                assertThat(
                    it,
                    instanceOf(
                        RequestIdIdempotencyException::class.java,
                    ),
                )
                val conflictException = it as RequestIdIdempotencyException
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
    fun appendEventStreamWhenParallel() {
        val eventStore = createEventStore()
        Flux.range(0, TIMES)
            .parallel(DEFAULT_PARALLELISM)
            .runOn(Schedulers.parallel())
            .flatMap { eventStore.append(generateEventStream()) }
            .`as` { StepVerifier.create(it) }
            .expectSubscription()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun loadEventStreamWhenParallel() {
        val eventStore = createEventStore()
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        Flux.range(0, TIMES)
            .parallel(DEFAULT_PARALLELISM)
            .runOn(Schedulers.parallel())
            .flatMap { eventStore.load(eventStream.aggregateId) }
            .`as` { StepVerifier.create(it) }
            .expectSubscription()
            .expectNextCount(TIMES.toLong())
            .verifyComplete()
    }

    @Test
    fun loadEventStreamWhenNotFound() {
        val eventStore = createEventStore()
        eventStore.load(namedAggregate.asAggregateId())
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun loadEventStreamGivenHeadVersion() {
        val eventStore = createEventStore()
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
    fun loadEventStreamGivenWrongVersion() {
        val eventStore = createEventStore()
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

    companion object {
        const val TIMES = 4000
        const val DEFAULT_PARALLELISM = 16
    }
}
