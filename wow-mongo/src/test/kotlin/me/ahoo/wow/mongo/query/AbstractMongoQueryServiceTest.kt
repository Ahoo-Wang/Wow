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

package me.ahoo.wow.mongo.query

import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.converter.ConditionConverter
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AbstractMongoQueryServiceTest {
    private val collection = mockk<MongoCollection<Document>>()
    private val service = object : AbstractMongoQueryService<Document>() {
        override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val collection: MongoCollection<Document> = this@AbstractMongoQueryServiceTest.collection
        override val converter = mockk<ConditionConverter<Bson>>()
        override val projectionConverter = mockk<MongoProjectionConverter>()
        override val sortConverter = mockk<MongoSortConverter>()
        override fun toTypedResult(document: Document): Document = document
        override fun toDynamicDocument(document: Document): DynamicDocument = error("Not used.")
    }

    @Test
    fun `negative list limit should fail before calling MongoDB`() {
        assertThrows<IllegalArgumentException> {
            service.list(ListQuery(Condition.ALL, limit = -1))
        }

        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `non-negative list limit should reach MongoDB`() {
        val bson = mockk<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        every { service.converter.convert(any()) } returns bson
        every { service.projectionConverter.convert(any()) } returns bson
        every { service.sortConverter.convert(any()) } returns bson
        every { collection.find(bson) } returns publisher
        every { publisher.projection(bson) } returns publisher
        every { publisher.sort(bson) } returns publisher
        every { publisher.limit(1) } returns publisher

        service.list(ListQuery(Condition.ALL, limit = 1))

        verify(exactly = 1) { publisher.limit(1) }
    }
}
