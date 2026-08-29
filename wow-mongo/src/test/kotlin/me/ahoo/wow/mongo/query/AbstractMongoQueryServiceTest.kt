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
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryService
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceFactory
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryService
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceFactory
import me.ahoo.wow.query.CursorTokenCodec
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.Base64

class AbstractMongoQueryServiceTest {
    private val collection = mockk<MongoCollection<Document>>()
    private val tokenCodec = CursorTokenCodec.fromBase64Url(
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
    )
    private val service = object : AbstractMongoQueryService<Document>() {
        override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val collection: MongoCollection<Document> = this@AbstractMongoQueryServiceTest.collection
        override val converter = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterConverter
        override val projectionConverter = mockk<MongoProjectionConverter>()
        override val sortConverter = mockk<MongoSortConverter>()
        override fun toTypedResult(document: Document): Document = document
        override fun toDynamicDocument(document: Document): DynamicDocument = document.toDynamicDocument()
    }
    private val cursorService = object : AbstractMongoQueryService<Document>() {
        override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val collection: MongoCollection<Document> = this@AbstractMongoQueryServiceTest.collection
        override val converter = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterConverter
        override val projectionConverter = MongoProjectionConverter(FieldConverter { it })
        override val sortConverter = MongoSortConverter(FieldConverter { it })
        override val cursorUniqueField: String = "id"
        override val cursorTokenCodec: CursorTokenCodec = tokenCodec
        override fun toTypedResult(document: Document): Document = document
        override fun toDynamicDocument(document: Document): DynamicDocument = document.toDynamicDocument()
    }

    @Test
    fun `negative list limit should fail before calling MongoDB`() {
        assertThrows<IllegalArgumentException> {
            service.list(ListQuery(MatchAllFilter, limit = -1))
        }

        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `non-negative list limit should reach MongoDB`() {
        val bson = mockk<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        every { service.projectionConverter.convert(any<Projection>()) } returns bson
        every { service.sortConverter.convert(any()) } returns bson
        every { collection.find(any<Bson>()) } returns publisher
        every { publisher.projection(bson) } returns publisher
        every { publisher.sort(bson) } returns publisher
        every { publisher.limit(1) } returns publisher
        every { publisher.subscribe(any()) } answers {
            Flux.empty<Document>().subscribe(firstArg<Subscriber<in Document>>())
        }

        service.list(ListQuery(MatchAllFilter, limit = 1)).test().verifyComplete()

        verify(exactly = 1) { publisher.limit(1) }
    }

    @Test
    fun `cursor should use lookahead without count or skip`() {
        val publisher = cursorPublisher(
            listOf(Document("id", "1"), Document("id", "2"), Document("id", "3")),
            limit = 3,
        )

        cursorService.cursor(CursorQuery(MatchAllFilter, size = 2)).test()
            .assertNext { page ->
                page.list.map { it.getString("id") }.assert().containsExactly("1", "2")
                page.nextCursor.assert().isNotNull()
            }
            .verifyComplete()

        verify(exactly = 1) { publisher.limit(3) }
        verify(exactly = 0) { publisher.skip(any()) }
        verify(exactly = 0) { collection.countDocuments(any<Bson>()) }
    }

    @Test
    fun `cursor filter should combine original filter and keyset`() {
        val filter = slot<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        every { collection.find(capture(filter)) } returns publisher
        every { publisher.projection(any()) } returns publisher
        every { publisher.sort(any()) } returns publisher
        every { publisher.limit(2) } returns publisher
        every { publisher.subscribe(any()) } answers {
            Flux.empty<Document>().subscribe(firstArg<Subscriber<in Document>>())
        }
        val cursor = MongoCursorCodec.encode(tokenCodec, listOf(1, "id-1"))

        cursorService.cursor(
            CursorQuery(
                MatchAllFilter,
                sort = listOf(Sort("rank", Sort.Direction.ASC)),
                size = 1,
                cursor = cursor,
            ),
        ).block()

        filter.captured.toBsonDocument().toJson().assert().contains(
            "\$and",
            "\"deleted\": false",
            "\"rank\": {\"\$gt\": 1}",
            "\"id\": {\"\$gt\": \"id-1\"}",
        )
    }

    @Test
    fun `cursor should use sort field mapping for keyset projection and token`() {
        val queryService = object : AbstractMongoQueryService<Document>() {
            override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
            override val collection: MongoCollection<Document> = this@AbstractMongoQueryServiceTest.collection
            override val converter = object : AbstractMongoFilterConverter() {
                override val fieldConverter: FieldConverter = FieldConverter { "filter_$it" }
            }
            override val projectionConverter = MongoProjectionConverter(FieldConverter { "projection_$it" })
            override val sortConverter = MongoSortConverter(FieldConverter { "sort_$it" })
            override val cursorUniqueField: String = "id"
            override val cursorTokenCodec: CursorTokenCodec = tokenCodec
            override fun toTypedResult(document: Document): Document = document
            override fun toDynamicDocument(document: Document): DynamicDocument = document.toDynamicDocument()
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
                    Document("projection_name", "two").append("sort_rank", 2).append("sort_id", "2"),
                    Document("projection_name", "three").append("sort_rank", 3).append("sort_id", "3"),
                ),
            ).subscribe(firstArg<Subscriber<in Document>>())
        }
        val cursor = MongoCursorCodec.encode(tokenCodec, listOf(1, "1"))

        val page = queryService.dynamicCursor(
            CursorQuery(
                MatchAllFilter,
                projection = Projection(include = listOf("name")),
                sort = listOf(Sort("rank", Sort.Direction.ASC)),
                size = 1,
                cursor = cursor,
            ),
        ).block()!!

        filter.captured.toBsonDocument().toJson().assert()
            .contains("sort_rank", "sort_id")
            .doesNotContain("filter_rank", "filter_id")
        projection.captured.toBsonDocument().toJson().assert()
            .contains("projection_name", "sort_rank", "sort_id")
            .doesNotContain("projection_rank", "projection_id")
        sort.captured.toBsonDocument().toJson().assert().contains("sort_rank", "sort_id")
        MongoCursorCodec.decode(tokenCodec, page.nextCursor!!, expectedSize = 2).let { values ->
            values[0].assert().isEqualTo(2)
            values[1].assert().isEqualTo("2")
        }
        page.list.single().containsKey("sort_rank").assert().isFalse()
        page.list.single().containsKey("sort_id").assert().isFalse()
    }

    @Test
    fun `projection should not leak internally included sort fields`() {
        cursorPublisher(
            listOf(
                Document("name", "one").append("createdAt", 1).append("id", "1"),
                Document("name", "two").append("createdAt", 2).append("id", "2"),
            ),
            limit = 2,
        )

        val page = cursorService.dynamicCursor(
            CursorQuery(
                MatchAllFilter,
                projection = Projection(include = listOf("name")),
                sort = listOf(Sort("createdAt", Sort.Direction.ASC)),
                size = 1,
            ),
        ).block()!!

        page.list.single().containsKey("createdAt").assert().isFalse()
        page.list.single().containsKey("id").assert().isFalse()
        page.nextCursor.assert().isNotNull()
    }

    @Test
    fun `cursor should remain unsupported without a unique field`() {
        service.cursor(CursorQuery(MatchAllFilter)).test()
            .expectErrorMatches {
                it is UnsupportedOperationException && it.message == "Cursor query is not supported."
            }
            .verify()

        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `cursor should remain unsupported without an encryption codec even on the first page`() {
        val unconfigured = object : AbstractMongoQueryService<Document>() {
            override val namedAggregate = MaterializedNamedAggregate("test", "aggregate")
            override val collection: MongoCollection<Document> = this@AbstractMongoQueryServiceTest.collection
            override val converter = me.ahoo.wow.mongo.query.snapshot.SnapshotFilterConverter
            override val projectionConverter = MongoProjectionConverter(FieldConverter { it })
            override val sortConverter = MongoSortConverter(FieldConverter { it })
            override val cursorUniqueField: String = "id"
            override fun toTypedResult(document: Document): Document = document
            override fun toDynamicDocument(document: Document): DynamicDocument = document.toDynamicDocument()
        }

        unconfigured.cursor(CursorQuery(MatchAllFilter)).test()
            .expectErrorMatches {
                it is UnsupportedOperationException && it.message == "Cursor query is not supported."
            }
            .verify()
        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `Mongo factory should accept cursor codec injection without changing its default`() {
        val database = mockk<MongoDatabase>()
        every { database.getCollection(any()) } returns collection

        MongoSnapshotQueryServiceFactory(database, cursorTokenCodec = tokenCodec)
            .create<Any>(MOCK_AGGREGATE_METADATA).assert().isNotNull()
        MongoSnapshotQueryServiceFactory(database)
            .create<Any>(MOCK_AGGREGATE_METADATA)
            .cursor(CursorQuery(MatchAllFilter))
            .test()
            .expectError(UnsupportedOperationException::class.java)
            .verify()

        MongoSnapshotQueryServiceFactory::class.java.constructors.any { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(
                    MongoDatabase::class.java,
                    List::class.java,
                    me.ahoo.wow.query.schema.QuerySchemaValidationMode::class.java
                ),
            )
        }.assert().isTrue()
        MongoEventStreamQueryServiceFactory::class.java.constructors.any { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(
                    MongoDatabase::class.java,
                    List::class.java,
                    me.ahoo.wow.query.schema.QuerySchemaValidationMode::class.java
                ),
            )
        }.assert().isTrue()
    }

    @Test
    fun `Mongo services should retain pre-cursor constructor overloads`() {
        listOf(
            MongoSnapshotQueryService::class.java,
            MongoEventStreamQueryService::class.java,
        ).forEach { serviceType ->
            serviceType.constructors.any { constructor -> constructor.parameterCount == 5 }
                .assert().isTrue()
        }
    }

    @Test
    fun `malformed cursor should fail identically with direct and schema fallback services`() {
        val schemaFallback = MongoSnapshotQueryService<Any>(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            collection = collection,
            schemaProvider = unavailableSchemaProvider(),
            cursorTokenCodec = tokenCodec,
        )

        listOf<QueryService<*>>(cursorService, schemaFallback).forEach { queryService ->
            queryService.cursor(CursorQuery(MatchAllFilter, cursor = "malformed!"))
                .test()
                .expectErrorMatches {
                    it is IllegalArgumentException && it.message == "Invalid cursor."
                }
                .verify()
        }
        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    @Test
    fun `snapshot cursor should append aggregate id and resolve schema`() {
        val schemaProvider = unavailableSchemaProvider()
        val service = MongoSnapshotQueryService<Any>(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            collection = collection,
            schemaProvider = schemaProvider,
            cursorTokenCodec = tokenCodec,
        )

        val result = executeBuiltInCursor(
            service,
            documents = listOf(Document("_id", "1"), Document("_id", "2")),
        )

        result.second.toBsonDocument().toJson().assert().contains("_id")
        MongoCursorCodec.decode(tokenCodec, result.third.nextCursor!!, expectedSize = 1)
            .single().assert().isEqualTo("1")
        verify(exactly = 1) { schemaProvider.schema() }
    }

    @Test
    fun `snapshot cursor should compile fallback keyset against primary key`() {
        val service = MongoSnapshotQueryService<Any>(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            collection = collection,
            schemaProvider = unavailableSchemaProvider(),
            cursorTokenCodec = tokenCodec,
        )
        val cursor = MongoCursorCodec.encode(tokenCodec, listOf("1"))

        val filter = executeBuiltInCursor(
            service,
            CursorQuery(MatchAllFilter, size = 1, cursor = cursor),
        ).first.toBsonDocument().toJson()

        filter.assert().contains("\"_id\": {\"\$gt\": \"1\"}")
            .doesNotContain("\"aggregateId\": {\"\$gt\"")
    }

    @Test
    fun `event cursor should append event id and resolve schema`() {
        val schemaProvider = unavailableSchemaProvider()
        val service = MongoEventStreamQueryService(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            collection = collection,
            schemaProvider = schemaProvider,
            cursorTokenCodec = tokenCodec,
        )

        executeBuiltInCursor(service).second.toBsonDocument().toJson().assert().contains("_id")
        verify(exactly = 1) { schemaProvider.schema() }
    }

    private fun cursorPublisher(
        documents: List<Document>,
        limit: Int,
    ): FindPublisher<Document> {
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

    private fun executeBuiltInCursor(
        queryService: QueryService<*>,
        query: CursorQuery = CursorQuery(MatchAllFilter, size = 1),
        documents: List<Document> = emptyList(),
    ): Triple<Bson, Bson, CursorPage<DynamicDocument>> {
        val filter = slot<Bson>()
        val sort = slot<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        every { collection.find(capture(filter)) } returns publisher
        every { publisher.projection(any()) } returns publisher
        every { publisher.sort(capture(sort)) } returns publisher
        every { publisher.limit(2) } returns publisher
        every { publisher.subscribe(any()) } answers {
            Flux.fromIterable(documents).subscribe(firstArg<Subscriber<in Document>>())
        }

        val page = queryService.dynamicCursor(query).block()!!
        return Triple(filter.captured, sort.captured, page)
    }

    private fun unavailableSchemaProvider(): QueryModelSchemaProvider = mockk {
        every { schema() } returns Mono.error(QuerySchemaUnavailableException("Unavailable."))
    }
}
