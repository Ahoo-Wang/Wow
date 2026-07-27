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
import co.elastic.clients.elasticsearch._types.VersionType
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.BulkResponse
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.elasticsearch.core.UpdateRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
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

class ElasticsearchSnapshotStoreSaveTest {
    private val client = mockk<ReactiveElasticsearchClient>()

    @Test
    fun `disabled batching should use strict external version index`() {
        val request = slot<IndexRequest<Map<String, Any?>>>()
        every { client.index(capture(request)) } returns Mono.just(mockk())
        val snapshot = snapshot(id = "order-direct", version = 3)

        ElasticsearchSnapshotStore(client)
            .save(snapshot)
            .test()
            .verifyComplete()

        request.captured.id().assert().isEqualTo("order-direct")
        request.captured.version().assert().isEqualTo(3L)
        request.captured.versionType().assert().isEqualTo(VersionType.External)
        verify(exactly = 0) { client.bulk(any<BulkRequest>()) }
    }

    @Test
    fun `direct external version conflict should use source version guarded update`() {
        val conflict = mockk<ElasticsearchException> {
            every { status() } returns 409
        }
        every { client.index(any<IndexRequest<Map<String, Any?>>>()) } returns Mono.error(conflict)
        val updateRequest = slot<UpdateRequest<Map<String, Any?>, Map<String, Any?>>>()

        @Suppress("UNCHECKED_CAST")
        val documentClass = Map::class.java as Class<Map<String, Any?>>
        every {
            client.update(
                capture(updateRequest),
                documentClass,
            )
        } returns Mono.just(mockk())

        ElasticsearchSnapshotStore(client)
            .save(snapshot(id = "order-stale", version = 2))
            .test()
            .verifyComplete()

        updateRequest.captured.id().assert().isEqualTo("order-stale")
        updateRequest.captured.refresh().assert().isEqualTo(
            co.elastic.clients.elasticsearch._types.Refresh.True
        )
        updateRequest.captured.retryOnConflict().assert().isNotNull()
        checkNotNull(updateRequest.captured.upsert())["version"].assert().isEqualTo(2)
        checkNotNull(updateRequest.captured.script())
            .params().keys.assert().contains("version", "snapshot")
    }

    @Test
    fun `direct conflict fallback failure should not be swallowed`() {
        val conflict = mockk<ElasticsearchException> {
            every { status() } returns 409
        }
        val fallbackFailure = IllegalStateException("stored snapshot version is unavailable")
        every { client.index(any<IndexRequest<Map<String, Any?>>>()) } returns Mono.error(conflict)

        @Suppress("UNCHECKED_CAST")
        val documentClass = Map::class.java as Class<Map<String, Any?>>
        every {
            client.update(
                any<UpdateRequest<Map<String, Any?>, Map<String, Any?>>>(),
                documentClass,
            )
        } returns Mono.error(fallbackFailure)

        ElasticsearchSnapshotStore(client)
            .save(snapshot(id = "order-invalid-legacy", version = 2))
            .test()
            .expectErrorMatches { it === fallbackFailure }
            .verify()
    }

    @Test
    fun `direct non conflict failures should not invoke version conflict fallback`() {
        val ordinaryFailure = IllegalStateException("index unavailable")
        val serviceUnavailable = mockk<ElasticsearchException> {
            every { status() } returns 503
        }
        every {
            client.index(any<IndexRequest<Map<String, Any?>>>())
        } returnsMany listOf(
            Mono.error(ordinaryFailure),
            Mono.error(serviceUnavailable),
        )

        ElasticsearchSnapshotStore(client).use { store ->
            store.save(snapshot(id = "order-ordinary-failure", version = 2))
                .test()
                .expectErrorMatches { it === ordinaryFailure }
                .verify()
            store.save(snapshot(id = "order-service-unavailable", version = 2))
                .test()
                .expectErrorMatches { it === serviceUnavailable }
                .verify()
        }

        @Suppress("UNCHECKED_CAST")
        val documentClass = Map::class.java as Class<Map<String, Any?>>
        verify(exactly = 0) {
            client.update(
                any<UpdateRequest<Map<String, Any?>, Map<String, Any?>>>(),
                documentClass,
            )
        }
    }

    @Test
    fun `enabled batching should save with one bulk request`() {
        val first = snapshot(id = "order-1", version = 3)
        val second = snapshot(id = "order-2", version = 5)
        val request = slot<BulkRequest>()
        every { client.bulk(capture(request)) } returns Mono.just(
            bulkResponse(
                responseItem(first),
                responseItem(second),
            )
        )
        val store = ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
                maxPendingSaves = 4,
            ),
        )

        Mono.zip(
            store.save(first).materialize(),
            store.save(second).materialize(),
        ).block()!!
        store.close()

        request.captured.operations().assert().hasSize(2)
        request.captured.operations().all { it.isIndex }.assert().isTrue()
        verify(exactly = 0) { client.index(any<IndexRequest<Map<String, Any?>>>()) }
    }

    @Test
    fun `batch request failure should reach every caller unchanged`() {
        val failure = IllegalStateException("bulk unavailable")
        every { client.bulk(any<BulkRequest>()) } returns Mono.error(failure)
        val store = ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
                maxPendingSaves = 2,
            ),
        )

        try {
            Flux.merge(
                store.save(snapshot("order-failure-1", 1)).materialize(),
                store.save(snapshot("order-failure-2", 1)).materialize(),
            ).collectList()
                .test()
                .assertNext { signals ->
                    signals.assert().hasSize(2)
                    signals.all { it.throwable === failure }.assert().isTrue()
                }
                .verifyComplete()
        } finally {
            store.close()
        }
    }

    @Test
    fun `configured lanes should keep the same snapshot key batches serial`() {
        val firstRequestStarted = CountDownLatch(1)
        val secondRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = Sinks.one<Void>()
        val requestCount = AtomicInteger()
        every { client.bulk(any<BulkRequest>()) } answers {
            val request = firstArg<BulkRequest>()
            val response = bulkResponse(request)
            when (requestCount.getAndIncrement()) {
                0 -> Mono.defer {
                    firstRequestStarted.countDown()
                    releaseFirstRequest.asMono().thenReturn(response)
                }

                else -> Mono.defer {
                    secondRequestStarted.countDown()
                    Mono.just(response)
                }
            }
        }
        val store = ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofHours(1),
                maxPendingSaves = 8,
                laneCount = 2,
            ),
        )
        val result = Flux.range(1, 4)
            .flatMap(
                { version -> store.save(snapshot("same-order", version)) },
                4,
            )
            .then()
            .toFuture()

        try {
            firstRequestStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            secondRequestStarted.await(50, TimeUnit.MILLISECONDS).assert().isFalse()

            releaseFirstRequest.tryEmitEmpty().isSuccess.assert().isTrue()
            result.get(1, TimeUnit.SECONDS)

            secondRequestStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            requestCount.get().assert().isEqualTo(2)
        } finally {
            store.close()
        }
    }

    @Test
    fun `close should flush a partial snapshot batch`() {
        val snapshot = snapshot(id = "order-close", version = 4)
        every { client.bulk(any<BulkRequest>()) } returns Mono.just(
            bulkResponse(responseItem(snapshot))
        )
        val store = ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 8,
                maxDelay = Duration.ofSeconds(30),
                maxPendingSaves = 8,
            ),
        )
        val result = store.save(snapshot).materialize().toFuture()

        store.close()

        result.get(1, TimeUnit.SECONDS)!!.isOnComplete.assert().isTrue()
        verify(exactly = 1) { client.bulk(any<BulkRequest>()) }
    }

    @Test
    fun `save after close should be rejected`() {
        val store = ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofMillis(1),
                maxPendingSaves = 2,
            ),
        )
        store.close()

        store.save(snapshot(id = "order-closed", version = 2))
            .test()
            .expectErrorMatches {
                it is IllegalStateException &&
                    it.message == "ElasticsearchSnapshotStore is closed."
            }
            .verify()
    }

    @Test
    fun `batch overflow and close timeout should map to SnapshotStore errors`() {
        val requestStarted = CountDownLatch(1)
        every { client.bulk(any<BulkRequest>()) } returns Mono.defer {
            requestStarted.countDown()
            Mono.never()
        }
        val options = ElasticsearchSnapshotStoreBatchOptions(
            enabled = true,
            maxSize = 2,
            maxDelay = Duration.ofHours(1),
            maxPendingSaves = 2,
        )
        val saver = BatchElasticsearchSnapshotSaver(
            elasticsearchClient = client,
            refreshPolicy = co.elastic.clients.elasticsearch._types.Refresh.False,
            options = options,
            closeTimeout = Duration.ofMillis(10),
        )
        val first = saver.save(snapshot("order-timeout-1", 1)).materialize().toFuture()
        val second = saver.save(snapshot("order-timeout-2", 1)).materialize().toFuture()
        requestStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

        saver.save(snapshot("order-overflow", 1))
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(ElasticsearchSnapshotStoreBatchOverflowException::class.java)
                (error as ElasticsearchSnapshotStoreBatchOverflowException)
                    .maxPendingSaves.assert().isEqualTo(2)
            }
            .verify()

        val closeError = assertThrows<ElasticsearchSnapshotStoreBatchCloseTimeoutException> {
            saver.close()
        }
        closeError.timeout.assert().isEqualTo(Duration.ofMillis(10))
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        assertThrows<ElasticsearchSnapshotStoreBatchCloseTimeoutException> {
            saver.close()
        }.assert().isSameAs(closeError)
    }

    @Test
    fun `batch saver close timeout should be positive`() {
        assertThrows<IllegalArgumentException> {
            BatchElasticsearchSnapshotSaver(
                elasticsearchClient = client,
                refreshPolicy = co.elastic.clients.elasticsearch._types.Refresh.False,
                options = ElasticsearchSnapshotStoreBatchOptions(),
                closeTimeout = Duration.ZERO,
            )
        }
    }

    private fun snapshot(
        id: String,
        version: Int,
    ): SimpleSnapshot<MockStateAggregate> {
        require(version > 0)
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(id)
        val aggregate: StateAggregate<MockStateAggregate> = ConstructorStateAggregateFactory.create(
            metadata = MOCK_AGGREGATE_METADATA.state,
            aggregateId = aggregateId,
        )
        aggregate.onSourcing(
            listOf(MockAggregateCreated(generateGlobalId())).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = aggregate.version,
            )
        )
        repeat(version - 1) {
            aggregate.onSourcing(
                listOf(MockAggregateChanged(generateGlobalId())).toDomainEventStream(
                    upstream = GivenInitializationCommand(aggregateId),
                    aggregateVersion = aggregate.version,
                )
            )
        }
        aggregate.version.assert().isEqualTo(version)
        return SimpleSnapshot(
            delegate = aggregate,
            snapshotTime = version.toLong(),
        )
    }

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
                val index = operation.index<Map<String, Any?>>()
                BulkResponseItem.of { item ->
                    item.operationType(OperationType.Index)
                        .index(index.index())
                        .id(index.id())
                        .status(200)
                }
            }.toTypedArray()
        )
    }

    private fun responseItem(
        snapshot: SimpleSnapshot<MockStateAggregate>,
    ): BulkResponseItem {
        return BulkResponseItem.of {
            it.operationType(OperationType.Index)
                .index(snapshot.aggregateId.toSnapshotIndexName())
                .id(snapshot.aggregateId.id)
                .status(200)
        }
    }
}
