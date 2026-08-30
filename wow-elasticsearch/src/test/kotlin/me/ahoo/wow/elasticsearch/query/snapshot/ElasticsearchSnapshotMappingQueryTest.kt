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

import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.snapshot.requiredQueryModelSchemaProvider
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

class ElasticsearchSnapshotMappingQueryTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val indicesClient = mockk<ReactiveElasticsearchIndicesClient>()
    private val searchRequest = slot<SearchRequest>()

    init {
        every { client.indices() } returns indicesClient
        every { client.search(capture(searchRequest), ObjectNode::class.java) } returns Mono.just(emptySearchResponse())
    }

    @Test
    fun `strict service should reject an unknown field before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )
        val service = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = client,
            queryBatchSize = DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = emptyList(),
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<Any>(MOCK_AGGREGATE_METADATA)

        service.list(ListQuery(filter = equal("state.unknown", "value"), limit = 10)).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should reject a root nested child filter before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )

        strictQueryBackend().list(
            ListQuery(filter = equal("state.orders.status", "PAID"), limit = 10),
        ).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should reject a root nested child sort before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )

        strictQueryBackend().list(
            ListQuery(
                filter = MatchAllFilter,
                sort = listOf(Sort("state.orders.status", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should reject presence checks on object containers before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )
        val service = strictQueryBackend()

        listOf(
            ExistsFilter(LogicalField("state")),
            IsEmptyFilter(LogicalField("state.orders")),
        ).forEach { filter ->
            service.list(ListQuery(filter = filter, limit = 10)).test()
                .expectError(QuerySchemaValidationException::class.java)
                .verify()
        }

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should execute a nested child inside element match`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )

        strictQueryBackend().list(
            ListQuery(
                filter = filter {
                    "state.orders".elementMatch {
                        "status" eq "PAID"
                    }
                },
                limit = 10,
            ),
        ).test().verifyComplete()

        val nested = searchRequest.captured.query()!!.bool().filter()[1].nested()
        nested.path().assert().isEqualTo("state.orders")
        nested.query().term().field().assert().isEqualTo("state.orders.status")
        verify(exactly = 1) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should reject a child below a single nested parent before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )

        strictQueryBackend().list(
            ListQuery(filter = equal("state.singleOrders.status", "PAID"), limit = 10),
        ).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should reject a child sort below a non-object nested parent before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )

        strictQueryBackend().list(
            ListQuery(
                filter = MatchAllFilter,
                sort = listOf(Sort("state.stringOrders.status", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should reject a child of an unindexed flattened field before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(
                TypeMapping.of { mapping ->
                    mapping.properties("tags") { it.flattened { flattened -> flattened.index(false) } }
                },
            ),
        )

        strictQueryBackend(emptyList()).list(
            ListQuery(filter = equal("tags.department", "eng"), limit = 10),
        ).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should reject dynamic exact without a keyword template before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(
                TypeMapping.of { mapping ->
                    mapping.properties("tags") {
                        it.`object` { objectField -> objectField.dynamic(co.elastic.clients.elasticsearch._types.mapping.DynamicMapping.True) }
                    }
                },
            ),
        )

        strictQueryBackend(emptyList()).list(
            ListQuery(filter = equal("tags.department", "eng"), limit = 10),
        ).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `compatible service should reject a known dynamic suffix before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(
                TypeMapping.of { mapping ->
                    mapping.properties("tags") {
                        it.`object` { objectField -> objectField.dynamic(DynamicMapping.True) }
                    }
                },
            ),
        )

        queryBackend().list(
            ListQuery(filter = equal("tags.department", "eng"), limit = 10),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `compatible service should not search system tags when mapping is unavailable`() {
        val failure = IllegalStateException("mapping unavailable")
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.error(failure)

        queryBackend().list(
            ListQuery(filter = equal("tags.department", "eng"), limit = 10),
        ).test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
                error.cause.assert().isSameAs(failure)
            }
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `strict service should reject an indexed dynamic flattened suffix before search`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(
                TypeMapping.of { mapping ->
                    mapping.properties("tags") { it.flattened { flattened -> flattened } }
                },
            ),
        )

        strictQueryBackend(emptyList()).list(
            ListQuery(filter = equal("tags.department", "eng"), limit = 10),
        ).test().expectError(QuerySchemaValidationException::class.java).verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `default snapshot query should compile fields from current mapping`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )
        val filter = filter {
            "state.name" eq "Wow"
            "state.name" search "Wow"
            "state.name".containsText("ow")
            "state.age" gt 18
        }

        queryBackend().list(
            ListQuery(
                filter = filter,
                projection = Projection(include = listOf("state.name")),
                sort = listOf(Sort("state.name", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).collectList().block()

        val filters = searchRequest.captured.query()!!.bool().filter()
        filters[1].term().field().assert().isEqualTo("state.name.keyword")
        filters[2].multiMatch().fields().single().assert().isEqualTo("state.name")
        filters[3].wildcard().field().assert().isEqualTo("state.name.keyword")
        filters[4].range().untyped().field().assert().isEqualTo("state.age")
        searchRequest.captured.sort().single().field().field().assert().isEqualTo("state.name.keyword")
        searchRequest.captured.source()!!.filter().includes().assert().containsExactly("state.name")
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy snapshot query should resolve logical fields before mapping inference`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )

        queryBackend().list(
            ListQuery(condition = Condition.eq("state.name", "Wow"), limit = 10),
        ).collectList().block()

        searchRequest.captured.query()!!.bool().filter()[1].term().field().assert()
            .isEqualTo("state.name.keyword")
    }

    @Test
    fun `missing field should fail without automatic mapping refresh`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )
        val service = queryBackend()

        repeat(2) {
            service.list(
                ListQuery(filter = equal("state.newField", "new"), limit = 10),
            ).test()
                .expectError(QuerySchemaValidationException::class.java)
                .verify()
        }

        verify(exactly = 1) { indicesClient.getMapping(any<GetMappingRequest>()) }
        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `typed element match should retain nested field qualification`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )

        queryBackend().list(
            ListQuery(
                filter = filter {
                    "body".elementMatch {
                        "name" eq "wow"
                    }
                },
                limit = 10,
            ),
        ).collectList().block()

        val nested = searchRequest.captured.query()!!.bool().filter()[1].nested()
        nested.path().assert().isEqualTo("body")
        nested.query().term().field().assert().isEqualTo("body.name")
    }

    @Test
    fun `explicit mapping refresh should make a new field queryable`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(queryMapping())),
            Mono.just(mappingResponse(queryMapping(includeNewField = true))),
        )
        val service = queryBackend()
        val query = ListQuery(filter = equal("state.newField", "new"), limit = 10)

        service.list(query).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()
        service.requiredQueryModelSchemaProvider().refresh().block()
        service.list(query).collectList().block()

        searchRequest.captured.query()!!.bool().filter()[1].term().field().assert().isEqualTo("state.newField")
        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `default factory should expose explicit mapping refresh`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(queryMapping())),
            Mono.just(mappingResponse(queryMapping(includeNewField = true))),
        )
        val factory = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = client,
            queryBatchSize = DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = schemaSources(),
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
        )
        val service = factory.create<Any>(MOCK_AGGREGATE_METADATA)
        val query = ListQuery(filter = equal("state.newField", "new"), limit = 10)

        service.list(query).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()
        service.requiredQueryModelSchemaProvider().refresh().block()
        service.list(query).collectList().block()

        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `factory with schema sources should use the supplied mapping resolver`() {
        val failure = IllegalStateException("custom resolver was used")
        val resolver = mockk<ElasticsearchIndexMappingResolver> {
            every { currentOrLoad(any()) } returns Mono.error(failure)
        }
        val service = ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = client,
            queryBatchSize = DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = DEFAULT_PIT_KEEP_ALIVE,
            indexMappingResolver = resolver,
            schemaSources = schemaSources(),
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
        ).create<Any>(MOCK_AGGREGATE_METADATA)

        service.requiredQueryModelSchemaProvider().schema().test()
            .expectErrorSatisfies { it.cause.assert().isSameAs(failure) }
            .verify()

        verify(exactly = 1) { resolver.currentOrLoad("wow.tck.mock_aggregate.snapshot") }
    }

    @Test
    fun `custom filter converter should keep physical field ownership`() {
        val convertedFilter = slot<FilterExpression>()
        val customConverter = mockk<me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter> {
            every { convert(capture(convertedFilter)) } returns matchAll { it }
        }
        val filter = equal("custom.physical", "value")
        val service = ElasticsearchSnapshotQueryBackend(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            elasticsearchClient = client,
            filterConverter = customConverter,
            queryBatchSize = DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = DEFAULT_PIT_KEEP_ALIVE,
        )

        service.list(ListQuery(filter = filter, limit = 10)).collectList().block()

        convertedFilter.captured.assert().isSameAs(filter)
        verify(exactly = 0) { client.indices() }
    }

    private fun queryBackend(): ElasticsearchSnapshotQueryBackend =
        ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = client,
            queryBatchSize = DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = schemaSources(),
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
        ).create<Any>(MOCK_AGGREGATE_METADATA) as ElasticsearchSnapshotQueryBackend

    private fun strictQueryBackend(
        sources: List<QuerySchemaSource> = schemaSources(),
    ): ElasticsearchSnapshotQueryBackend =
        ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = client,
            queryBatchSize = DEFAULT_SEARCH_BATCH_SIZE,
            queryKeepAlive = DEFAULT_PIT_KEEP_ALIVE,
            schemaSources = sources,
            validationMode = QuerySchemaValidationMode.STRICT,
        ).create<Any>(MOCK_AGGREGATE_METADATA) as ElasticsearchSnapshotQueryBackend

    private fun schemaSources(): List<QuerySchemaSource> {
        val context = QuerySchemaContext(MOCK_AGGREGATE_METADATA.materialize(), QueryModel.SNAPSHOT)
        val fields = listOf(
            "state.name" to QueryValueType.STRING,
            "state.age" to QueryValueType.INTEGER,
            "state.newField" to QueryValueType.STRING,
        ).associate { (field, type) ->
            LogicalField(field) to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(type)))
        }.toMutableMap()
        fields[LogicalField("state.orders")] = QueryFieldDeclaration(
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)),
            cardinality = DeclarationValue.Set(QueryCardinality.MANY),
        )
        fields[LogicalField("state.orders.status")] = QueryFieldDeclaration(
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        )
        fields[LogicalField("state.singleOrders")] = QueryFieldDeclaration(
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)),
            cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
        )
        fields[LogicalField("state.singleOrders.status")] = QueryFieldDeclaration(
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        )
        fields[LogicalField("state.stringOrders")] = QueryFieldDeclaration(
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
            cardinality = DeclarationValue.Set(QueryCardinality.MANY),
        )
        fields[LogicalField("state.stringOrders.status")] = QueryFieldDeclaration(
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        )
        return listOf(
            BeanQuerySchemaSource(
                listOf(QuerySchemaRegistration(context, QuerySchemaDeclaration(fields))),
            ),
        )
    }

    private fun equal(field: String, value: Any): EqualFilter =
        EqualFilter(LogicalField(field), JsonSerializer.valueToTree(value))

    private fun mappingResponse(mapping: TypeMapping): GetMappingResponse =
        GetMappingResponse.of { response ->
            response.mappings(
                "wow.test.aggregate.snapshot",
                IndexMappingRecord.of { record -> record.mappings(mapping) },
            )
        }

    private fun queryMapping(includeNewField: Boolean = false): TypeMapping =
        TypeMapping.of { mapping ->
            mapping.properties("body") { body ->
                body.nested { nested ->
                    nested.properties("name") { name -> name.keyword { it } }
                        .properties("description") { description -> description.text { it } }
                        .properties("title") { title ->
                            title.text { text ->
                                text.fields("keyword") { keyword -> keyword.keyword { it } }
                            }
                        }
                }
            }
            mapping.properties("state") { state ->
                state.`object` { objectField ->
                    objectField
                        .properties("name") { name ->
                            name.text { text ->
                                text.fields("keyword") { keyword -> keyword.keyword { it } }
                            }
                        }.properties("age") { age -> age.integer { it } }
                        .properties("orders") { orders ->
                            orders.nested { nested ->
                                nested.properties("status") { status -> status.keyword { it } }
                            }
                        }
                        .properties("singleOrders") { orders ->
                            orders.nested { nested ->
                                nested.properties("status") { status -> status.keyword { it } }
                            }
                        }.properties("stringOrders") { orders ->
                            orders.nested { nested ->
                                nested.properties("status") { status -> status.keyword { it } }
                            }
                        }
                    if (includeNewField) {
                        objectField.properties("newField") { field -> field.keyword { it } }
                    }
                    objectField
                }
            }
        }

    private fun emptySearchResponse(): SearchResponse<ObjectNode> =
        SearchResponse.of<ObjectNode> {
            it.took(1)
                .timedOut(false)
                .shards { shards -> shards.failed(0).successful(1).total(1) }
                .hits { hits -> hits.hits(emptyList()) }
        }
}
