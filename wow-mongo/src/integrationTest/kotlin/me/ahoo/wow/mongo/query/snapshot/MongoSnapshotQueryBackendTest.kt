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

package me.ahoo.wow.mongo.query.snapshot

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.toMongoSnapshotWrite
import me.ahoo.wow.mongo.versionGuardedSnapshotReplacement
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.validateQuery
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy.Companion.toFilterExpression
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.query.SnapshotQueryBackendSpec
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonTimestamp
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.jvm.javaField

class MongoSnapshotQueryBackendTest : SnapshotQueryBackendSpec() {

    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    lateinit var database: MongoDatabase

    @BeforeEach
    override fun setup() {
        database = mongo.database()
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .createIndex(Indexes.text("state.data"))
            .toMono().test().expectNextCount(1).verifyComplete()
        super.setup()
        setStateValidator(nestedLineDateValidator())
    }

    override fun createSnapshotQueryBackendFactory(): SnapshotQueryBackendFactory {
        return MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources + invalidNumericFixtureSource(),
        )
    }

    override fun createSnapshotStore(): SnapshotStore {
        return NativeDateSnapshotStore(database)
    }

    override fun prepareNullAndMissingCursorSnapshots(nullId: String, missingId: String) {
        val collection = database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
        collection.updateOne(
            Filters.eq("_id", nullId),
            Document("\$set", Document().append("state.createdAt", null)),
        ).toMono().test().expectNextCount(1).verifyComplete()
        collection.updateOne(
            Filters.eq("_id", missingId),
            Document("\$unset", Document("state.createdAt", "")),
        ).toMono().test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `retry should create a clean snapshot object node after a discarded mutation`() {
        val attempts = AtomicInteger()
        val seen = mutableListOf<ObjectNode>()

        queryBackendBinding.list(snapshotOwnershipQuery())
            .next()
            .doOnNext { node ->
                seen += node
                if (attempts.getAndIncrement() == 0) {
                    node.put("mutated", true)
                    error("retry-once")
                }
            }.retry(1)
            .test()
            .assertNext { retried ->
                seen.assert().hasSize(2)
                retried.assert().isNotSameAs(seen.first())
                retried.path("mutated").isMissingNode.assert().isTrue()
                retried.path("aggregateId").textValue().assert().isEqualTo(snapshot.aggregateId.id)
                retried.has("_id").assert().isFalse()
            }.verifyComplete()
    }

    @Test
    fun `repeat should create clean snapshot object nodes for every subscription`() {
        queryBackendBinding.list(snapshotOwnershipQuery())
            .next()
            .repeat(1)
            .index()
            .doOnNext { indexed ->
                if (indexed.t1 == 0L) {
                    indexed.t2.put("mutated", true)
                }
            }.map { it.t2 }
            .collectList()
            .test()
            .assertNext { nodes ->
                nodes.assert().hasSize(2)
                nodes[1].assert().isNotSameAs(nodes[0])
                nodes[0].path("mutated").booleanValue().assert().isTrue()
                nodes[1].path("mutated").isMissingNode.assert().isTrue()
                nodes.map { it.path("aggregateId").textValue() }.assert()
                    .containsExactly(snapshot.aggregateId.id, snapshot.aggregateId.id)
                nodes.all { !it.has("_id") }.assert().isTrue()
            }.verifyComplete()
    }

    @Test
    fun `concurrent subscriptions should receive isolated snapshot object nodes`() {
        val publisher = queryBackendBinding.list(snapshotOwnershipQuery()).next()

        Mono.zip(
            publisher.subscribeOn(Schedulers.parallel()),
            publisher.subscribeOn(Schedulers.parallel()),
        ).test()
            .assertNext { nodes ->
                nodes.t1.put("mutated", true)
                nodes.t2.assert().isNotSameAs(nodes.t1)
                nodes.t2.path("mutated").isMissingNode.assert().isTrue()
                nodes.t2.path("aggregateId").textValue().assert().isEqualTo(snapshot.aggregateId.id)
                nodes.t2.has("_id").assert().isFalse()
            }.verifyComplete()
    }

    private fun snapshotOwnershipQuery(): ListQuery = ListQuery(
        filter = filterExpression { id(snapshot.aggregateId.id) },
        limit = 1,
    )

    @Test
    fun `identity exclusion should preserve snapshot payload across query result shapes`() {
        val logicalId = "aggregateId"
        val filter = filterExpression { id(snapshot.aggregateId.id) }
        val payloadField = "state"
        val schema = queryBackendBinding.schemaProvider.schema().block()!!
        val projection = Projection(exclude = listOf(QueryField(logicalId)))
        val backend = queryBackendBinding.backend
        val single = SingleQuery(filter, projection)
        val list = ListQuery(filter, projection, limit = 1)
        val paged = PagedQuery(filter, projection, pagination = Pagination(size = 1))
        val results = listOf(
            backend.single(single.also { validateQuery(it, schema) }, schema)
                .map(::listOf),
            backend.list(list.also { validateQuery(it, schema) }, schema)
                .collectList(),
            backend.paged(paged.also { validateQuery(it, schema) }, schema)
                .map { page ->
                    page.total.assert().isEqualTo(1L)
                    page.list
                },
        )

        results.forEach { result ->
            result.test().assertNext { nodes ->
                val node = nodes.single()
                node.has(logicalId).assert().isFalse()
                node.has("_id").assert().isFalse()
                node.has(payloadField).assert().isTrue()
                node.path("state").isObject.assert().isTrue()
                node.path("state").path("id").asString().assert().isEqualTo(snapshot.aggregateId.id)
            }.verifyComplete()
        }
    }

    @Test
    fun `minimum and maximum should ignore non-numeric BSON values`() {
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .insertMany(
                listOf(
                    Document("_id", "mixed-number")
                        .append("deleted", false)
                        .append("state", Document("mixedValue", 10)),
                    Document("_id", "mixed-string")
                        .append("deleted", false)
                        .append("state", Document("mixedValue", "not-a-number")),
                ),
            ).toMono().then().test().verifyComplete()

        aggregation {
            min("state.mixedValue", "minimum")
            max("state.mixedValue", "maximum")
        }.query(queryBackendBinding)
            .test()
            .assertNext { row ->
                row.assertWireEquals(mapOf("minimum" to 10.0, "maximum" to 10.0))
            }.verifyComplete()
    }

    @Test
    fun `aggregation helper should prepare only on subscription`() {
        val querySchema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT)), emptyMap())
        val schemaCalls = AtomicInteger()
        val backend = object : SnapshotQueryBackend by NoOpSnapshotQueryBackend(MOCK_AGGREGATE_METADATA) {}
        val schemaProvider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> {
                schemaCalls.incrementAndGet()
                return Mono.just(querySchema)
            }

            override fun refresh(): Mono<QueryModelSchema> = schema()
        }

        val publisher = aggregation { count("count") }.query(QueryBackendBinding(backend, schemaProvider))

        schemaCalls.get().assert().isZero()
        publisher.test().verifyComplete()
        schemaCalls.get().assert().isOne()
    }

    @Test
    fun `numeric aggregation should reject non-finite BSON values`() {
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .insertOne(
                Document("_id", "non-finite")
                    .append("deleted", false)
                    .append("state", Document("value", Double.NaN)),
            ).toMono().then().test().verifyComplete()

        aggregation { sum("state.value", "total") }
            .query(queryBackendBinding)
            .test()
            .expectErrorMessage("Aggregation metric [total] must be finite.")
            .verify()
    }

    @Test
    fun `model text search executes while unsupported field restricted search is rejected`() {
        updateStateData("searchable")

        assertThrows<QuerySchemaValidationException> {
            queryBackendBinding.list(
                ListQuery(filter = SearchFilter("searchable", setOf(QueryField("state.data"))), limit = 10),
            )
        }

        val strictService = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
        ).create(MOCK_AGGREGATE_METADATA)
        strictService.list(
            ListQuery(filter = SearchFilter("searchable"), limit = 10),
        )
            .test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should execute ordinary string ranges`() {
        updateStateData("searchable")

        val service = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
        ).create(MOCK_AGGREGATE_METADATA)
        service.list(
            ListQuery(filter = filterExpression { "state.data" gt "alpha" }, limit = 10),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `unknown logical fields fail before MongoDB execution`() {
        assertThrows<QuerySchemaValidationException> {
            queryBackendBinding.list(ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10))
        }
    }

    @Test
    fun `invalid declared epoch literals fail before MongoDB`() {
        run {
            val service = MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = querySchemaSources,
            ).create(MOCK_AGGREGATE_METADATA)

            assertThrows<QuerySchemaValidationException> {
                service.list(
                    ListQuery(
                        filter = filterExpression { "firstEventTime" lte "not-a-timestamp" },
                        limit = 10,
                    ),
                )
            }
        }
    }

    @Test
    fun `strict should execute a client-declared formatted temporal range`() {
        val field = QueryField("state.formattedDate")
        val today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString()
        setStateValidator(Document("formattedDate", Document("bsonType", "string")))
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document("\$set", Document(field.path, today)),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val service = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources + formattedTemporalSource(field, "yyyy-MM-dd"),
        ).create(MOCK_AGGREGATE_METADATA)

        service.list(
            ListQuery(filter = TodayFilter(field, zoneId = "UTC"), limit = 10),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should delegate numeric array ranges metrics and histograms`() {
        val fieldPath = "state.values"
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document("\$set", Document(fieldPath, listOf(7))),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val service = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources + numericArraySource(fieldPath),
        ).create(MOCK_AGGREGATE_METADATA)

        service.list(
            ListQuery(filter = filterExpression { fieldPath.between(2, 8) }, limit = 10),
        ).test().expectNextCount(1).verifyComplete()
        aggregation { sum(field(fieldPath) * constant(1.0), "total") }
            .query(service)
            .test()
            .assertNext { row -> row.assertWireEquals(mapOf("total" to 7.0)) }
            .verifyComplete()
        aggregation { sum(fieldPath, "total") }.query(service)
            .test()
            .assertNext { row -> row.assertWireEquals(mapOf("total" to 7.0)) }
            .verifyComplete()
        val histogram = aggregation {
            histogram(fieldPath, 5.0, "bucket")
            count("count")
        }
        histogram.query(service).test()
            .assertNext { row -> row.assertWireEquals(mapOf("bucket" to 5.0, "count" to 1L)) }
            .verifyComplete()

        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document("\$set", Document(fieldPath, listOf(1, 2, 3))),
            ).toMono().test().expectNextCount(1).verifyComplete()
        histogram.query(service).test().verifyComplete()
    }

    @Test
    fun `strict should execute the built-in ABAC tags filter shape`() {
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document(
                    "\$set",
                    Document("tags.visibility", listOf("public"))
                        .append("tags.department", listOf("eng")),
                ),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val strictService = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
        ).create(MOCK_AGGREGATE_METADATA)
        val abacFilter = mapOf(
            "visibility" to listOf("*"),
            "department" to listOf("eng"),
        ).toFilterExpression()

        strictService.list(
            ListQuery(filter = abacFilter, limit = 10),
        )
            .test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `projection should compile logical scalar and object nodes to physical subtrees`() {
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document(
                    "\$set",
                    Document("document", Document("name", "visible").append("secret", "hidden")),
                ),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val service = QueryBackendBinding(
            MongoSnapshotQueryBackend(
                namedAggregate = MOCK_AGGREGATE_METADATA,
                collection = database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName()),
            ),
            projectionSchemaProvider(),
        )
        fun query(projection: Projection) = service.list(
            ListQuery(MatchAllFilter, projection = projection, limit = 1),
        ).blockFirst()!!

        query(Projection(include = listOf(QueryField("view")))).path("document").let { document ->
            document.path("name").asString().assert().isEqualTo("visible")
            document.path("secret").asString().assert().isEqualTo("hidden")
        }
        query(Projection(include = listOf(QueryField("view.name")))).path("document").let { document ->
            document.path("name").asString().assert().isEqualTo("visible")
            document.has("secret").assert().isFalse()
        }
        query(Projection(exclude = listOf(QueryField("view")))).has("document").assert().isFalse()
        query(Projection(exclude = listOf(QueryField("view.name")))).path("document").let { document ->
            document.has("name").assert().isFalse()
            document.path("secret").asString().assert().isEqualTo("hidden")
        }
        query(
            Projection(
                include = listOf(QueryField("view"), QueryField("view.name")),
                exclude = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
            ),
        ).let { result ->
            result.has(MessageRecords.AGGREGATE_ID).assert().isFalse()
            result.path("document").path("name").asString().assert().isEqualTo("visible")
            result.path("document").path("secret").asString().assert().isEqualTo("hidden")
        }
        query(
            Projection(
                include = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
                exclude = listOf(QueryField("view.name")),
            ),
        ).let { result ->
            result.has(MessageRecords.AGGREGATE_ID).assert().isTrue()
            result.path("document").has("name").assert().isFalse()
            result.path("document").path("secret").asString().assert().isEqualTo("hidden")
        }
    }

    @Test
    fun `gateway should pin projection compilation and masking to one schema generation`() {
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document(
                    "\$set",
                    Document("state.secret", "top-secret").append("state.safe", "decoy"),
                ),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val maskRule = maskRule()
        val deleted = QueryField("deleted")
        val deletionSchema = projectionFieldSchema(deleted).copy(
            value = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
            capabilities = setOf(QueryCapability.EXACT_MATCH),
        )
        val first = projectionSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                deleted to deletionSchema,
                QueryField("state.alias") to projectionFieldSchema(QueryField("state.secret"), maskRule),
            ),
        )
        val second = projectionSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                deleted to deletionSchema,
                QueryField("state.alias") to projectionFieldSchema(QueryField("state.safe"), maskRule),
            ),
        )
        val schemaCalls = AtomicInteger()
        val schemaProvider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(
                if (schemaCalls.getAndIncrement() == 0) first else second,
            )

            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
        val backend = MongoSnapshotQueryBackend(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            collection = database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName()),
        )
        val gateway = DefaultSnapshotQueryGateway<Any>(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            binding = QueryBackendBinding(backend, schemaProvider),
            targetType = JsonSerializer.typeFactory.constructType(Any::class.java),
        )

        val result = gateway.dynamicSingle(
            SingleQuery(
                MatchAllFilter,
                projection = Projection(include = listOf(QueryField("state.alias"))),
            ),
        ).block()!!

        result.path("state").path("secret").asString().assert().isEqualTo("**********")
        result.path("state").has("safe").assert().isFalse()
        schemaCalls.get().assert().isOne()
    }

    @Test
    fun `invalid dynamic tags reject filters and projections then recover`() {
        setValidator(Document("tags", Document("bsonType", "string")))
        val abacFilter = mapOf(
            "visibility" to listOf("*"),
            "department" to listOf("eng"),
        ).toFilterExpression()

        run {
            val invalidService = MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = querySchemaSources,
            ).create(MOCK_AGGREGATE_METADATA)
            assertThrows<QuerySchemaValidationException> {
                invalidService.list(ListQuery(filter = abacFilter, limit = 10))
            }
            val rawProjectionQuery = ListQuery(
                filter = MatchAllFilter,
                projection = Projection(include = listOf(QueryField("tags.department"))),
                limit = 10,
            )
            assertThrows<QuerySchemaValidationException> {
                invalidService.list(rawProjectionQuery)
            }
        }

        setValidator(Document("tags", Document("bsonType", "object")))
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document(
                    "\$set",
                    Document("tags.visibility", listOf("public"))
                        .append("tags.department", listOf("eng")),
                ),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val validService = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
        ).create(MOCK_AGGREGATE_METADATA)

        validService.list(
            ListQuery(filter = abacFilter, limit = 10),
        )
            .test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `native BSON date and timestamp value filters should fail closed before MongoDB execution`() {
        val now = Instant.now()
        setStateValidator(
            Document("nativeDate", Document("bsonType", "date"))
                .append("nativeTimestamp", Document("bsonType", "timestamp")),
        )
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document(
                    "\$set",
                    Document("state.nativeDate", Date.from(now))
                        .append("state.nativeTimestamp", BsonTimestamp(now.epochSecond.toInt(), 1)),
                ),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val filters: List<FilterExpression> = listOf(
            filterExpression { "state.nativeDate" eq now.toEpochMilli() },
            filterExpression {
                "state.nativeTimestamp".between(now.minusSeconds(60).toEpochMilli(), now.plusSeconds(60).toEpochMilli())
            },
            TodayFilter(QueryField("state.nativeDate"), zoneId = "UTC"),
        )

        run {
            val service = MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = querySchemaSources + nativeTemporalSource(),
            ).create(MOCK_AGGREGATE_METADATA)
            filters.forEach { filter ->
                assertThrows<QuerySchemaValidationException> {
                    service.list(ListQuery(filter = filter, limit = 10))
                }
            }
        }
    }

    @Test
    fun `native BSON temporal sort terms and date histogram should remain executable`() {
        val now = Instant.now()
        setStateValidator(
            Document("nativeDate", Document("bsonType", "date"))
                .append("nativeTimestamp", Document("bsonType", "timestamp")),
        )
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document(
                    "\$set",
                    Document("state.nativeDate", Date.from(now))
                        .append("state.nativeTimestamp", BsonTimestamp(now.epochSecond.toInt(), 1)),
                ),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val service = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources + nativeTemporalSource(),
        ).create(MOCK_AGGREGATE_METADATA)

        service.list(
            ListQuery(
                filter = MatchAllFilter,
                sort = listOf(Sort(QueryField("state.nativeDate"), Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
        aggregation {
            terms("state.nativeDate", "date")
            count("count")
        }.query(service).test().expectNextCount(1).verifyComplete()
        aggregation {
            dateHistogram("state.nativeTimestamp", AggregationDateUnit.DAY, "day")
            count("count")
        }.query(service).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `date operations without a validator should fail closed before MongoDB execution`() {
        clearValidator()
        run {
            val service = MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = querySchemaSources + nativeTemporalSource(),
            ).create(MOCK_AGGREGATE_METADATA)

            assertThrows<QuerySchemaValidationException> {
                service.list(
                    ListQuery(
                        filter = TodayFilter(QueryField("state.nativeDate"), zoneId = "UTC"),
                        limit = 10,
                    ),
                )
            }
            val query = aggregation {
                dateHistogram("state.nativeDate", AggregationDateUnit.DAY, "day")
                count("count")
            }
            assertThrows<QuerySchemaValidationException> { resolveAggregation(service, query) }
        }
    }

    @Test
    fun `declared dynamic string map terms execute using value node bindings`() {
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document("\$set", Document("state.attributes", Document("color", "red"))),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val query = aggregation {
            terms("state.attributes.color", "color")
            count("count")
        }
        val sources = querySchemaSources + dynamicStringMapSource()

        val compatibleService = MongoSnapshotQueryBackendFactory(database = database, schemaSources = sources)
            .create(MOCK_AGGREGATE_METADATA)
        query.query(compatibleService).test()
            .assertNext { row -> row.assertWireEquals(mapOf("color" to "red", "count" to 1L)) }
            .verifyComplete()

    }

    @Test
    fun `strict should reject invalid container descendants and execute valid element match`() {
        setStateValidator(
            Document(
                "orders",
                Document("bsonType", "array").append("items", Document("bsonType", "string")),
            ),
        )
        val invalidService = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
        ).create(MOCK_AGGREGATE_METADATA)

        assertThrows<QuerySchemaValidationException> {
            invalidService.list(
                ListQuery(
                    filter = filterExpression { "state.orders.status" eq "created" },
                    limit = 10,
                ),
            )
        }
        assertThrows<QuerySchemaValidationException> {
            invalidService.list(
                ListQuery(
                    filter = MatchAllFilter,
                    sort = listOf(Sort(QueryField("state.orders.status"), Sort.Direction.ASC)),
                    limit = 10,
                ),
            )
        }

        setStateValidator(
            Document(
                "orders",
                Document("bsonType", "array").append(
                    "items",
                    Document("bsonType", "object").append(
                        "properties",
                        Document("status", Document("bsonType", "string")),
                    ),
                ),
            ),
        )
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document("\$set", Document("state.orders", listOf(Document("status", "created")))),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val validService = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
        ).create(MOCK_AGGREGATE_METADATA)

        validService.list(
            ListQuery(
                filter = filterExpression {
                    "state.orders".elementMatch { "status" eq "created" }
                },
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `epoch date histogram should floor negatives and ignore invalid or multi values`() {
        val collection = database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
        collection.insertMany(
            listOf(
                epochDocument("epoch-negative", -500L),
                epochDocument("epoch-zero", 0L),
                epochDocument("epoch-singleton", listOf(1_000L)),
                epochDocument("epoch-invalid", "invalid"),
                epochDocument("epoch-multi", listOf(1_000L, 2_000L)),
            ),
        ).toMono().then().test().verifyComplete()
        val service = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = listOf(epochSource("state.epochMicros", TimeUnit.MICROSECONDS)),
        ).create(MOCK_AGGREGATE_METADATA)

        aggregation {
            dateHistogram("state.epochMicros", me.ahoo.wow.api.query.AggregationDateUnit.DAY, "day")
            count("count")
        }.query(service)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(ObjectNode::toWireJsonNode).assert().containsExactly(
                    mapOf("day" to -86_400_000L, "count" to 1L).toWireJsonNode(),
                    mapOf("day" to 0L, "count" to 2L).toWireJsonNode(),
                )
            }
            .verifyComplete()
    }

    @Test
    fun `microsecond epoch conversion should preserve long precision and floor negatives`() {
        val ids = listOf("epoch-extreme", "epoch-negative-precision")
        val collection = database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
        collection.insertMany(
            listOf(
                epochDocument(ids[0], 9_223_372_036_852_999_000L),
                epochDocument(ids[1], -500L),
            ),
        ).toMono().then().test().verifyComplete()
        val query = aggregation {
            dateHistogram("state.epochMicros", AggregationDateUnit.DAY, "day")
            count("count")
        }
        val service = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = listOf(epochSource("state.epochMicros", TimeUnit.MICROSECONDS)),
        ).create(MOCK_AGGREGATE_METADATA)
        val dateInput = MongoAggregationCompiler(SnapshotFilterCompiler)
            .compile(query, service.schemaProvider.schema().block()!!)
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().getDocument("\$group")
            .getDocument("_id").getDocument("day")
            .getDocument("\$toLong").getDocument("\$dateTrunc")["date"]
        val projection = BsonDocument(
            "\$project",
            BsonDocument("_id", BsonInt32(1))
                .append("epochMillis", BsonDocument("\$toLong", dateInput)),
        )

        collection.aggregate(
            listOf(
                Document("\$match", Document("_id", Document("\$in", ids))),
                projection,
            ),
        ).toFlux().collectList().test()
            .assertNext { documents ->
                documents.associate { it.getString("_id") to it.getLong("epochMillis") }.assert().isEqualTo(
                    mapOf(
                        ids[0] to 9_223_372_036_852_999L,
                        ids[1] to -1L,
                    ),
                )
            }
            .verifyComplete()
    }

    @Test
    fun `aggregation should execute resolved epoch filters at root and element scopes`() {
        val now = Instant.now()
        val timeZone = ZoneOffset.ofHours(12 - now.atOffset(ZoneOffset.UTC).hour)
        val nowSeconds = now.epochSecond
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document(
                    "\$set",
                    Document("state.epochSeconds", nowSeconds)
                        .append(
                            "state.events",
                            listOf(Document("occurredAt", nowSeconds)),
                        ),
                ),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val service = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources + aggregationExecutionSource(),
        ).create(MOCK_AGGREGATE_METADATA)

        aggregation {
            filter(TodayFilter(QueryField("state.epochSeconds"), zoneId = timeZone.id))
            expand("state.events") { "occurredAt".today(timeZone) }
            count("count")
        }.query(service)
            .test()
            .assertNext { row -> row.assertWireEquals(mapOf("count" to 1L)) }
            .verifyComplete()
    }

    private fun epochDocument(id: String, value: Any): Document = Document("_id", id)
        .append("deleted", false)
        .append("state", Document("epochMicros", value))

    private fun projectionSchemaProvider(): QueryModelSchemaProvider {
        val schema = projectionSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                QueryField("deleted") to ProjectionField(
                    QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
                    QueryField("deleted"),
                    setOf(QueryCapability.EXACT_MATCH),
                ),
                QueryField("view") to projectionFieldSchema(QueryField("document"), kind = QueryValueKind.OBJECT),
                QueryField("view.name") to projectionFieldSchema(QueryField("document.name")),
                QueryField(MessageRecords.AGGREGATE_ID) to projectionFieldSchema(QueryField("_id")),
            ),
        )
        return object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
            override fun refresh(): Mono<QueryModelSchema> = Mono.just(schema)
        }
    }

    private data class ProjectionField(
        val value: QueryValueSchema,
        val path: QueryField,
        val capabilities: Set<QueryCapability> = emptySet(),
    )

    private fun projectionFieldSchema(
        projectionField: QueryField,
        maskRule: MaskRule? = null,
        kind: QueryValueKind = QueryValueKind.SCALAR,
    ) = ProjectionField(
        QueryValueSchema(kind, valueTypes = setOf(if (kind == QueryValueKind.OBJECT) QueryValueType.OBJECT else QueryValueType.STRING), maskRule = maskRule),
        projectionField,
    )

    private fun projectionSchema(
        model: QueryModel,
        capabilities: Set<QueryCapability>,
        fields: Map<QueryField, ProjectionField>,
    ): QueryModelSchema {
        fun objectAt(prefix: String): QueryValueSchema {
            val names = fields.keys.filter { it.path.startsWith(prefix) }
                .map { it.path.removePrefix(prefix).substringBefore('.') }.distinct()
            return QueryValueSchema(QueryValueKind.OBJECT, properties = names.associateWith { name ->
                val path = prefix + name
                val fixture = fields[QueryField(path)]
                if (fixture == null || fixture.value.kind == QueryValueKind.OBJECT) objectAt("$path.") else fixture.value
            })
        }
        val definition = LogicalQuerySchema(objectAt(""))
        fun path(field: QueryField) = QueryPathTemplate(field.path.split('.').map(QueryPathSegment::Property))
        val bindings = fields.map { (field, fixture) ->
            val native = path(fixture.path)
            path(field) to QueryValueBindings(
                fixture.capabilities.associateWith { QueryFieldBindingTemplate(native, null) }, native, native,
            )
        }.toMap()
        return QueryModelSchema(model, capabilities, definition, bindings)
    }

    private fun maskRule(): MaskRule {
        val annotation = Masked::value.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private fun updateStateData(value: String) {
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document("\$set", Document("state.data", value)),
            ).toMono().test().expectNextCount(1).verifyComplete()
    }

    private fun setStateValidator(properties: Document) {
        setValidator(
            Document(
                "state",
                Document("bsonType", "object").append("properties", properties),
            ),
        )
    }

    private fun clearValidator() {
        database.runCommand(
            Document("collMod", MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
                .append("validator", Document()),
        ).toMono().test().expectNextCount(1).verifyComplete()
    }

    private fun nestedLineDateValidator() = Document(
        "orders",
        Document("bsonType", "array").append(
            "items",
            Document("bsonType", "object").append(
                "properties",
                Document(
                    "lines",
                    Document("bsonType", "array").append(
                        "items",
                        Document("bsonType", "object").append(
                            "properties",
                            Document("createdAt", Document("bsonType", "date")),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun setValidator(properties: Document) {
        database.runCommand(
            Document("collMod", MOCK_AGGREGATE_METADATA.toSnapshotCollectionName()).append(
                "validator",
                Document(
                    "\$jsonSchema",
                    Document("bsonType", "object").append(
                        "properties",
                        properties,
                    ),
                ),
            ),
        ).toMono().test().expectNextCount(1).verifyComplete()
    }

    private fun nativeTemporalSource(): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.BEAN

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
            QuerySchemaDeclaration(
                listOf("state.nativeDate", "state.nativeTimestamp").associate { field ->
                    QueryField(field) to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                        nullable = DeclarationValue.Set(false),
                        required = DeclarationValue.Set(true),
                        semanticType = DeclarationValue.Set(Temporal.Date),
                    )
                },
            ),
        )
    }

    private fun formattedTemporalSource(field: QueryField, pattern: String): QuerySchemaSource =
        object : QuerySchemaSource {
            override val priority: Int = QuerySchemaSourcePriority.BEAN

            override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
                QuerySchemaDeclaration(
                    mapOf(
                        field to QueryFieldDeclaration(
                            valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                            semanticType = DeclarationValue.Set(Temporal.Formatted(pattern)),
                        ),
                    ),
                ),
            )
        }

    private fun numericArraySource(field: String): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.BEAN

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
            QuerySchemaDeclaration(
                mapOf(
                    QueryField(field) to QueryFieldDeclaration(
                        items = DeclarationValue.Set(QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)))),
                    ),
                ),
            ),
        )
    }

    private fun dynamicStringMapSource(): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.BEAN

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
            QuerySchemaDeclaration(
                mapOf(
                    QueryField("state.attributes") to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)),
                        nullable = DeclarationValue.Set(false),
                        required = DeclarationValue.Set(true),
                        additionalProperties = DeclarationValue.Set(QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))),
                    ),
                ),
            ),
        )
    }

    private fun epochSource(field: String, timeUnit: TimeUnit): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.BEAN

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
            QuerySchemaDeclaration(
                mapOf(
                    QueryField(field) to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
                        nullable = DeclarationValue.Set(true),
                        required = DeclarationValue.Set(false),
                        semanticType = DeclarationValue.Set(Temporal.Epoch(timeUnit)),
                    ),
                ),
            ),
        )
    }

    private fun invalidNumericFixtureSource(): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.BEAN

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
            QuerySchemaDeclaration(
                listOf("state.mixedValue", "state.value", "state.orders.lines.missing").associate { name ->
                    QueryField(name) to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.DECIMAL)),
                        nullable = DeclarationValue.Set(true),
                        required = DeclarationValue.Set(false),
                    )
                },
            ),
        )
    }

    private fun aggregationExecutionSource(): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.BEAN

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
            QuerySchemaDeclaration(
                mapOf(
                    QueryField("state.epochSeconds") to epochDeclaration(),
                    QueryField("state.events") to QueryFieldDeclaration(
                        items = DeclarationValue.Set(QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)))),
                    ),
                    QueryField("state.events.occurredAt") to epochDeclaration(),
                ),
            ),
        )
    }

    private fun epochDeclaration() = QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
        nullable = DeclarationValue.Set(false),
        required = DeclarationValue.Set(true),
        semanticType = DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)),
    )
}

private fun AggregationQuery.query(
    binding: QueryBackendBinding<SnapshotQueryBackend>,
): Flux<ObjectNode> = Mono.defer { binding.schemaProvider.schema() }.flatMapMany { schema ->
    binding.backend.aggregate(this.also { validateQuery(it, schema) }, schema)
}

private fun QueryBackendBinding<SnapshotQueryBackend>.list(
    query: IListQuery,
): Flux<ObjectNode> {
    val schema = schemaProvider.schema().block()!!
    return backend.list(query.also { validateQuery(it, schema) }, schema)
}

private fun resolveAggregation(
    binding: QueryBackendBinding<SnapshotQueryBackend>,
    query: AggregationQuery,
): AggregationQuery {
    val schema = binding.schemaProvider.schema().block()!!
    return query.also { validateQuery(it, schema) }
}
private fun Any.toWireJsonNode(): JsonNode = JsonSerializer.readTree(JsonSerializer.writeValueAsBytes(this))
private fun ObjectNode.assertWireEquals(expected: Any) {
    toWireJsonNode().assert().isEqualTo(expected.toWireJsonNode())
}

private class NativeDateSnapshotStore(private val database: MongoDatabase) :
    SnapshotStore by MongoSnapshotStore(database) {
    override fun <S : Any> save(snapshot: Snapshot<S>) = snapshot.toMongoSnapshotWrite().let { write ->
        write.document.convertLineDates()
        database.getCollection(write.collectionName)
            .updateOne(
                Filters.eq("_id", write.id),
                versionGuardedSnapshotReplacement(write.document),
                UpdateOptions().upsert(true),
            ).toMono()
            .doOnNext { check(it.wasAcknowledged()) }
            .then()
    }
}

@Suppress("UNCHECKED_CAST")
private fun Document.convertLineDates() {
    val state = this["state"] as? MutableMap<String, Any?> ?: return
    val orders = state["orders"] as? List<*> ?: return
    for (order in orders) {
        val lines = (order as? Map<*, *>)?.get("lines") as? List<*> ?: continue
        for (line in lines) {
            val values = line as? MutableMap<String, Any?> ?: continue
            val createdAt = values["createdAt"] as? String ?: continue
            values["createdAt"] = Date.from(Instant.parse(createdAt))
        }
    }
}

private data class Masked(@field:Mask val value: String)
