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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.toObjectNode
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

class AbstractMongoQueryBackendTest {
    private val collection = mockk<MongoCollection<Document>>()
    private val backend = object : AbstractMongoQueryBackend() {
        override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val collection: MongoCollection<Document> = this@AbstractMongoQueryBackendTest.collection
        override val converter = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterConverter
        override val projectionConverter = mockk<MongoProjectionConverter>()
        override val sortConverter = mockk<MongoSortConverter>()
        override fun toObjectNode(document: Document): ObjectNode = document.toObjectNode()
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }

    @Test
    fun `negative list limit should fail before calling MongoDB`() {
        assertThrows<IllegalArgumentException> {
            backend.list(ListQuery(MatchAllFilter, limit = -1))
        }

        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `non-negative list limit should reach MongoDB`() {
        val bson = mockk<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        arrangePublisher(publisher, bson) { Flux.empty() }

        backend.list(ListQuery(MatchAllFilter, limit = 1)).test().verifyComplete()

        verify(exactly = 1) { publisher.limit(1) }
    }

    @Test
    fun `each list subscription should receive an exclusive mutable object node`() {
        val bson = mockk<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        val document = Document("value", 1)
        arrangePublisher(publisher, bson) { Flux.just(document) }
        val result = backend.list(ListQuery(MatchAllFilter, limit = 1))

        val first = result.blockFirst()!!
        first.put("mutated", true)
        val second = result.blockFirst()!!

        second.assert().isNotSameAs(first)
        second.path("mutated").isMissingNode.assert().isTrue()
    }

    @Test
    fun `list should release cursor on completion`() {
        val signals = mutableListOf<SignalType>()
        arrangePublisher(mockk(), mockk()) {
            Flux.just(Document("value", 1)).doFinally(signals::add)
        }

        backend.list(ListQuery(MatchAllFilter, limit = 1)).then().test().verifyComplete()

        signals.assert().containsExactly(SignalType.ON_COMPLETE)
    }

    @Test
    fun `list should propagate a partial cursor failure and release it with error`() {
        val signals = mutableListOf<SignalType>()
        arrangePublisher(mockk(), mockk()) {
            Flux.just(Document("value", 1))
                .concatWith(Flux.error(IllegalStateException("cursor-failed")))
                .doFinally(signals::add)
        }

        backend.list(ListQuery(MatchAllFilter, limit = 1)).test()
            .expectNextCount(1)
            .expectErrorMessage("cursor-failed")
            .verify()

        signals.assert().containsExactly(SignalType.ON_ERROR)
    }

    @Test
    fun `list should release cursor on cancellation`() {
        val signals = mutableListOf<SignalType>()
        arrangePublisher(mockk(), mockk()) {
            Flux.just(Document("value", 1))
                .concatWith(Flux.never())
                .doFinally(signals::add)
        }

        backend.list(ListQuery(MatchAllFilter, limit = 1)).take(1).test()
            .expectNextCount(1)
            .verifyComplete()

        signals.assert().containsExactly(SignalType.CANCEL)
    }

    private fun arrangePublisher(
        publisher: FindPublisher<Document>,
        bson: Bson,
        source: () -> Flux<Document>,
    ) {
        every { backend.projectionConverter.convert(any()) } returns bson
        every { backend.sortConverter.convert(any()) } returns bson
        every { collection.find(any<Bson>()) } returns publisher
        every { publisher.projection(bson) } returns publisher
        every { publisher.sort(bson) } returns publisher
        every { publisher.limit(1) } returns publisher
        every { publisher.subscribe(any()) } answers {
            source().subscribe(firstArg<Subscriber<in Document>>())
        }
    }
}
