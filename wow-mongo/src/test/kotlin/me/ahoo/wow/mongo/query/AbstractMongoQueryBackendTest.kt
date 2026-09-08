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
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryBackend
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryBackend
import me.ahoo.wow.mongo.toObjectNode
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
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
import tools.jackson.databind.node.StringNode

class AbstractMongoQueryBackendTest {
    private val collection = mockk<MongoCollection<Document>>()
    private val schema = testSchema(fields = emptyMap())
    private val backend = object : AbstractMongoQueryBackend() {
        override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val collection: MongoCollection<Document> = this@AbstractMongoQueryBackendTest.collection
        override val filterCompiler = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler
        override fun toObjectNode(document: Document): ObjectNode = document.toObjectNode()
    }

    @Test
    fun `negative list limit should fail before calling MongoDB`() {
        assertThrows<IllegalArgumentException> {
            backend.list(ListQuery(MatchAllFilter, limit = -1), schema)
        }

        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `paged mixed array sort fails before count or find`() {
        val scalar = mongoScalar(QueryValueType.INTEGER)
        val array = QueryValueSchema(QueryValueKind.ARRAY, items = scalar)
        val mixed = QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(scalar, array))
        val arraySchema = mongoTestSchema(
            fields = mapOf(
                QueryField("a") to MongoTestField(mixed, setOf(QueryCapability.SORT), "a"),
                QueryField("b") to MongoTestField(array, setOf(QueryCapability.SORT), "b"),
            )
        )
        Mono.defer {
            backend.paged(
                PagedQuery(
                    MatchAllFilter,
                    sort = listOf(Sort(QueryField("a"), Sort.Direction.ASC), Sort(QueryField("b"), Sort.Direction.ASC))
                ),
                arraySchema,
            )
        }.test().expectError(QuerySchemaValidationException::class.java).verify()
        verify(exactly = 0) { collection.countDocuments(any<Bson>()) }
        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `invalid paged projection should fail before count or find`() {
        Mono.defer {
            backend.paged(
                PagedQuery(
                    MatchAllFilter,
                    projection = Projection(
                        include = listOf(QueryField("name")),
                        exclude = listOf(QueryField("secret")),
                    ),
                    pagination = Pagination(size = 1),
                ),
                schema,
            )
        }.test().expectError(IllegalArgumentException::class.java).verify()

        verify(exactly = 0) { collection.countDocuments(any<Bson>()) }
        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `non-negative list limit should reach MongoDB`() {
        val publisher = mockk<FindPublisher<Document>>()
        arrangePublisher(publisher) { Flux.empty() }

        backend.list(ListQuery(MatchAllFilter, limit = 1), schema).test().verifyComplete()

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
            ListQuery(
                EqualFilter(QueryField("aggregateId"), StringNode.valueOf("id")),
                limit = 1,
            ),
            customSchema,
        ).test().verifyComplete()

        filter.captured.toBsonDocument().toJson().assert()
            .contains("custom.aggregateId")
            .doesNotContain("custom._id")
    }

    @Test
    fun `each list subscription should receive an exclusive mutable object node`() {
        val publisher = mockk<FindPublisher<Document>>()
        val document = Document("value", 1)
        arrangePublisher(publisher) { Flux.just(document) }
        val result = backend.list(ListQuery(MatchAllFilter, limit = 1), schema)

        val first = result.blockFirst()!!
        first.put("mutated", true)
        val second = result.blockFirst()!!

        second.assert().isNotSameAs(first)
        second.path("mutated").isMissingNode.assert().isTrue()
    }

    @Test
    fun `list should release cursor on completion`() {
        val signals = mutableListOf<SignalType>()
        arrangePublisher(mockk()) {
            Flux.just(Document("value", 1)).doFinally(signals::add)
        }

        backend.list(ListQuery(MatchAllFilter, limit = 1), schema).then().test().verifyComplete()

        signals.assert().containsExactly(SignalType.ON_COMPLETE)
    }

    @Test
    fun `list should propagate a partial cursor failure and release it with error`() {
        val signals = mutableListOf<SignalType>()
        arrangePublisher(mockk()) {
            Flux.just(Document("value", 1))
                .concatWith(Flux.error(IllegalStateException("cursor-failed")))
                .doFinally(signals::add)
        }

        backend.list(ListQuery(MatchAllFilter, limit = 1), schema).test()
            .expectNextCount(1)
            .expectErrorMessage("cursor-failed")
            .verify()

        signals.assert().containsExactly(SignalType.ON_ERROR)
    }

    @Test
    fun `list should release cursor on cancellation`() {
        val signals = mutableListOf<SignalType>()
        arrangePublisher(mockk()) {
            Flux.just(Document("value", 1))
                .concatWith(Flux.never())
                .doFinally(signals::add)
        }

        backend.list(ListQuery(MatchAllFilter, limit = 1), schema).take(1).test()
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

        val page = backend.cursor(
            CursorQuery(
                MatchAllFilter,
                sort = listOf(
                    Sort(QueryField("rank"), Sort.Direction.ASC),
                    Sort(QueryField("id"), Sort.Direction.ASC),
                ),
                size = 1,
            ),
            cursorSchema("rank", "id"),
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

        backend.cursor(
            CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("rank"), Sort.Direction.DESC)), size = 1),
            cursorSchema("rank"),
        ).block()

        sort.captured.toBsonDocument().toJson().assert().contains("rank").doesNotContain("id")
    }

    @Test
    fun `raw cursor should reject sort without cursor capability before find`() {
        val rank = QueryField("rank")
        val sortOnlySchema = testSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(rank to fieldSchema("rank", setOf(QueryCapability.PRESENCE, QueryCapability.SORT))),
        )

        Mono.defer {
            backend.cursor(
                CursorQuery(MatchAllFilter, sort = listOf(Sort(rank, Sort.Direction.ASC))),
                sortOnlySchema,
            )
        }.test().expectError(QuerySchemaValidationException::class.java).verify()

        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `raw cursor should reject duplicate physical sort fields before find`() {
        val first = QueryField("first")
        val second = QueryField("second")
        val duplicateSchema = testSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                first to fieldSchema("shared", setOf(QueryCapability.CURSOR_SORT)),
                second to fieldSchema("shared", setOf(QueryCapability.CURSOR_SORT)),
            ),
        )

        Mono.defer {
            backend.cursor(
                CursorQuery(
                    MatchAllFilter,
                    sort = listOf(
                        Sort(first, Sort.Direction.ASC),
                        Sort(second, Sort.Direction.ASC),
                    ),
                ),
                duplicateSchema,
            )
        }.test().expectError(QuerySchemaValidationException::class.java).verify()

        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `cursor keyset projection and token should use physical sort paths`() {
        val mappedBackend = object : AbstractMongoQueryBackend() {
            override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
            override val collection: MongoCollection<Document> = this@AbstractMongoQueryBackendTest.collection
            override val filterCompiler = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler
            override fun toObjectNode(document: Document): ObjectNode = document.toObjectNode()
        }
        val filter = slot<Bson>()
        val projection = slot<Bson>()
        val sort = slot<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        every { collection.find(capture(filter)) } returns publisher
        every { publisher.projection(capture(projection)) } returns publisher
        every { publisher.sort(capture(sort)) } returns publisher
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
                projection = Projection(include = listOf(QueryField("name"))),
                sort = listOf(
                    Sort(QueryField("rank"), Sort.Direction.ASC),
                    Sort(QueryField("id"), Sort.Direction.ASC),
                ),
                size = 1,
                cursor = MongoCursorCodec.encode(listOf(1, "1")),
            ),
            physicalCursorSchema(),
        ).block()!!

        filter.captured.toBsonDocument().toJson().assert().contains("physical_rank", "physical_id")
        projection.captured.toBsonDocument().toJson().assert()
            .contains("physical_name", "physical_rank", "physical_id")
        sort.captured.toBsonDocument().toJson().assert().contains("physical_rank", "physical_id")
        MongoCursorCodec.decode(page.nextCursor!!, 2).assert().containsExactly(2, "2")
        page.list.single().has("physical_rank").assert().isFalse()
        page.list.single().has("physical_id").assert().isFalse()
    }

    @Test
    fun `built-in cursor mappers should hide cursor-only logical ids`() {
        val builtIns = listOf(
            QueryModel.SNAPSHOT to MessageRecords.AGGREGATE_ID,
            QueryModel.EVENT_STREAM to MessageRecords.ID,
        )

        builtIns.forEach { (model, logicalId) ->
            val builtIn = builtInCursorBackend(model)
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
                    CursorQuery(
                        MatchAllFilter,
                        projection = projection,
                        sort = listOf(
                            Sort(QueryField(logicalId), Sort.Direction.ASC),
                            Sort(QueryField("rank"), Sort.Direction.ASC),
                        ),
                        size = 1,
                    ),
                    identitySchema(model, logicalId),
                ).block()!!

                page.list.single().path("name").asString().assert().isEqualTo("one")
                page.list.single().has(logicalId).assert().isFalse()
                MongoCursorCodec.decode(page.nextCursor!!, 2).assert().containsExactly("1", 1)
            }
        }
    }

    private fun arrangePublisher(
        publisher: FindPublisher<Document>,
        source: () -> Flux<Document>,
    ) {
        every { collection.find(any<Bson>()) } returns publisher
        every { publisher.projection(null) } returns publisher
        every { publisher.sort(null) } returns publisher
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
        return testSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                logical to fieldSchema(physicalPath, setOf(QueryCapability.EXACT_MATCH)),
            ),
        )
    }

    private fun physicalCursorSchema(): QueryModelSchema {
        val base = testSchema(
            fields = listOf("name", "rank", "id").associate { path ->
                QueryField(path) to fieldSchema("physical_$path", setOf(QueryCapability.PRESENCE, QueryCapability.CURSOR_SORT))
            }
        )
        return QueryModelSchema(
            base.model,
            base.capabilities,
            base.definition,
            base.bindings.mapValues { (path, binding) ->
                if (path.segments.size != 1 || path.field(emptyList()).path == "deleted") return@mapValues binding
                val ordinary =
                    QueryPathTemplate(listOf(QueryPathSegment.Property("ordinary_${path.field(emptyList()).path}")))
                QueryValueBindings(
                    binding.bindings + (QueryCapability.SORT to QueryFieldBindingTemplate(ordinary, setOf(QueryStorageType("string")))),
                    binding.projectionPath,
                    binding.responsePath
                )
            }
        )
    }

    private fun cursorSchema(vararg paths: String) = testSchema(
        fields = paths.associate { path ->
            QueryField(path) to fieldSchema(path, setOf(QueryCapability.PRESENCE, QueryCapability.CURSOR_SORT))
        }
    )

    private fun identitySchema(model: QueryModel, logicalPath: String) = testSchema(
        model,
        fields = mapOf(
            QueryField(logicalPath) to fieldSchema("_id", setOf(QueryCapability.PRESENCE, QueryCapability.CURSOR_SORT)),
            QueryField("rank") to fieldSchema("rank", setOf(QueryCapability.PRESENCE, QueryCapability.CURSOR_SORT)),
            QueryField("name") to fieldSchema("name", setOf(QueryCapability.PRESENCE)),
        ),
    )

    private fun fieldSchema(physicalPath: String, capabilities: Set<QueryCapability>): MongoTestField =
        MongoTestField(mongoScalar(), capabilities, physicalPath)

    private fun testSchema(
        model: QueryModel = QueryModel.SNAPSHOT,
        capabilities: Set<QueryCapability> = emptySet(),
        fields: Map<QueryField, MongoTestField>,
    ) = mongoTestSchema(
        model,
        capabilities,
        mapOf(
            QueryField("deleted") to MongoTestField(mongoScalar(QueryValueType.BOOLEAN), setOf(QueryCapability.EXACT_MATCH), "deleted"),
            QueryField("name") to MongoTestField(mongoScalar(), setOf(QueryCapability.PRESENCE), "name"),
            QueryField("secret") to MongoTestField(mongoScalar(), setOf(QueryCapability.PRESENCE), "secret"),
        ) + fields
    )
}
