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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.CountResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.converter.ConditionConverter
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

class AbstractElasticsearchQueryServiceTest {
    private val elasticsearchClient = mockk<ReactiveElasticsearchClient>()
    private val conditionConverter = mockk<ConditionConverter<Query>> {
        every { convert(any()) } returns matchAll { it }
    }
    private val queryService = TestElasticsearchQueryService(elasticsearchClient, conditionConverter)

    @Test
    fun `dynamic list should not track exact total hits`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), Map::class.java) } returns Mono.just(
            searchResponse(total = null)
        )

        val result = queryService.dynamicList(
            ListQuery(
                condition = Condition.ALL,
                projection = Projection(include = listOf("field")),
                sort = listOf(Sort("field", Sort.Direction.ASC)),
                limit = 7,
            )
        ).collectList().block()!!

        request.captured.trackTotalHits()!!.enabled().assert().isFalse()
        request.captured.size().assert().isEqualTo(7)
        request.captured.source()!!.filter().includes().assert().containsExactly("field")
        request.captured.sort().assert().hasSize(1)
        result.assert().hasSize(1)
    }

    @Test
    fun `dynamic paged should track exact total hits`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), Map::class.java) } returns Mono.just(
            searchResponse(total = 42)
        )

        val result = queryService.dynamicPaged(PagedQuery(Condition.ALL)).block()!!

        request.captured.trackTotalHits()!!.enabled().assert().isTrue()
        result.total.assert().isEqualTo(42)
        result.list.assert().hasSize(1)
        result.list.single()["field"].assert().isEqualTo("value")
    }

    @Test
    fun `count should use count api`() {
        val request = slot<CountRequest>()
        every { elasticsearchClient.count(capture(request)) } returns Mono.just(
            CountResponse.of {
                it.count(42)
                    .shards { shards -> shards.failed(0).successful(1).total(1) }
            }
        )

        val result = queryService.count(Condition.ALL).block()!!

        request.captured.index().assert().containsExactly("test-index")
        result.assert().isEqualTo(42)
        verify(exactly = 0) { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) }
    }

    private fun searchResponse(total: Long?): SearchResponse<Map<*, *>> {
        return SearchResponse.of<Map<*, *>> {
            it.took(1)
                .timedOut(false)
                .shards { shards -> shards.failed(0).successful(1).total(1) }
                .hits { hits ->
                    if (total != null) {
                        hits.total { totalHits -> totalHits.relation(TotalHitsRelation.Eq).value(total) }
                    }
                    hits.hits { hit ->
                        hit.index("test-index")
                            .id("1")
                            .source(mutableMapOf<String, Any?>("field" to "value"))
                    }.hits { hit -> hit.index("test-index").id("2") }
                }
        }
    }

    private class TestElasticsearchQueryService(
        override val elasticsearchClient: ReactiveElasticsearchClient,
        override val conditionConverter: ConditionConverter<Query>,
    ) : AbstractElasticsearchQueryService<DynamicDocument>() {
        override val namedAggregate: NamedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val indexName: String = "test-index"

        override fun toTypedResult(document: DynamicDocument): DynamicDocument = document
    }
}
