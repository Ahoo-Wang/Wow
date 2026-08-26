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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.concurrent.TimeUnit

class MongoSnapshotQueryServiceTest : SnapshotQueryServiceSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    lateinit var database: MongoDatabase

    @BeforeEach
    override fun setup() {
        database = mongo.database()
        super.setup()
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return MongoSnapshotQueryServiceFactory(database)
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

    private fun epochDocument(id: String, value: Any): Document = Document("_id", id)
        .append("deleted", false)
        .append("state", Document("epochMicros", value))

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
}
