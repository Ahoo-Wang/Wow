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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.Buckets
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.SimpleDynamicDocument
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration

class ElasticsearchAggregationPagerTest {
    private val client = mockk<ReactiveElasticsearchClient>()

    @Test
    fun `group sort should pass composite after key and close latest pit`() {
        val requests = mutableListOf<SearchRequest>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        stubPointInTime(closeRequest)
        every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(groupResponse("pit-2", listOf(bucket("a", 2), bucket("b", 3)), "b")),
            Mono.just(groupResponse("pit-3", listOf(bucket("c", 1)))),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.product", "product")
                count("count")
                limit(3)
            },
        )

        pager(batchSize = 2).execute(plan)
            .map { it.getValue<String>("product") }
            .test()
            .expectNext("a", "b", "c")
            .verifyComplete()

        requests.assert().hasSize(2)
        requests[0].size().assert().isEqualTo(0)
        requests[0].aggregations().values.single().composite().after().assert().isEmpty()
        requests[1].aggregations().values.single().composite().after().getValue("product").stringValue()
            .assert().isEqualTo("b")
        requests[1].pit()!!.id().assert().isEqualTo("pit-2")
        closeRequest.captured.id().assert().isEqualTo("pit-3")
    }

    @Test
    fun `metric sort should stream every bucket and retain exact bounded top N`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(groupResponse("pit-2", listOf(metricBucket("a", 3.0), metricBucket("b", 9.0)), "b")),
            Mono.just(groupResponse("pit-3", listOf(metricBucket("c", 7.0)))),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.product", "product")
                sum("state.total", "total")
                sort { "total".desc() }
                limit(2)
            },
        )

        pager(batchSize = 2).execute(plan)
            .map { it.getValue<Double>("total") }
            .test()
            .expectNext(9.0, 7.0)
            .verifyComplete()

        requests.assert().hasSize(2)
    }

    @Test
    fun `metric sort should retain exact bounded top N with complete tie sort`() {
        val rows = listOf(
            SimpleDynamicDocument(mutableMapOf("product" to "c", "total" to 7.0)),
            SimpleDynamicDocument(mutableMapOf("product" to "a", "total" to 7.0)),
            SimpleDynamicDocument(mutableMapOf("product" to "b", "total" to 7.0)),
            SimpleDynamicDocument(mutableMapOf("product" to "d", "total" to 3.0)),
        )

        selectTopRows(
            rows,
            listOf(Sort("total", Sort.Direction.DESC), Sort("product", Sort.Direction.ASC)),
            limit = 2,
        ).map { it["product"] }.assert().containsExactly("a", "b")
    }

    @Test
    fun `summary should request once and normalize empty values`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(summaryResponse())
        val plan = compiler().compile(
            aggregation {
                count("count")
                sum("state.total", "total")
            },
        )

        pager().execute(plan).test()
            .assertNext {
                it["count"].assert().isEqualTo(0L)
                it.containsKey("total").assert().isTrue()
                it["total"].assert().isNull()
            }
            .verifyComplete()

        verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
    }

    @Test
    fun `group aggregation should not emit an empty row`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(groupResponse("pit-2", emptyList()))
        val plan = compiler().compile(
            aggregation {
                terms("state.product", "product")
                count("count")
            },
        )

        pager().execute(plan).test().verifyComplete()
    }

    @Test
    fun `non finite metric should fail the aggregation`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", listOf(metricBucket("a", Double.POSITIVE_INFINITY))),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.product", "product")
                sum("state.total", "total")
            },
        )

        pager().execute(plan).test()
            .expectErrorMessage("Aggregation metric [total] must be finite.")
            .verify()
    }

    @Test
    fun `snapshot service with custom converter should aggregate without loading mapping`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(summaryResponse())
        val converter = mockk<AbstractElasticsearchFilterConverter> {
            every { convert(any(), any()) } returns
                co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll { it }
        }
        val service = ElasticsearchSnapshotQueryService<Any>(MOCK_AGGREGATE_METADATA, client, converter)

        service.aggregate(
            aggregation {
                count("count")
                sum("physical.total", "total")
            },
        ).test()
            .expectNextCount(1)
            .verifyComplete()

        verify(exactly = 0) { client.indices() }
    }

    private fun compiler() = ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping = null)

    private fun pager(batchSize: Int = 100) = ElasticsearchAggregationPager(
        client,
        "test-index",
        batchSize,
        Duration.ofMinutes(1),
    )

    private fun stubPointInTime(closeRequest: io.mockk.CapturingSlot<ClosePointInTimeRequest>? = null) {
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(
            OpenPointInTimeResponse.of {
                it.id("pit-1").shards { shards -> shards.failed(0).successful(1).total(1) }
            },
        )
        val close = ClosePointInTimeResponse.of { it.succeeded(true).numFreed(1) }
        if (closeRequest == null) {
            every { client.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.just(close)
        } else {
            every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(close)
        }
    }

    private fun bucket(product: String, count: Long): CompositeBucket = CompositeBucket.of {
        it.key("product", product).docCount(count)
    }

    private fun metricBucket(product: String, total: Double): CompositeBucket = CompositeBucket.of {
        it.key("product", product)
            .docCount(1)
            .aggregations("total", Aggregate.of { value -> value.sum { sum -> sum.value(total) } })
            .aggregations(
                "__wow_value_count_total",
                Aggregate.of { value -> value.valueCount { count -> count.value(1.0) } },
            )
    }

    private fun groupResponse(
        pitId: String,
        buckets: List<CompositeBucket>,
        afterProduct: String? = null,
    ): SearchResponse<Map<*, *>> = response(pitId) { aggregate ->
        aggregate.composite { composite ->
            composite.buckets(Buckets.of<CompositeBucket> { it.array(buckets) }).apply {
                if (afterProduct != null) afterKey("product", FieldValue.of(afterProduct))
            }
        }
    }

    private fun summaryResponse(): SearchResponse<Map<*, *>> = response("pit-2") { aggregate ->
        aggregate.filter { filter ->
            filter.docCount(0)
                .aggregations("total", Aggregate.of { value -> value.sum { sum -> sum.value(0.0) } })
                .aggregations(
                    "__wow_value_count_total",
                    Aggregate.of { value -> value.valueCount { count -> count.value(0.0) } },
                )
        }
    }

    private fun response(
        pitId: String,
        aggregate: (Aggregate.Builder) -> co.elastic.clients.util.ObjectBuilder<Aggregate>,
    ): SearchResponse<Map<*, *>> = SearchResponse.of<Map<*, *>> {
        it.took(1)
            .timedOut(false)
            .pitId(pitId)
            .shards { shards -> shards.failed(0).successful(1).total(1) }
            .hits { hits -> hits.hits(emptyList()) }
            .aggregations("__wow_aggregation", Aggregate.of(aggregate))
    }
}
