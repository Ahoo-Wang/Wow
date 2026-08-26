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
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch.core.UpdateRequest
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.query.snapshot.requiredQueryModelSchemaProvider
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
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
                    mapping.properties("state") { state ->
                        state.`object` { stateObject ->
                            stateObject
                                .properties("data") { data ->
                                    data.text { text ->
                                        text.fielddata(false)
                                            .fields("keyword") { keyword -> keyword.keyword { it } }
                                    }
                                }.properties("decimalValue") { it.double_ { number -> number } }
                                .properties("unreadableNumber") {
                                    it.double_ { number -> number.index(false).docValues(false) }
                                }.properties("epochMicros") { it.long_ { number -> number } }
                                .properties("epochNanos") { it.long_ { number -> number } }
                                .properties("epochSeconds") { it.long_ { number -> number } }
                                .properties("epochFraction") { it.double_ { number -> number } }
                                .properties("orders") { orders ->
                                    orders.nested { ordersNested ->
                                        ordersNested.properties("status") { it.keyword { keyword -> keyword } }
                                        .properties("lines") { lines ->
                                            lines.nested { linesNested ->
                                                linesNested
                                                    .properties("productId") { it.keyword { keyword -> keyword } }
                                                    .properties("quantity") { it.integer { number -> number } }
                                                    .properties("amount") { it.double_ { number -> number } }
                                                    .properties("samples") { it.double_ { number -> number } }
                                                    .properties("createdAt") { it.date { date -> date } }
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

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory =
        ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)

    override fun createSnapshotStore(): SnapshotStore = ElasticsearchSnapshotStore(elasticsearchClient)

    @Test
    fun `direct service constructor should retain snapshot identity schema behavior`() {
        val service = ElasticsearchSnapshotQueryService<MockStateAggregate>(
            MOCK_AGGREGATE_METADATA,
            elasticsearchClient,
        )

        service.schema().test()
            .assertNext { schema ->
                schema.model.assert().isEqualTo(QueryModel.SNAPSHOT)
                schema.fields.keys.assert().contains(LogicalField("aggregateId"))
            }.verifyComplete()
    }

    @Test
    fun `computed metric should ignore an unreadable text field`() {
        aggregation {
            sum(field("state.data") * constant(1.0), "unreadable")
        }.query(snapshotQueryService)
            .test()
            .assertNext { it.toMap().assert().isEqualTo(mapOf("unreadable" to null)) }
            .verifyComplete()
    }

    @Test
    fun `computed metric should ignore a mapped field without index or doc values`() {
        aggregation {
            sum(field("state.unreadableNumber") * constant(1.0), "unreadable")
        }.query(snapshotQueryService)
            .test()
            .assertNext { it.toMap().assert().isEqualTo(mapOf("unreadable" to null)) }
            .verifyComplete()
    }

    @Test
    fun `provider refresh should publish new mapping alias and runtime capabilities`() {
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val service = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            listOf(
                source(
                    stringField("state.keywordOnly"),
                    stringField("state.textOnly"),
                    stringField("state.runtimeCode"),
                ),
            ),
            me.ahoo.wow.query.schema.QuerySchemaValidationMode.COMPATIBLE,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val provider = service.requiredQueryModelSchemaProvider()
        val initial = provider.schema().block()!!
        initial.fields.getValue(LogicalField("state.runtimeCode")).bindings.assert()
            .doesNotContainKey(QueryCapability.EXACT_MATCH)

        elasticsearchClient.indices().putMapping(
            PutMappingRequest.of { request ->
                request.index(indexName)
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

        val refreshed = provider.refresh().block()!!
        refreshed.fields.getValue(LogicalField("state.keywordOnly"))
            .bindings.getValue(QueryCapability.EXACT_MATCH).physicalPath.assert().isEqualTo("state.keywordOnly")
        refreshed.fields.getValue(LogicalField("state.textOnly"))
            .bindings.getValue(QueryCapability.FULL_TEXT_TERMS).physicalPath.assert().isEqualTo("state.textOnly")
        refreshed.fields.getValue(LogicalField("state.runtimeCode"))
            .bindings.getValue(QueryCapability.SORT).physicalPath.assert().isEqualTo("state.runtimeCode")
        provider.schema().block().assert().isSameAs(refreshed)

        service.dynamicList(
            ListQuery(
                filter = filterExpression { "state.runtimeCode" eq "runtime" },
                sort = listOf(Sort("state.runtimeCode", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `epoch runtime fields should floor negatives and not emit overflow fractional or multi values`() {
        updateState(
            mapOf(
                "epochMicros" to -500L,
                "epochNanos" to -500_000L,
                "epochSeconds" to Long.MAX_VALUE,
                "epochFraction" to 1.5,
            ),
        )
        val service = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            listOf(
                source(
                    epochField("state.epochMicros", TimeUnit.MICROSECONDS),
                    epochField("state.epochNanos", TimeUnit.NANOSECONDS),
                    epochField("state.epochSeconds", TimeUnit.SECONDS),
                    epochField("state.epochFraction", TimeUnit.MILLISECONDS),
                ),
            ),
            me.ahoo.wow.query.schema.QuerySchemaValidationMode.COMPATIBLE,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        dateHistogram(service, "state.epochMicros").test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf("day" to -86_400_000L, "count" to 1L),
                )
            }.verifyComplete()
        dateHistogram(service, "state.epochNanos").test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf("day" to -86_400_000L, "count" to 1L),
                )
            }.verifyComplete()
        dateHistogram(service, "state.epochSeconds").test()
            .assertNext { it.assert().isEmpty() }
            .verifyComplete()
        dateHistogram(service, "state.epochFraction").test()
            .assertNext { it.assert().isEmpty() }
            .verifyComplete()

        updateState(mapOf("epochMicros" to listOf(1_000L, 2_000L)))
        dateHistogram(service, "state.epochMicros").test()
            .assertNext { it.assert().isEmpty() }
            .verifyComplete()
    }

    private fun dateHistogram(service: SnapshotQueryService<*>, field: String) = aggregation {
        dateHistogram(field, me.ahoo.wow.api.query.AggregationDateUnit.DAY, "day")
        count("count")
    }.query(service).collectList()

    @Suppress("UNCHECKED_CAST")
    private fun updateState(state: Map<String, Any>) {
        elasticsearchClient.update(
            UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { request ->
                request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
                    .id(snapshot.aggregateId.id)
                    .doc(mapOf("state" to state))
                    .refresh(Refresh.True)
            },
            Map::class.java as Class<Map<String, Any?>>,
        ).block()
    }

    private fun source(vararg fields: Pair<LogicalField, QueryFieldDeclaration>): QuerySchemaSource =
        object : QuerySchemaSource {
            override val priority: Int = QuerySchemaSourcePriority.BEAN

            override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> =
                Flux.just(QuerySchemaDeclaration(fields.toMap()))
        }

    private fun stringField(field: String) = LogicalField(field) to QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
    )

    private fun epochField(field: String, timeUnit: TimeUnit) = LogicalField(field) to QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
        nullable = DeclarationValue.Set(true),
        required = DeclarationValue.Set(false),
        cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
        semanticType = DeclarationValue.Set(Temporal.Epoch(timeUnit)),
    )
}
