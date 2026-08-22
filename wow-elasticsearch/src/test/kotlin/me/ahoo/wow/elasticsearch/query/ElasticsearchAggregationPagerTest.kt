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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregate
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch._types.aggregations.SumAggregate
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.util.NamedValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationPlan
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ElasticsearchAggregationPagerTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val source = NamedValue.of(
        "group",
        CompositeAggregationSource.of { it.terms { terms -> terms.field("group") } },
    )
    private val plan = ElasticsearchAggregationPlan(
        query = matchAll { it },
        aggregationQuery = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms("group", "group")),
            metrics = listOf(AggregationMetric.Count("count")),
        ),
        elements = emptyList(),
    )

    @Test
    fun `should reject invalid configuration and inputs`() {
        assertThrows<IllegalArgumentException> { ElasticsearchAggregationPager(client, "index", batchSize = 0) }
        assertThrows<IllegalArgumentException> {
            ElasticsearchAggregationPager(client, "index", batchSize = DEFAULT_SEARCH_BATCH_SIZE + 1)
        }
        val pager = ElasticsearchAggregationPager(client, "index")
        assertThrows<IllegalArgumentException> { pager.search(plan, emptyList(), emptyMap()) }
        assertThrows<IllegalArgumentException> { pager.search(plan, listOf(source), emptyMap(), limit = -1) }
    }

    @Test
    fun `should stop at a finite limit and keep the original pit when response id is blank`() {
        val requests = mutableListOf<SearchRequest>()
        val close = slot<ClosePointInTimeRequest>()
        stubPointInTime(close)
        every { client.search(capture(requests), Map::class.java) } returns Mono.just(
            response("", listOf(bucket(1)), FieldValue.of(1L)),
        )

        ElasticsearchAggregationPager(client, "index", batchSize = 2)
            .search(plan, listOf(source), emptyMap(), limit = 1)
            .test()
            .expectNextCount(1)
            .verifyComplete()

        requests.assert().hasSize(1)
        requests.single().aggregations()[ROWS]!!.composite().size().assert().isEqualTo(1)
        close.captured.id().assert().isEqualTo("pit-1")
    }

    @Test
    fun `should page buckets and close latest pit`() {
        val requests = mutableListOf<SearchRequest>()
        val close = slot<ClosePointInTimeRequest>()
        stubPointInTime(close)
        every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(response("pit-2", listOf(bucket(1), bucket(2)), FieldValue.of(2L))),
            Mono.just(response("pit-3", listOf(bucket(3)))),
        )

        ElasticsearchAggregationPager(client, "index", batchSize = 2)
            .search(plan, listOf(source), emptyMap())
            .map { it.key()["group"]!!.longValue() }
            .test()
            .expectNext(1L, 2L, 3L)
            .verifyComplete()

        requests.assert().hasSize(2)
        requests[1].aggregations()[ROWS]!!.composite().after()["group"]!!.longValue().assert().isEqualTo(2L)
        close.captured.id().assert().isEqualTo("pit-3")
    }

    @Test
    fun `should close pit on cancellation`() {
        val close = slot<ClosePointInTimeRequest>()
        stubPointInTime(close)
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(response("pit-2", listOf(bucket(1), bucket(2)), FieldValue.of(2L))),
            Mono.never(),
        )

        ElasticsearchAggregationPager(client, "index", batchSize = 2)
            .search(plan, listOf(source), emptyMap())
            .test()
            .expectNextCount(1)
            .thenCancel()
            .verify()

        close.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `should reject incomplete responses and still close pit`() {
        val close = slot<ClosePointInTimeRequest>()
        stubPointInTime(close)
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            response("pit-2", emptyList(), failedShards = 1),
        )

        ElasticsearchAggregationPager(client, "index", batchSize = 2)
            .search(plan, listOf(source), emptyMap())
            .test()
            .expectErrorMessage("Elasticsearch aggregation search failed on 1 shard(s).")
            .verify()

        close.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `should reject timed out responses and still close pit`() {
        val close = slot<ClosePointInTimeRequest>()
        stubPointInTime(close)
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            response("pit-2", emptyList(), timedOut = true),
        )

        ElasticsearchAggregationPager(client, "index", batchSize = 2)
            .search(plan, listOf(source), emptyMap())
            .test()
            .expectErrorMessage("Elasticsearch aggregation search timed out.")
            .verify()

        close.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `should reject missing and malformed row aggregations`() {
        val cases = listOf(
            response("pit-2", emptyList(), includeRows = false) to
                "Elasticsearch aggregation response is missing [$ROWS].",
            response(
                "pit-2",
                emptyList(),
                rowsOverride = Aggregate(SumAggregate.of { it.value(1.0) }),
            ) to "Elasticsearch aggregation response [$ROWS] must be composite.",
            response(
                "pit-2",
                emptyList(),
                rowsOverride = Aggregate(
                    CompositeAggregate.of { composite ->
                        composite.buckets { buckets -> buckets.keyed(emptyMap()) }
                    },
                ),
            ) to "Elasticsearch composite aggregation must return array buckets.",
        )

        cases.forEach { (response, message) ->
            val caseClient = mockk<ReactiveElasticsearchClient>()
            val close = slot<ClosePointInTimeRequest>()
            every { caseClient.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(
                OpenPointInTimeResponse.of {
                    it.id("pit-1").shards { shards -> shards.failed(0).successful(1).total(1) }
                },
            )
            every { caseClient.closePointInTime(capture(close)) } returns Mono.just(
                ClosePointInTimeResponse.of { it.succeeded(true).numFreed(1) },
            )
            every { caseClient.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(response)

            ElasticsearchAggregationPager(caseClient, "index", batchSize = 2)
                .search(plan, listOf(source), emptyMap())
                .test()
                .expectErrorMessage(message)
                .verify()
            close.captured.id().assert().isEqualTo("pit-2")
        }
    }

    private fun stubPointInTime(close: io.mockk.CapturingSlot<ClosePointInTimeRequest>) {
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(
            OpenPointInTimeResponse.of {
                it.id("pit-1").shards { shards -> shards.failed(0).successful(1).total(1) }
            },
        )
        every { client.closePointInTime(capture(close)) } returns Mono.just(
            ClosePointInTimeResponse.of { it.succeeded(true).numFreed(1) },
        )
    }

    private fun bucket(key: Long): CompositeBucket = CompositeBucket.of {
        it.key("group", key).docCount(1)
    }

    private fun response(
        pitId: String,
        buckets: List<CompositeBucket>,
        afterKey: FieldValue? = null,
        failedShards: Int = 0,
        timedOut: Boolean = false,
        rowsOverride: Aggregate? = null,
        includeRows: Boolean = true,
    ): SearchResponse<Map<*, *>> = SearchResponse.of {
        it.took(1)
            .timedOut(timedOut)
            .shards { shards -> shards.failed(failedShards).successful(1).total(1 + failedShards) }
            .hits { hits -> hits.hits(emptyList()) }
            .also { response ->
                if (includeRows) {
                    response.aggregations(
                        ROWS,
                        rowsOverride ?: Aggregate(
                            CompositeAggregate.of { composite ->
                                composite.buckets { container -> container.array(buckets) }
                                if (afterKey != null) {
                                    composite.afterKey("group", afterKey)
                                }
                                composite
                            },
                        ),
                    )
                }
            }.pitId(pitId)
    }

    private companion object {
        const val ROWS = "__wow_rows"
    }
}
