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

import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.result.UpdateResult
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class MongoSnapshotStoreSaveTest {

    @Test
    fun `save should issue one version guarded pipeline update`() {
        val snapshot = snapshot()
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val filter = slot<Bson>()
        val updatePipeline = slot<List<Bson>>()
        val updateOptions = slot<UpdateOptions>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.updateOne(
                capture(filter),
                capture(updatePipeline),
                capture(updateOptions),
            )
        } returns Mono.just(UpdateResult.acknowledged(1, 1, null))

        MongoSnapshotStore(database).save(snapshot)
            .test()
            .verifyComplete()

        val codecRegistry = MongoClientSettings.getDefaultCodecRegistry()
        filter.captured.toBsonDocument(BsonDocument::class.java, codecRegistry).assert()
            .isEqualTo(
                Filters.eq(Documents.ID_FIELD, snapshot.aggregateId.id)
                    .toBsonDocument(BsonDocument::class.java, codecRegistry)
            )
        updatePipeline.captured.assert().hasSize(1)
        updateOptions.captured.isUpsert.assert().isTrue()
        verify(exactly = 1) {
            collection.updateOne(any<Bson>(), any<List<Bson>>(), any<UpdateOptions>())
        }
    }

    @Test
    fun `save should reject an unacknowledged update`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.updateOne(
                any<Bson>(),
                any<List<Bson>>(),
                any<UpdateOptions>(),
            )
        } returns Mono.just(UpdateResult.unacknowledged())

        MongoSnapshotStore(database).save(snapshot())
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()
    }

    private fun snapshot(): SimpleSnapshot<MockStateAggregate> {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("order-1")
        val stateAggregate =
            ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        stateAggregate.onSourcing(
            MockAggregateCreated("created").toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = stateAggregate.version,
            )
        )
        return SimpleSnapshot(stateAggregate, snapshotTime = 1)
    }
}
