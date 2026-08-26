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
import com.mongodb.client.model.Indexes
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.MongoSnapshotStore
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
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter.Companion.toFilterExpression
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.bson.Document
import org.bson.BsonTimestamp
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.TimeUnit

class MongoSnapshotQueryServiceTest : SnapshotQueryServiceSpec() {
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
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return MongoSnapshotQueryServiceFactory(database, querySchemaSources)
    }

    override fun createSnapshotStore(): SnapshotStore {
        return MongoSnapshotStore(database)
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
        }.query(snapshotQueryService)
            .test()
            .assertNext { row ->
                row.toMap().assert().isEqualTo(mapOf("minimum" to 10.0, "maximum" to 10.0))
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
            .query(snapshotQueryService)
            .test()
            .expectErrorMessage("Aggregation metric [total] must be finite.")
            .verify()
    }

    @Test
    fun `direct service constructor should retain snapshot identity schema behavior`() {
        val service = MongoSnapshotQueryService<MockStateAggregate>(
            MOCK_AGGREGATE_METADATA,
            database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName()),
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

        snapshotQueryService.dynamicList(
            ListQuery(
                filter = SearchFilter("searchable", setOf(LogicalField("state.data"))),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()

        val strictService = MongoSnapshotQueryServiceFactory(
            database,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        strictService.dynamicList(ListQuery(filter = SearchFilter("searchable"), limit = 10))
            .test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should reject unknown fields while compatible executes fallback`() {
        snapshotQueryService.dynamicList(
            ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10),
        ).test().verifyComplete()

        val strictService = MongoSnapshotQueryServiceFactory(
            database,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        strictService.dynamicList(
            ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
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
        val strictService = MongoSnapshotQueryServiceFactory(
            database,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val abacFilter = mapOf(
            "visibility" to listOf("*"),
            "department" to listOf("eng"),
        ).toFilterExpression()

        strictService.dynamicList(ListQuery(filter = abacFilter, limit = 10))
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
            val service = MongoSnapshotQueryServiceFactory(
                database,
                schemaSources = querySchemaSources + nativeTemporalSource(),
                validationMode = mode,
            ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
            filters.forEach { filter ->
                service.dynamicList(ListQuery(filter = filter, limit = 10))
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
        val service = MongoSnapshotQueryServiceFactory(
            database,
            schemaSources = querySchemaSources + nativeTemporalSource(),
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        service.dynamicList(
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
    fun `strict should reject invalid container descendants and execute valid element match`() {
        setStateValidator(
            Document(
                "orders",
                Document("bsonType", "array").append("items", Document("bsonType", "string")),
            ),
        )
        val invalidService = MongoSnapshotQueryServiceFactory(
            database,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        invalidService.dynamicList(
            ListQuery(
                filter = filterExpression { "state.orders.status" eq "created" },
                limit = 10,
            ),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
        invalidService.dynamicList(
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
        val validService = MongoSnapshotQueryServiceFactory(
            database,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        validService.dynamicList(
            ListQuery(
                filter = filterExpression {
                    "state.orders".elementMatch { "status" eq "created" }
                },
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `epoch date histogram should floor negatives and safely group invalid or multi values as null`() {
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
        val service = MongoSnapshotQueryServiceFactory(
            database,
            schemaSources = listOf(epochSource("state.epochMicros", TimeUnit.MICROSECONDS)),
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        aggregation {
            dateHistogram("state.epochMicros", me.ahoo.wow.api.query.AggregationDateUnit.DAY, "day")
            count("count")
        }.query(service)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf("day" to null, "count" to 2L),
                    mapOf("day" to -86_400_000L, "count" to 1L),
                    mapOf("day" to 0L, "count" to 2L),
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
        val service = MongoSnapshotQueryServiceFactory(
            database,
            schemaSources = querySchemaSources + aggregationExecutionSource(),
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        aggregation {
            filter(TodayFilter(LogicalField("state.epochSeconds"), zoneId = timeZone.id))
            expand("state.events") { "occurredAt".today(timeZone) }
            count("count")
        }.query(service)
            .test()
            .assertNext { row -> row.toMap().assert().isEqualTo(mapOf("count" to 1L)) }
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
        database.runCommand(
            Document("collMod", MOCK_AGGREGATE_METADATA.toSnapshotCollectionName()).append(
                "validator",
                Document(
                    "\$jsonSchema",
                    Document("bsonType", "object").append(
                        "properties",
                        Document(
                            "state",
                            Document("bsonType", "object").append("properties", properties),
                        ),
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
