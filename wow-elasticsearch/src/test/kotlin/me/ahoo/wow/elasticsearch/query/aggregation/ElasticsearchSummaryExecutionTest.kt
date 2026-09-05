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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotFilterCompiler
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.schema.QueryModelSchema
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.CompletableFuture

class ElasticsearchSummaryExecutionTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())

    @Test
    fun `summary should defer each repeated search and create fresh rows`() {
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(response(1)),
            Mono.just(response(2)),
        )
        val result = ElasticsearchAggregationPager(client, "summary-alias").execute(plan())
        verify(exactly = 0) { client.search(any<SearchRequest>(), Map::class.java) }

        val rows = result.repeat(1).collectList().block()!!

        rows.map { it.path("count").longValue() }.assert().containsExactly(1L, 2L)
        rows[0].assert().isNotSameAs(rows[1])
        verify(exactly = 2) { client.search(any<SearchRequest>(), Map::class.java) }
        verifyNoPointInTime()
    }

    @Test
    fun `summary should propagate a search failure`() {
        val failure = IllegalStateException("search-failure")
        every { client.search(any<SearchRequest>(), Map::class.java) } returns
            Mono.error<ResponseBody<Map<*, *>>>(failure)

        ElasticsearchAggregationPager(client, "summary-alias").execute(plan()).test()
            .expectErrorMatches { it === failure }
            .verify()

        verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
        verifyNoPointInTime()
    }

    @Test
    fun `summary retry should issue a fresh search and emit only the successful row`() {
        val failure = IllegalStateException("first-search")
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.error<ResponseBody<Map<*, *>>>(failure),
            Mono.just(response(2)),
        )
        val result = ElasticsearchAggregationPager(client, "summary-alias").execute(plan())

        result.retry(1).test()
            .assertNext { it.path("count").longValue().assert().isEqualTo(2L) }
            .verifyComplete()

        verify(exactly = 2) { client.search(any<SearchRequest>(), Map::class.java) }
        verifyNoPointInTime()
    }

    @Test
    fun `cancelling a summary should cancel its search future`() {
        val future = CompletableFuture<ResponseBody<Map<*, *>>>()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.fromFuture(future)
        val result = ElasticsearchAggregationPager(client, "summary-alias").execute(plan())

        result.test().thenCancel().verify()

        future.isCancelled.assert().isTrue()
        verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
        verifyNoPointInTime()
    }

    @Test
    fun `nested summary should preserve compiled request and normalize metrics`() {
        val request = slot<SearchRequest>()
        every { client.search(capture(request), Map::class.java) } returns Mono.just(nestedResponse())
        val plan = compile(
            aggregation {
                expand("state.items")
                count("count")
                sum(field("amount") * constant(2.0), "total")
            },
        )

        ElasticsearchAggregationPager(client, "summary-alias").execute(plan).test()
            .assertNext {
                it.path("count").longValue().assert().isEqualTo(3L)
                it.path("total").doubleValue().assert().isEqualTo(12.0)
            }
            .verifyComplete()

        request.captured.apply {
            index().assert().containsExactly("summary-alias")
            pit().assert().isNull()
            allowPartialSearchResults().assert().isEqualTo(false)
            size().assert().isEqualTo(0)
            trackTotalHits()!!.enabled().assert().isFalse()
            query().assert().isEqualTo(plan.rootQuery)
            runtimeMappings().assert().isEqualTo(plan.runtimeMappings)
            runtimeMappings().keys.assert().containsExactly("__wow_expression_1")
        }
        val root = request.captured.aggregations().getValue("__wow_aggregation")
        root.nested().path().assert().isEqualTo("state.items")
        val scope = root.aggregations().getValue("__wow_element_filter_0")
        scope.filter().assert().isEqualTo(plan.elements.single().filter)
        scope.aggregations().getValue("total").sum().field().assert().isEqualTo("__wow_expression_1")
        scope.aggregations().getValue("__wow_value_count_total").valueCount().field()
            .assert().isEqualTo("__wow_expression_1")
        verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
        verifyNoPointInTime()
    }

    private fun plan() = compile(aggregation { count("count") })

    private fun compile(query: me.ahoo.wow.api.query.AggregationQuery) =
        ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(query, schema)

    private fun response(count: Long): SearchResponse<Map<*, *>> = response(
        Aggregate.of { aggregate -> aggregate.filter { filter -> filter.docCount(count) } },
    )

    private fun nestedResponse(): SearchResponse<Map<*, *>> = response(
        Aggregate.of { aggregate ->
            aggregate.nested { nested ->
                nested.docCount(3)
                    .aggregations(
                        "__wow_element_filter_0",
                        Aggregate.of { value ->
                            value.filter { filter ->
                                filter.docCount(3)
                                    .aggregations("total", Aggregate.of { it.sum { sum -> sum.value(12.0) } })
                                    .aggregations(
                                        "__wow_value_count_total",
                                        Aggregate.of { it.valueCount { count -> count.value(3.0) } },
                                    )
                            }
                        },
                    )
            }
        },
    )

    private fun response(root: Aggregate): SearchResponse<Map<*, *>> = SearchResponse.of<Map<*, *>> {
        it.took(1)
            .timedOut(false)
            .shards { shards -> shards.failed(0).successful(1).total(1) }
            .hits { hits -> hits.hits(emptyList()) }
            .aggregations("__wow_aggregation", root)
    }

    private fun verifyNoPointInTime() {
        verify(exactly = 0) { client.openPointInTime(any<OpenPointInTimeRequest>()) }
        verify(exactly = 0) { client.closePointInTime(any<ClosePointInTimeRequest>()) }
    }
}
