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
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords
import org.bson.BsonDocument
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class MongoEventStoreRequestIdTest {
    @Test
    fun `exists request id should filter by tenant id`() {
        val aggregateId = MaterializedNamedAggregate("order-service", "order")
            .aggregateId("order-1", tenantId = "tenant-1")
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val findPublisher = mockk<FindPublisher<Document>>()
        val filter = slot<Bson>()
        every { database.getCollection(any<String>()) } returns collection
        every { collection.find(capture(filter)) } returns findPublisher
        every { findPublisher.limit(1) } returns findPublisher
        every { findPublisher.first() } returns Mono.empty()
        val eventStore = MongoEventStore(database)

        StepVerifier.create(eventStore.existsRequestId(aggregateId, "request-1"))
            .expectNext(false)
            .verifyComplete()

        val filterJson = filter.captured
            .toBsonDocument(BsonDocument::class.java, MongoClientSettings.getDefaultCodecRegistry())
            .toJson()
        filterJson.assert().contains(MessageRecords.AGGREGATE_ID)
        filterJson.assert().contains(MessageRecords.REQUEST_ID)
        filterJson.assert().contains(MessageRecords.TENANT_ID)
        filterJson.assert().contains("tenant-1")
    }
}
