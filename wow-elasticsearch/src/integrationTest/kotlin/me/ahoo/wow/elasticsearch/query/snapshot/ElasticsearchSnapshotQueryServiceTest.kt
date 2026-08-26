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
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.core.UpdateRequest
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.createElasticsearchTemplate
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter.Companion.toFilterExpression
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.query.snapshot.requiredQueryModelSchemaProvider
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import tools.jackson.databind.JsonNode

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
                .settings { settings ->
                    settings.otherSettings(
                        "index.query.default_field",
                        JsonData.of(
                            listOf(
                                "state.data",
                                "state.decimalValue",
                                "state.orders.lines.createdAt",
                            ),
                        ),
                    )
                }
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
                                .properties("createdAt") { it.long_ { number -> number } }
                                .properties("unreadableNumber") {
                                    it.double_ { number -> number.index(false).docValues(false) }
                                }.properties("epochMicros") { it.long_ { number -> number } }
                                .properties("epochMillis") { it.long_ { number -> number } }
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
                                                    .properties("epochSeconds") { it.long_ { number -> number } }
                                                    .properties("productName") { productName ->
                                                        productName.text { text ->
                                                            text.fields("keyword") { keyword -> keyword.keyword { it } }
                                                        }
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

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory =
        ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            querySchemaSources,
            QuerySchemaValidationMode.COMPATIBLE,
        )

    override fun createSnapshotStore(): SnapshotStore = ElasticsearchSnapshotStore(elasticsearchClient)

    @Test
    fun `model level search should execute against mixed text numeric and date mappings`() {
        updateState(mapOf("data" to "searchable"))

        snapshotQueryService.dynamicList(
            ListQuery(filter = SearchFilter("searchable"), limit = 10),
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `field specific search bindings should be exact`() {
        updateState(mapOf("data" to "searchable"))
        val strictService = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            querySchemaSources,
            QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        strictService.dynamicList(
            ListQuery(
                filter = SearchFilter("searchable", setOf(LogicalField("state.data"))),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should reject unknown fields while compatible executes fallback`() {
        snapshotQueryService.dynamicList(
            ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10),
        ).test().verifyComplete()

        val strictService = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            querySchemaSources,
            QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        strictService.dynamicList(
            ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `strict should execute the built-in ABAC tags filter shape`() {
        val strictService = strictService()
        val schema = strictService.requiredQueryModelSchemaProvider().schema().block()!!
        schema.fields.getValue(LogicalField("tags")).bindings.keys.assert().contains(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
        )
        updateDocument(
            mapOf(
                "tags" to mapOf(
                    "visibility" to listOf("public"),
                    "department" to listOf("eng"),
                ),
            ),
        )
        val abacFilter = mapOf(
            "visibility" to listOf("*"),
            "department" to listOf("eng"),
            "region" to listOf("cn"),
        ).toFilterExpression()

        strictService.dynamicList(ListQuery(filter = abacFilter, limit = 10))
            .test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should reject ABAC exact before search without a keyword template`() {
        val current = currentMapping()
        recreateSnapshotIndex(
            TypeMapping.of { mapping ->
                mapping.dynamic(DynamicMapping.True)
                    .properties(current.properties())
                    .properties("tags") {
                        it.`object` { objectField -> objectField.dynamic(DynamicMapping.True) }
                    }
            },
        )
        val strictService = strictService()
        strictService.requiredQueryModelSchemaProvider().schema().block()!!
            .fields.getValue(LogicalField("tags")).bindings.keys.assert()
            .containsExactly(QueryCapability.PRESENCE)
        updateDocument(mapOf("tags" to mapOf("department" to listOf("eng"))))

        strictService.dynamicList(
            ListQuery(
                filter = mapOf("department" to listOf("eng")).toFilterExpression(),
                limit = 10,
            ),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `strict should reject ABAC exact when an earlier text template can match`() {
        recreateSnapshotIndex(
            """
            {
              "date_detection": false,
              "properties": {"tags": {"type": "object", "dynamic": true}},
              "dynamic_templates": [
                {
                  "text_first": {
                    "match_mapping_type": "*",
                    "path_match": "tags.*",
                    "mapping": {"type": "text"}
                  }
                },
                {
                  "keyword_fallback": {
                    "match_mapping_type": "string",
                    "path_match": "tags.*",
                    "mapping": {"type": "keyword"}
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertStrictAbacExactRejected()
    }

    @Test
    fun `strict should reject inherited ABAC exact for an explicit text child`() {
        recreateSnapshotIndex(
            """
            {
              "date_detection": false,
              "properties": {
                "tags": {
                  "type": "object",
                  "dynamic": true,
                  "properties": {
                    "department": {
                      "type": "text",
                      "fields": {"keyword": {"type": "keyword"}}
                    }
                  }
                }
              },
              "dynamic_templates": [
                {
                  "tags_keyword": {
                    "match_mapping_type": "string",
                    "path_match": "tags.*",
                    "mapping": {"type": "keyword"}
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertStrictAbacExactRejected()
    }

    @Test
    fun `strict should reject ABAC exact below the protocol ignore above limit`() {
        recreateSnapshotIndex(
            """
            {
              "date_detection": false,
              "properties": {"tags": {"type": "object", "dynamic": true}},
              "dynamic_templates": [
                {
                  "tags_keyword": {
                    "match_mapping_type": "string",
                    "path_match": "tags.*",
                    "mapping": {"type": "keyword", "ignore_above": 1024}
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertStrictAbacExactRejected(expectPresence = false)
    }

    @Test
    fun `strict should reject flattened ABAC below the protocol ignore above limit`() {
        recreateSnapshotIndex(
            """
            {
              "dynamic": true,
              "properties": {
                "tags": {"type": "flattened", "ignore_above": 1024}
              }
            }
            """.trimIndent(),
        )

        assertStrictAbacExactRejected(expectPresence = false)
    }

    @Test
    fun `strict should execute ABAC through flattened tags under root strict`() {
        val current = currentMapping()
        recreateSnapshotIndex(
            TypeMapping.of { mapping ->
                mapping.dynamic(DynamicMapping.Strict)
                    .properties(current.properties())
                    .properties("tags") { it.flattened { flattened -> flattened } }
            },
        )
        val strictService = strictService()
        strictService.requiredQueryModelSchemaProvider().schema().block()!!
            .fields.getValue(LogicalField("tags")).bindings.keys.assert().contains(
                QueryCapability.PRESENCE,
                QueryCapability.EXACT_MATCH,
            )
        updateDocument(
            mapOf(
                "tags" to mapOf(
                    "visibility" to listOf("public"),
                    "department" to listOf("eng"),
                ),
            ),
        )

        strictService.dynamicList(
            ListQuery(
                filter = mapOf(
                    "visibility" to listOf("*"),
                    "department" to listOf("eng"),
                ).toFilterExpression(),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should reject a root nested child filter`() {
        strictService().dynamicList(
            ListQuery(filter = filterExpression { "state.orders.status" eq "PAID" }, limit = 10),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `strict should reject a root nested child sort`() {
        strictService().dynamicList(
            ListQuery(
                filter = MatchAllFilter,
                sort = listOf(Sort("state.orders.status", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `strict should execute a nested child inside element match`() {
        updateState(mapOf("orders" to listOf(mapOf("status" to "PAID"))))

        strictService().dynamicList(
            ListQuery(
                filter = filterExpression {
                    "state.orders".elementMatch {
                        "status" eq "PAID"
                    }
                },
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `aggregation should execute resolved temporal and multi-field filters in two nested scopes`() {
        val now = Instant.now()
        val timeZone = ZoneOffset.ofHours(12 - now.atOffset(ZoneOffset.UTC).hour)
        val nowSeconds = now.epochSecond
        updateState(
            mapOf(
                "orders" to listOf(
                    mapOf(
                        "status" to "PAID",
                        "lines" to listOf(
                            mapOf(
                                "productName" to "Widget",
                                "epochSeconds" to nowSeconds,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val service = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            querySchemaSources + source(
                stringField("state.orders.lines.productName"),
                epochField("state.orders.lines.epochSeconds", TimeUnit.SECONDS),
            ),
            QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        aggregation {
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") {
                "productName" eq "Widget"
                "epochSeconds".today(timeZone)
            }
            count("count")
        }.query(service)
            .test()
            .assertNext { row -> row.toMap().assert().isEqualTo(mapOf("count" to 1L)) }
            .verifyComplete()
    }

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
        val service = ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)
            .create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        aggregation {
            sum(field("state.data") * constant(1.0), "unreadable")
        }.query(service)
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
    fun `signed epoch runtime fields should use adapter and preserve floor overflow and multi guards`() {
        updateState(
            mapOf(
                "epochMicros" to -500L,
                "epochMillis" to 0L,
                "epochNanos" to -500_000L,
                "epochSeconds" to Long.MAX_VALUE,
            ),
        )
        val service = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            listOf(
                source(
                    epochField("state.epochMicros", TimeUnit.MICROSECONDS),
                    epochField("state.epochMillis", TimeUnit.MILLISECONDS),
                    epochField("state.epochNanos", TimeUnit.NANOSECONDS),
                    epochField("state.epochSeconds", TimeUnit.SECONDS),
                ),
            ),
            QuerySchemaValidationMode.COMPATIBLE,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val schema = service.requiredQueryModelSchemaProvider().schema().block()!!
        listOf("state.epochMicros", "state.epochMillis", "state.epochNanos", "state.epochSeconds")
            .forEach { field ->
                schema.fields.getValue(LogicalField(field)).bindings
                    .getValue(QueryCapability.AGGREGATE_TEMPORAL).let { binding ->
                        binding.physicalPath.assert().isEqualTo(field)
                        binding.storageType?.value.assert().isEqualTo("long")
                    }
            }

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
        dateHistogram(service, "state.epochMillis").test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf("day" to 0L, "count" to 1L),
                )
            }.verifyComplete()

        updateState(mapOf("epochMillis" to Long.MAX_VALUE))
        dateHistogram(service, "state.epochMillis").test()
            .assertNext { rows ->
                rows.assert().hasSize(1)
                rows.single()["count"].assert().isEqualTo(1L)
            }.verifyComplete()

        updateState(mapOf("epochMicros" to listOf(1_000L, 2_000L)))
        dateHistogram(service, "state.epochMicros").test()
            .assertNext { it.assert().isEmpty() }
            .verifyComplete()
    }

    @Test
    fun `fractional epoch defensive script should not emit`() {
        updateState(mapOf("epochFraction" to 1.5))

        dateHistogram(defensiveEpochService(), "state.epochFraction").test()
            .assertNext { it.assert().isEmpty() }
            .verifyComplete()
    }

    @Test
    fun `floating epoch at two to the sixty third should not emit`() {
        updateState(mapOf("epochFraction" to 9.223372036854776E18))

        dateHistogram(defensiveEpochService(), "state.epochFraction").test()
            .assertNext { it.assert().isEmpty() }
            .verifyComplete()
    }

    private fun dateHistogram(service: SnapshotQueryService<*>, field: String) = aggregation {
        dateHistogram(field, me.ahoo.wow.api.query.AggregationDateUnit.DAY, "day")
        count("count")
    }.query(service).collectList()

    @Suppress("UNCHECKED_CAST")
    private fun updateState(state: Map<String, Any>) {
        updateDocument(mapOf("state" to state))
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateDocument(document: Map<String, Any>) {
        elasticsearchClient.update(
            UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { request ->
                request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
                    .id(snapshot.aggregateId.id)
                    .doc(document)
                    .refresh(Refresh.True)
            },
            Map::class.java as Class<Map<String, Any?>>,
        ).block()
    }

    private fun strictService(): SnapshotQueryService<MockStateAggregate> =
        ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            querySchemaSources,
            QuerySchemaValidationMode.STRICT,
        ).create(MOCK_AGGREGATE_METADATA)

    private fun currentMapping(): TypeMapping = elasticsearchClient.indices().getMapping { request ->
        request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
    }.block()!!.mappings().values.single().mappings()

    private fun recreateSnapshotIndex(mapping: TypeMapping) {
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        Mono.defer {
            elasticsearchClient.indices().deleteIndexTemplate { request ->
                request.name("wow-snapshot-template")
            }
        }.then(
            Mono.defer { elasticsearchClient.indices().delete { request -> request.index(indexName) } },
        ).then(
            Mono.defer {
                elasticsearchClient.indices().create { request -> request.index(indexName).mappings(mapping) }
            },
        ).then(
            Mono.defer { snapshotStore.save(snapshot) },
        ).block()
    }

    private fun recreateSnapshotIndex(mappingJson: String) {
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val template = JsonSerializer.readValue(
            """
            {
              "index_patterns": ["$indexName"],
              "template": {"mappings": $mappingJson}
            }
            """.trimIndent(),
            JsonNode::class.java,
        )
        val initializer = IndexTemplateInitializer(elasticsearchClient.createElasticsearchTemplate())
        Mono.defer {
            elasticsearchClient.indices().deleteIndexTemplate { request -> request.name("wow-snapshot-template") }
        }.then(
            Mono.defer { elasticsearchClient.indices().delete { request -> request.index(indexName) } },
        ).then(
            Mono.defer { initializer.initTemplate("wow-snapshot-template", template) },
        ).then(
            Mono.defer { elasticsearchClient.indices().create { request -> request.index(indexName) } },
        ).then(
            Mono.defer { snapshotStore.save(snapshot) },
        ).block()
    }

    private fun assertStrictAbacExactRejected(expectPresence: Boolean = true) {
        val strictService = strictService()
        val bindings = strictService.requiredQueryModelSchemaProvider().schema().block()!!
            .fields.getValue(LogicalField("tags")).bindings.keys
        if (expectPresence) {
            bindings.assert().containsExactly(QueryCapability.PRESENCE)
        } else {
            bindings.assert().isEmpty()
        }
        updateDocument(mapOf("tags" to mapOf("department" to listOf("eng"))))

        strictService.dynamicList(
            ListQuery(
                filter = mapOf("department" to listOf("eng")).toFilterExpression(),
                limit = 10,
            ),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    private fun defensiveEpochService(): SnapshotQueryService<MockStateAggregate> {
        val field = LogicalField("state.epochFraction")
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(field to defensiveEpochField(field)),
        )
        val provider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)

            override fun refresh(): Mono<QueryModelSchema> = Mono.just(schema)
        }
        return ElasticsearchSnapshotQueryService(
            MOCK_AGGREGATE_METADATA,
            elasticsearchClient,
            provider,
            QuerySchemaValidationMode.COMPATIBLE,
        )
    }

    private fun defensiveEpochField(field: LogicalField) = QueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = setOf(QueryValueType.INTEGER),
        nullable = true,
        required = false,
        cardinality = QueryCardinality.SINGLE,
        semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
        dynamicChildren = false,
        bindings = mapOf(
            QueryCapability.AGGREGATE_TEMPORAL to QueryFieldBinding(
                physicalPath = field.value,
                storageType = QueryStorageType("double"),
            ),
        ),
    )

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
