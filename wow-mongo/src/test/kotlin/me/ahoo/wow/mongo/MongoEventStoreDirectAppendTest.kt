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

import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import com.mongodb.client.result.InsertOneResult
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class MongoEventStoreDirectAppendTest {
    private val namedAggregate = MaterializedNamedAggregate("order-service", "order")

    @Test
    fun `disabled batching should append directly`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every { collection.insertOne(any<Document>()) } returns
            Mono.just(InsertOneResult.acknowledged(null))
        val eventStore = MongoEventStore(
            database = database,
            batchOptions = MongoEventStoreBatchOptions(enabled = false),
        )

        StepVerifier.create(eventStore.append(eventStream("order-direct")))
            .verifyComplete()
        eventStore.close()

        verify(exactly = 1) { collection.insertOne(any<Document>()) }
        verify(exactly = 0) { collection.insertMany(any<List<Document>>(), any()) }
    }

    @Test
    fun `unacknowledged direct append should fail`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every { collection.insertOne(any<Document>()) } returns
            Mono.just(InsertOneResult.unacknowledged())

        StepVerifier.create(MongoEventStore(database).append(eventStream("order-unacknowledged")))
            .expectError(IllegalStateException::class.java)
            .verify()
    }

    @Test
    fun `direct append should preserve Mongo write error mapping`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val writeException = MongoWriteException(
            WriteError(
                11000,
                "duplicate key - ${AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME}",
                BsonDocument(),
            ),
            ServerAddress("localhost"),
        )
        every { database.getCollection(any<String>()) } returns collection
        every { collection.insertOne(any<Document>()) } returns Mono.error(writeException)

        StepVerifier.create(
            MongoEventStore(database).append(
                eventStream(
                    id = "order-conflict",
                    aggregateVersion = 1,
                ),
            ),
        ).expectErrorSatisfies { error ->
            error.assert().isInstanceOf(EventVersionConflictException::class.java)
            error.cause.assert().isSameAs(writeException)
        }.verify()
    }

    private fun eventStream(
        id: String,
        aggregateVersion: Int = 0,
    ) = MockDomainEventStreams.generateEventStream(
        aggregateId = namedAggregate.aggregateId(id),
        aggregateVersion = aggregateVersion,
        eventCount = 1,
    )
}
