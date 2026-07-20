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

import com.mongodb.MongoException
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.result.UpdateResult
import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.ListCollectionNamesPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class MongoFailurePathTest {

    @Test
    fun `context guard rejects an empty ownership claim response`() {
        val collection = mockk<MongoCollection<Document>>()
        val findPublisher = mockk<FindPublisher<Document>>()
        every { collection.find(any<Bson>()) } returns findPublisher
        every { findPublisher.first() } returns Mono.empty()
        every {
            collection.findOneAndUpdate(
                any<Bson>(),
                any<Bson>(),
                any<FindOneAndUpdateOptions>(),
            )
        } returns Mono.empty()
        val database = mockk<MongoDatabase>()
        every { database.name } returns "test"
        every { database.getCollection(any()) } returns collection
        every { database.listCollectionNames() } returns emptyCollectionNamesPublisher()

        assertThrows<IllegalStateException> {
            MongoDatabaseContextGuard(database).ensureContext("sales")
        }.message.assert().contains("did not return its bounded context ownership marker")
    }

    @Test
    fun `checkpoint save rejects an unacknowledged write`() {
        val collection = mockk<MongoCollection<Document>>()
        every {
            collection.updateOne(
                any<Bson>(),
                any<Bson>(),
                any<UpdateOptions>(),
            )
        } returns Mono.just(UpdateResult.unacknowledged())
        val database = mockk<MongoDatabase>()
        every { database.getCollection(any()) } returns collection
        val snapshot = snapshot()

        StepVerifier.create(MongoSnapshotStore(database).saveCheckpoint(snapshot))
            .expectErrorSatisfies { error ->
                error.assert()
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("checkpoint write was not acknowledged")
            }
            .verify()
    }

    @Test
    fun `collection initialization propagates non namespace-exists errors`() {
        val database = mockk<MongoDatabase>()
        every { database.listCollectionNames() } returns emptyCollectionNamesPublisher()
        every { database.createCollection("orders") } returns Mono.error(MongoException(13, "denied"))

        assertThrows<MongoException> {
            AggregateSchemaInitializer.run {
                database.ensureCollection("orders")
            }
        }.code.assert().isEqualTo(13)
    }

    @Test
    fun `ascending index applies default and explicit uniqueness`() {
        ascendingIndex("tenantId").apply {
            name.assert().isEqualTo("tenantId_1")
            unique.assert().isFalse()
        }
        ascendingIndex("aggregateId", "version", unique = true).apply {
            name.assert().isEqualTo("aggregateId_1_version_1")
            unique.assert().isTrue()
        }
    }

    private fun snapshot(): Snapshot<MockStateAggregate> {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val state = MockStateAggregate(aggregateId.id)
        val aggregate = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
            aggregateId = aggregateId,
            state = state,
            version = 10,
        )
        return SimpleSnapshot(aggregate, snapshotTime = 10)
    }

    private fun emptyCollectionNamesPublisher(): ListCollectionNamesPublisher =
        mockk {
            every { subscribe(any()) } answers {
                firstArg<Subscriber<in String>>().complete()
            }
        }

    private fun <T> Subscriber<in T>.complete() {
        onSubscribe(
            object : Subscription {
                override fun request(numberOfElements: Long) {
                    onComplete()
                }

                override fun cancel() = Unit
            },
        )
    }
}
