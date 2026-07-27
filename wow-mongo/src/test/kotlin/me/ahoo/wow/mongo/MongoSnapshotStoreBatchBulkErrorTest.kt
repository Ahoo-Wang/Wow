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
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.WriteModel
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration

class MongoSnapshotStoreBatchBulkErrorTest {
    @Test
    fun `unordered bulk error should fail only the matching save`() {
        val bulkError = MongoBulkWriteException(
            acknowledgedUpdateResult(matchedCount = 1),
            listOf(BulkWriteError(121, "validation failed", BsonDocument(), 0)),
            null,
            ServerAddress("localhost"),
            emptySet(),
        )

        storeReturning(bulkError).use { store ->
            val signals = Mono.zip(
                store.save(snapshot("order-1")).materialize(),
                store.save(snapshot("order-2")).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isSameAs(bulkError)
            signals.t2.isOnComplete.assert().isTrue()
        }
    }

    @Test
    fun `write concern error should fail every uncertain save`() {
        val bulkError = MongoBulkWriteException(
            acknowledgedUpdateResult(matchedCount = 0),
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

        storeReturning(bulkError).use { store ->
            val signals = Mono.zip(
                store.save(snapshot("order-1")).materialize(),
                store.save(snapshot("order-2")).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isInstanceOf(RecoverableMongoBulkWriteException::class.java)
            signals.t2.throwable.assert().isSameAs(signals.t1.throwable)
        }
    }

    @Test
    fun `recoverable write error should fail only its matching save as recoverable`() {
        val bulkError = MongoBulkWriteException(
            acknowledgedUpdateResult(matchedCount = 1),
            listOf(BulkWriteError(91, "shutdown in progress", BsonDocument(), 0)),
            null,
            ServerAddress("localhost"),
            emptySet(),
        )

        storeReturning(bulkError).use { store ->
            val signals = Mono.zip(
                store.save(snapshot("order-1")).materialize(),
                store.save(snapshot("order-2")).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isInstanceOf(RecoverableMongoBulkWriteException::class.java)
            signals.t2.isOnComplete.assert().isTrue()
        }
    }

    @Test
    fun `inconsistent bulk result should not infer a successful save`() {
        val bulkError = MongoBulkWriteException(
            acknowledgedUpdateResult(matchedCount = 0),
            listOf(BulkWriteError(121, "validation failed", BsonDocument(), 0)),
            null,
            ServerAddress("localhost"),
            emptySet(),
        )

        storeReturning(bulkError).use { store ->
            val signals = Mono.zip(
                store.save(snapshot("order-1")).materialize(),
                store.save(snapshot("order-2")).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isSameAs(bulkError)
            signals.t2.throwable.assert().isSameAs(bulkError)
        }
    }

    @Test
    fun `overlapping or out of range upsert metadata should fail the whole batch`() {
        listOf(0, 2).forEach { invalidUpsertIndex ->
            val bulkError = MongoBulkWriteException(
                acknowledgedUpdateResult(
                    matchedCount = 0,
                    upserts = listOf(BulkWriteUpsert(invalidUpsertIndex, BsonString("upserted"))),
                ),
                listOf(BulkWriteError(121, "validation failed", BsonDocument(), 0)),
                null,
                ServerAddress("localhost"),
                emptySet(),
            )

            storeReturning(bulkError).use { store ->
                val signals = Mono.zip(
                    store.save(snapshot("order-1")).materialize(),
                    store.save(snapshot("order-2")).materialize(),
                ).block()!!

                signals.t1.throwable.assert().isSameAs(bulkError)
                signals.t2.throwable.assert().isSameAs(bulkError)
            }
        }
    }

    @Test
    fun `duplicate upsert metadata should fail the whole batch`() {
        val duplicateUpserts = listOf(
            BulkWriteUpsert(1, BsonString("first")),
            BulkWriteUpsert(1, BsonString("duplicate")),
        )
        val bulkError = MongoBulkWriteException(
            acknowledgedUpdateResult(matchedCount = 0, upserts = duplicateUpserts),
            listOf(BulkWriteError(121, "validation failed", BsonDocument(), 0)),
            null,
            ServerAddress("localhost"),
            emptySet(),
        )

        storeReturning(bulkError, maxSize = 3).use { store ->
            val signals = Mono.zip(
                store.save(snapshot("order-1")).materialize(),
                store.save(snapshot("order-2")).materialize(),
                store.save(snapshot("order-3")).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isSameAs(bulkError)
            signals.t2.throwable.assert().isSameAs(bulkError)
            signals.t3.throwable.assert().isSameAs(bulkError)
        }
    }

    private fun storeReturning(
        error: MongoBulkWriteException,
        maxSize: Int = 2,
    ): MongoSnapshotStore {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.bulkWrite(
                any<List<WriteModel<Document>>>(),
                any<BulkWriteOptions>(),
            )
        } returns Mono.error(error)
        return MongoSnapshotStore(
            database = database,
            batchOptions = MongoSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = maxSize,
                maxDelay = Duration.ofSeconds(1),
                maxPendingSaves = maxSize,
            ),
        )
    }

    private fun snapshot(id: String): SimpleSnapshot<MockStateAggregate> {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(id)
        val aggregate = ConstructorStateAggregateFactory.create(
            MOCK_AGGREGATE_METADATA.state,
            aggregateId,
        )
        aggregate.onSourcing(
            MockAggregateCreated("created").toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = aggregate.version,
            )
        )
        return SimpleSnapshot(aggregate, snapshotTime = 1)
    }

    private fun acknowledgedUpdateResult(
        matchedCount: Int,
        upserts: List<BulkWriteUpsert> = emptyList(),
    ): BulkWriteResult {
        return BulkWriteResult.acknowledged(
            0,
            matchedCount,
            0,
            matchedCount,
            upserts,
            emptyList<BulkWriteInsert>(),
        )
    }
}
