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
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryBackend
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryBackend
import me.ahoo.wow.mongo.toObjectNode
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode

class AbstractMongoQueryBackendTest {
    private val collection = mockk<MongoCollection<Document>>()
    private val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())
    private val backend = object : AbstractMongoQueryBackend() {
        override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val collection: MongoCollection<Document> = this@AbstractMongoQueryBackendTest.collection
        override val filterCompiler = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler
        override val projectionConverter = mockk<MongoProjectionConverter>()
        override val sortConverter = mockk<MongoSortConverter>()
        override fun toObjectNode(document: Document): ObjectNode = document.toObjectNode()
    }
    private val cursorBackend = object : AbstractMongoQueryBackend() {
        override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val collection: MongoCollection<Document> = this@AbstractMongoQueryBackendTest.collection
        override val filterCompiler = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler
        override val projectionConverter = MongoProjectionConverter(FieldConverter { it })
        override val sortConverter = MongoSortConverter(FieldConverter { it })
        fun find(queryable: Queryable<*>): FindPublisher<Document> = findDocument(queryable, schema)
        override fun toObjectNode(document: Document): ObjectNode = document.toObjectNode()
    }

    @Test
    fun `Mongo find document API should require schema`() {
        val publisher = mockk<FindPublisher<Document>>()
        every { collection.find(any<Bson>()) } returns publisher
        every { publisher.projection(null) } returns publisher
        every { publisher.sort(null) } returns publisher
        val query = ListQuery(MatchAllFilter, limit = 1)

        cursorBackend.find(query).assert().isSameAs(publisher)
        collection.findDocument(
            compiler = cursorBackend.filterCompiler,
            queryable = query,
            projectionConverter = cursorBackend.projectionConverter,
            sortConverter = cursorBackend.sortConverter,
            schema = schema,
        ).assert().isSameAs(publisher)
    }

    @Test
    fun `single should pass the resolved schema instance to projection conversion`() {
        val bson = mockk<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        arrangePublisher(publisher, bson) { Flux.empty() }

        backend.single(ResolvedQuery(SingleQuery(MatchAllFilter), schema)).test().verifyComplete()

        verify(exactly = 1) { backend.projectionConverter.convert(any(), match { it === schema }) }
    }

    @Test
    fun `negative list limit should fail before calling MongoDB`() {
        assertThrows<IllegalArgumentException> {
            backend.list(ResolvedQuery(ListQuery(MatchAllFilter, limit = -1), schema))
        }

        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `non-negative list limit should reach MongoDB`() {
        val bson = mockk<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        arrangePublisher(publisher, bson) { Flux.empty() }

        backend.list(ResolvedQuery(ListQuery(MatchAllFilter, limit = 1), schema)).test().verifyComplete()

        verify(exactly = 1) { publisher.limit(1) }
    }

    @Test
    fun `filter compiler should use schema physical bindings`() {
        val customBackend = MongoSnapshotQueryBackend(
            namedAggregate = MaterializedNamedAggregate("test", "aggregate"),
            collection = collection,
        )
        val customSchema = schema("aggregateId", "custom.aggregateId")
        val filter = slot<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        every { collection.find(capture(filter)) } returns publisher
        every { publisher.projection(null) } returns publisher
        every { publisher.sort(null) } returns publisher
        every { publisher.limit(1) } returns publisher
        every { publisher.subscribe(any()) } answers {
            Flux.empty<Document>().subscribe(firstArg<Subscriber<in Document>>())
        }

        customBackend.list(
            ResolvedQuery(
                ListQuery(
                    EqualFilter(QueryField("aggregateId"), StringNode.valueOf("id")),
                    limit = 1,
                ),
                customSchema,
            ),
        ).test().verifyComplete()

        filter.captured.toBsonDocument().toJson().assert()
            .contains("custom.aggregateId")
            .doesNotContain("custom._id")
    }

    @Test
    fun `each list subscription should receive an exclusive mutable object node`() {
        val bson = mockk<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        val document = Document("value", 1)
        arrangePublisher(publisher, bson) { Flux.just(document) }
        val result = backend.list(ResolvedQuery(ListQuery(MatchAllFilter, limit = 1), schema))

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

        backend.list(ResolvedQuery(ListQuery(MatchAllFilter, limit = 1), schema)).then().test().verifyComplete()

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

        backend.list(ResolvedQuery(ListQuery(MatchAllFilter, limit = 1), schema)).test()
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

        backend.list(ResolvedQuery(ListQuery(MatchAllFilter, limit = 1), schema)).take(1).test()
            .expectNextCount(1)
            .verifyComplete()

        signals.assert().containsExactly(SignalType.CANCEL)
    }

    @Test
    fun `cursor should use lookahead without count or skip`() {
        val publisher = cursorPublisher(
            listOf(Document("rank", 1).append("id", "1"), Document("rank", 2).append("id", "2")),
            limit = 2,
        )

        val page = cursorBackend.cursor(
            ResolvedQuery(
                CursorQuery(
                    MatchAllFilter,
                    sort = listOf(
                        Sort(QueryField("rank"), Sort.Direction.ASC),
                        Sort(QueryField("id"), Sort.Direction.ASC),
                    ),
                    size = 1,
                ),
                schema,
            ),
        ).block()!!

        page.list.single().path("rank").asInt().assert().isEqualTo(1)
        page.nextCursor.assert().isNotNull()
        verify(exactly = 1) { publisher.limit(2) }
        verify(exactly = 0) { publisher.skip(any()) }
        verify(exactly = 0) { collection.countDocuments(any<Bson>()) }
    }

    @Test
    fun `cursor should execute the already resolved sort`() {
        val sort = slot<Bson>()
        val publisher = cursorPublisher(emptyList(), limit = 2)
        every { publisher.sort(capture(sort)) } returns publisher

        cursorBackend.cursor(
            ResolvedQuery(
                CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("rank"), Sort.Direction.DESC)), size = 1),
                schema,
            ),
        ).block()

        sort.captured.toBsonDocument().toJson().assert().contains("rank").doesNotContain("id")
    }

    @Test
    fun `cursor keyset projection and token should use physical sort paths`() {
        val mappedBackend = object : AbstractMongoQueryBackend() {
            override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
            override val collection: MongoCollection<Document> = this@AbstractMongoQueryBackendTest.collection
            override val filterCompiler = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler
            override val projectionConverter = MongoProjectionConverter(FieldConverter { "physical_$it" })
            override val sortConverter = MongoSortConverter(FieldConverter { "physical_$it" })
            override fun toObjectNode(document: Document): ObjectNode = document.toObjectNode()
        }
        val filter = slot<Bson>()
        val projection = slot<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        every { collection.find(capture(filter)) } returns publisher
        every { publisher.projection(capture(projection)) } returns publisher
        every { publisher.sort(any()) } returns publisher
        every { publisher.limit(2) } returns publisher
        every { publisher.subscribe(any()) } answers {
            Flux.fromIterable(
                listOf(
                    Document("physical_name", "two")
                        .append("physical_rank", 2)
                        .append("physical_id", "2"),
                    Document("physical_name", "three")
                        .append("physical_rank", 3)
                        .append("physical_id", "3"),
                ),
            ).subscribe(firstArg<Subscriber<in Document>>())
        }

        val page = mappedBackend.cursor(
            ResolvedQuery(
                CursorQuery(
                    MatchAllFilter,
                    projection = Projection(include = listOf(QueryField("name"))),
                    sort = listOf(
                        Sort(QueryField("rank"), Sort.Direction.ASC),
                        Sort(QueryField("id"), Sort.Direction.ASC),
                    ),
                    size = 1,
                    cursor = MongoCursorCodec.encode(listOf(1, "1")),
                ),
                schema,
            ),
        ).block()!!

        filter.captured.toBsonDocument().toJson().assert().contains("physical_rank", "physical_id")
        projection.captured.toBsonDocument().toJson().assert()
            .contains("physical_name", "physical_rank", "physical_id")
        MongoCursorCodec.decode(page.nextCursor!!, 2).assert().containsExactly(2, "2")
        page.list.single().has("physical_rank").assert().isFalse()
        page.list.single().has("physical_id").assert().isFalse()
    }

    @Test
    fun `built-in cursor mappers should hide cursor-only logical ids`() {
        val builtIns = listOf(
            MessageRecords.AGGREGATE_ID to builtInCursorBackend(
                QueryModel.SNAPSHOT,
            ),
            MessageRecords.ID to builtInCursorBackend(QueryModel.EVENT_STREAM),
        )

        builtIns.forEach { (logicalId, builtIn) ->
            listOf(
                Projection(include = listOf(QueryField("name"))),
                Projection(exclude = listOf(QueryField(logicalId))),
            ).forEach { projection ->
                cursorPublisher(
                    listOf(
                        Document("_id", "1").append("name", "one").append("rank", 1),
                        Document("_id", "2").append("name", "two").append("rank", 2),
                    ),
                    limit = 2,
                )

                val page = builtIn.cursor(
                    ResolvedQuery(
                        CursorQuery(
                            MatchAllFilter,
                            projection = projection,
                            sort = listOf(
                                Sort(QueryField(logicalId), Sort.Direction.ASC),
                                Sort(QueryField("rank"), Sort.Direction.ASC),
                            ),
                            size = 1,
                        ),
                        schema,
                    ),
                ).block()!!

                page.list.single().path("name").asString().assert().isEqualTo("one")
                page.list.single().has(logicalId).assert().isFalse()
                MongoCursorCodec.decode(page.nextCursor!!, 2).assert().containsExactly("1", 1)
            }
        }
    }

    private fun arrangePublisher(
        publisher: FindPublisher<Document>,
        bson: Bson,
        source: () -> Flux<Document>,
    ) {
        every { backend.projectionConverter.convert(any(), schema) } returns bson
        every { backend.sortConverter.convert(any()) } returns bson
        every { collection.find(any<Bson>()) } returns publisher
        every { publisher.projection(bson) } returns publisher
        every { publisher.sort(bson) } returns publisher
        every { publisher.limit(1) } returns publisher
        every { publisher.first() } returns publisher
        every { publisher.subscribe(any()) } answers {
            source().subscribe(firstArg<Subscriber<in Document>>())
        }
    }

    private fun cursorPublisher(documents: List<Document>, limit: Int): FindPublisher<Document> {
        val publisher = mockk<FindPublisher<Document>>()
        every { collection.find(any<Bson>()) } returns publisher
        every { publisher.projection(any()) } returns publisher
        every { publisher.sort(any()) } returns publisher
        every { publisher.limit(limit) } returns publisher
        every { publisher.subscribe(any()) } answers {
            Flux.fromIterable(documents).subscribe(firstArg<Subscriber<in Document>>())
        }
        return publisher
    }

    private fun builtInCursorBackend(model: QueryModel): QueryBackend {
        return if (model == QueryModel.SNAPSHOT) {
            MongoSnapshotQueryBackend(
                namedAggregate = MaterializedNamedAggregate("test", "aggregate"),
                collection = collection,
            )
        } else {
            MongoEventStreamQueryBackend(
                namedAggregate = MaterializedNamedAggregate("test", "aggregate"),
                collection = collection,
            )
        }
    }

    private fun schema(logicalPath: String, physicalPath: String): QueryModelSchema {
        val logical = QueryField(logicalPath)
        return QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                logical to QueryFieldSchema(
                    title = null,
                    description = null,
                    enumValues = null,
                    valueTypes = setOf(QueryValueType.STRING),
                    nullable = false,
                    required = true,
                    cardinality = QueryCardinality.SINGLE,
                    semanticType = null,
                    dynamicChildren = false,
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            logical,
                            QueryField(physicalPath),
                            QueryStorageType("test"),
                        ),
                    ),
                    rewriteMode = QueryRewriteMode.NONE,
                ),
            ),
        )
    }
}
