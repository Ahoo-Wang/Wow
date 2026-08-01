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

package me.ahoo.wow.mongo

import com.mongodb.bulk.BulkWriteInsert
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.bulk.BulkWriteUpsert
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.WriteModel
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.batch.BatchObservation
import me.ahoo.wow.infra.batch.BatchObserver
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MongoSnapshotStoreBatchTest {
    private val database = mockk<MongoDatabase>()
    private val collection = mockk<MongoCollection<Document>>()

    @Test
    fun `enabled batching should save with one unordered bulk request`() {
        val writes = slot<List<WriteModel<Document>>>()
        val bulkOptions = slot<BulkWriteOptions>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.bulkWrite(capture(writes), capture(bulkOptions))
        } answers {
            Mono.just(acknowledgedUpdateResult(firstArg<List<WriteModel<Document>>>().size))
        }
        val store = batchStore(maxSize = 2)

        try {
            Mono.zip(
                store.save(snapshot(id = "order-1", version = 3)).materialize(),
                store.save(snapshot(id = "order-2", version = 5)).materialize(),
            ).block()

            writes.captured.assert().hasSize(2)
            writes.captured.all { it is UpdateOneModel<*> }.assert().isTrue()
            bulkOptions.captured.isOrdered.assert().isFalse()
            verify(exactly = 1) {
                collection.bulkWrite(any<List<WriteModel<Document>>>(), any<BulkWriteOptions>())
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun `enabled batching should publish physical batch observations`() {
        val observations = CopyOnWriteArrayList<BatchObservation>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.bulkWrite(any<List<WriteModel<Document>>>(), any<BulkWriteOptions>())
        } returns Mono.just(acknowledgedUpdateResult(2))
        val store = MongoSnapshotStore(
            database = database,
            batchOptions = MongoSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
                maxPendingSaves = 2,
            ),
            observer = BatchObserver(observations::add),
        )

        try {
            Flux.merge(
                store.save(snapshot(id = "observed-order-1", version = 1)),
                store.save(snapshot(id = "observed-order-2", version = 1)),
            ).then()
                .test()
                .verifyComplete()
        } finally {
            store.close()
        }

        observations.filterIsInstance<BatchObservation.BatchWriteCompleted>()
            .single()
            .writtenItems.assert().isEqualTo(2)
    }

    @Test
    fun `same aggregate writes in one batch should coalesce to the newest snapshot`() {
        val writes = slot<List<WriteModel<Document>>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.bulkWrite(capture(writes), any<BulkWriteOptions>())
        } returns Mono.just(acknowledgedUpdateResult(1))
        val store = batchStore(maxSize = 2)

        try {
            Mono.zip(
                store.save(snapshot(id = "same-order", version = 5)).materialize(),
                store.save(snapshot(id = "same-order", version = 3)).materialize(),
            ).test()
                .assertNext { signals ->
                    signals.t1.isOnComplete.assert().isTrue()
                    signals.t2.isOnComplete.assert().isTrue()
                }
                .verifyComplete()

            writes.captured.assert().hasSize(1)
            val update = writes.captured.single() as UpdateOneModel<Document>
            update.updatePipeline.assert().hasSize(1)
        } finally {
            store.close()
        }
    }

    @Test
    fun `request failure should reach every caller unchanged`() {
        val failure = IllegalStateException("bulk unavailable")
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.bulkWrite(any<List<WriteModel<Document>>>(), any<BulkWriteOptions>())
        } returns Mono.error(failure)
        val store = batchStore(maxSize = 2)

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
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.bulkWrite(any<List<WriteModel<Document>>>(), any<BulkWriteOptions>())
        } answers {
            val result = acknowledgedUpdateResult(firstArg<List<WriteModel<Document>>>().size)
            when (requestCount.getAndIncrement()) {
                0 -> Mono.defer {
                    firstRequestStarted.countDown()
                    releaseFirstRequest.asMono().thenReturn(result)
                }

                else -> Mono.defer {
                    secondRequestStarted.countDown()
                    Mono.just(result)
                }
            }
        }
        val store = batchStore(
            maxSize = 2,
            maxDelay = Duration.ofHours(1),
            maxPendingSaves = 8,
            laneCount = 2,
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
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.bulkWrite(any<List<WriteModel<Document>>>(), any<BulkWriteOptions>())
        } answers {
            Mono.just(acknowledgedUpdateResult(firstArg<List<WriteModel<Document>>>().size))
        }
        val store = batchStore(
            maxSize = 8,
            maxDelay = Duration.ofSeconds(30),
            maxPendingSaves = 8,
        )
        val result = store.save(snapshot(id = "order-close", version = 4))
            .materialize()
            .toFuture()

        store.close()

        result.get(1, TimeUnit.SECONDS)!!.isOnComplete.assert().isTrue()
        verify(exactly = 1) {
            collection.bulkWrite(any<List<WriteModel<Document>>>(), any<BulkWriteOptions>())
        }
    }

    @Test
    fun `save after close should be rejected`() {
        val store = batchStore(maxSize = 2)
        store.close()

        store.save(snapshot(id = "order-closed", version = 2))
            .test()
            .expectErrorMatches {
                it is IllegalStateException &&
                    it.message == "MongoSnapshotStore is closed."
            }
            .verify()
    }

    @Test
    fun `overflow and close timeout should map to snapshot store errors`() {
        val requestStarted = CountDownLatch(1)
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.bulkWrite(any<List<WriteModel<Document>>>(), any<BulkWriteOptions>())
        } returns Mono.defer {
            requestStarted.countDown()
            Mono.never()
        }
        val options = MongoSnapshotStoreBatchOptions(
            enabled = true,
            maxSize = 2,
            maxDelay = Duration.ofHours(1),
            maxPendingSaves = 2,
        )
        val saver = BatchMongoSnapshotSaver(
            database = database,
            options = options,
            closeTimeout = Duration.ofMillis(10),
        )
        val first = saver.save(snapshot("order-timeout-1", 1)).materialize().toFuture()
        val second = saver.save(snapshot("order-timeout-2", 1)).materialize().toFuture()
        requestStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

        saver.save(snapshot("order-overflow", 1))
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(MongoSnapshotStoreBatchOverflowException::class.java)
                (error as MongoSnapshotStoreBatchOverflowException)
                    .maxPendingSaves.assert().isEqualTo(2)
            }
            .verify()

        val closeError = assertThrows<MongoSnapshotStoreBatchCloseTimeoutException> {
            saver.close()
        }
        closeError.timeout.assert().isEqualTo(Duration.ofMillis(10))
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        assertThrows<MongoSnapshotStoreBatchCloseTimeoutException> {
            saver.close()
        }.assert().isSameAs(closeError)
    }

    @Test
    fun `batch saver close timeout should be positive`() {
        assertThrows<IllegalArgumentException> {
            BatchMongoSnapshotSaver(
                database = database,
                options = MongoSnapshotStoreBatchOptions(),
                closeTimeout = Duration.ZERO,
            )
        }
    }

    private fun batchStore(
        maxSize: Int,
        maxDelay: Duration = Duration.ofSeconds(1),
        maxPendingSaves: Int = maxSize,
        laneCount: Int = 1,
    ): MongoSnapshotStore {
        return MongoSnapshotStore(
            database = database,
            batchOptions = MongoSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = maxSize,
                maxDelay = maxDelay,
                maxPendingSaves = maxPendingSaves,
                laneCount = laneCount,
            ),
        )
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

    private fun acknowledgedUpdateResult(operationCount: Int): BulkWriteResult {
        return BulkWriteResult.acknowledged(
            0,
            operationCount,
            0,
            operationCount,
            emptyList<BulkWriteUpsert>(),
            emptyList<BulkWriteInsert>(),
        )
    }
}
