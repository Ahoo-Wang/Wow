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
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class MongoEventStoreBatchBulkErrorTest {
    private val namedAggregate = MaterializedNamedAggregate("order-service", "order")

    @Test
    fun `unordered bulk error should fail only the matching append`() {
        val bulkError = MongoBulkWriteException(
            acknowledgedBulkResult(insertedCount = 1),
            listOf(duplicateKeyError(index = 0)),
            null,
            ServerAddress("localhost"),
            emptySet(),
        )
        val eventStore = eventStoreReturning(bulkError)

        eventStore.use {
            StepVerifier.create(
                Flux.merge(
                    it.append(eventStream("order-1", aggregateVersion = 1))
                        .thenReturn("unexpected-first-success")
                        .onErrorResume(EventVersionConflictException::class.java) {
                            Mono.just("first-conflict")
                        },
                    it.append(eventStream("order-2", aggregateVersion = 1))
                        .thenReturn("second-success"),
                ).collectList()
            ).assertNext { results ->
                results.assert().contains("first-conflict", "second-success")
            }.verifyComplete()
        }
    }

    @Test
    fun `write error should take precedence over write concern error for matching append`() {
        val bulkError = MongoBulkWriteException(
            acknowledgedBulkResult(insertedCount = 1),
            listOf(duplicateKeyError(index = 0)),
            WriteConcernError(
                64,
                "WriteConcernFailed",
                "write concern failed",
                BsonDocument(),
            ),
            ServerAddress("localhost"),
            emptySet(),
        )

        eventStoreReturning(bulkError).use { eventStore ->
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
        val bulkError = MongoBulkWriteException(
            acknowledgedBulkResult(insertedCount = 0),
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

        eventStoreReturning(bulkError).use { eventStore ->
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
        val bulkError = MongoBulkWriteException(
            acknowledgedBulkResult(insertedCount = 0),
            listOf(duplicateKeyError(index = 0)),
            null,
            ServerAddress("localhost"),
            emptySet(),
        )

        eventStoreReturning(bulkError).use { eventStore ->
            val signals = Mono.zip(
                eventStore.append(eventStream("order-1", aggregateVersion = 1)).materialize(),
                eventStore.append(eventStream("order-2", aggregateVersion = 1)).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isSameAs(bulkError)
            signals.t2.throwable.assert().isSameAs(bulkError)
        }
    }

    @Test
    fun `invalid bulk write metadata should fail the entire batch without inferring success`() {
        val duplicateIndexError = BulkWriteError(11000, "duplicate index", BsonDocument(), 0)
        val invalidBulkErrors = listOf(
            MongoBulkWriteException(
                acknowledgedBulkResult(insertedCount = 0),
                listOf(duplicateIndexError, duplicateIndexError),
                null,
                ServerAddress("localhost"),
                emptySet(),
            ),
            MongoBulkWriteException(
                acknowledgedBulkResult(insertedCount = 1),
                listOf(BulkWriteError(11000, "out of range index", BsonDocument(), 2)),
                null,
                ServerAddress("localhost"),
                emptySet(),
            ),
            MongoBulkWriteException(
                acknowledgedBulkResult(insertedCount = 2),
                emptyList(),
                null,
                ServerAddress("localhost"),
                emptySet(),
            ),
            MongoBulkWriteException(
                BulkWriteResult.unacknowledged(),
                listOf(BulkWriteError(11000, "unacknowledged result", BsonDocument(), 0)),
                null,
                ServerAddress("localhost"),
                emptySet(),
            ),
        )

        invalidBulkErrors.forEachIndexed { index, bulkError ->
            eventStoreReturning(bulkError).use { eventStore ->
                val signals = Mono.zip(
                    eventStore.append(eventStream("order-invalid-$index-a")).materialize(),
                    eventStore.append(eventStream("order-invalid-$index-b")).materialize(),
                ).block()!!

                signals.t1.throwable.assert().isSameAs(bulkError)
                signals.t2.throwable.assert().isSameAs(bulkError)
            }
        }
    }

    private fun eventStoreReturning(error: MongoBulkWriteException): MongoEventStore {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } returns Mono.error(error)
        return MongoEventStore(
            database,
            MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofMillis(10),
            ),
        )
    }

    private fun acknowledgedBulkResult(insertedCount: Int): BulkWriteResult {
        return BulkWriteResult.acknowledged(
            insertedCount,
            0,
            0,
            0,
            emptyList<BulkWriteUpsert>(),
            emptyList<BulkWriteInsert>(),
        )
    }

    private fun duplicateKeyError(index: Int): BulkWriteError {
        return BulkWriteError(
            11000,
            "duplicate key - ${AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME}",
            BsonDocument(),
            index,
        )
    }

    private fun eventStream(id: String, aggregateVersion: Int = 0): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(id),
            aggregateVersion = aggregateVersion,
            eventCount = 1,
        )
    }
}
