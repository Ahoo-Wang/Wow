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
import me.ahoo.wow.exception.recoverable
import me.ahoo.wow.infra.batch.BatchObservation
import me.ahoo.wow.infra.batch.BatchObserver
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.event.MockDomainEventStreams
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
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `enabled batching should publish physical batch observations`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val observations = CopyOnWriteArrayList<BatchObservation>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))

        MongoEventStore(
            database = database,
            batchOptions = batchOptions(maxSize = 2),
            observer = BatchObserver(observations::add),
        ).use { eventStore ->
            StepVerifier.create(
                Flux.merge(
                    eventStore.append(eventStream("observed-order-1")),
                    eventStore.append(eventStream("observed-order-2")),
                ).then()
            )
                .verifyComplete()
        }

        observations.filterIsInstance<BatchObservation.BatchWriteCompleted>()
            .single()
            .writtenItems.assert().isEqualTo(2)
    }

    @Test
    fun `one batch should isolate writes by event stream collection`() {
        val database = mockk<MongoDatabase>()
        val orderCollection = mockk<MongoCollection<Document>>()
        val paymentCollection = mockk<MongoCollection<Document>>()
        val paymentAggregate = MaterializedNamedAggregate("payment-service", "payment")
        val orderDocuments = slot<List<Document>>()
        val paymentDocuments = slot<List<Document>>()
        every {
            database.getCollection(namedAggregate.toEventStreamCollectionName())
        } returns orderCollection
        every {
            database.getCollection(paymentAggregate.toEventStreamCollectionName())
        } returns paymentCollection
        every {
            orderCollection.insertMany(capture(orderDocuments), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))
        every {
            paymentCollection.insertMany(capture(paymentDocuments), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))

        MongoEventStore(database, batchOptions(maxSize = 2)).use { eventStore ->
            StepVerifier.create(
                Flux.merge(
                    eventStore.append(eventStream("order-1")),
                    eventStore.append(eventStream("payment-1", aggregate = paymentAggregate)),
                ).then()
            ).verifyComplete()
        }

        orderDocuments.captured.single()
            .getString(MessageRecords.AGGREGATE_ID)
            .assert().isEqualTo("order-1")
        paymentDocuments.captured.single()
            .getString(MessageRecords.AGGREGATE_ID)
            .assert().isEqualTo("payment-1")
        verify(exactly = 1) { orderCollection.insertMany(any<List<Document>>(), any()) }
        verify(exactly = 1) { paymentCollection.insertMany(any<List<Document>>(), any()) }
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
    fun `configured lanes should allow concurrent insert many requests for different aggregates`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val writesStarted = CountDownLatch(2)
        val releaseWrites = Sinks.one<InsertManyResult>()
        val inFlightWrites = AtomicInteger()
        val maxInFlightWrites = AtomicInteger()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            Mono.defer {
                val inFlight = inFlightWrites.incrementAndGet()
                maxInFlightWrites.accumulateAndGet(inFlight, ::maxOf)
                writesStarted.countDown()
                releaseWrites.asMono()
                    .doFinally {
                        inFlightWrites.decrementAndGet()
                    }
            }
        }
        val laneRepresentatives = (1..100)
            .map { index -> eventStream("lane-$index") }
            .groupBy { eventStream -> Math.floorMod(eventStream.aggregateId.hashCode(), 2) }
            .values
            .map { eventStreams -> eventStreams.first() }
        laneRepresentatives.assert().hasSize(2)
        val streams = laneRepresentatives.flatMap { eventStream ->
            listOf(
                eventStream,
                eventStream(eventStream.aggregateId.id, aggregateVersion = 1),
            )
        }
        val eventStore = MongoEventStore(
            database = database,
            batchOptions = MongoEventStoreBatchOptions(
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
            writesStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            maxInFlightWrites.get().assert().isEqualTo(2)

            releaseWrites.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                .isSuccess.assert().isTrue()
            result.get(1, TimeUnit.SECONDS)
        } finally {
            eventStore.close()
        }
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

            BatchMongoEventStreamAppender(
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
        val batcher = BatchMongoEventStreamAppender(
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
        val batcher = BatchMongoEventStreamAppender(
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

    private fun eventStream(
        id: String,
        aggregateVersion: Int = 0,
        aggregate: MaterializedNamedAggregate = namedAggregate,
    ): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = aggregate.aggregateId(id),
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
