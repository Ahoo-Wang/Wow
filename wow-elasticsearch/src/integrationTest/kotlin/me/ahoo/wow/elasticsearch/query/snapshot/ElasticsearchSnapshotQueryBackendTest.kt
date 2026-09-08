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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy.Companion.toFilterExpression
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.query.SnapshotQueryBackendSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import tools.jackson.databind.node.JsonNodeFactory
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
                                }.properties("names") { it.`object` { names ->
                                    names.properties("en") { it.`object` { en ->
                                        en.properties("primary") { it.text { text ->
                                            text.fields("keyword") { it.keyword { it } }
                                        } }
                                    } }
                                } }
                                .properties("scores") { it.long_ { it } }
                                .properties("times") { it.long_ { it } }
                                .properties("decimalValue") { it.double_ { number -> number } }
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
                                                        .properties("missing") { it.double_ { it } }
                                                        .properties("createdAt") { it.date { date -> date } }
                                                        .properties("epochSeconds") { it.long_ { number -> number } }
                                                        .properties("productName") { productName ->
                                                            productName.text { text ->
                                                                text.fields(
                                                                    "keyword",
                                                                ) { keyword ->
                                                                    keyword.keyword { it }
                                                                }
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

        snapshotOwnershipQuery().query(queryBackendBinding)
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
        snapshotOwnershipQuery().query(queryBackendBinding)
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
        val publisher = snapshotOwnershipQuery().query(queryBackendBinding).next()

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
    fun `cursor repeat should create clean snapshot object nodes for every subscription`() {
        val schema = queryBackendBinding.schemaProvider.schema().block()!!
        val query = CursorQuery(filterExpression { "aggregateId" eq snapshot.aggregateId.id }, sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC)), size = 1)
        val publisher = snapshotQueryBackend.cursor(
            me.ahoo.wow.query.schema.validateQuery(query, schema),
            schema,
        )

        publisher.map { it.list.single() }.repeat(1).index()
            .doOnNext { indexed -> if (indexed.t1 == 0L) indexed.t2.put("mutated", true) }
            .map { it.t2 }.collectList().test()
            .assertNext { nodes ->
                nodes.assert().hasSize(2)
                nodes[1].assert().isNotSameAs(nodes[0])
                nodes[1].has("mutated").assert().isFalse()
                nodes.map { it.path("aggregateId").asString() }.assert()
                    .containsExactly(snapshot.aggregateId.id, snapshot.aggregateId.id)
            }.verifyComplete()
    }

    @Test
    fun `model level search should execute against mixed text numeric and date mappings`() {
        updateState(mapOf("data" to "searchable"))

        ListQuery(filter = SearchFilter("searchable"), limit = 10).query(queryBackendBinding).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `aggregation helper should prepare only on subscription`() {
        val querySchema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), me.ahoo.wow.query.schema.LogicalQuerySchema(me.ahoo.wow.query.schema.QueryValueSchema(me.ahoo.wow.api.query.schema.QueryValueKind.OBJECT)), emptyMap())
        val schemaCalls = AtomicInteger()
        val binding = QueryBackendBinding(
            NoOpSnapshotQueryBackend(MOCK_AGGREGATE_METADATA),
            object : QueryModelSchemaProvider {
                override fun schema(): Mono<QueryModelSchema> {
                    schemaCalls.incrementAndGet()
                    return Mono.just(querySchema)
                }

                override fun refresh(): Mono<QueryModelSchema> = schema()
            },
        )

        val publisher = aggregation { count("count") }.query(binding)

        schemaCalls.get().assert().isZero()
        publisher.test().verifyComplete()
        schemaCalls.get().assert().isOne()
    }

    @Test
    fun `field specific search bindings should be exact`() {
        updateState(mapOf("data" to "searchable"))
        val strictService = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = querySchemaSources,
        ).create(MOCK_AGGREGATE_METADATA)

        ListQuery(
            filter = SearchFilter("searchable", setOf(QueryField("state.data"))),
            limit = 10,
        ).query(strictService).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict projection should return the source field instead of its query multi-field`() {
        val field = "state.sourceOnlyName"
        updateState(mapOf("sourceOnlyName" to "visible"))
        val service = strictService(querySchemaSources + source(stringField(field)))

        ListQuery(
            filter = filterExpression { field gt "alpha" },
            projection = Projection(include = listOf(QueryField(field))),
            limit = 10,
        ).query(service).test()
            .assertNext { document ->
                document.path("state").path("sourceOnlyName").asString().assert().isEqualTo("visible")
            }.verifyComplete()
    }

    @Test
    fun `strict projection should resolve an alias to its source target`() {
        val field = "state.sourceOnlyAlias"
        updateState(mapOf("sourceOnlyName" to "visible"))
        val service = strictService(querySchemaSources + source(stringField(field)))

        ListQuery(
            filter = MatchAllFilter,
            projection = Projection(include = listOf(QueryField(field))),
            limit = 10,
        ).query(service).test()
            .assertNext { document ->
                document.path("state").path("sourceOnlyName").asString().assert().isEqualTo("visible")
            }.verifyComplete()
    }

    @Test
    fun `strict projection should return a declared unmapped source field`() {
        val field = "state.opaque.name"
        updateState(mapOf("opaque" to mapOf("name" to "visible")))
        val service = strictService(querySchemaSources + source(stringField(field)))

        ListQuery(
            filter = MatchAllFilter,
            projection = Projection(include = listOf(QueryField(field))),
            limit = 10,
        ).query(service).test()
            .assertNext { document ->
                document.path("state").path("opaque").path("name").asString().assert().isEqualTo("visible")
            }.verifyComplete()
    }

    @Test
    fun `projection should compile logical scalar and object nodes to physical subtrees`() {
        updateDocument(mapOf("document" to mapOf("name" to "visible", "secret" to "hidden")))
        val binding = QueryBackendBinding(
            ElasticsearchSnapshotQueryBackend(
                namedAggregate = MOCK_AGGREGATE_METADATA,
                elasticsearchClient = elasticsearchClient,
            ),
            projectionSchemaProvider(),
        )
        fun query(projection: Projection) =
            ListQuery(MatchAllFilter, projection = projection, limit = 1)
                .query(binding).blockFirst()!!

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
    fun `null replacement must not advertise source null or equality semantics`() {
        val field = "state.nullReplacement"
        elasticsearchClient.indices().putMapping { request ->
            request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName())
                .properties(field) { it.keyword { it.nullValue("NULL_TOKEN") } }
        }.block()
        updateDocument(mapOf("state" to mapOf("nullReplacement" to null)))
        val storedSource = elasticsearchClient.search(co.elastic.clients.elasticsearch.core.SearchRequest.of { request ->
            request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName()).allowPartialSearchResults(false)
                .query(co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term { it.field(field).value("NULL_TOKEN") }).size(1)
        }, ObjectNode::class.java).block()!!.hits().hits().single().source()!!
        storedSource.path("state").path("nullReplacement").isNull.assert().isTrue()
        elasticsearchClient.count(co.elastic.clients.elasticsearch.core.CountRequest.of { request ->
            request.index(MOCK_AGGREGATE_METADATA.toSnapshotIndexName()).query(co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.exists { it.field(field) })
        }).block()!!.count().assert().isEqualTo(1L)
        val binding = strictService(querySchemaSources + source(QueryField(field) to QueryFieldDeclaration(
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)), nullable = DeclarationValue.Set(true),
        )))
        val schema = binding.schemaProvider.schema().block()!!
        schema.field(QueryField(field))!!.bindings.assert().isEmpty()
        schema.field(QueryField(field))!!.projectionField.assert().isEqualTo(QueryField(field))
        listOf(me.ahoo.wow.api.query.IsNullFilter(QueryField(field)),
            me.ahoo.wow.api.query.EqualFilter(QueryField(field), JsonNodeFactory.instance.stringNode("NULL_TOKEN"))).forEach { query ->
            assertThrows<QuerySchemaValidationException> { me.ahoo.wow.query.schema.validateQuery(query, schema) }
        }
    }

    @Test
    fun `known map keys use their keyword binding and source while future keys remain unsupported`() {
        val strings = QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
        val names = QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.OBJECT),
            additionalProperties = DeclarationValue.Set(QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.OBJECT),
                additionalProperties = DeclarationValue.Set(strings))))
        val binding = strictService(querySchemaSources + source(QueryField("state.names") to names))
        val schema = binding.schemaProvider.schema().block()!!
        updateState(mapOf("names" to mapOf("en" to mapOf("primary" to "Hello"), "fr" to mapOf("primary" to "Bonjour"))))
        val query = ListQuery(filter = filterExpression { "state.names.en.primary" eq "Hello" },
            projection = Projection(include = listOf(QueryField("state.names.en.primary"))), limit = 10)
        binding.backend.list(me.ahoo.wow.query.schema.validateQuery(query, schema), schema).test()
            .assertNext { it.path("state").path("names").path("en").path("primary").asString().assert().isEqualTo("Hello") }
            .verifyComplete()
        schema.field(QueryField("state.names.en.primary"))!!.binding(QueryCapability.EXACT_MATCH)!!.physicalField
            .assert().isEqualTo(QueryField("state.names.en.primary.keyword"))
        schema.field(QueryField("state.names.en.primary.keyword")).assert().isNull()
        assertThrows<QuerySchemaValidationException> {
            me.ahoo.wow.query.schema.validateQuery(ListQuery(filter = filterExpression { "state.names.fr.primary" eq "Bonjour" }), schema)
        }
    }

    @Test
    fun `scalar array members and epoch array relative time execute against native fields`() {
        fun array(item: QueryFieldDeclaration) = QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.ARRAY),
            items = DeclarationValue.Set(item))
        val scores = array(QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER))))
        val times = array(QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
            semanticType = DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS))))
        val binding = strictService(querySchemaSources + source(QueryField("state.scores") to scores, QueryField("state.times") to times))
        updateState(mapOf("scores" to listOf(1, 3), "times" to listOf(Instant.now().epochSecond)))
        ListQuery(filter = filterExpression { "state.scores" eq 3 }, limit = 10).query(binding).test().expectNextCount(1).verifyComplete()
        ListQuery(filter = filterExpression { "state.scores" gt 2 }, limit = 10).query(binding).test().expectNextCount(1).verifyComplete()
        ListQuery(filter = TodayFilter(QueryField("state.times"), zoneId = "UTC"), limit = 10)
            .query(binding).test().expectNextCount(1).verifyComplete()
        val schema = binding.schemaProvider.schema().block()!!
        schema.field(QueryField("state.scores"))!!.binding(QueryCapability.CURSOR_SORT).assert().isNull()
        assertThrows<QuerySchemaValidationException> {
            binding.backend.list(ListQuery(filter = me.ahoo.wow.api.query.EqualFilter(QueryField("state.scores"),
                JsonNodeFactory.instance.arrayNode().add(1).add(3))), schema)
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

        ListQuery(
            filter = TodayFilter(field, zoneId = "UTC"),
            sort = listOf(Sort(QueryField("eventTime"), Sort.Direction.DESC)),
            limit = 10,
        ).query(service).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `strict should execute logical document id equality`() {
        val service = strictService()
        ListQuery(
            filter = me.ahoo.wow.api.query.IdFilter(snapshot.aggregateId.id),
            limit = 10,
        ).query(service).test().expectNextCount(1).verifyComplete()
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
        ).query(service).test().expectNextCount(1).verifyComplete()
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

        ListQuery(
            filter = filterExpression { field eq "green" },
            projection = Projection(include = listOf(QueryField(field))),
            limit = 10,
        ).query(service).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `unknown fields fail in every query mode`() {
        ListQuery(filter = filterExpression { "state.unknown" eq "value" }, limit = 10)
            .query(queryBackendBinding).test().expectError(QuerySchemaValidationException::class.java).verify()
    }

    @Test
    fun `all modes should reject invalid declared epoch literals before Elasticsearch`() {
        listOf(
            queryBackendBinding,
            strictService(),
        ).forEach { service ->
            assertThrows<QuerySchemaValidationException> {
                validated(
                    service,
                    ListQuery(
                        filter = filterExpression { "firstEventTime" lte "not-a-timestamp" },
                        limit = 10,
                    ),

                )
            }
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

        listOf(
            queryBackendBinding,
            strictService(),
        ).forEach { service ->
            assertThrows<QuerySchemaValidationException> {
                validated(service, ListQuery(filter = mismatchedPrincipal, limit = 10))
            }
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
        strictService.schemaProvider.schema().block()!!
            .field(QueryField("tags"))!!.bindings.assert().doesNotContainKey(QueryCapability.EXACT_MATCH)
        updateDocument(mapOf("tags" to mapOf("department" to listOf("eng"))))

        assertThrows<QuerySchemaValidationException> {
            validated(
                strictService,
                ListQuery(
                    filter = mapOf("department" to listOf("eng")).toFilterExpression(),
                    limit = 10,
                ),

            )
        }
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
        compatibleService.schemaProvider.schema().block()!!
            .field(QueryField("tags"))!!.bindings.assert().doesNotContainKey(QueryCapability.EXACT_MATCH)
        updateDocument(
            mapOf(
                "tags" to mapOf(
                    "visibility" to listOf("public"),
                    "department" to listOf("eng"),
                ),
            ),
        )

        val filter = mapOf("department" to listOf("eng")).toFilterExpression()
        listOf(
            compatibleService,
            strictService(),
        ).forEach { service ->
            assertThrows<QuerySchemaValidationException> {
                validated(service, ListQuery(filter = filter, limit = 10))
            }
        }
    }

    @Test
    fun `strict should reject a root nested child filter`() {
        val service = strictService()
        assertThrows<QuerySchemaValidationException> {
            validated(
                service,
                ListQuery(filter = filterExpression { "state.orders.status" eq "PAID" }, limit = 10),

            )
        }
    }

    @Test
    fun `strict should reject a root nested child sort`() {
        val service = strictService()
        assertThrows<QuerySchemaValidationException> {
            validated(
                service,
                ListQuery(
                    filter = MatchAllFilter,
                    sort = listOf(Sort(QueryField("state.orders.status"), Sort.Direction.ASC)),
                    limit = 10,
                ),

            )
        }
    }

    @Test
    fun `strict should execute a nested child inside element match`() {
        updateState(mapOf("orders" to listOf(mapOf("status" to "PAID"))))

        val service = strictService()
        ListQuery(
            filter = filterExpression {
                "state.orders".elementMatch {
                    "status" eq "PAID"
                }
            },
            limit = 10,
        ).query(service).test().expectNextCount(1).verifyComplete()
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
                epochField("state.orders.lines.epochSeconds", TimeUnit.SECONDS),
            ),
        ).create(MOCK_AGGREGATE_METADATA)

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
    fun `computed metric should reject a text field without numeric representation`() {
        val service = strictService()
        aggregation {
            sum(field("state.data") * constant(1.0), "unreadable")
        }.query(service)
            .test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()
    }

    @Test
    fun `computed metric should reject a mapped field without index or doc values`() {
        val service = strictService(querySchemaSources + source(QueryField("state.unreadableNumber") to QueryFieldDeclaration(
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.DECIMAL)),
        )))
        aggregation {
            sum(field("state.unreadableNumber") * constant(1.0), "unreadable")
        }.query(service)
            .test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()
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
        ).create(MOCK_AGGREGATE_METADATA)
        val provider = service.schemaProvider
        val initial = provider.schema().block()!!
        checkNotNull(initial.field(QueryField("state.runtimeCode"))).bindings.assert()
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
        checkNotNull(refreshed.field(QueryField("state.keywordOnly")))
            .bindings.getValue(QueryCapability.EXACT_MATCH).physicalField.assert()
            .isEqualTo(QueryField("state.keywordOnly"))
        checkNotNull(refreshed.field(QueryField("state.textOnly")))
            .bindings.getValue(QueryCapability.FULL_TEXT_TERMS).physicalField.assert()
            .isEqualTo(QueryField("state.textOnly"))
        checkNotNull(refreshed.field(QueryField("state.runtimeCode")))
            .bindings.getValue(QueryCapability.SORT).physicalField.assert()
            .isEqualTo(QueryField("state.runtimeCode"))
        checkNotNull(refreshed.field(QueryField("state.runtimeCode"))).projectionField.assert().isNull()
        provider.schema().block().assert().isSameAs(refreshed)

        ListQuery(
            filter = filterExpression { "state.runtimeCode" eq "runtime" },
            sort = listOf(Sort(QueryField("state.runtimeCode"), Sort.Direction.ASC)),
            limit = 10,
        ).query(service).test().expectNextCount(1).verifyComplete()
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
        ).create(MOCK_AGGREGATE_METADATA)
        val schema = service.schemaProvider.schema().block()!!
        listOf("state.epochMicros", "state.epochMillis", "state.epochNanos", "state.epochSeconds")
            .forEach { field ->
                checkNotNull(schema.field(QueryField(field))).bindings
                    .getValue(QueryCapability.AGGREGATE_TEMPORAL).let { binding ->
                        binding.physicalField.assert().isEqualTo(QueryField(field))
                        binding.storageTypes?.singleOrNull()?.value.assert().isEqualTo("long")
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

    private fun dateHistogram(binding: QueryBackendBinding<SnapshotQueryBackend>, field: String) = aggregation {
        dateHistogram(field, me.ahoo.wow.api.query.AggregationDateUnit.DAY, "day")
        count("count")
    }.query(binding).collectList()

    private fun projectionSchemaProvider(): QueryModelSchemaProvider {
        val schema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            definition = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
                "deleted" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
                        "view" to QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
                    "name" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
                )),
            ))),
            bindings = mapOf(
                path("deleted") to QueryValueBindings(mapOf(QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(path("deleted"), null))),
                path("view") to QueryValueBindings(projectionPath = path("document"), responsePath = path("document")),
                path("view.name") to QueryValueBindings(projectionPath = path("document.name"), responsePath = path("document.name")),
            ),
        )
        return object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
            override fun refresh(): Mono<QueryModelSchema> = Mono.just(schema)
        }
    }

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
    ): QueryBackendBinding<SnapshotQueryBackend> =
        ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = schemaSources,
        ).create(MOCK_AGGREGATE_METADATA)

    private fun compatibleService(): QueryBackendBinding<SnapshotQueryBackend> =
        ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = querySchemaSources,
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

    private fun defensiveEpochService(): QueryBackendBinding<SnapshotQueryBackend> {
        val field = QueryField("state.epochFraction")
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
                "state" to QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
                    "epochFraction" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER),
                        semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS)),
                )),
                "deleted" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
            ))),
            mapOf(
                path(field.path) to QueryValueBindings(mapOf(QueryCapability.AGGREGATE_TEMPORAL to
                    QueryFieldBindingTemplate(path(field.path), setOf(QueryStorageType("double"))))),
                path("deleted") to QueryValueBindings(mapOf(QueryCapability.EXACT_MATCH to
                    QueryFieldBindingTemplate(path("deleted"), setOf(QueryStorageType("boolean"))))),
            ),
        )
        val provider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)

            override fun refresh(): Mono<QueryModelSchema> = Mono.just(schema)
        }
        return QueryBackendBinding(
            ElasticsearchSnapshotQueryBackend(
                namedAggregate = MOCK_AGGREGATE_METADATA,
                elasticsearchClient = elasticsearchClient,
            ),
            provider,
        )
    }

    private fun path(field: String) = QueryPathTemplate(field.split('.').map(QueryPathSegment::Property))

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
        semanticType = DeclarationValue.Set(Temporal.Epoch(timeUnit)),
    )
}

private fun AggregationQuery.query(
    binding: QueryBackendBinding<SnapshotQueryBackend>,

) = Mono.defer { binding.schemaProvider.schema() }.flatMapMany { schema ->
    binding.backend.aggregate(me.ahoo.wow.query.schema.validateQuery(this, schema), schema)
}

private fun IListQuery.query(
    binding: QueryBackendBinding<SnapshotQueryBackend>,

): Flux<ObjectNode> = Mono.defer { binding.schemaProvider.schema() }.flatMapMany { schema ->
    binding.backend.list(me.ahoo.wow.query.schema.validateQuery(this, schema), schema)
}

private fun validated(
    binding: QueryBackendBinding<SnapshotQueryBackend>,
    query: IListQuery,

): IListQuery {
    val schema = binding.schemaProvider.schema().block()!!
    return me.ahoo.wow.query.schema.validateQuery(query, schema)
}
