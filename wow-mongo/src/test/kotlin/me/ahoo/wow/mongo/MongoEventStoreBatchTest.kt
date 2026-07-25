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

import com.mongodb.MongoBulkWriteException
import com.mongodb.ServerAddress
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteInsert
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.bulk.BulkWriteUpsert
import com.mongodb.bulk.WriteConcernError
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.result.InsertManyResult
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.exception.recoverable
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MongoEventStoreBatchTest {
    private val namedAggregate = MaterializedNamedAggregate("order-service", "order")

    @Test
    fun `concurrent appends should use one unordered insert many`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val documents = slot<List<Document>>()
        val insertOptions = slot<InsertManyOptions>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(capture(documents), capture(insertOptions))
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            StepVerifier.create(
                Flux.merge(
                    eventStore.append(eventStream("order-1")),
                    eventStore.append(eventStream("order-2")),
                ).then()
            ).verifyComplete()
        }

        documents.captured.assert().hasSize(2)
        insertOptions.captured.isOrdered.assert().isFalse()
        verify(exactly = 1) { collection.insertMany(any<List<Document>>(), any()) }
        verify(exactly = 0) { collection.insertOne(any<Document>()) }
    }

    @Test
    fun `max delay should flush a partial batch`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))

        MongoEventStore(database, batchOptions(maxSize = 8)).use { eventStore ->
            StepVerifier.create(eventStore.append(eventStream("order-1")))
                .verifyComplete()
        }

        verify(exactly = 1) { collection.insertMany(any<List<Document>>(), any()) }
    }

    @Test
    fun `successive batches should wait for downstream write capacity`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val inFlightWrites = AtomicInteger()
        val maxInFlightWrites = AtomicInteger()
        val batchSizes = mutableListOf<Int>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            batchSizes += firstArg<List<Document>>().size
            Mono.defer {
                val inFlight = inFlightWrites.incrementAndGet()
                maxInFlightWrites.updateAndGet { current -> maxOf(current, inFlight) }
                Mono.delay(Duration.ofMillis(50))
                    .thenReturn(InsertManyResult.acknowledged(emptyMap()))
                    .doOnSuccess {
                        inFlightWrites.decrementAndGet()
                    }
            }
        }

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            StepVerifier.create(
                Flux.range(1, 8)
                    .flatMap { index ->
                        eventStore.append(eventStream("order-$index"))
                    }.then()
            ).verifyComplete()
        }

        batchSizes.sum().assert().isEqualTo(8)
        batchSizes.all { it in 1..2 }.assert().isTrue()
        maxInFlightWrites.get().assert().isEqualTo(1)
    }

    @Test
    fun `multiple producer threads should enqueue safely`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))

        MongoEventStore(database, batchOptions(maxSize = 128)).use { eventStore ->
            StepVerifier.create(
                Flux.range(1, 128)
                    .flatMap { index ->
                        eventStore.append(eventStream("order-$index"))
                            .subscribeOn(Schedulers.parallel())
                    }.then()
            ).verifyComplete()
        }

        verify(atLeast = 1) { collection.insertMany(any<List<Document>>(), any()) }
    }

    @Test
    fun `unordered bulk error should fail only the matching append`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val bulkError = MongoBulkWriteException(
            BulkWriteResult.acknowledged(
                1,
                0,
                0,
                0,
                emptyList<BulkWriteUpsert>(),
                emptyList<BulkWriteInsert>(),
            ),
            listOf(
                BulkWriteError(
                    11000,
                    "duplicate key - ${AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME}",
                    BsonDocument(),
                    0,
                )
            ),
            null,
            ServerAddress("localhost"),
            emptySet(),
        )
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.error(bulkError)

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            StepVerifier.create(
                Flux.merge(
                    eventStore.append(eventStream("order-1", aggregateVersion = 1))
                        .thenReturn("unexpected-first-success")
                        .onErrorResume(EventVersionConflictException::class.java) {
                            Mono.just("first-conflict")
                        },
                    eventStore.append(eventStream("order-2", aggregateVersion = 1))
                        .thenReturn("second-success"),
                ).collectList()
            ).assertNext { results ->
                results.assert().contains("first-conflict", "second-success")
            }.verifyComplete()
        }
    }

    @Test
    fun `write error should take precedence over write concern error for matching append`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val bulkError = MongoBulkWriteException(
            BulkWriteResult.acknowledged(
                1,
                0,
                0,
                0,
                emptyList<BulkWriteUpsert>(),
                emptyList<BulkWriteInsert>(),
            ),
            listOf(
                BulkWriteError(
                    11000,
                    "duplicate key - ${AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME}",
                    BsonDocument(),
                    0,
                )
            ),
            WriteConcernError(
                64,
                "WriteConcernFailed",
                "write concern failed",
                BsonDocument(),
            ),
            ServerAddress("localhost"),
            emptySet(),
        )
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.error(bulkError)

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            val signals = Mono.zip(
                eventStore.append(eventStream("order-1", aggregateVersion = 1)).materialize(),
                eventStore.append(eventStream("order-2", aggregateVersion = 1)).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isInstanceOf(EventVersionConflictException::class.java)
            signals.t2.throwable.assert().isSameAs(bulkError)
        }
    }

    @Test
    fun `recoverable write concern error should fail every uncertain append`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val bulkError = MongoBulkWriteException(
            BulkWriteResult.acknowledged(
                0,
                0,
                0,
                0,
                emptyList<BulkWriteUpsert>(),
                emptyList<BulkWriteInsert>(),
            ),
            emptyList(),
            WriteConcernError(
                91,
                "ShutdownInProgress",
                "shutdown in progress",
                BsonDocument(),
            ),
            ServerAddress("localhost"),
            emptySet(),
        )
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.error(bulkError)

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            val signals = Mono.zip(
                eventStore.append(eventStream("order-1")).materialize(),
                eventStore.append(eventStream("order-2")).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isInstanceOf(RecoverableMongoBulkWriteException::class.java)
            signals.t2.throwable.assert().isSameAs(signals.t1.throwable)
            val recoverableError = signals.t1.throwable as RecoverableMongoBulkWriteException
            recoverableError.error.code.assert().isEqualTo(91)
            recoverableError.cause.assert().isSameAs(bulkError)
        }
    }

    @Test
    fun `inconsistent bulk result should not infer a successful append`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val bulkError = MongoBulkWriteException(
            BulkWriteResult.acknowledged(
                0,
                0,
                0,
                0,
                emptyList<BulkWriteUpsert>(),
                emptyList<BulkWriteInsert>(),
            ),
            listOf(
                BulkWriteError(
                    11000,
                    "duplicate key - ${AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME}",
                    BsonDocument(),
                    0,
                )
            ),
            null,
            ServerAddress("localhost"),
            emptySet(),
        )
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.error(bulkError)

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            val signals = Mono.zip(
                eventStore.append(eventStream("order-1", aggregateVersion = 1)).materialize(),
                eventStore.append(eventStream("order-2", aggregateVersion = 1)).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isSameAs(bulkError)
            signals.t2.throwable.assert().isSameAs(bulkError)
        }
    }

    @Test
    fun `synchronous batch construction error should not terminate later batches`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val synchronousError = IllegalStateException("synchronous insertMany failure")
        var attempts = 0
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            if (attempts++ == 0) {
                throw synchronousError
            }
            Mono.just(InsertManyResult.acknowledged(emptyMap()))
        }

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            val failedSignals = Mono.zip(
                eventStore.append(eventStream("order-1")).materialize(),
                eventStore.append(eventStream("order-2")).materialize(),
            ).block()!!
            failedSignals.t1.throwable.assert().isSameAs(synchronousError)
            failedSignals.t2.throwable.assert().isSameAs(synchronousError)

            StepVerifier.create(
                Flux.merge(
                    eventStore.append(eventStream("order-3")),
                    eventStore.append(eventStream("order-4")),
                ).then()
            ).verifyComplete()
        }
    }

    @Test
    fun `unacknowledged batch result should fail that batch without terminating later batches`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        var attempts = 0
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            if (attempts++ == 0) {
                Mono.just(InsertManyResult.unacknowledged())
            } else {
                Mono.just(InsertManyResult.acknowledged(emptyMap()))
            }
        }

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            val failedSignals = Mono.zip(
                eventStore.append(eventStream("order-1")).materialize(),
                eventStore.append(eventStream("order-2")).materialize(),
            ).block()!!

            failedSignals.t1.throwable.assert().isInstanceOf(IllegalStateException::class.java)
            failedSignals.t1.throwable?.message.assert()
                .isEqualTo("MongoDB did not acknowledge the event stream batch append.")
            failedSignals.t2.throwable.assert().isInstanceOf(IllegalStateException::class.java)
            failedSignals.t2.throwable?.message.assert()
                .isEqualTo("MongoDB did not acknowledge the event stream batch append.")

            StepVerifier.create(
                Flux.merge(
                    eventStore.append(eventStream("order-3")),
                    eventStore.append(eventStream("order-4")),
                ).then()
            ).verifyComplete()
        }

        verify(exactly = 2) { collection.insertMany(any<List<Document>>(), any()) }
    }

    @Test
    fun `document conversion error should fail only the matching append`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val conversionError = IllegalArgumentException("document conversion failure")
        val invalidStream = eventStream("order-invalid")
        val validStream1 = eventStream("order-valid-1")
        val validStream2 = eventStream("order-valid-2")
        val validDocument1 = validStream1.toDocument()
        val validDocument2 = validStream2.toDocument()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))
        mockkStatic("me.ahoo.wow.mongo.DocumentsKt")

        try {
            every { invalidStream.toDocument() } throws conversionError
            every { validStream1.toDocument() } returns validDocument1
            every { validStream2.toDocument() } returns validDocument2

            MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
                StepVerifier.create(eventStore.append(invalidStream))
                    .expectErrorMatches {
                        it === conversionError
                    }.verify()

                StepVerifier.create(
                    Flux.merge(
                        eventStore.append(validStream1),
                        eventStore.append(validStream2),
                    ).then()
                ).verifyComplete()
            }
        } finally {
            unmockkStatic("me.ahoo.wow.mongo.DocumentsKt")
        }
    }

    @Test
    fun `request construction error should not leak pending capacity`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val constructionError = IllegalArgumentException("collection name failure")
        val invalidStream = mockk<DomainEventStream>()
        val validStream1 = eventStream("order-valid-1")
        val validStream2 = eventStream("order-valid-2")
        val validDocument1 = validStream1.toDocument()
        val validDocument2 = validStream2.toDocument()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))
        mockkStatic("me.ahoo.wow.mongo.DocumentsKt")

        try {
            every { invalidStream.toDocument() } returns Document()
            every { invalidStream.aggregateName } throws constructionError
            every { validStream1.toDocument() } returns validDocument1
            every { validStream2.toDocument() } returns validDocument2

            MongoEventStoreBatcher(
                database = database,
                options = MongoEventStoreBatchOptions(
                    enabled = true,
                    maxSize = 2,
                    maxDelay = Duration.ofHours(1),
                    maxPendingAppends = 2,
                ),
            ).use { batcher ->
                StepVerifier.create(batcher.append(invalidStream))
                    .expectErrorMatches {
                        it === constructionError
                    }.verify()

                StepVerifier.create(
                    Flux.merge(
                        batcher.append(validStream1),
                        batcher.append(validStream2),
                    ).then()
                ).verifyComplete()
            }
        } finally {
            unmockkStatic("me.ahoo.wow.mongo.DocumentsKt")
        }
    }

    @Test
    fun `cancelling a queued append should skip its MongoDB write`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val writtenDocuments = mutableListOf<Document>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            writtenDocuments += firstArg<List<Document>>()
            Mono.just(InsertManyResult.acknowledged(emptyMap()))
        }

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            val cancelled = eventStore.append(eventStream("order-cancelled")).subscribe()
            cancelled.dispose()

            StepVerifier.create(
                Flux.merge(
                    eventStore.append(eventStream("order-active-1")),
                    eventStore.append(eventStream("order-active-2")),
                ).then()
            ).verifyComplete()
        }

        writtenDocuments.assert().hasSize(2)
    }

    @Test
    fun `a fully cancelled batch should not call MongoDB`() {
        val database = mockk<MongoDatabase>()
        val batcher = MongoEventStoreBatcher(
            database = database,
            options = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofHours(1),
                maxPendingAppends = 2,
            ),
        )

        val cancelled = batcher.append(eventStream("order-cancelled")).subscribe()
        cancelled.dispose()
        batcher.close()

        verify(exactly = 0) { database.getCollection(any<String>()) }
    }

    @Test
    fun `pending capacity should reject overflow without terminating the batcher`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val insertResult = Sinks.one<InsertManyResult>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns insertResult.asMono()
        val options = MongoEventStoreBatchOptions(
            enabled = true,
            maxSize = 2,
            maxDelay = Duration.ofHours(1),
            maxPendingAppends = 2,
        )

        MongoEventStore(database, options).use { eventStore ->
            val first = eventStore.append(eventStream("order-1")).toFuture()
            val second = eventStore.append(eventStream("order-2")).toFuture()

            StepVerifier.create(eventStore.append(eventStream("order-overflow")))
                .expectErrorSatisfies { error ->
                    error.assert().isInstanceOf(MongoEventStoreBatchOverflowException::class.java)
                    val overflow = error as MongoEventStoreBatchOverflowException
                    overflow.maxPendingAppends.assert().isEqualTo(2)
                    overflow.message.assert().isEqualTo(
                        "MongoEventStore batch pending append capacity[2] has been exhausted."
                    )
                    overflow.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
                }
                .verify()

            insertResult.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                .assert().isEqualTo(Sinks.EmitResult.OK)
            first.join()
            second.join()

            StepVerifier.create(
                Flux.merge(
                    eventStore.append(eventStream("order-3")),
                    eventStore.append(eventStream("order-4")),
                ).then()
            ).verifyComplete()
        }
    }

    @Test
    fun `concurrent admission should never exceed pending capacity`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val insertResult = Sinks.one<InsertManyResult>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns insertResult.asMono()
        val pendingCapacity = 16
        val batcher = MongoEventStoreBatcher(
            database = database,
            options = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = pendingCapacity,
                maxDelay = Duration.ofHours(1),
                maxPendingAppends = pendingCapacity,
            ),
        )
        val executor = Executors.newFixedThreadPool(32)
        val start = CountDownLatch(1)

        try {
            val submissions = (0 until 128).map { index ->
                executor.submit(
                    java.util.concurrent.Callable {
                        start.await()
                        batcher.append(eventStream("order-$index")).toFuture()
                    }
                )
            }
            start.countDown()
            val appendResults = submissions.map {
                it.get(1, TimeUnit.SECONDS)
            }
            val accepted = appendResults.filterNot { it.isDone }
            val rejected = appendResults.filter { it.isDone }

            accepted.assert().hasSize(pendingCapacity)
            rejected.assert().hasSize(128 - pendingCapacity)
            rejected.forEach {
                assertThrows<CompletionException>(it::join)
                    .cause.assert().isInstanceOf(MongoEventStoreBatchOverflowException::class.java)
            }

            insertResult.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                .assert().isEqualTo(Sinks.EmitResult.OK)
            accepted.forEach {
                it.join()
            }
            batcher.close()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun eventStream(id: String, aggregateVersion: Int = 0): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(id),
            aggregateVersion = aggregateVersion,
            eventCount = 1,
        )
    }

    private fun batchOptions(maxSize: Int): MongoEventStoreBatchOptions {
        return MongoEventStoreBatchOptions(
            enabled = true,
            maxSize = maxSize,
            maxDelay = Duration.ofMillis(10),
        )
    }
}
