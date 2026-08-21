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
import co.elastic.clients.elasticsearch._types.query_dsl.Query
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
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono

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
    fun `missing field should refresh mapping once before compiling query`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(queryMapping())),
            Mono.just(mappingResponse(queryMapping(includeNewField = true))),
        )

        queryService().dynamicList(
            ListQuery(condition = Condition.eq("state.newField", "new"), limit = 10),
        ).collectList().block()

        searchRequest.captured.query()!!.bool().filter()[1].term().field().assert().isEqualTo("state.newField")
        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `custom condition converter should keep physical field ownership`() {
        val convertedCondition = slot<Condition>()
        val customConverter = mockk<ConditionConverter<Query>> {
            every { convert(capture(convertedCondition)) } returns matchAll { it }
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

        convertedCondition.captured.assert().isEqualTo(condition)
        verify(exactly = 0) { client.indices() }
    }

    private fun queryService(): ElasticsearchSnapshotQueryService<Any> =
        ElasticsearchSnapshotQueryService(
            MOCK_AGGREGATE_METADATA,
            client,
            SnapshotConditionConverter,
            DEFAULT_SEARCH_BATCH_SIZE,
            DEFAULT_PIT_KEEP_ALIVE,
            ElasticsearchIndexMappingResolver(client),
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
