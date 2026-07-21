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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.tck.metrics.LoggingMeterRegistryInitializer
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream as generateMockEventStream

/**
 * Provides tests for verifying `EventStore` specification rules.
 *
 * @author ahoo wang
 */
@ExtendWith(LoggingMeterRegistryInitializer::class)
abstract class EventStoreSpec {
    val namedAggregate = EventStoreSpec::class.java.requiredNamedAggregate()
    lateinit var eventStore: EventStore
    protected open val concurrencyTestIterations: Int = DEFAULT_CONCURRENCY_TEST_ITERATIONS
    protected open val concurrencyTestMaxConcurrency: Int = DEFAULT_CONCURRENCY_TEST_MAX_CONCURRENCY

    @BeforeEach
    open fun setup() {
        eventStore = createEventStore().metrizable()
    }

    protected abstract fun createEventStore(): EventStore

    protected fun generateEventStream(aggregateId: AggregateId): DomainEventStream {
        return generateMockEventStream(aggregateId, eventCount = 10)
    }

    protected fun generateEventStream(): DomainEventStream {
        return generateEventStream(namedAggregate.aggregateId())
    }

    @Test
    fun appendEventStream() {
        val eventStream = generateEventStream()
        eventStream.count().assert().isEqualTo(eventStream.size)
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        eventStore.load(eventStream.aggregateId)
            .test()
            .expectNextMatches {
                it.aggregateId.assert().isEqualTo(eventStream.aggregateId)
                it.version.assert().isEqualTo(eventStream.version)
                it.size.assert().isEqualTo(eventStream.size)
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
                it.assert().isInstanceOf(DuplicateAggregateIdException::class.java)
                val conflictException = it as DuplicateAggregateIdException
                conflictException.eventStream.assert().isEqualTo(conflictingStream)
                true
            }
            .verify()
        eventStore.load(aggregateId)
            .test()
            .consumeNextWith {
                it.size.assert().isEqualTo(eventStream.size)
            }
            .verifyComplete()
    }

    @Test
    fun appendEventStreamWhenEventVersionConflict() {
        val aggregateId = namedAggregate.aggregateId()
        val eventStream =
            MockAggregateCreated(generateGlobalId())
                .toDomainEventStream(
                    GivenInitializationCommand(aggregateId),
                    Version.UNINITIALIZED_VERSION,
                )
        eventStore.append(eventStream)
            .test()
            .verifyComplete()

        val changeStream =
            MockAggregateCreated(generateGlobalId())
                .toDomainEventStream(
                    GivenInitializationCommand(aggregateId),
                    Version.UNINITIALIZED_VERSION + 1,
                )
        eventStore.append(changeStream)
            .test()
            .verifyComplete()
        val conflictingStream =
            MockAggregateCreated(generateGlobalId())
                .toDomainEventStream(
                    GivenInitializationCommand(aggregateId),
                    Version.UNINITIALIZED_VERSION + 1,
                )
        eventStore.append(conflictingStream)
            .test()
            .expectErrorMatches {
                it.assert().isInstanceOf(EventVersionConflictException::class.java)
                val conflictException = it as EventVersionConflictException
                conflictException.eventStream.assert().isEqualTo(conflictingStream)
                true
            }
            .verify()
    }

    @Test
    open fun appendEventStreamWhenDuplicateRequestIdException() {
        val requestId = generateGlobalId()
        val aggregateId = namedAggregate.aggregateId()
        val eventStream =
            MockAggregateCreated(generateGlobalId()).toDomainEventStream(
                GivenInitializationCommand(aggregateId, requestId = requestId),
                Version.UNINITIALIZED_VERSION,
            )
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        val conflictingStream =
            MockAggregateCreated(generateGlobalId()).toDomainEventStream(
                GivenInitializationCommand(aggregateId, requestId = requestId),
                Version.UNINITIALIZED_VERSION + 1,
            )
        eventStore.append(conflictingStream)
            .test()
            .expectErrorMatches {
                it.assert().isInstanceOf(DuplicateRequestIdException::class.java)
                val duplicateRequestIdException = it as DuplicateRequestIdException
                duplicateRequestIdException.requestId.assert().isEqualTo(conflictingStream.requestId)
                true
            }
            .verify()
        eventStore.load(aggregateId)
            .test()
            .consumeNextWith {
                it.size.assert().isEqualTo(eventStream.size)
            }
            .verifyComplete()
    }

    @Test
    fun existsRequestId() {
        val eventStore = createEventStore().metrizable()
        val aggregateId = namedAggregate.aggregateId()
        val eventStream =
            MockAggregateCreated(generateGlobalId()).toDomainEventStream(
                GivenInitializationCommand(aggregateId, requestId = generateGlobalId()),
                Version.UNINITIALIZED_VERSION,
            )
        eventStore.append(eventStream)
            .test()
            .verifyComplete()

        eventStore.existsRequestId(aggregateId, eventStream.requestId)
            .test()
            .expectNext(true)
            .verifyComplete()
        eventStore.existsRequestId(aggregateId, generateGlobalId())
            .test()
            .expectNext(false)
            .verifyComplete()
        eventStore.existsRequestId(namedAggregate.aggregateId(), eventStream.requestId)
            .test()
            .expectNext(false)
            .verifyComplete()
    }

    @Test
    fun appendEventStreamWhenSameRequestIdAcrossDifferentAggregates() {
        val requestId = generateGlobalId()
        val firstAggregateId = namedAggregate.aggregateId(generateGlobalId())
        val secondAggregateId = namedAggregate.aggregateId(generateGlobalId())
        val firstStream =
            MockAggregateCreated(generateGlobalId()).toDomainEventStream(
                GivenInitializationCommand(firstAggregateId, requestId = requestId),
                Version.UNINITIALIZED_VERSION,
            )
        val secondStream =
            MockAggregateCreated(generateGlobalId()).toDomainEventStream(
                GivenInitializationCommand(secondAggregateId, requestId = requestId),
                Version.UNINITIALIZED_VERSION,
            )

        eventStore.append(firstStream)
            .test()
            .verifyComplete()
        eventStore.append(secondStream)
            .test()
            .verifyComplete()

        eventStore.existsRequestId(firstAggregateId, requestId)
            .test()
            .expectNext(true)
            .verifyComplete()
        eventStore.existsRequestId(secondAggregateId, requestId)
            .test()
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun appendEventStreamWhenParallel() {
        val eventStore = createEventStore().metrizable()
        Flux.range(0, concurrencyTestIterations)
            .flatMap(
                {
                    eventStore.append(generateEventStream())
                        .subscribeOn(Schedulers.parallel())
                },
                concurrencyTestMaxConcurrency,
            )
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
        Flux.range(0, concurrencyTestIterations)
            .flatMap(
                {
                    eventStore.load(eventStream.aggregateId)
                        .subscribeOn(Schedulers.parallel())
                },
                concurrencyTestMaxConcurrency,
            )
            .test()
            .expectSubscription()
            .expectNextCount(concurrencyTestIterations.toLong())
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
                actualStream.aggregateId.assert().isEqualTo(eventStream.aggregateId)
                actualStream.version.assert().isEqualTo(1)
                actualStream.size.assert().isEqualTo(10)
                true
            }
            .verifyComplete()
    }

    @Test
    fun singleEventStream() {
        val eventStore = createEventStore().metrizable()
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        val version = 1
        eventStore.single(eventStream.aggregateId, version)
            .test()
            .expectNextMatches { actualStream: DomainEventStream ->
                actualStream.aggregateId.assert().isEqualTo(eventStream.aggregateId)
                actualStream.version.assert().isEqualTo(1)
                actualStream.size.assert().isEqualTo(10)
                true
            }
            .verifyComplete()
    }

    @Test
    open fun lastEventStream() {
        val eventStore = createEventStore().metrizable()
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        eventStore.last(eventStream.aggregateId)
            .test()
            .expectNextMatches { actualStream: DomainEventStream ->
                actualStream.aggregateId.assert().isEqualTo(eventStream.aggregateId)
                actualStream.version.assert().isEqualTo(1)
                true
            }.verifyComplete()
    }

    @Test
    open fun loadEventStreamByEventTime() {
        val eventStore = createEventStore().metrizable()
        val eventStream = generateEventStream()
        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        eventStore.load(eventStream.aggregateId, 0, eventStream.createTime)
            .test()
            .expectNextMatches { actualStream: DomainEventStream ->
                actualStream.aggregateId.assert().isEqualTo(eventStream.aggregateId)
                actualStream.version.assert().isEqualTo(1)
                actualStream.size.assert().isEqualTo(10)
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
    open fun scanAggregateId() {
        val eventStore = createEventStore().metrizable()
        val cursorId = generateGlobalId()
        val aggregateId = namedAggregate.aggregateId(generateGlobalId())
        eventStore.append(generateEventStream(aggregateId))
            .test()
            .verifyComplete()

        eventStore.scanAggregateId(namedAggregate, afterId = cursorId, limit = 1)
            .test()
            .expectNext(aggregateId)
            .verifyComplete()
        eventStore.scanAggregateId(namedAggregate, afterId = aggregateId.id, limit = 1)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldFilterNamedAggregate() {
        val eventStore = createEventStore().metrizable()
        val cursorId = generateGlobalId()
        val targetAggregateId = namedAggregate.aggregateId(generateGlobalId())
        val otherAggregateId = "other_aggregate"
            .toNamedAggregate(namedAggregate.contextName)
            .aggregateId(generateGlobalId())
        eventStore.append(generateEventStream(targetAggregateId))
            .test()
            .verifyComplete()
        eventStore.append(generateEventStream(otherAggregateId))
            .test()
            .verifyComplete()

        eventStore.scanAggregateId(namedAggregate, afterId = cursorId, limit = 10)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().containsExactly(targetAggregateId)
            }
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldFilterBoundedContext() {
        val eventStore = createEventStore().metrizable()
        val cursorId = generateGlobalId()
        val targetAggregateId = namedAggregate.aggregateId(generateGlobalId())
        val otherAggregateId = namedAggregate.aggregateName
            .toNamedAggregate(contextName = "other_context")
            .aggregateId(generateGlobalId())
        eventStore.append(generateEventStream(targetAggregateId))
            .test()
            .verifyComplete()
        eventStore.append(generateEventStream(otherAggregateId))
            .test()
            .verifyComplete()

        eventStore.scanAggregateId(namedAggregate, afterId = cursorId, limit = 10)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().containsExactly(targetAggregateId)
            }
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldPreserveTenantId() {
        val eventStore = createEventStore().metrizable()
        val cursorId = generateGlobalId()
        val aggregateId = namedAggregate.aggregateId(generateGlobalId(), tenantId = "tenant-1")
        eventStore.append(generateEventStream(aggregateId))
            .test()
            .verifyComplete()

        eventStore.scanAggregateId(namedAggregate, afterId = cursorId, limit = 10)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().containsExactly(aggregateId)
            }
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldLimitResult() {
        val eventStore = createEventStore().metrizable()
        val cursorId = generateGlobalId()
        val aggregateIds = (1..20).map {
            namedAggregate.aggregateId(generateGlobalId())
        }
        aggregateIds.forEach { aggregateId ->
            eventStore.append(generateEventStream(aggregateId))
                .test()
                .verifyComplete()
        }

        eventStore.scanAggregateId(namedAggregate, afterId = cursorId, limit = 3)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().hasSize(3)
            }
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldReturnLexicographicalOrder() {
        val eventStore = createEventStore().metrizable()
        val idPrefix = generateGlobalId()
        val aggregateIds = listOf("003", "001", "004", "002").map {
            namedAggregate.aggregateId("$idPrefix-$it")
        }
        aggregateIds.forEach { aggregateId ->
            eventStore.append(generateEventStream(aggregateId))
                .test()
                .verifyComplete()
        }

        eventStore.scanAggregateId(namedAggregate, afterId = "$idPrefix-001", limit = 2)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().containsExactly(
                    namedAggregate.aggregateId("$idPrefix-002"),
                    namedAggregate.aggregateId("$idPrefix-003"),
                )
            }
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldReturnEachAggregateOnce() {
        val eventStore = createEventStore().metrizable()
        val cursorId = generateGlobalId()
        val aggregateId = namedAggregate.aggregateId(generateGlobalId())
        eventStore.append(generateEventStream(aggregateId))
            .test()
            .verifyComplete()
        eventStore.append(
            generateMockEventStream(
                aggregateId = aggregateId,
                aggregateVersion = Version.INITIAL_VERSION,
            )
        ).test()
            .verifyComplete()

        eventStore.scanAggregateId(namedAggregate, afterId = cursorId, limit = 10)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().containsExactly(aggregateId)
            }
            .verifyComplete()
    }

    companion object {
        const val DEFAULT_CONCURRENCY_TEST_ITERATIONS = 1000
        const val DEFAULT_CONCURRENCY_TEST_MAX_CONCURRENCY = 2

        @Deprecated("Use DEFAULT_CONCURRENCY_TEST_ITERATIONS.")
        const val TIMES = DEFAULT_CONCURRENCY_TEST_ITERATIONS

        @Deprecated("Use DEFAULT_CONCURRENCY_TEST_MAX_CONCURRENCY.")
        const val DEFAULT_PARALLELISM = DEFAULT_CONCURRENCY_TEST_MAX_CONCURRENCY
    }
}
