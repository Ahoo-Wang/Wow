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
import com.mongodb.MongoNamespace
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.ListCollectionNamesPublisher
import com.mongodb.reactivestreams.client.ListIndexesPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.Mono

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

    @Test
    fun `event stream schema creates scan index in version id order`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val indexKeys = mutableListOf<Bson>()
        every { database.name } returns "test"
        every { database.listCollectionNames() } returns emptyCollectionNamesPublisher()
        every { database.createCollection(any()) } returns Mono.empty()
        every { database.getCollection(any<String>()) } returns collection
        every { collection.namespace } returns MongoNamespace("test", "order_event_stream")
        every { collection.listIndexes() } returns emptyIndexesPublisher()
        every {
            collection.createIndex(capture(indexKeys), any<IndexOptions>())
        } returns Mono.just("created")

        EventStreamSchemaInitializer(database)
            .initSchema(MaterializedNamedAggregate("order-service", "order"))

        indexKeys.filterIsInstance<Document>()
            .map { index -> index.entries.map { it.key to it.value } }
            .assert()
            .contains(
                listOf(
                    MessageRecords.VERSION to 1,
                    MessageRecords.AGGREGATE_ID to 1,
                ),
            )
    }

    private fun emptyCollectionNamesPublisher(): ListCollectionNamesPublisher =
        mockk {
            every { subscribe(any()) } answers {
                firstArg<Subscriber<in String>>().complete()
            }
        }

    private fun emptyIndexesPublisher(): ListIndexesPublisher<Document> =
        mockk {
            every { subscribe(any()) } answers {
                firstArg<Subscriber<in Document>>().complete()
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
