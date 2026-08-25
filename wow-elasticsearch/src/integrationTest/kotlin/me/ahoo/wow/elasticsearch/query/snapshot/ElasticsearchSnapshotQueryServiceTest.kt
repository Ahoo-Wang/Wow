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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch.core.UpdateRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.FieldType
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldUsage
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldResolutionException
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class ElasticsearchSnapshotQueryServiceTest : SnapshotQueryServiceSpec() {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    lateinit var elasticsearchClient: ReactiveElasticsearchClient

    @BeforeEach
    override fun setup() {
        elasticsearchClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        elasticsearchClient.initSnapshotTemplate()
        elasticsearchClient.indices().create { request ->
            request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
                .mappings { mapping ->
                    mapping
                        .properties("epoch") { it.long_ { number -> number.ignoreMalformed(true) } }
                        .properties("epochFraction") { it.double_ { number -> number.ignoreMalformed(true) } }
                        .properties("epochMulti") { it.long_ { number -> number } }
                        .properties("epochUnsigned") { it.unsignedLong { number -> number } }
                        .properties("relativeDate") { it.date { date -> date } }
                        .properties("relativeEpoch") { it.long_ { number -> number } }
                        .properties("relativeText") { property ->
                            property.text { text ->
                                text.fields("keyword") { field -> field.keyword { keyword -> keyword } }
                            }
                        }
                        .properties("state") { state ->
                            state.`object` { stateObject ->
                                stateObject
                                    .properties("data") { data ->
                                        data.text { text ->
                                            text.fielddata(false)
                                                .fields("keyword") { keyword -> keyword.keyword { it } }
                                        }
                                    }
                                    .properties("decimalValue") { it.double_ { number -> number } }
                                    .properties("unreadableNumber") {
                                        it.double_ { number -> number.index(false).docValues(false) }
                                    }
                                    .properties("orders") { orders ->
                                        orders.nested { ordersNested ->
                                            ordersNested.properties("status") { it.keyword { keyword -> keyword } }
                                            .properties("lines") { lines ->
                                                lines.nested { linesNested ->
                                                    linesNested
                                                        .properties("productId") {
                                                            it.keyword { keyword -> keyword }
                                                        }
                                                        .properties("quantity") { it.integer { number -> number } }
                                                        .properties("amount") { it.double_ { number -> number } }
                                                        .properties("samples") { it.double_ { number -> number } }
                                                        .properties("createdAt") { it.date { date -> date } }
                                                        .properties("createdAtEpochSecond") {
                                                            it.long_ { number -> number }
                                                        }
                                                        .properties("createdAtNanos") {
                                                            it.dateNanos { date -> date }
                                                        }
                                                        .properties("discounts") { discounts ->
                                                            discounts.nested { discountsNested ->
                                                                discountsNested
                                                                    .properties("type") {
                                                                        it.keyword { keyword -> keyword }
                                                                    }.properties("amount") {
                                                                        it.double_ { number -> number }
                                                                    }
                                                            }
                                                        }
                                                }
                                            }
                                        }
                                    }
                            }
                    }
                }
        }.block()
        super.setup()
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)
    }

    override fun createSnapshotStore(): SnapshotStore {
        return ElasticsearchSnapshotStore(elasticsearchClient)
    }

    @Test
    fun `computed metric should ignore a presence-resolved text field without fielddata`() {
        aggregation {
            sum(field("state.data") * constant(1.0), "unreadable")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(mapOf("unreadable" to null))
            }.verifyComplete()
    }

    @Test
    fun `computed metric should ignore a mapped field without index or doc values`() {
        aggregation {
            sum(field("state.unreadableNumber") * constant(1.0), "unreadable")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(mapOf("unreadable" to null))
            }.verifyComplete()
    }

    @Test
    fun `relative time filters should enforce declared temporal mappings`() {
        val pattern = "yyyy-MM-dd HH:mm:ss"
        val instant = Instant.now()
        updateSnapshot(
            mapOf(
                "relativeDate" to instant,
                "relativeEpoch" to instant.toEpochMilli(),
                "relativeText" to DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC).format(instant),
            ),
        )
        val compatible = listOf(
            RecentDaysFilter(LogicalField("relativeDate", FieldType.Temporal.Date), 2, "UTC"),
            RecentDaysFilter(LogicalField("relativeEpoch", FieldType.Temporal.Number()), 2, "UTC"),
            RecentDaysFilter(
                LogicalField("relativeText", FieldType.Temporal.String(pattern)),
                2,
                "UTC",
            ),
        )

        compatible.forEach { filter ->
            snapshotQueryService.dynamicList(ListQuery(filter = filter, limit = 10))
                .test()
                .expectNextCount(1)
                .verifyComplete()
        }

        val conflicts = listOf(
            RecentDaysFilter(LogicalField("relativeEpoch", FieldType.Temporal.Date), 2, "UTC") to
                listOf("relativeEpoch", "long", "date or date_nanos"),
            RecentDaysFilter(LogicalField("relativeDate", FieldType.Temporal.Number()), 2, "UTC") to
                listOf("relativeDate", "date", "signed numeric"),
            RecentDaysFilter(LogicalField("relativeEpoch", FieldType.Temporal.String(pattern)), 2, "UTC") to
                listOf("relativeEpoch", "long", "keyword-compatible string"),
        )
        conflicts.forEach { (filter, details) ->
            snapshotQueryService.dynamicList(ListQuery(filter = filter, limit = 10))
                .test()
                .expectErrorSatisfies { error ->
                    error.assert().isInstanceOf(ElasticsearchFieldResolutionException::class.java)
                    error.message.assert().contains(*details.toTypedArray())
                }.verify()
        }
    }

    @Test
    fun `DATE histograms should use native date and date_nanos without runtime fields`() {
        saveAggregationStates(*aggregationStates().toTypedArray())
        val mapping = ElasticsearchIndexMappingResolver(elasticsearchClient)
            .currentOrLoad(MOCK_AGGREGATE_METADATA.toSnapshotIndexName()).block()!!

        listOf("createdAt", "createdAtNanos").forEach { field ->
            val query = aggregation {
                expand("state.orders") { "status" eq "PAID" }
                expand("lines") { "quantity" gte 2 }
                dateHistogram(
                    LogicalField(field, FieldType.Temporal.Date),
                    AggregationDateUnit.DAY,
                    "day",
                    ZoneOffset.UTC,
                )
                count("count")
            }
            ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping).compile(query)
                .runtimeMappings.assert().isEmpty()

            query.query(snapshotQueryService)
                .collectList()
                .test()
                .assertNext { rows ->
                    rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                        mapOf("day" to 1_767_312_000_000L, "count" to 2L),
                        mapOf("day" to 1_767_398_400_000L, "count" to 1L),
                        mapOf("day" to 1_769_990_400_000L, "count" to 1L),
                    )
                }.verifyComplete()
        }
    }

    @Test
    fun `TEMPORAL_NUMBER histograms should execute every TimeUnit conversion`() {
        mapOf(
            TimeUnit.NANOSECONDS to 1_767_225_600_000_000_000L,
            TimeUnit.MICROSECONDS to 1_767_225_600_000_000L,
            TimeUnit.MILLISECONDS to 1_767_225_600_000L,
            TimeUnit.SECONDS to 1_767_225_600L,
            TimeUnit.MINUTES to 29_453_760L,
            TimeUnit.HOURS to 490_896L,
            TimeUnit.DAYS to 20_454L,
        ).forEach { (timeUnit, value) ->
            updateSnapshot(mapOf("epoch" to value))

            assertEpochBuckets(
                field = "epoch",
                timeUnit = timeUnit,
                aggregateIds = listOf(snapshot.aggregateId.id),
                expected = listOf(mapOf("day" to 1_767_225_600_000L, "count" to 1L)),
            )
        }
    }

    @Test
    fun `TEMPORAL_NUMBER sub-millisecond histogram should floor negative epochs`() {
        indexDocument("negative-micros", mapOf("epoch" to -500L))

        assertEpochBuckets(
            field = "epoch",
            timeUnit = TimeUnit.MICROSECONDS,
            aggregateIds = listOf("negative-micros"),
            expected = listOf(mapOf("day" to -86_400_000L, "count" to 1L)),
        )
    }

    @Test
    fun `TEMPORAL_NUMBER histograms should accept scalar and singleton arrays only`() {
        val epochMillis = 1_767_225_600_000L
        indexDocument(
            "valid",
            mapOf(
                "epoch" to epochMillis,
                "epochFraction" to epochMillis.toDouble(),
                "epochMulti" to epochMillis,
            ),
        )
        indexDocument(
            "singleton",
            mapOf(
                "epoch" to listOf(epochMillis),
                "epochFraction" to listOf(epochMillis.toDouble()),
                "epochMulti" to listOf(epochMillis),
            ),
        )
        indexDocument("missing", emptyMap())
        indexDocument("null", mapOf("epoch" to null, "epochFraction" to null, "epochMulti" to null))
        indexDocument(
            "empty",
            mapOf(
                "epoch" to emptyList<Long>(),
                "epochFraction" to emptyList<Double>(),
                "epochMulti" to emptyList<Long>(),
            ),
        )
        indexDocument("malformed", mapOf("epoch" to "not-a-number"))
        indexDocument(
            "multi",
            mapOf(
                "epoch" to listOf(epochMillis, epochMillis + 86_400_000L),
                "epochFraction" to listOf(epochMillis.toDouble(), epochMillis.toDouble() + 86_400_000),
                "epochMulti" to listOf(epochMillis, epochMillis + 86_400_000L),
            ),
        )
        indexDocument("fraction", mapOf("epochFraction" to epochMillis + 0.5))
        indexDocument("non-finite", mapOf("epochFraction" to "NaN"))

        val aggregateIds =
            listOf("valid", "singleton", "missing", "null", "empty", "malformed", "multi", "fraction", "non-finite")
        val expected = listOf(mapOf("day" to epochMillis, "count" to 2L))
        assertEpochBuckets("epoch", TimeUnit.MILLISECONDS, aggregateIds, expected)
        assertEpochBuckets("epochFraction", TimeUnit.MILLISECONDS, aggregateIds, expected)
        assertEpochBuckets("epochMulti", TimeUnit.MILLISECONDS, aggregateIds, expected)
    }

    @Test
    fun `TEMPORAL_NUMBER histogram should distinguish floating upper bound from signed Long boundary`() {
        indexDocument("signed-boundary", mapOf("epoch" to Long.MAX_VALUE))
        indexDocument("floating-boundary", mapOf("epochFraction" to Math.scalb(1.0, 63)))

        assertEpochBuckets(
            "epochFraction",
            TimeUnit.NANOSECONDS,
            listOf("floating-boundary"),
            emptyList(),
        )
        assertEpochBuckets(
            "epoch",
            TimeUnit.NANOSECONDS,
            listOf("signed-boundary"),
            listOf(mapOf("day" to 9_223_286_400_000L, "count" to 1L)),
        )
        assertEpochBuckets("epoch", TimeUnit.DAYS, listOf("signed-boundary"), emptyList())
    }

    @Test
    fun `default snapshot times should support TEMPORAL_NUMBER milliseconds histograms`() {
        val properties = elasticsearchClient.indices().getMapping(
            GetMappingRequest.of { it.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName()) },
        ).block()!!.mappings().values.single().mappings().properties()

        listOf("firstEventTime", "eventTime", "snapshotTime").forEach { name ->
            properties.getValue(name)._kind().assert().isEqualTo(Property.Kind.Long)
            aggregation {
                filter { aggregateId(snapshot.aggregateId.id) }
                dateHistogram(
                    LogicalField(
                        name,
                        FieldType.Temporal.Number(TimeUnit.MILLISECONDS),
                    ),
                    AggregationDateUnit.DAY,
                    "day",
                    ZoneOffset.UTC,
                )
                count("count")
            }.query(snapshotQueryService).test()
                .assertNext { row ->
                    row["day"].assert().isInstanceOf(Long::class.javaObjectType)
                    row["count"].assert().isEqualTo(1L)
                }.verifyComplete()
        }
    }

    @Test
    fun `temporal mapping conflicts should fail before execution`() {
        listOf(
            aggregation {
                dateHistogram(
                    LogicalField("snapshotTime", FieldType.Temporal.Date),
                    AggregationDateUnit.DAY,
                    "day",
                    ZoneOffset.UTC,
                )
                count("count")
            } to listOf("snapshotTime", "DATE", "long", "date or date_nanos"),
            aggregation {
                expand("state.orders")
                expand("lines")
                dateHistogram(
                    LogicalField("createdAt", FieldType.Temporal.Number()),
                    AggregationDateUnit.DAY,
                    "day",
                    ZoneOffset.UTC,
                )
                count("count")
            } to listOf("state.orders.lines.createdAt", "TEMPORAL_NUMBER", "date", "signed numeric"),
        ).forEach { (query, details) ->
            query.query(snapshotQueryService).test()
                .expectErrorSatisfies { error ->
                    error.assert().isInstanceOf(ElasticsearchFieldResolutionException::class.java)
                    error.message.assert().contains(*details.toTypedArray())
                }.verify()
        }
    }

    @Test
    fun `unsigned_long should be rejected for numeric epochs`() {
        aggregation {
            dateHistogram(
                LogicalField("epochUnsigned", FieldType.Temporal.Number()),
                AggregationDateUnit.DAY,
                "day",
                ZoneOffset.UTC,
            )
            count("count")
        }.query(snapshotQueryService).test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(ElasticsearchFieldResolutionException::class.java)
                error.message.assert()
                    .contains("epochUnsigned")
                    .contains("TEMPORAL_NUMBER")
                    .contains("unsigned_long")
                    .contains("signed numeric with doc values")
            }.verify()
    }

    @Test
    fun `TEMPORAL_NUMBER runtime mapping should remain available across composite pages`() {
        saveAggregationStates(*aggregationStates().toTypedArray())
        val queryService = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            1,
            DEFAULT_PIT_KEEP_ALIVE,
            ElasticsearchIndexMappingResolver(elasticsearchClient),
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        aggregation {
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gte 2 }
            dateHistogram(
                LogicalField(
                    "createdAtEpochSecond",
                    FieldType.Temporal.Number(TimeUnit.SECONDS),
                ),
                AggregationDateUnit.DAY,
                "day",
                ZoneOffset.UTC,
            )
            count("count")
        }.query(queryService)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf("day" to 1_767_312_000_000L, "count" to 2L),
                    mapOf("day" to 1_767_398_400_000L, "count" to 1L),
                    mapOf("day" to 1_769_990_400_000L, "count" to 1L),
                )
            }.verifyComplete()
    }

    @Test
    fun `active refresh should expose mapped alias and runtime capabilities to new queries`() {
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val mappingResolver = ElasticsearchIndexMappingResolver(elasticsearchClient)
        val initial = mappingResolver.currentOrLoad(indexName).block()!!
        initial.resolve("state.data", ElasticsearchFieldUsage.SEARCH).assert().isEqualTo("state.data")
        initial.resolve("state.data", ElasticsearchFieldUsage.EXACT).assert().isEqualTo("state.data.keyword")

        elasticsearchClient.indices().putMapping(
            PutMappingRequest.of { request ->
                request.index(indexName)
                    .properties("aggregateIdAlias") { field ->
                        field.alias { alias -> alias.path("aggregateId") }
                    }
                    .properties("state") { state ->
                        state.`object` { objectField ->
                            objectField
                                .properties("keywordOnly") { it.keyword { keyword -> keyword } }
                                .properties("textOnly") { it.text { text -> text } }
                        }
                    }.runtime("state.runtimeCode") { runtime ->
                        runtime.type(RuntimeFieldType.Keyword)
                            .script { script ->
                                script.source { source -> source.scriptString("emit('runtime')") }
                            }
                    }
            },
        ).block()

        val refreshed = mappingResolver.refresh(indexName).block()!!
        refreshed.changed.assert().isTrue()
        refreshed.mapping.resolve("state.keywordOnly", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.keywordOnly")
        refreshed.mapping.resolve("state.textOnly", ElasticsearchFieldUsage.SEARCH)
            .assert().isEqualTo("state.textOnly")
        refreshed.mapping.resolve("aggregateIdAlias", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("aggregateIdAlias")
        refreshed.mapping.resolve("state.runtimeCode", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("state.runtimeCode")

        val queryService = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            DEFAULT_SEARCH_BATCH_SIZE,
            DEFAULT_PIT_KEEP_ALIVE,
            mappingResolver,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        queryService.dynamicList(
            ListQuery(
                filter = filterExpression {
                    "state.keywordOnly" eq "exact"
                    "state.textOnly" search "search"
                },
                limit = 10,
            ),
        ).test()
            .verifyComplete()

        queryService.dynamicList(
            ListQuery(
                filter = filterExpression {
                    "aggregateIdAlias" eq snapshot.aggregateId.id
                    "state.runtimeCode" eq "runtime"
                },
                sort = listOf(Sort("state.runtimeCode", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `mapping capabilities should support special field types and metadata sorts`() {
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        elasticsearchClient.indices().putMapping(
            PutMappingRequest.of { request ->
                request.index(indexName)
                    .properties("docValueOnlyKeyword") { field ->
                        field.keyword { keyword -> keyword.index(false) }
                    }.properties("docValueOnlyLong") { field ->
                        field.long_ { number -> number.index(false) }
                    }.properties("ipAddress") { field ->
                        field.ip { ip -> ip.index(false) }
                    }.properties("integerRange") { field ->
                        field.integerRange { range -> range }
                    }.properties("ipRange") { field ->
                        field.ipRange { range -> range }
                    }.properties("sortableText") { field ->
                        field.text { text -> text.fielddata(true) }
                    }.properties("labels") { field ->
                        field.flattened { flattened -> flattened }
                    }
            },
        ).block()

        @Suppress("UNCHECKED_CAST")
        val documentClass = Map::class.java as Class<Map<String, Any?>>
        elasticsearchClient.update(
            UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { request ->
                request.index(indexName)
                    .id(snapshot.aggregateId.id)
                    .doc(
                        mapOf(
                            "docValueOnlyKeyword" to "exact",
                            "docValueOnlyLong" to 42,
                            "ipAddress" to "192.168.1.1",
                            "integerRange" to mapOf("gte" to 10, "lte" to 20),
                            "ipRange" to "192.168.0.0/24",
                            "sortableText" to "single",
                            "labels" to mapOf("release" to "v1.2.3"),
                        ),
                    ).refresh(Refresh.True)
            },
            documentClass,
        ).block()

        val mappingResolver = ElasticsearchIndexMappingResolver(elasticsearchClient)
        val mapping = mappingResolver.currentOrLoad(indexName).block()!!
        mapping.resolve("docValueOnlyKeyword", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("docValueOnlyKeyword")
        mapping.resolve("docValueOnlyLong", ElasticsearchFieldUsage.RANGE)
            .assert().isEqualTo("docValueOnlyLong")
        mapping.resolve("docValueOnlyLong", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("docValueOnlyLong")
        mapping.resolve("ipAddress", ElasticsearchFieldUsage.RANGE)
            .assert().isEqualTo("ipAddress")
        mapping.resolve("integerRange", ElasticsearchFieldUsage.RANGE)
            .assert().isEqualTo("integerRange")
        mapping.resolve("ipRange", ElasticsearchFieldUsage.RANGE)
            .assert().isEqualTo("ipRange")
        mapping.resolve("sortableText", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("sortableText")
        mapping.resolve("labels.release", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("labels.release")
        mapping.resolve("labels.release", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("labels.release")

        ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            DEFAULT_SEARCH_BATCH_SIZE,
            DEFAULT_PIT_KEEP_ALIVE,
            mappingResolver,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
            .dynamicList(
                ListQuery(
                    filter = filterExpression {
                        "docValueOnlyKeyword" eq "exact"
                        "docValueOnlyLong" gt 1
                        "ipAddress" gt "192.168.0.1"
                        "integerRange" gt 15
                        "ipRange" gt "192.168.0.128"
                        "labels.release" eq "v1.2.3"
                    },
                    sort = listOf(
                        Sort("docValueOnlyLong", Sort.Direction.ASC),
                        Sort("sortableText", Sort.Direction.ASC),
                        Sort("labels.release", Sort.Direction.ASC),
                        Sort("_score", Sort.Direction.DESC),
                        Sort("_doc", Sort.Direction.ASC),
                        Sort("_shard_doc", Sort.Direction.ASC),
                    ),
                    limit = 10,
                ),
            ).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    private fun updateSnapshot(fields: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val documentClass = Map::class.java as Class<Map<String, Any?>>
        elasticsearchClient.update(
            UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { request ->
                request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
                    .id(snapshot.aggregateId.id)
                    .doc(fields)
                    .refresh(Refresh.True)
            },
            documentClass,
        ).block()
    }

    private fun indexDocument(id: String, fields: Map<String, Any?>) {
        val document = linkedMapOf<String, Any?>("aggregateId" to id, "deleted" to false).apply { putAll(fields) }
        elasticsearchClient.index<Map<String, Any?>> { request ->
            request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
                .id(id)
                .document(document)
                .refresh(Refresh.True)
        }.block()
    }

    private fun assertEpochBuckets(
        field: String,
        timeUnit: TimeUnit,
        aggregateIds: List<String>,
        expected: List<Map<String, Any?>>,
    ) {
        aggregation {
            filter { aggregateIds(aggregateIds) }
            dateHistogram(
                LogicalField(field, FieldType.Temporal.Number(timeUnit)),
                AggregationDateUnit.DAY,
                "day",
                ZoneOffset.UTC,
            )
            count("count")
        }.query(snapshotQueryService)
            .collectList()
            .test()
            .assertNext { rows -> rows.map(Map<String, Any?>::toMap).assert().containsExactly(*expected.toTypedArray()) }
            .verifyComplete()
    }
}
