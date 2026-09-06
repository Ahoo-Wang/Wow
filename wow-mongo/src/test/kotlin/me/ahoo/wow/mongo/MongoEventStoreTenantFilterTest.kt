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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords
import org.bson.BsonDocument
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class MongoEventStoreTenantFilterTest {
    @Test
    fun `load stream by version should filter by tenant id`() {
        val filterJson = captureFindFilter { eventStore, aggregateId ->
            eventStore.load(aggregateId, headVersion = 1, tailVersion = 10)
        }

        filterJson.assert().contains(MessageRecords.AGGREGATE_ID)
        filterJson.assert().contains(MessageRecords.TENANT_ID)
        filterJson.assert().contains(MessageRecords.VERSION)
        filterJson.assert().contains("tenant-1")
    }

    @Test
    fun `load stream by event time should filter by tenant id`() {
        val filterJson = captureFindFilter { eventStore, aggregateId ->
            eventStore.load(aggregateId, headEventTime = 1L, tailEventTime = 10L)
        }

        filterJson.assert().contains(MessageRecords.AGGREGATE_ID)
        filterJson.assert().contains(MessageRecords.TENANT_ID)
        filterJson.assert().contains(MessageRecords.CREATE_TIME)
        filterJson.assert().contains("tenant-1")
    }

    @Test
    fun `last should filter by tenant id`() {
        val filterJson = captureFindFilter { eventStore, aggregateId ->
            eventStore.last(aggregateId)
        }

        filterJson.assert().contains(MessageRecords.AGGREGATE_ID)
        filterJson.assert().contains(MessageRecords.TENANT_ID)
        filterJson.assert().contains("tenant-1")
    }

    private fun captureFindFilter(invocation: (MongoEventStore, AggregateId) -> Unit): String {
        val aggregateId = MaterializedNamedAggregate("order-service", "order")
            .aggregateId("order-1", tenantId = "tenant-1")
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val findPublisher = mockk<FindPublisher<Document>>()
        val filter = slot<Bson>()
        every { database.getCollection(any<String>()) } returns collection
        every { collection.find(capture(filter)) } returns findPublisher
        every { findPublisher.sort(any()) } returns findPublisher
        every { findPublisher.limit(any()) } returns findPublisher
        every { findPublisher.first() } returns Mono.empty()
        val eventStore = MongoEventStore(database)

        invocation(eventStore, aggregateId)

        return filter.captured
            .toBsonDocument(BsonDocument::class.java, MongoClientSettings.getDefaultCodecRegistry())
            .toJson()
    }
}
