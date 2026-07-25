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
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MongoEventStoreBatchCancellationTest {
    private val namedAggregate = MaterializedNamedAggregate("order-service", "order")

    @Test
    fun `cancelling a queued append should promptly reclaim live capacity without over-release`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val firstInsertResult = Sinks.one<InsertManyResult>()
        val firstWriteStarted = CountDownLatch(1)
        val insertAttempts = AtomicInteger()
        val writtenBatches = mutableListOf<List<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            writtenBatches += firstArg<List<Document>>()
            if (insertAttempts.getAndIncrement() == 0) {
                firstWriteStarted.countDown()
                firstInsertResult.asMono()
            } else {
                Mono.just(InsertManyResult.acknowledged(emptyMap()))
            }
        }
        val batcher = createBatcher(database, maxPendingAppends = 3)

        try {
            val first = batcher.append(eventStream("order-1")).toFuture()
            val second = batcher.append(eventStream("order-2")).toFuture()
            firstWriteStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            val cancelled = batcher.append(eventStream("order-cancelled")).subscribe()
            cancelled.dispose()
            val replacement = batcher.append(eventStream("order-replacement")).toFuture()
            val overflow = batcher.append(eventStream("order-overflow")).toFuture()

            try {
                replacement.isCompletedExceptionally.assert().isFalse()
                overflow.isCompletedExceptionally.assert().isTrue()
                assertThrows<CompletionException>(overflow::join)
                    .cause.assert().isInstanceOf(MongoEventStoreBatchOverflowException::class.java)
            } finally {
                firstInsertResult.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                    .assert().isEqualTo(Sinks.EmitResult.OK)
            }

            first.join()
            second.join()
            replacement.join()
        } finally {
            batcher.close()
        }

        writtenBatches.flatten()
            .map { it.getString(MessageRecords.AGGREGATE_ID) }
            .assert()
            .containsExactlyInAnyOrder("order-1", "order-2", "order-replacement")
    }

    @Test
    fun `cancelling an in-flight append should not release capacity or skip its write`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val insertResult = Sinks.one<InsertManyResult>()
        val writeStarted = CountDownLatch(1)
        val writtenDocuments = slot<List<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(capture(writtenDocuments), any())
        } returns Mono.defer {
            writeStarted.countDown()
            insertResult.asMono()
        }
        val batcher = createBatcher(database, maxPendingAppends = 2)

        try {
            val cancelledInFlight = batcher.append(eventStream("order-in-flight-cancelled")).toFuture()
            val active = batcher.append(eventStream("order-active")).toFuture()
            writeStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            cancelledInFlight.cancel(true).assert().isTrue()

            val overflow = batcher.append(eventStream("order-overflow")).toFuture()
            overflow.isCompletedExceptionally.assert().isTrue()
            assertThrows<CompletionException>(overflow::join)
                .cause.assert().isInstanceOf(MongoEventStoreBatchOverflowException::class.java)

            insertResult.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                .assert().isEqualTo(Sinks.EmitResult.OK)
            active.join()
        } finally {
            insertResult.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
            batcher.close()
        }

        writtenDocuments.captured
            .map { it.getString(MessageRecords.AGGREGATE_ID) }
            .assert()
            .containsExactlyInAnyOrder("order-in-flight-cancelled", "order-active")
    }

    @Test
    fun `cancelled placeholders should remain bounded while MongoDB is stalled`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val insertResult = Sinks.one<InsertManyResult>()
        val writeStarted = CountDownLatch(1)
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.defer {
            writeStarted.countDown()
            insertResult.asMono()
        }
        val batcher = createBatcher(database, maxPendingAppends = 3)

        try {
            val first = batcher.append(eventStream("order-1")).toFuture()
            val second = batcher.append(eventStream("order-2")).toFuture()
            writeStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            repeat(3) { index ->
                val cancelled = batcher.append(eventStream("order-cancelled-$index")).toFuture()
                cancelled.isCompletedExceptionally.assert().isFalse()
                cancelled.cancel(true).assert().isTrue()
            }
            val overflow = batcher.append(eventStream("order-retained-overflow")).toFuture()
            overflow.isCompletedExceptionally.assert().isTrue()
            assertThrows<CompletionException>(overflow::join)
                .cause.assert().isInstanceOf(MongoEventStoreBatchOverflowException::class.java)

            insertResult.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
                .assert().isEqualTo(Sinks.EmitResult.OK)
            first.join()
            second.join()
        } finally {
            insertResult.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
            batcher.close()
        }

        verify(exactly = 1) { collection.insertMany(any<List<Document>>(), any()) }
    }

    private fun createBatcher(
        database: MongoDatabase,
        maxPendingAppends: Int,
    ): MongoEventStoreBatcher {
        return MongoEventStoreBatcher(
            database = database,
            options = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofHours(1),
                maxPendingAppends = maxPendingAppends,
            ),
        )
    }

    private fun eventStream(id: String): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(id),
            eventCount = 1,
        )
    }
}
