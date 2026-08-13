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

package me.ahoo.wow.elasticsearch.eventsourcing

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.OpType
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.BulkResponse
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import com.fasterxml.jackson.annotation.JsonProperty
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ElasticsearchEventStoreAppendTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val namedAggregate = MaterializedNamedAggregate("order-service", "order")

    @Test
    fun `disabled batching should use a single create request`() {
        val request = slot<IndexRequest<Map<String, Any?>>>()
        every { client.index(capture(request)) } returns Mono.just(mockk())
        val eventStream = eventStream("order-direct", aggregateVersion = 1)
        val eventStore = ElasticsearchEventStore(client)

        eventStore.append(eventStream)
            .test()
            .verifyComplete()
        eventStore.close()

        request.captured.opType().assert().isEqualTo(OpType.Create)
        request.captured.id().assert().isEqualTo(eventStream.toDocId())
        request.captured.routing().assert().isEqualTo(listOf("order-direct"))
        assertEventPresenceEncoded(checkNotNull(request.captured.document()))
        verify(exactly = 0) { client.bulk(any<BulkRequest>()) }
    }

    @Test
    fun `direct create conflict should preserve version conflict semantics`() {
        val failure = mockk<ElasticsearchException> {
            every { status() } returns 409
        }
        every { client.index(any<IndexRequest<Map<String, Any?>>>()) } returns Mono.error(failure)
        val eventStream = eventStream("order-conflict", aggregateVersion = 1)

        ElasticsearchEventStore(client)
            .append(eventStream)
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(EventVersionConflictException::class.java)
                (error as EventVersionConflictException).eventStream.assert().isSameAs(eventStream)
                error.cause.assert().isSameAs(failure)
            }
            .verify()
    }

    @Test
    fun `direct non conflict failure should remain unchanged`() {
        val failure = IllegalStateException("index unavailable")
        every {
            client.index(any<IndexRequest<Map<String, Any?>>>())
        } returns Mono.error(failure)

        ElasticsearchEventStore(client).use { eventStore ->
            eventStore.append(eventStream("order-direct-failure"))
                .test()
                .expectErrorMatches { it === failure }
                .verify()
        }
    }

    @Test
    fun `reserved namespace should fail direct append before client IO`() {
        val eventStore = ElasticsearchEventStore(client)

        assertThrows<IllegalArgumentException> {
            eventStore.append(reservedEventStream("reserved-direct"))
        }

        verify(exactly = 0) { client.index(any<IndexRequest<Map<String, Any?>>>()) }
        verify(exactly = 0) { client.bulk(any<BulkRequest>()) }
    }

    @Test
    fun `reserved namespace should fail batch append before client IO`() {
        val eventStore = ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxPendingAppends = 2,
            ),
        )

        try {
            eventStore.append(reservedEventStream("reserved-batch"))
                .test()
                .expectError(IllegalArgumentException::class.java)
                .verify()
            verify(exactly = 0) { client.bulk(any<BulkRequest>()) }
        } finally {
            eventStore.close()
        }
    }

    @Test
    fun `enabled batching should use one bulk create request`() {
        val request = slot<BulkRequest>()
        val firstEventStream = eventStream("order-1", aggregateVersion = 1)
        val secondEventStream = eventStream("order-2", aggregateVersion = 1)
        every { client.bulk(capture(request)) } returns Mono.just(
            bulkResponse(
                responseItem(firstEventStream),
                responseItem(secondEventStream),
            )
        )
        val eventStore = ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
                maxPendingAppends = 4,
            ),
        )

        Mono.zip(
            eventStore.append(firstEventStream).materialize(),
            eventStore.append(secondEventStream).materialize(),
        ).block()!!
        eventStore.close()

        request.captured.operations().assert().hasSize(2)
        request.captured.operations().all { it.isCreate }.assert().isTrue()
        request.captured.operations().forEach { operation ->
            assertEventPresenceEncoded(checkNotNull(operation.create<Map<String, Any?>>().document()))
        }
        verify(exactly = 0) { client.index(any<IndexRequest<Map<String, Any?>>>()) }
    }

    @Test
    fun `batch request failure should reach every caller unchanged`() {
        val failure = IllegalStateException("bulk unavailable")
        every { client.bulk(any<BulkRequest>()) } returns Mono.error(failure)
        val eventStore = ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
                maxPendingAppends = 2,
            ),
        )

        try {
            Flux.merge(
                eventStore.append(eventStream("order-failure-1")).materialize(),
                eventStore.append(eventStream("order-failure-2")).materialize(),
            ).collectList()
                .test()
                .assertNext { signals ->
                    signals.assert().hasSize(2)
                    signals.all { it.throwable === failure }.assert().isTrue()
                }
                .verifyComplete()
        } finally {
            eventStore.close()
        }
    }

    @Test
    fun `close should flush a partial batch and settle its caller`() {
        val eventStream = eventStream("order-close", aggregateVersion = 1)
        val registry = SimpleMeterRegistry()
        every { client.bulk(any<BulkRequest>()) } returns Mono.just(
            bulkResponse(responseItem(eventStream))
        )
        val eventStore = ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 8,
                maxDelay = Duration.ofSeconds(30),
                maxPendingAppends = 8,
            ),
            metrics = WowMetrics(registry),
        )
        val result = eventStore.append(eventStream)
            .materialize()
            .toFuture()

        eventStore.close()

        result.get(1, TimeUnit.SECONDS)!!.isOnComplete.assert().isTrue()
        verify(exactly = 1) { client.bulk(any<BulkRequest>()) }
        registry.get("wow.batch.write")
            .tag("coordinator", "ElasticsearchEventStore")
            .timer()
            .count()
            .assert()
            .isEqualTo(1)
    }

    @Test
    fun `append after close should be rejected`() {
        val eventStore = ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofMillis(1),
                maxPendingAppends = 2,
            ),
        )
        eventStore.close()

        eventStore.append(eventStream("order-closed"))
            .test()
            .expectErrorMatches {
                it is IllegalStateException &&
                    it.message == "ElasticsearchEventStore is closed."
            }
            .verify()
    }

    @Test
    fun `concurrent appends should all settle through bounded bulk requests`() {
        val batchCount = AtomicInteger()
        val itemCount = AtomicInteger()
        every { client.bulk(any<BulkRequest>()) } answers {
            val request = firstArg<BulkRequest>()
            batchCount.incrementAndGet()
            itemCount.addAndGet(request.operations().size)
            Mono.just(
                BulkResponse.of { response ->
                    response.errors(false)
                        .items(
                            request.operations().map { operation ->
                                val create = operation.create<Map<String, Any?>>()
                                BulkResponseItem.of { item ->
                                    item.operationType(OperationType.Create)
                                        .index(create.index())
                                        .id(create.id())
                                        .status(201)
                                }
                            }
                        )
                        .took(1)
                }
            )
        }
        val eventStore = ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 8,
                maxDelay = Duration.ofMillis(2),
                maxPendingAppends = 64,
            ),
        )

        Flux.range(0, 64)
            .flatMap(
                { index ->
                    eventStore.append(
                        eventStream("order-concurrent-$index", aggregateVersion = 1)
                    )
                },
                32,
            )
            .then()
            .block(Duration.ofSeconds(5))
        eventStore.close()

        itemCount.get().assert().isEqualTo(64)
        batchCount.get().assert().isLessThan(64)
    }

    @Test
    fun `configured lanes should allow concurrent bulk requests for different aggregates`() {
        val requestsStarted = CountDownLatch(2)
        val releaseRequests = Sinks.one<Void>()
        val inFlightRequests = AtomicInteger()
        val maxInFlightRequests = AtomicInteger()
        every { client.bulk(any<BulkRequest>()) } answers {
            val request = firstArg<BulkRequest>()
            Mono.defer {
                val inFlight = inFlightRequests.incrementAndGet()
                maxInFlightRequests.accumulateAndGet(inFlight, ::maxOf)
                requestsStarted.countDown()
                releaseRequests.asMono()
                    .thenReturn(bulkResponse(request))
                    .doFinally {
                        inFlightRequests.decrementAndGet()
                    }
            }
        }
        val streams = eventStreamsInTwoLanes()
        val eventStore = ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofHours(1),
                maxPendingAppends = 8,
                laneCount = 2,
            ),
        )
        val result = Flux.fromIterable(streams)
            .flatMap(eventStore::append, streams.size)
            .then()
            .toFuture()

        try {
            requestsStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            maxInFlightRequests.get().assert().isEqualTo(2)

            releaseRequests.tryEmitEmpty().isSuccess.assert().isTrue()
            result.get(1, TimeUnit.SECONDS)
        } finally {
            eventStore.close()
        }
    }

    @Test
    fun `batch overflow and close timeout should map to EventStore errors`() {
        val requestStarted = CountDownLatch(1)
        every { client.bulk(any<BulkRequest>()) } returns Mono.defer {
            requestStarted.countDown()
            Mono.never()
        }
        val options = ElasticsearchEventStoreBatchOptions(
            enabled = true,
            maxSize = 2,
            maxDelay = Duration.ofHours(1),
            maxPendingAppends = 2,
        )
        val appender = BatchElasticsearchEventStreamAppender(
            elasticsearchClient = client,
            refreshPolicy = co.elastic.clients.elasticsearch._types.Refresh.False,
            options = options,
            closeTimeout = Duration.ofMillis(10),
        )
        val first = appender.append(eventStream("order-timeout-1")).materialize().toFuture()
        val second = appender.append(eventStream("order-timeout-2")).materialize().toFuture()
        requestStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

        appender.append(eventStream("order-overflow"))
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(ElasticsearchEventStoreBatchOverflowException::class.java)
                (error as ElasticsearchEventStoreBatchOverflowException)
                    .maxPendingAppends.assert().isEqualTo(2)
            }
            .verify()

        val closeError = assertThrows<ElasticsearchEventStoreBatchCloseTimeoutException> {
            appender.close()
        }
        closeError.timeout.assert().isEqualTo(Duration.ofMillis(10))
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        assertThrows<ElasticsearchEventStoreBatchCloseTimeoutException> {
            appender.close()
        }.assert().isSameAs(closeError)
    }

    @Test
    fun `batch appender close timeout should be positive`() {
        assertThrows<IllegalArgumentException> {
            BatchElasticsearchEventStreamAppender(
                elasticsearchClient = client,
                refreshPolicy = co.elastic.clients.elasticsearch._types.Refresh.False,
                options = ElasticsearchEventStoreBatchOptions(),
                closeTimeout = Duration.ZERO,
            )
        }
    }

    @Test
    fun `batch options should validate capacity`() {
        assertThrows<IllegalArgumentException> {
            ElasticsearchEventStoreBatchOptions(
                maxSize = 2,
                maxPendingAppends = 1,
            )
        }
    }

    private fun eventStream(
        id: String,
        aggregateVersion: Int = 0,
    ): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(id),
            aggregateVersion = aggregateVersion,
            eventCount = 1,
        )
    }

    private fun reservedEventStream(id: String): DomainEventStream =
        MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(id),
            eventCount = 1,
            createdEventSupplier = { ReservedEvent("collision") },
        )

    private fun bulkResponse(
        vararg items: BulkResponseItem,
    ): BulkResponse {
        return BulkResponse.of {
            it.errors(false)
                .items(items.toList())
                .took(1)
        }
    }

    private fun bulkResponse(request: BulkRequest): BulkResponse {
        return bulkResponse(
            *request.operations().map { operation ->
                val create = operation.create<Map<String, Any?>>()
                BulkResponseItem.of { item ->
                    item.operationType(OperationType.Create)
                        .index(create.index())
                        .id(create.id())
                        .status(201)
                }
            }.toTypedArray()
        )
    }

    private fun eventStreamsInTwoLanes(): List<DomainEventStream> {
        val laneRepresentatives = (1..100)
            .map { index -> eventStream("lane-$index") }
            .groupBy { eventStream -> Math.floorMod(eventStream.aggregateId.hashCode(), 2) }
            .values
            .map { eventStreams -> eventStreams.first() }
        laneRepresentatives.assert().hasSize(2)
        return laneRepresentatives.flatMap { eventStream ->
            listOf(
                eventStream,
                eventStream(eventStream.aggregateId.id, aggregateVersion = 1),
            )
        }
    }

    private fun responseItem(eventStream: DomainEventStream): BulkResponseItem {
        return BulkResponseItem.of {
            it.operationType(OperationType.Create)
                .index(eventStream.aggregateId.toEventStreamIndexName())
                .id(eventStream.toDocId())
                .status(201)
        }
    }
}

private data class ReservedEvent(
    @field:JsonProperty("__wow_query")
    val reserved: String,
)

private fun assertEventPresenceEncoded(value: Any?) {
    when (value) {
        is Map<*, *> -> {
            value.containsKey("__wow_query").assert().isTrue()
            value.entries
                .filterNot { (key, _) -> key == "__wow_query" }
                .forEach { (_, nested) -> assertEventPresenceEncoded(nested) }
        }

        is Iterable<*> -> value.forEach(::assertEventPresenceEncoded)
        is Array<*> -> value.forEach(::assertEventPresenceEncoded)
    }
}
