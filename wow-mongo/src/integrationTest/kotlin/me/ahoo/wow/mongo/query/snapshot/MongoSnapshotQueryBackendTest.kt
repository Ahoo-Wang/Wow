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

import com.mongodb.reactivestreams.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.toMongoSnapshotWrite
import me.ahoo.wow.mongo.versionGuardedSnapshotReplacement
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.requiredQueryModelSchemaProvider
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter.Companion.toFilterExpression
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.SnapshotQueryBackendSpec
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.Document
import org.bson.BsonTimestamp
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

class MongoSnapshotQueryBackendTest : SnapshotQueryBackendSpec() {
    override val cursorQuerySupported: Boolean = true

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
            schemaSources = querySchemaSources,
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

        snapshotQueryBackend.list(snapshotOwnershipQuery())
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
        snapshotQueryBackend.list(snapshotOwnershipQuery())
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
        val publisher = snapshotQueryBackend.list(snapshotOwnershipQuery()).next()

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
        }.query(snapshotQueryBackend)
            .test()
            .assertNext { row ->
                row.assertWireEquals(mapOf("minimum" to 10.0, "maximum" to 10.0))
            }.verifyComplete()
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
            .query(snapshotQueryBackend)
            .test()
            .expectErrorMessage("Aggregation metric [total] must be finite.")
            .verify()
    }

    @Test
    fun `direct backend constructor should retain snapshot identity schema behavior`() {
        val service = MongoSnapshotQueryBackend(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            collection = database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName()),
        )

        service.schema().test()
            .assertNext { schema ->
                schema.model.assert().isEqualTo(QueryModel.SNAPSHOT)
                schema.fields.keys.assert().contains(LogicalField("aggregateId"))
            }
            .verifyComplete()
    }

    @Test
    fun `empty search fields should be exact while explicit fields execute compatibly`() {
        updateStateData("searchable")

        snapshotQueryBackend.list(
            ListQuery(
                filter = SearchFilter("searchable", setOf(LogicalField("state.data"))),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()

        val strictService = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        strictService.list(ListQuery(filter = SearchFilter("searchable"), limit = 10))
            .test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should execute ordinary string ranges`() {
        updateStateData("searchable")

        MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
            .list(
                ListQuery(filter = filterExpression { "state.data" gt "alpha" }, limit = 10),
            ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should reject unknown fields while compatible executes fallback`() {
        snapshotQueryBackend.list(
            ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10),
        ).test().verifyComplete()

        val strictService = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        strictService.list(
            ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `all modes should reject invalid declared epoch literals before MongoDB`() {
        QuerySchemaValidationMode.entries.forEach { mode ->
            val service = MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = querySchemaSources,
                validationMode = mode,
            ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

            service.list(
                ListQuery(
                    filter = filterExpression { "firstEventTime" lte "not-a-timestamp" },
                    limit = 10,
                ),
            ).test().expectError(QuerySchemaValidationException::class.java).verify()
        }
    }

    @Test
    fun `strict should execute a client-declared formatted temporal range`() {
        val field = LogicalField("state.formattedDate")
        val today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString()
        setStateValidator(Document("formattedDate", Document("bsonType", "string")))
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .updateOne(
                Document("_id", snapshot.aggregateId.id),
                Document("\$set", Document(field.value, today)),
            ).toMono().test().expectNextCount(1).verifyComplete()
        val service = MongoSnapshotQueryBackendFactory(
            database = database,
            schemaSources = querySchemaSources + formattedTemporalSource(field, "yyyy-MM-dd"),
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

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
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        service.list(
            ListQuery(filter = filterExpression { fieldPath.between(2, 8) }, limit = 10),
        ).test().expectNextCount(1).verifyComplete()
        aggregation { sum(field(fieldPath) * constant(1.0), "total") }.query(service)
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
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val abacFilter = mapOf(
            "visibility" to listOf("*"),
            "department" to listOf("eng"),
        ).toFilterExpression()

        strictService.list(ListQuery(filter = abacFilter, limit = 10))
            .test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `invalid dynamic tags should fail filters while projection respects mode then recover`() {
        setValidator(Document("tags", Document("bsonType", "string")))
        val abacFilter = mapOf(
            "visibility" to listOf("*"),
            "department" to listOf("eng"),
        ).toFilterExpression()

        QuerySchemaValidationMode.entries.forEach { mode ->
            val invalidService = MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = querySchemaSources,
                validationMode = mode,
            ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
            invalidService.list(ListQuery(filter = abacFilter, limit = 10))
                .test().expectError(QuerySchemaValidationException::class.java).verify()
            val projectionQuery = invalidService.list(
                ListQuery(
                    filter = MatchAllFilter,
                    projection = Projection(include = listOf("tags.department")),
                    limit = 10,
                ),
            ).test()
            if (mode == QuerySchemaValidationMode.COMPATIBLE) {
                projectionQuery.expectNextCount(1).verifyComplete()
            } else {
                projectionQuery.expectError(QuerySchemaValidationException::class.java).verify()
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
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        validService.list(ListQuery(filter = abacFilter, limit = 10))
            .test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `native BSON date and timestamp value filters should fail closed in every validation mode`() {
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
            TodayFilter(LogicalField("state.nativeDate"), zoneId = "UTC"),
        )

        QuerySchemaValidationMode.entries.forEach { mode ->
            val service = MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = querySchemaSources + nativeTemporalSource(),
                validationMode = mode,
            ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
            filters.forEach { filter ->
                service.list(ListQuery(filter = filter, limit = 10))
                    .test().expectError(QuerySchemaValidationException::class.java).verify()
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
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        service.list(
            ListQuery(
                filter = MatchAllFilter,
                sort = listOf(Sort("state.nativeDate", Sort.Direction.ASC)),
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
    fun `date operations without a validator should fail closed in every validation mode`() {
        clearValidator()
        QuerySchemaValidationMode.entries.forEach { mode ->
            val service = MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = querySchemaSources + nativeTemporalSource(),
                validationMode = mode,
            ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

            service.list(
                ListQuery(
                    filter = TodayFilter(LogicalField("state.nativeDate"), zoneId = "UTC"),
                    limit = 10,
                ),
            ).test().expectError(QuerySchemaValidationException::class.java).verify()
            aggregation {
                dateHistogram("state.nativeDate", AggregationDateUnit.DAY, "day")
                count("count")
            }.query(service).test().expectError(QuerySchemaValidationException::class.java).verify()
        }
    }

    @Test
    fun `dynamic string map terms should execute compatibly and fail strict validation`() {
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

        query.query(
            MongoSnapshotQueryBackendFactory(database = database, schemaSources = sources)
                .create<MockStateAggregate>(MOCK_AGGREGATE_METADATA),
        ).test()
            .assertNext { row -> row.assertWireEquals(mapOf("color" to "red", "count" to 1L)) }
            .verifyComplete()
        query.query(
            MongoSnapshotQueryBackendFactory(
                database = database,
                schemaSources = sources,
                validationMode = QuerySchemaValidationMode.STRICT,
            ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
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
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        invalidService.list(
            ListQuery(
                filter = filterExpression { "state.orders.status" eq "created" },
                limit = 10,
            ),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
        invalidService.list(
            ListQuery(
                filter = MatchAllFilter,
                sort = listOf(Sort("state.orders.status", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()

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
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

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
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

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
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val dateInput = MongoAggregationCompiler(SnapshotFilterConverter)
            .compile(query, service.requiredQueryModelSchemaProvider().schema().block()!!)
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
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        aggregation {
            filter(TodayFilter(LogicalField("state.epochSeconds"), zoneId = timeZone.id))
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
                    LogicalField(field) to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                        nullable = DeclarationValue.Set(false),
                        required = DeclarationValue.Set(true),
                        cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
                        semanticType = DeclarationValue.Set(Temporal.Date),
                    )
                },
            ),
        )
    }

    private fun formattedTemporalSource(field: LogicalField, pattern: String): QuerySchemaSource =
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
                    LogicalField(field) to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
                        cardinality = DeclarationValue.Set(QueryCardinality.MANY),
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
                    LogicalField("state.attributes") to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)),
                        nullable = DeclarationValue.Set(false),
                        required = DeclarationValue.Set(true),
                        cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
                        dynamicChildren = DeclarationValue.Set(true),
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
                    LogicalField(field) to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
                        nullable = DeclarationValue.Set(true),
                        required = DeclarationValue.Set(false),
                        cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
                        semanticType = DeclarationValue.Set(Temporal.Epoch(timeUnit)),
                    ),
                ),
            ),
        )
    }

    private fun aggregationExecutionSource(): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.BEAN

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
            QuerySchemaDeclaration(
                mapOf(
                    LogicalField("state.epochSeconds") to epochDeclaration(),
                    LogicalField("state.events") to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)),
                        cardinality = DeclarationValue.Set(QueryCardinality.MANY),
                    ),
                    LogicalField("state.events.occurredAt") to epochDeclaration(),
                ),
            ),
        )
    }

    private fun epochDeclaration() = QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
        nullable = DeclarationValue.Set(false),
        required = DeclarationValue.Set(true),
        cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
        semanticType = DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)),
    )
}

private fun AggregationQuery.query(backend: SnapshotQueryBackend): Flux<ObjectNode> = backend.aggregate(this)
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
