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
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.FieldType
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
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
        super.setup()
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return MongoSnapshotQueryServiceFactory(database)
    }

    override fun createSnapshotStore(): SnapshotStore {
        return MongoSnapshotStore(database)
    }

    @Test
    fun `DATE histogram should group native BSON dates`() {
        val instant = Instant.parse("2026-01-01T10:00:00Z")
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .insertOne(
                Document("_id", "native-date")
                    .append("deleted", false)
                    .append("state", Document("nativeDate", Date.from(instant))),
            ).toMono().then().test().verifyComplete()

        aggregation {
            dateHistogram(
                LogicalField("state.nativeDate", FieldType.Temporal.Date),
                AggregationDateUnit.DAY,
                "day",
                ZoneOffset.UTC,
            )
            count("count")
        }.query(snapshotQueryService)
            .test()
            .assertNext { row ->
                row.toMap().assert().isEqualTo(mapOf("day" to 1_767_225_600_000L, "count" to 1L))
            }.verifyComplete()
    }

    @Test
    fun `NUMBER histograms should ignore invalid scalar and non-scalar values`() {
        val epochMillis = 1_767_225_600_000L
        val epochDay = 20_454L
        database.getCollection(MOCK_AGGREGATE_METADATA.toSnapshotCollectionName())
            .insertMany(
                listOf(
                    Document("_id", "valid")
                        .append("deleted", false)
                        .append(
                            "state",
                            Document("epochMillis", epochMillis).append("epochDay", epochDay),
                        ),
                    Document("_id", "missing")
                        .append("deleted", false)
                        .append("state", Document()),
                    Document("_id", "null")
                        .append("deleted", false)
                        .append("state", Document("epochMillis", null).append("epochDay", null)),
                    Document("_id", "empty")
                        .append("deleted", false)
                        .append(
                            "state",
                            Document("epochMillis", emptyList<Long>()).append("epochDay", emptyList<Long>()),
                        ),
                    Document("_id", "multi")
                        .append("deleted", false)
                        .append(
                            "state",
                            Document("epochMillis", listOf(epochMillis, epochMillis + 86_400_000L))
                                .append("epochDay", listOf(epochDay, epochDay + 1)),
                        ),
                    Document("_id", "string")
                        .append("deleted", false)
                        .append(
                            "state",
                            Document("epochMillis", epochMillis.toString()).append("epochDay", epochDay.toString()),
                        ),
                    Document("_id", "fraction")
                        .append("deleted", false)
                        .append("state", Document("epochMillis", 1.5).append("epochDay", 1.5)),
                    Document("_id", "non-finite")
                        .append("deleted", false)
                        .append(
                            "state",
                            Document("epochMillis", Double.NaN)
                                .append("epochDay", Double.POSITIVE_INFINITY),
                        ),
                    Document("_id", "overflow")
                        .append("deleted", false)
                        .append(
                            "state",
                            Document("epochDay", Long.MAX_VALUE),
                        ),
                ),
            ).toMono().then().test().verifyComplete()

        mapOf(
            "epochMillis" to TimeUnit.MILLISECONDS,
            "epochDay" to TimeUnit.DAYS,
        ).forEach { (field, timeUnit) ->
            aggregation {
                dateHistogram(
                    LogicalField("state.$field", FieldType.Temporal.NumericEpoch(timeUnit)),
                    AggregationDateUnit.DAY,
                    "day",
                    ZoneOffset.UTC,
                )
                count("count")
            }.query(snapshotQueryService)
                .collectList()
                .test()
                .assertNext { rows ->
                    rows.map(Map<String, Any?>::toMap).assert()
                        .containsExactly(mapOf("day" to epochMillis, "count" to 1L))
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
}
