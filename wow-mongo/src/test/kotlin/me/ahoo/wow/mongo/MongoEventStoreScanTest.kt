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
import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.serialization.MessageRecords
import org.bson.BsonDocument
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test

class MongoEventStoreScanTest {

    @Test
    fun `scan aggregate id should sort by aggregate id`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val findPublisher = mockk<FindPublisher<Document>>()
        every { database.getCollection(any<String>()) } returns collection
        every { collection.find(any<Bson>()) } returns findPublisher
        every { findPublisher.projection(any<Bson>()) } returns findPublisher
        every { findPublisher.sort(any<Bson>()) } returns findPublisher
        every { findPublisher.limit(any()) } returns findPublisher
        val eventStore = MongoEventStore(database)

        eventStore.scanAggregateId(MaterializedNamedAggregate("order-service", "order"), afterId = "001", limit = 2)

        verify { findPublisher.sort(any()) }
    }

    @Test
    fun `scan aggregate id should filter by named aggregate fields`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val findPublisher = mockk<FindPublisher<Document>>()
        val filter = slot<Bson>()
        every { database.getCollection(any<String>()) } returns collection
        every { collection.find(capture(filter)) } returns findPublisher
        every { findPublisher.projection(any<Bson>()) } returns findPublisher
        every { findPublisher.sort(any<Bson>()) } returns findPublisher
        every { findPublisher.limit(any()) } returns findPublisher
        val eventStore = MongoEventStore(database)

        eventStore.scanAggregateId(MaterializedNamedAggregate("order-service", "order"), afterId = "001", limit = 2)

        val filterJson = filter.captured
            .toBsonDocument(BsonDocument::class.java, MongoClientSettings.getDefaultCodecRegistry())
            .toJson()
        filterJson.assert().contains(MessageRecords.CONTEXT_NAME)
        filterJson.assert().contains("order-service")
        filterJson.assert().contains(MessageRecords.AGGREGATE_NAME)
        filterJson.assert().contains("order")
        filterJson.assert().contains(MessageRecords.AGGREGATE_ID)
        filterJson.assert().contains(MessageRecords.VERSION)
    }
}
