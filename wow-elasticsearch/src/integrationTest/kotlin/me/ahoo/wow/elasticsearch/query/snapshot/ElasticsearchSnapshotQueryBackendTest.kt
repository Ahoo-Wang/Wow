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
import co.elastic.clients.elasticsearch._types.ScriptLanguage
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.core.UpdateRequest
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
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
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter.Companion.toFilterExpression
import me.ahoo.wow.query.snapshot.requiredQueryModelSchemaProvider
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.SnapshotQueryBackendSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ElasticsearchSnapshotQueryBackendTest : SnapshotQueryBackendSpec() {

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
                                .properties("sourceOnlyName") { name ->
                                    name.text { text ->
                                        text.index(false)
                                            .fields("keyword") { keyword -> keyword.keyword { it } }
                                    }
                                }.properties("formattedDate") { it.keyword { keyword -> keyword } }
                                .properties("sourceOnlyAlias") {
                                    it.alias { alias -> alias.path("state.sourceOnlyName") }
                                }
                                .properties("fielddataCategory") { it.text { text -> text.fielddata(true) } }
                                .properties("ipValue") { it.ip { field -> field } }
                                .properties("versionValue") { it.version { field -> field } }
                                .properties("opaque") { it.`object` { field -> field.enabled(false) } }
                                .properties("labels") { it.flattened { flattened -> flattened } }
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

    override fun createSnapshotQueryBackendFactory(): SnapshotQueryBackendFactory =
        ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
        )

    override fun createSnapshotStore(): SnapshotStore = ElasticsearchSnapshotStore(elasticsearchClient)

    @Suppress("UNCHECKED_CAST")
    override fun prepareNullAndMissingCursorSnapshots(nullId: String, missingId: String) {
        elasticsearchClient.update(
            UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { request ->
                request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
                    .id(nullId)
                    .doc(mapOf("state" to mapOf("createdAt" to null)))
                    .refresh(Refresh.True)
            },
            Map::class.java as Class<Map<String, Any?>>,
        ).block()
        elasticsearchClient.update(
            UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { request ->
                request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
                    .id(missingId)
                    .script { script ->
                        script.lang(ScriptLanguage.Painless)
                            .source { source -> source.scriptString("ctx._source.state.remove('createdAt')") }
                    }.refresh(Refresh.True)
            },
            Map::class.java as Class<Map<String, Any?>>,
        ).block()
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
                retried.path("aggregateId").asString().assert().isEqualTo(snapshot.aggregateId.id)
            }.verifyComplete()
    }

    @Test
    fun `repeat should create clean snapshot object nodes for every subscription`() {
        snapshotQueryBackend.list(snapshotOwnershipQuery())
            .next()
            .repeat(1)
            .index()
            .doOnNext { indexed ->
                if (indexed.t1 == 0L) indexed.t2.put("mutated", true)
            }.map { it.t2 }
            .collectList()
            .test()
            .assertNext { nodes ->
                nodes.assert().hasSize(2)
                nodes[1].assert().isNotSameAs(nodes[0])
                nodes[0].path("mutated").booleanValue().assert().isTrue()
                nodes[1].path("mutated").isMissingNode.assert().isTrue()
                nodes.map { it.path("aggregateId").asString() }.assert()
                    .containsExactly(snapshot.aggregateId.id, snapshot.aggregateId.id)
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
                nodes.t2.path("aggregateId").asString().assert().isEqualTo(snapshot.aggregateId.id)
            }.verifyComplete()
    }

    private fun snapshotOwnershipQuery(): ListQuery = ListQuery(
        filter = filterExpression { "aggregateId" eq snapshot.aggregateId.id },
        limit = 1,
    )

    @Test
    fun `model level search should execute against mixed text numeric and date mappings`() {
        updateState(mapOf("data" to "searchable"))

        snapshotQueryBackend.list(
            ListQuery(filter = SearchFilter("searchable"), limit = 10),
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `field specific search bindings should be exact`() {
        updateState(mapOf("data" to "searchable"))
        val strictService = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

        strictService.list(
            ListQuery(
                filter = SearchFilter("searchable", setOf(QueryField("state.data"))),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict projection should return the source field instead of its query multi-field`() {
        val field = "state.sourceOnlyName"
        updateState(mapOf("sourceOnlyName" to "visible"))
        val service = strictService(querySchemaSources + source(stringField(field)))

        service.list(
            ListQuery(
                filter = filterExpression { field gt "alpha" },
                projection = Projection(include = listOf(QueryField(field))),
                limit = 10,
            ),
        ).test()
            .assertNext { document ->
                document.path("state").path("sourceOnlyName").asString().assert().isEqualTo("visible")
            }.verifyComplete()
    }

    @Test
    fun `strict projection should resolve an alias to its source target`() {
        val field = "state.sourceOnlyAlias"
        updateState(mapOf("sourceOnlyName" to "visible"))
        val service = strictService(querySchemaSources + source(stringField(field)))

        service.list(
            ListQuery(
                filter = MatchAllFilter,
                projection = Projection(include = listOf(QueryField(field))),
                limit = 10,
            ),
        ).test()
            .assertNext { document ->
                document.path("state").path("sourceOnlyName").asString().assert().isEqualTo("visible")
            }.verifyComplete()
    }

    @Test
    fun `strict projection should return a declared unmapped source field`() {
        val field = "state.opaque.name"
        updateState(mapOf("opaque" to mapOf("name" to "visible")))
        val service = strictService(querySchemaSources + source(stringField(field)))

        service.list(
            ListQuery(
                filter = MatchAllFilter,
                projection = Projection(include = listOf(QueryField(field))),
                limit = 10,
            ),
        ).test()
            .assertNext { document ->
                document.path("state").path("opaque").path("name").asString().assert().isEqualTo("visible")
            }.verifyComplete()
    }

    @Test
    fun `projection should compile logical scalar and object nodes to physical subtrees`() {
        updateDocument(mapOf("document" to mapOf("name" to "visible", "secret" to "hidden")))
        val service = ElasticsearchSnapshotQueryBackend(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            elasticsearchClient = elasticsearchClient,
            schemaProvider = projectionSchemaProvider(),
            validationMode = QuerySchemaValidationMode.STRICT,
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
    }

    @Test
    fun `strict should execute metadata sort and formatted temporal range`() {
        val field = QueryField("state.formattedDate")
        val today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString()
        updateState(mapOf("formattedDate" to today))
        val service = strictService(
            querySchemaSources + source(formattedField(field.path, "yyyy-MM-dd")),
        )

        service.list(
            ListQuery(
                filter = TodayFilter(field, zoneId = "UTC"),
                sort = listOf(Sort(QueryField("_score"), Sort.Direction.DESC)),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should execute generic document id equality`() {
        strictService().list(
            ListQuery(
                filter = filterExpression { "_id" eq snapshot.aggregateId.id },
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should execute fielddata terms and string native operations`() {
        updateState(
            mapOf(
                "fielddataCategory" to "alpha",
                "ipValue" to "192.0.2.1",
                "versionValue" to "1.2.3",
            ),
        )
        val service = strictService(
            querySchemaSources + source(
                stringField("state.fielddataCategory"),
                stringField("state.ipValue"),
                stringField("state.versionValue"),
            ),
        )

        service.list(
            ListQuery(
                filter = filterExpression {
                    "state.ipValue" eq "192.0.2.1"
                    "state.ipValue" gt "192.0.2.0"
                    "state.versionValue" eq "1.2.3"
                },
                sort = listOf(
                    Sort(QueryField("state.ipValue"), Sort.Direction.ASC),
                    Sort(QueryField("state.versionValue"), Sort.Direction.ASC),
                ),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
        aggregation {
            terms("state.fielddataCategory", "category")
            terms("state.ipValue", "ip")
            terms("state.versionValue", "version")
            count("count")
        }.query(service).test()
            .assertNext { row ->
                row.path("category").asString().assert().isEqualTo("alpha")
                row.path("ip").asString().assert().isEqualTo("192.0.2.1")
                row.path("version").asString().assert().isEqualTo("1.2.3")
                row.path("count").longValue().assert().isEqualTo(1L)
            }.verifyComplete()
    }

    @Test
    fun `strict should query an explicitly declared flattened descendant`() {
        val field = "state.labels.color"
        updateState(mapOf("labels" to mapOf("color" to "green")))
        val service = strictService(querySchemaSources + source(stringField(field)))

        service.list(
            ListQuery(
                filter = filterExpression { field eq "green" },
                projection = Projection(include = listOf(QueryField(field))),
                limit = 10,
            ),
        ).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should reject unknown fields while compatible executes fallback`() {
        snapshotQueryBackend.list(
            ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10),
        ).test().verifyComplete()

        val strictService = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        strictService.list(
            ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `all modes should reject invalid declared epoch literals before Elasticsearch`() {
        listOf(snapshotQueryBackend, strictService()).forEach { service ->
            service.list(
                ListQuery(
                    filter = filterExpression { "firstEventTime" lte "not-a-timestamp" },
                    limit = 10,
                ),
            ).test().expectError(QuerySchemaValidationException::class.java).verify()
        }
    }

    @Test
    fun `all modes should reject dynamic ABAC before ignored values can fail open`() {
        updateDocument(
            mapOf(
                "tags" to mapOf(
                    "department" to listOf("x".repeat(9000)),
                ),
            ),
        )
        val mismatchedPrincipal = mapOf("department" to listOf("eng")).toFilterExpression()

        listOf(snapshotQueryBackend, strictService()).forEach { service ->
            service.list(ListQuery(filter = mismatchedPrincipal, limit = 10))
                .test().expectError(QuerySchemaValidationException::class.java).verify()
        }
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
            .fields.getValue(QueryField("tags")).bindings.assert().isEmpty()
        updateDocument(mapOf("tags" to mapOf("department" to listOf("eng"))))

        strictService.list(
            ListQuery(
                filter = mapOf("department" to listOf("eng")).toFilterExpression(),
                limit = 10,
            ),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `all modes should reject flattened dynamic tags`() {
        val current = currentMapping()
        recreateSnapshotIndex(
            TypeMapping.of { mapping ->
                mapping.dynamic(DynamicMapping.Strict)
                    .properties(current.properties())
                    .properties("tags") { it.flattened { flattened -> flattened } }
            },
        )
        val compatibleService = compatibleService()
        compatibleService.requiredQueryModelSchemaProvider().schema().block()!!
            .fields.getValue(QueryField("tags")).bindings.assert().isEmpty()
        updateDocument(
            mapOf(
                "tags" to mapOf(
                    "visibility" to listOf("public"),
                    "department" to listOf("eng"),
                ),
            ),
        )

        val filter = mapOf("department" to listOf("eng")).toFilterExpression()
        listOf(compatibleService, strictService()).forEach { service ->
            service.list(ListQuery(filter = filter, limit = 10))
                .test().expectError(QuerySchemaValidationException::class.java).verify()
        }
    }

    @Test
    fun `strict should reject a root nested child filter`() {
        strictService().list(
            ListQuery(filter = filterExpression { "state.orders.status" eq "PAID" }, limit = 10),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `strict should reject a root nested child sort`() {
        strictService().list(
            ListQuery(
                filter = MatchAllFilter,
                sort = listOf(Sort(QueryField("state.orders.status"), Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `strict should execute a nested child inside element match`() {
        updateState(mapOf("orders" to listOf(mapOf("status" to "PAID"))))

        strictService().list(
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
        val service = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = querySchemaSources + source(
                stringField("state.orders.lines.productName"),
                epochField("state.orders.lines.epochSeconds", TimeUnit.SECONDS),
            ),
            validationMode = QuerySchemaValidationMode.STRICT,
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
            .assertNext { row -> row.path("count").longValue().assert().isEqualTo(1L) }
            .verifyComplete()
    }

    @Test
    fun `direct service constructor should retain snapshot identity schema behavior`() {
        val service = ElasticsearchSnapshotQueryBackend(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            elasticsearchClient = elasticsearchClient,
        )

        service.schema().test()
            .assertNext { schema ->
                schema.model.assert().isEqualTo(QueryModel.SNAPSHOT)
                schema.fields.keys.assert().contains(QueryField("aggregateId"))
            }.verifyComplete()
    }

    @Test
    fun `computed metric should ignore an unreadable text field`() {
        val service = ElasticsearchSnapshotQueryBackendFactory(elasticsearchClient = elasticsearchClient)
            .create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        aggregation {
            sum(field("state.data") * constant(1.0), "unreadable")
        }.query(service)
            .test()
            .assertNext { it.path("unreadable").isNull.assert().isTrue() }
            .verifyComplete()
    }

    @Test
    fun `computed metric should ignore a mapped field without index or doc values`() {
        aggregation {
            sum(field("state.unreadableNumber") * constant(1.0), "unreadable")
        }.query(snapshotQueryBackend)
            .test()
            .assertNext { it.path("unreadable").isNull.assert().isTrue() }
            .verifyComplete()
    }

    @Test
    fun `provider refresh should publish new mapping alias and runtime capabilities`() {
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val service = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = listOf(
                source(
                    stringField("state.keywordOnly"),
                    stringField("state.textOnly"),
                    stringField("state.runtimeCode"),
                ),
            ),
            validationMode = me.ahoo.wow.query.schema.QuerySchemaValidationMode.COMPATIBLE,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val provider = service.requiredQueryModelSchemaProvider()
        val initial = provider.schema().block()!!
        initial.fields.getValue(QueryField("state.runtimeCode")).bindings.assert()
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
        refreshed.fields.getValue(QueryField("state.keywordOnly"))
            .bindings.getValue(QueryCapability.EXACT_MATCH).physicalField.assert()
            .isEqualTo(QueryField("state.keywordOnly"))
        refreshed.fields.getValue(QueryField("state.textOnly"))
            .bindings.getValue(QueryCapability.FULL_TEXT_TERMS).physicalField.assert()
            .isEqualTo(QueryField("state.textOnly"))
        refreshed.fields.getValue(QueryField("state.runtimeCode"))
            .bindings.getValue(QueryCapability.SORT).physicalField.assert()
            .isEqualTo(QueryField("state.runtimeCode"))
        refreshed.fields.getValue(QueryField("state.runtimeCode")).projectionField.assert().isNull()
        provider.schema().block().assert().isSameAs(refreshed)

        service.list(
            ListQuery(
                filter = filterExpression { "state.runtimeCode" eq "runtime" },
                sort = listOf(Sort(QueryField("state.runtimeCode"), Sort.Direction.ASC)),
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
        val service = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = listOf(
                source(
                    epochField("state.epochMicros", TimeUnit.MICROSECONDS),
                    epochField("state.epochMillis", TimeUnit.MILLISECONDS),
                    epochField("state.epochNanos", TimeUnit.NANOSECONDS),
                    epochField("state.epochSeconds", TimeUnit.SECONDS),
                ),
            ),
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val schema = service.requiredQueryModelSchemaProvider().schema().block()!!
        listOf("state.epochMicros", "state.epochMillis", "state.epochNanos", "state.epochSeconds")
            .forEach { field ->
                schema.fields.getValue(QueryField(field)).bindings
                    .getValue(QueryCapability.AGGREGATE_TEMPORAL).let { binding ->
                        binding.physicalField.assert().isEqualTo(QueryField(field))
                        binding.storageType?.value.assert().isEqualTo("long")
                    }
            }

        dateHistogram(service, "state.epochMicros").test()
            .assertNext { rows ->
                rows.map { it.path("day").longValue() to it.path("count").longValue() }.assert()
                    .containsExactly(-86_400_000L to 1L)
            }.verifyComplete()
        dateHistogram(service, "state.epochNanos").test()
            .assertNext { rows ->
                rows.map { it.path("day").longValue() to it.path("count").longValue() }.assert()
                    .containsExactly(-86_400_000L to 1L)
            }.verifyComplete()
        dateHistogram(service, "state.epochSeconds").test()
            .assertNext { it.assert().isEmpty() }
            .verifyComplete()
        dateHistogram(service, "state.epochMillis").test()
            .assertNext { rows ->
                rows.map { it.path("day").longValue() to it.path("count").longValue() }.assert()
                    .containsExactly(0L to 1L)
            }.verifyComplete()

        updateState(mapOf("epochMillis" to Long.MAX_VALUE))
        dateHistogram(service, "state.epochMillis").test()
            .assertNext { rows ->
                rows.assert().hasSize(1)
                rows.single().path("count").longValue().assert().isEqualTo(1L)
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

    private fun dateHistogram(service: SnapshotQueryBackend, field: String) = aggregation {
        dateHistogram(field, me.ahoo.wow.api.query.AggregationDateUnit.DAY, "day")
        count("count")
    }.query(service).collectList()

    private fun projectionSchemaProvider(): QueryModelSchemaProvider {
        val schema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                QueryField("view") to projectionFieldSchema(QueryField("document")),
                QueryField("view.name") to projectionFieldSchema(QueryField("document.name")),
            ),
        )
        return object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
            override fun refresh(): Mono<QueryModelSchema> = Mono.just(schema)
        }
    }

    private fun projectionFieldSchema(projectionField: QueryField) = QueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = emptySet(),
        nullable = true,
        required = false,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = false,
        bindings = emptyMap(),
        projectionField = projectionField,
        rewriteMode = QueryRewriteMode.NONE,
    )

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

    private fun strictService(
        schemaSources: List<QuerySchemaSource> = querySchemaSources,
    ): SnapshotQueryBackend =
        ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = schemaSources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

    private fun compatibleService(): SnapshotQueryBackend =
        ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = querySchemaSources,
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

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

    private fun defensiveEpochService(): SnapshotQueryBackend {
        val field = QueryField("state.epochFraction")
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(field to defensiveEpochField(field)),
        )
        val provider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)

            override fun refresh(): Mono<QueryModelSchema> = Mono.just(schema)
        }
        return ElasticsearchSnapshotQueryBackend(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            elasticsearchClient = elasticsearchClient,
            schemaProvider = provider,
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
        )
    }

    private fun defensiveEpochField(field: QueryField) = QueryFieldSchema(
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
                resolvedField = field,
                physicalField = field,
                storageType = QueryStorageType("double"),
            ),
        ),
        rewriteMode = QueryRewriteMode.INFER,
    )

    private fun source(vararg fields: Pair<QueryField, QueryFieldDeclaration>): QuerySchemaSource =
        object : QuerySchemaSource {
            override val priority: Int = QuerySchemaSourcePriority.BEAN

            override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> =
                Flux.just(QuerySchemaDeclaration(fields.toMap()))
        }

    private fun stringField(field: String) = QueryField(field) to QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
    )

    private fun formattedField(field: String, pattern: String) = QueryField(field) to QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        semanticType = DeclarationValue.Set(Temporal.Formatted(pattern)),
    )

    private fun epochField(field: String, timeUnit: TimeUnit) = QueryField(field) to QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
        nullable = DeclarationValue.Set(true),
        required = DeclarationValue.Set(false),
        cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
        semanticType = DeclarationValue.Set(Temporal.Epoch(timeUnit)),
    )
}

private fun AggregationQuery.query(backend: SnapshotQueryBackend) = backend.aggregate(this)
