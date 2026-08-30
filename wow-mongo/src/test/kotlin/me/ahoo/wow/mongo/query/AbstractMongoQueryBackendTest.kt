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
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
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
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
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
    private lateinit var resolvedCursorQuery: ICursorQuery
    private val cursorBackend = object : AbstractMongoQueryBackend() {
        override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val collection: MongoCollection<Document> = this@AbstractMongoQueryBackendTest.collection
        override val converter = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterConverter
        override val projectionConverter = MongoProjectionConverter(FieldConverter { it })
        override val sortConverter = MongoSortConverter(FieldConverter { it })
        override val cursorUniqueField: String = "id"
        override fun resolve(query: ICursorQuery): Mono<ICursorQuery> = Mono.just(query).doOnNext {
            resolvedCursorQuery = it
        }
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

    @Test
    fun `cursor should use lookahead without count or skip`() {
        val publisher = cursorPublisher(
            listOf(Document("rank", 1).append("id", "1"), Document("rank", 2).append("id", "2")),
            limit = 2,
        )

        val page = cursorBackend.cursor(
            CursorQuery(MatchAllFilter, sort = listOf(Sort("rank", Sort.Direction.ASC)), size = 1),
        ).block()!!

        page.list.single().path("rank").asInt().assert().isEqualTo(1)
        page.nextCursor.assert().isNotNull()
        verify(exactly = 1) { publisher.limit(2) }
        verify(exactly = 0) { publisher.skip(any()) }
        verify(exactly = 0) { collection.countDocuments(any<Bson>()) }
    }

    @Test
    fun `cursor should append unique sort before resolve`() {
        cursorPublisher(emptyList(), limit = 2)

        cursorBackend.cursor(
            CursorQuery(MatchAllFilter, sort = listOf(Sort("rank", Sort.Direction.DESC)), size = 1),
        ).block()

        resolvedCursorQuery.sort.assert().containsExactly(
            Sort("rank", Sort.Direction.DESC),
            Sort("id", Sort.Direction.ASC),
        )
    }

    @Test
    fun `built-in cursor backends should resolve schema before Mongo access`() {
        val schemaProvider = mockk<QueryModelSchemaProvider>()
        every { schemaProvider.schema() } returns Mono.error(QuerySchemaUnavailableException("unavailable"))
        val backends = listOf<QueryBackend>(
            MongoSnapshotQueryBackend(
                namedAggregate = MaterializedNamedAggregate("test", "aggregate"),
                collection = collection,
                schemaProvider = schemaProvider,
            ),
            MongoEventStreamQueryBackend(
                namedAggregate = MaterializedNamedAggregate("test", "aggregate"),
                collection = collection,
                schemaProvider = schemaProvider,
            ),
        )

        backends.forEach { builtIn ->
            builtIn.cursor(CursorQuery(MatchAllFilter)).test()
                .expectError(QuerySchemaUnavailableException::class.java)
                .verify()
        }

        verify(exactly = 2) { schemaProvider.schema() }
        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `built-in cursor should reject masked aliases before Mongo access`() {
        val schemaProvider = mockk<QueryModelSchemaProvider>()
        every { schemaProvider.schema() } returns Mono.just(
            QueryModelSchema(
                model = QueryModel.SNAPSHOT,
                capabilities = emptySet(),
                fields = mapOf(
                    LogicalField(MessageRecords.AGGREGATE_ID) to cursorFieldSchema("_id"),
                    LogicalField("state.emailAlias") to cursorFieldSchema(
                        physicalPath = "masked_email",
                        projectionPath = "state.email",
                        maskRule = mockk(),
                    ),
                    LogicalField("state.email") to cursorFieldSchema("email"),
                    LogicalField("state.secret") to cursorFieldSchema("secret", maskRule = mockk()),
                    LogicalField("state.secretAlias") to cursorFieldSchema("secret"),
                ),
            ),
        )
        val builtIn = MongoSnapshotQueryBackend(
            namedAggregate = MaterializedNamedAggregate("test", "aggregate"),
            collection = collection,
            schemaProvider = schemaProvider,
        )

        listOf("state.email", "state.secretAlias").forEach { alias ->
            builtIn.cursor(CursorQuery(MatchAllFilter, sort = listOf(Sort(alias, Sort.Direction.ASC))))
                .test()
                .expectError(QuerySchemaValidationException::class.java)
                .verify()
        }

        verify(exactly = 2) { schemaProvider.schema() }
        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `cursor keyset projection and token should use physical sort paths`() {
        val mappedBackend = object : AbstractMongoQueryBackend() {
            override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
            override val collection: MongoCollection<Document> = this@AbstractMongoQueryBackendTest.collection
            override val converter = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterConverter
            override val projectionConverter = MongoProjectionConverter(FieldConverter { "physical_$it" })
            override val sortConverter = MongoSortConverter(FieldConverter { "physical_$it" })
            override val cursorUniqueField: String = "id"
            override fun toObjectNode(document: Document): ObjectNode = document.toObjectNode()
            override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
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
            CursorQuery(
                MatchAllFilter,
                projection = Projection(include = listOf("name")),
                sort = listOf(Sort("rank", Sort.Direction.ASC)),
                size = 1,
                cursor = MongoCursorCodec.encode(listOf(1, "1")),
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
                MessageRecords.AGGREGATE_ID,
                QueryModel.SNAPSHOT,
            ),
            MessageRecords.ID to builtInCursorBackend(MessageRecords.ID, QueryModel.EVENT_STREAM),
        )

        builtIns.forEach { (logicalId, builtIn) ->
            listOf(
                Projection(include = listOf("name")),
                Projection(exclude = listOf(logicalId)),
            ).forEach { projection ->
                cursorPublisher(
                    listOf(
                        Document("_id", "1").append("name", "one"),
                        Document("_id", "2").append("name", "two"),
                    ),
                    limit = 2,
                )

                val page = builtIn.cursor(
                    CursorQuery(MatchAllFilter, projection = projection, size = 1),
                ).block()!!

                page.list.single().path("name").asString().assert().isEqualTo("one")
                page.list.single().has(logicalId).assert().isFalse()
                MongoCursorCodec.decode(page.nextCursor!!, 1).single().assert().isEqualTo("1")
            }
        }
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

    private fun builtInCursorBackend(logicalId: String, model: QueryModel): QueryBackend {
        val schemaProvider = mockk<QueryModelSchemaProvider>()
        every { schemaProvider.schema() } returns Mono.just(
            QueryModelSchema(
                model = model,
                capabilities = emptySet(),
                fields = mapOf(
                    LogicalField(logicalId) to cursorFieldSchema("_id"),
                    LogicalField("name") to cursorFieldSchema("name"),
                ),
            ),
        )
        return if (model == QueryModel.SNAPSHOT) {
            MongoSnapshotQueryBackend(
                namedAggregate = MaterializedNamedAggregate("test", "aggregate"),
                collection = collection,
                schemaProvider = schemaProvider,
            )
        } else {
            MongoEventStreamQueryBackend(
                namedAggregate = MaterializedNamedAggregate("test", "aggregate"),
                collection = collection,
                schemaProvider = schemaProvider,
            )
        }
    }

    private fun cursorFieldSchema(
        physicalPath: String,
        projectionPath: String = physicalPath,
        maskRule: MaskRule? = null,
    ) = QueryFieldSchema(
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
            QueryCapability.PRESENCE to QueryFieldBinding(physicalPath, storageType = null),
            QueryCapability.SORT to QueryFieldBinding(physicalPath, storageType = null),
        ),
        projectionPath = projectionPath,
        maskRule = maskRule,
    )
}
