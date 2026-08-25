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
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldUsage
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
                                                    .properties("productId") { it.keyword { keyword -> keyword } }
                                                    .properties("quantity") { it.integer { number -> number } }
                                                    .properties("amount") { it.double_ { number -> number } }
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
}
