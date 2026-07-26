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

import com.mongodb.client.result.InsertManyResult
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.metrics.MetricEventStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class MongoEventStoreBatchCloseTest {
    private val namedAggregate = MaterializedNamedAggregate("order-service", "order")

    @Test
    fun `close timeout should be positive`() {
        listOf(Duration.ZERO, Duration.ofNanos(-1)).forEach { closeTimeout ->
            assertThrows<IllegalArgumentException> {
                BatchMongoEventStreamAppender(
                    database = mockk(),
                    options = batchOptions(maxSize = 2),
                    closeTimeout = closeTimeout,
                )
            }.message.assert().isEqualTo("closeTimeout must be positive.")
        }
    }

    @Test
    fun `close should flush a partial batch`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))
        val eventStore = MongoEventStore(
            database,
            MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = 8,
                maxDelay = Duration.ofHours(1),
            )
        )

        val appendResult = eventStore.append(eventStream("order-1")).toFuture()
        eventStore.close()

        appendResult.join()
        verify(exactly = 1) { collection.insertMany(any<List<Document>>(), any()) }
    }

    @Test
    fun `closing a decorated EventStore should flush a partial batch`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))
        val eventStore: EventStore = MetricEventStore(
            MongoEventStore(
                database,
                MongoEventStoreBatchOptions(
                    enabled = true,
                    maxSize = 8,
                    maxDelay = Duration.ofHours(1),
                )
            )
        )

        val appendResult = eventStore.append(eventStream("order-decorated")).toFuture()
        eventStore.close()

        appendResult.join()
        verify(exactly = 1) { collection.insertMany(any<List<Document>>(), any()) }
    }

    @Test
    fun `normal close should be idempotent and reject later appends`() {
        val batcher = BatchMongoEventStreamAppender(
            database = mockk(),
            options = batchOptions(maxSize = 2),
        )

        batcher.close()
        batcher.close()

        StepVerifier.create(batcher.append(eventStream("order-after-close")))
            .expectErrorMatches {
                it is IllegalStateException && it.message == "MongoEventStore is closed."
            }.verify()
    }

    @Test
    fun `append racing with close should be rejected before reaching MongoDB`() {
        val database = mockk<MongoDatabase>()
        val batcher = BatchMongoEventStreamAppender(
            database = database,
            options = batchOptions(maxSize = 2),
        )
        val eventStream = eventStream("order-racing-close")
        val document = eventStream.toDocument()
        val conversionStarted = CountDownLatch(1)
        val releaseConversion = CountDownLatch(1)
        val appendExecutor = Executors.newSingleThreadExecutor()
        var appendSubmission: Future<CompletableFuture<Void?>>? = null
        mockkStatic("me.ahoo.wow.mongo.DocumentsKt")

        try {
            every { eventStream.toDocument() } answers {
                conversionStarted.countDown()
                releaseConversion.await()
                document
            }
            appendSubmission = appendExecutor.submit<CompletableFuture<Void?>> {
                batcher.append(eventStream).toFuture()
            }
            conversionStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            batcher.close()
            releaseConversion.countDown()

            val appendResult = checkNotNull(appendSubmission).get(1, TimeUnit.SECONDS)
            assertThrows<CompletionException>(appendResult::join)
                .cause.assert().isInstanceOf(IllegalStateException::class.java)
            verify(exactly = 0) { database.getCollection(any<String>()) }
        } finally {
            releaseConversion.countDown()
            appendExecutor.shutdown()
            appendSubmission?.let { submission ->
                runCatching {
                    submission.get(1, TimeUnit.SECONDS)
                        .get(1, TimeUnit.SECONDS)
                }
            }
            if (!appendExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                appendExecutor.shutdownNow()
                appendExecutor.awaitTermination(1, TimeUnit.SECONDS)
            }
            runCatching(batcher::close)
            unmockkStatic("me.ahoo.wow.mongo.DocumentsKt")
        }
    }

    @Test
    fun `close invoked from append completion should await the remaining drain`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val insertIndex = AtomicInteger()
        val firstInsert = Sinks.one<InsertManyResult>()
        val secondInsert = Sinks.one<InsertManyResult>()
        val firstWriteSubscribed = CountDownLatch(1)
        val secondWriteSubscribed = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            when (insertIndex.getAndIncrement()) {
                0 -> Mono.defer {
                    firstWriteSubscribed.countDown()
                    firstInsert.asMono()
                }

                else -> Mono.defer {
                    secondWriteSubscribed.countDown()
                    secondInsert.asMono()
                }
            }
        }
        val eventStore = MongoEventStore(database, batchOptions(maxSize = 2))

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            val firstAppend = eventStore.append(eventStream("order-1"))
                .doOnSuccess {
                    eventStore.close()
                    closeReturned.countDown()
                }.toFuture()
            val secondAppend = eventStore.append(eventStream("order-2")).toFuture()
            firstWriteSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            val thirdAppend = eventStore.append(eventStream("order-3")).toFuture()

            firstInsert.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                .assert().isEqualTo(Sinks.EmitResult.OK)
            secondWriteSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            closeReturned.await(100, TimeUnit.MILLISECONDS).assert().isFalse()

            secondInsert.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                .assert().isEqualTo(Sinks.EmitResult.OK)
            closeReturned.await(1, TimeUnit.SECONDS).assert().isTrue()
            firstAppend.join()
            secondAppend.join()
            thirdAppend.join()
        }
    }

    @Test
    fun `close should await queued result dispatch`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val resultCallbacksStarted = CountDownLatch(4)
        val releaseResultCallbacks = CountDownLatch(1)
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))
        val batcher = BatchMongoEventStreamAppender(
            database = database,
            options = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofHours(1),
                maxPendingAppends = 32,
            ),
        )
        val appends = (0 until 10).map { index ->
            var append = batcher.append(eventStream("order-$index"))
            if (index in setOf(0, 2, 4, 6)) {
                append = append.doOnSuccess {
                    resultCallbacksStarted.countDown()
                    releaseResultCallbacks.await()
                }
            }
            append.toFuture()
        }
        val closeExecutor = Executors.newSingleThreadExecutor()

        try {
            resultCallbacksStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            appends[8].isDone.assert().isFalse()
            appends[9].isDone.assert().isFalse()
            val closeResult = closeExecutor.submit {
                batcher.close()
            }

            assertThrows<TimeoutException> {
                closeResult.get(100, TimeUnit.MILLISECONDS)
            }

            releaseResultCallbacks.countDown()
            closeResult.get(1, TimeUnit.SECONDS)
            appends.forEach(CompletableFuture<Void?>::join)
        } finally {
            releaseResultCallbacks.countDown()
            runCatching(batcher::close)
            closeExecutor.shutdownNow()
        }
    }

    @Test
    fun `result callbacks should close concurrently without blocking result drain`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val resultCallbacksReady = CountDownLatch(4)
        val invokeClose = CountDownLatch(1)
        val callbackClosesReturned = CountDownLatch(4)
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))
        val batcher = BatchMongoEventStreamAppender(
            database = database,
            options = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofHours(1),
                maxPendingAppends = 32,
            ),
            closeTimeout = Duration.ofMillis(250),
        )

        try {
            val appends = (0 until 10).map { index ->
                var append = batcher.append(eventStream("order-$index"))
                if (index in setOf(0, 2, 4, 6)) {
                    append = append.doOnSuccess {
                        resultCallbacksReady.countDown()
                        invokeClose.await()
                        try {
                            batcher.close()
                        } finally {
                            callbackClosesReturned.countDown()
                        }
                    }
                }
                append.toFuture()
            }
            resultCallbacksReady.await(1, TimeUnit.SECONDS).assert().isTrue()
            appends[8].isDone.assert().isFalse()
            appends[9].isDone.assert().isFalse()

            invokeClose.countDown()
            callbackClosesReturned.await(1, TimeUnit.SECONDS).assert().isTrue()
            batcher.close()
            CompletableFuture.allOf(*appends.toTypedArray()).get(1, TimeUnit.SECONDS)
        } finally {
            invokeClose.countDown()
            runCatching(batcher::close)
        }
    }

    @Test
    fun `close timeout should include result dispatch backlog`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val resultCallbackStarted = CountDownLatch(1)
        val releaseResultCallback = CountDownLatch(1)
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.just(InsertManyResult.acknowledged(emptyMap()))
        val batcher = BatchMongoEventStreamAppender(
            database = database,
            options = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofHours(1),
            ),
            closeTimeout = Duration.ofMillis(50),
        )

        try {
            val first = batcher.append(eventStream("order-1"))
                .doOnSuccess {
                    resultCallbackStarted.countDown()
                    releaseResultCallback.await()
                }.toFuture()
            val second = batcher.append(eventStream("order-2")).toFuture()
            resultCallbackStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            val closeError = assertThrows<MongoEventStoreBatchCloseTimeoutException> {
                batcher.close()
            }
            first.isDone.assert().isFalse()
            second.isDone.assert().isFalse()

            releaseResultCallback.countDown()
            CompletableFuture.allOf(first, second).get(1, TimeUnit.SECONDS)
            assertThrows<MongoEventStoreBatchCloseTimeoutException> {
                batcher.close()
            }.assert().isSameAs(closeError)
        } finally {
            releaseResultCallback.countDown()
            runCatching(batcher::close)
        }
    }

    @Test
    fun `concurrent close callers should await the same drain`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val writeSubscribed = CountDownLatch(1)
        val insertResult = Sinks.one<InsertManyResult>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.defer {
            writeSubscribed.countDown()
            insertResult.asMono()
        }
        val eventStore = MongoEventStore(database, batchOptions(maxSize = 2))
        val firstAppend = eventStore.append(eventStream("order-1")).toFuture()
        val secondAppend = eventStore.append(eventStream("order-2")).toFuture()
        writeSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val executor = Executors.newFixedThreadPool(2)
        val startClose = CountDownLatch(1)

        try {
            val firstClose = executor.submit {
                startClose.await()
                eventStore.close()
            }
            val secondClose = executor.submit {
                startClose.await()
                eventStore.close()
            }
            startClose.countDown()

            assertThrows<TimeoutException> {
                firstClose.get(100, TimeUnit.MILLISECONDS)
            }
            assertThrows<TimeoutException> {
                secondClose.get(100, TimeUnit.MILLISECONDS)
            }

            insertResult.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                .assert().isEqualTo(Sinks.EmitResult.OK)
            firstClose.get(1, TimeUnit.SECONDS)
            secondClose.get(1, TimeUnit.SECONDS)
            firstAppend.join()
            secondAppend.join()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `close timeout should terminate pending appends`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.never()
        val batcher = BatchMongoEventStreamAppender(
            database = database,
            options = batchOptions(maxSize = 2),
            closeTimeout = Duration.ofMillis(50),
        )
        val first = batcher.append(eventStream("order-1")).toFuture()
        val second = batcher.append(eventStream("order-2")).toFuture()

        val closeError = assertThrows<MongoEventStoreBatchCloseTimeoutException> {
            batcher.close()
        }
        closeError.timeout.assert().isEqualTo(Duration.ofMillis(50))
        closeError.message.assert().isEqualTo(
            "MongoEventStore batcher did not close within [PT0.05S]."
        )
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertThrows<CompletionException> {
                first.join()
            }.cause.assert().isSameAs(closeError)
            assertThrows<CompletionException> {
                second.join()
            }.cause.assert().isSameAs(closeError)
            StepVerifier.create(batcher.append(eventStream("order-after-close")))
                .expectError(MongoEventStoreBatchCloseTimeoutException::class.java)
                .verify()
            assertThrows<MongoEventStoreBatchCloseTimeoutException> {
                batcher.close()
            }.assert().isSameAs(closeError)
        }
    }

    @Test
    fun `interrupted close should preserve interrupt and fail pending appends`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val writeSubscribed = CountDownLatch(1)
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.defer {
            writeSubscribed.countDown()
            Mono.never()
        }
        val batcher = BatchMongoEventStreamAppender(
            database = database,
            options = batchOptions(maxSize = 2),
            closeTimeout = Duration.ofSeconds(1),
        )
        val first = batcher.append(eventStream("order-1")).toFuture()
        val second = batcher.append(eventStream("order-2")).toFuture()
        val closeObservation = CompletableFuture<Pair<Throwable?, Boolean>>()
        val closeThread = Thread(
            {
                Thread.currentThread().interrupt()
                val error = runCatching(batcher::close).exceptionOrNull()
                val interruptPreserved = Thread.currentThread().isInterrupted
                Thread.interrupted()
                closeObservation.complete(error to interruptPreserved)
            },
            "wow-mongo-interrupted-close-test"
        ).apply {
            isDaemon = true
        }

        try {
            writeSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            closeThread.start()
            val (observedError, interruptPreserved) =
                closeObservation.get(1, TimeUnit.SECONDS)
            val closeError = checkNotNull(observedError)
            closeError.assert().isInstanceOf(IllegalStateException::class.java)
            closeError.cause.assert().isInstanceOf(InterruptedException::class.java)
            interruptPreserved.assert().isTrue()

            listOf(first, second).forEach {
                assertThrows<CompletionException>(it::join)
                    .cause.assert().isSameAs(closeError)
            }
            assertThrows<IllegalStateException> {
                batcher.close()
            }.assert().isSameAs(closeError)
        } finally {
            if (closeThread.isAlive) {
                closeThread.interrupt()
                closeThread.join(1_000)
            } else {
                runCatching(batcher::close)
            }
        }
    }

    private fun eventStream(id: String): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(id),
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
