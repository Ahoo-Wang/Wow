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
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldResolutionException
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ElasticsearchSnapshotMappingQueryTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val indicesClient = mockk<ReactiveElasticsearchIndicesClient>()
    private val searchRequest = slot<SearchRequest>()

    init {
        every { client.indices() } returns indicesClient
        every { client.search(capture(searchRequest), Map::class.java) } returns Mono.just(emptySearchResponse())
    }

    @Test
    fun `default snapshot query should compile fields from current mapping`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )
        val condition = Condition.and(
            Condition.eq("state.name", "Wow"),
            Condition.match("state.name", "Wow"),
            Condition.contains("state.name", "ow"),
            Condition.gt("state.age", 18),
        )

        queryService().dynamicList(
            ListQuery(
                condition = condition,
                projection = Projection(include = listOf("state.name")),
                sort = listOf(Sort("state.name", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).collectList().block()

        val filters = searchRequest.captured.query()!!.bool().filter()[1].bool().filter()
        filters[0].term().field().assert().isEqualTo("state.name.keyword")
        filters[1].match().field().assert().isEqualTo("state.name")
        filters[2].wildcard().field().assert().isEqualTo("state.name.keyword")
        filters[3].range().untyped().field().assert().isEqualTo("state.age")
        searchRequest.captured.sort().single().field().field().assert().isEqualTo("state.name.keyword")
        searchRequest.captured.source()!!.filter().includes().assert().containsExactly("state.name")
    }

    @Test
    fun `missing field should fail without automatic mapping refresh`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )
        val service = queryService()

        repeat(2) {
            service.dynamicList(
                ListQuery(condition = Condition.eq("state.newField", "new"), limit = 10),
            ).test()
                .expectError(ElasticsearchFieldResolutionException::class.java)
                .verify()
        }

        verify(exactly = 1) { indicesClient.getMapping(any<GetMappingRequest>()) }
        verify(exactly = 0) { client.search(any<SearchRequest>(), Map::class.java) }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy element match should retain nested match query`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            mappingResponse(queryMapping()),
        )

        queryService().dynamicList(
            ListQuery(
                condition = Condition.elemMatch("body", Condition.match("body.name", "wow")),
                limit = 10,
            ),
        ).collectList().block()

        val nested = searchRequest.captured.query()!!.bool().filter()[1].nested()
        nested.path().assert().isEqualTo("body")
        nested.query().bool().filter().single().match().field().assert().isEqualTo("body.name")
    }

    @Test
    fun `explicit mapping refresh should make a new field queryable`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(queryMapping())),
            Mono.just(mappingResponse(queryMapping(includeNewField = true))),
        )
        val resolver = ElasticsearchIndexMappingResolver(client)
        val service = queryService(resolver)
        val query = ListQuery(condition = Condition.eq("state.newField", "new"), limit = 10)

        service.dynamicList(query).test()
            .expectError(ElasticsearchFieldResolutionException::class.java)
            .verify()
        service.refreshIndexMapping().block()
        service.dynamicList(query).collectList().block()

        searchRequest.captured.query()!!.bool().filter()[1].term().field().assert().isEqualTo("state.newField")
        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `default factory should expose explicit mapping refresh`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(queryMapping())),
            Mono.just(mappingResponse(queryMapping(includeNewField = true))),
        )
        val factory = ElasticsearchSnapshotQueryServiceFactory(client)
        val service = factory.create<Any>(MOCK_AGGREGATE_METADATA)
        val query = ListQuery(condition = Condition.eq("state.newField", "new"), limit = 10)

        service.dynamicList(query).test()
            .expectError(ElasticsearchFieldResolutionException::class.java)
            .verify()
        factory.refreshIndexMapping(MOCK_AGGREGATE_METADATA).block()
        service.dynamicList(query).collectList().block()

        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `custom condition converter should keep physical field ownership`() {
        val convertedFilter = slot<me.ahoo.wow.api.query.FilterExpression>()
        val customConverter = mockk<me.ahoo.wow.elasticsearch.query.AbstractElasticsearchConditionConverter> {
            every { convert(capture(convertedFilter)) } returns matchAll { it }
        }
        val condition = Condition.eq("custom.physical", "value")
        val service = ElasticsearchSnapshotQueryService<Any>(
            MOCK_AGGREGATE_METADATA,
            client,
            customConverter,
            DEFAULT_SEARCH_BATCH_SIZE,
            DEFAULT_PIT_KEEP_ALIVE,
            ElasticsearchIndexMappingResolver(client),
        )

        service.dynamicList(ListQuery(condition = condition, limit = 10)).collectList().block()

        convertedFilter.captured.assert().isEqualTo(condition.toFilterExpression())
        assertThrows<IllegalArgumentException> { service.refreshIndexMapping() }
        verify(exactly = 0) { client.indices() }
    }

    @Test
    fun `custom condition converter should keep physical field ownership for aggregation`() {
        val convertedFilter = slot<FilterExpression>()
        val customConverter = mockk<me.ahoo.wow.elasticsearch.query.AbstractElasticsearchConditionConverter> {
            every { convert(capture(convertedFilter)) } returns matchAll { it }
        }
        val aggregationFilter = filter { "custom.physical" eq "value" }
        val service = ElasticsearchSnapshotQueryService<Any>(
            MOCK_AGGREGATE_METADATA,
            client,
            customConverter,
            DEFAULT_SEARCH_BATCH_SIZE,
            DEFAULT_PIT_KEEP_ALIVE,
            ElasticsearchIndexMappingResolver(client),
        )

        service.aggregate(
            AggregationQuery(
                filter = aggregationFilter,
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).collectList().block()

        convertedFilter.captured.assert().isEqualTo(aggregationFilter)
        verify(exactly = 0) { client.indices() }
    }

    private fun queryService(
        resolver: ElasticsearchIndexMappingResolver = ElasticsearchIndexMappingResolver(client),
    ): ElasticsearchSnapshotQueryService<Any> =
        ElasticsearchSnapshotQueryService(
            MOCK_AGGREGATE_METADATA,
            client,
            SnapshotConditionConverter,
            DEFAULT_SEARCH_BATCH_SIZE,
            DEFAULT_PIT_KEEP_ALIVE,
            resolver,
        )

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
                    if (includeNewField) {
                        objectField.properties("newField") { field -> field.keyword { it } }
                    }
                    objectField
                }
            }
        }

    private fun emptySearchResponse(): SearchResponse<Map<*, *>> =
        SearchResponse.of<Map<*, *>> {
            it.took(1)
                .timedOut(false)
                .shards { shards -> shards.failed(0).successful(1).total(1) }
                .hits { hits -> hits.hits(emptyList()) }
        }
}
