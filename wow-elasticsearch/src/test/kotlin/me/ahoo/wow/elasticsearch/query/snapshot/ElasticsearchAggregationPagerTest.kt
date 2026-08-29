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
import co.elastic.clients.elasticsearch._types.aggregations.DoubleTermsBucket
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket
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
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPager
import me.ahoo.wow.elasticsearch.query.aggregation.selectTopRows
import me.ahoo.wow.elasticsearch.query.toObjectNode
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration

class ElasticsearchAggregationPagerTest {
    private val client = mockk<ReactiveElasticsearchClient>()

    @Test
    fun `should reject invalid paging options`() {
        assertThrows<IllegalArgumentException> {
            ElasticsearchAggregationPager(client, "test-index", batchSize = 0)
        }.message.assert().contains("batchSize must be between 1 and")
        assertThrows<IllegalArgumentException> {
            ElasticsearchAggregationPager(client, "test-index", batchSize = DEFAULT_SEARCH_BATCH_SIZE + 1)
        }.message.assert().contains("batchSize must be between 1 and")
        assertThrows<IllegalArgumentException> {
            ElasticsearchAggregationPager(client, "test-index", batchSize = 1, keepAlive = Duration.ZERO)
        }.message.assert().isEqualTo("keepAlive must be greater than or equal to 1ms.")
    }

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
            .map { it.path("product").asString() }
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
                sum(field("state.total") * constant(2.0), "total")
                sort { "total".desc() }
                limit(2)
            },
        )

        pager(batchSize = 2).execute(plan)
            .map { it.path("total").doubleValue() }
            .test()
            .expectNext(9.0, 7.0)
            .verifyComplete()

        requests.assert().hasSize(2)
        requests.forEach { request ->
            request.runtimeMappings().assert().isEqualTo(plan.runtimeMappings)
        }
    }

    @Test
    fun `numeric metrics should request every supported aggregation`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { client.search(capture(requests), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", emptyList()),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.product", "product")
                sum("state.amount", "total")
                avg("state.amount", "average")
                min("state.amount", "minimum")
                max("state.amount", "maximum")
            },
        )

        pager().execute(plan).test().verifyComplete()

        requests.single().aggregations().values.single().aggregations().apply {
            getValue("total").sum().field().assert().isEqualTo("state.amount")
            getValue("average").avg().field().assert().isEqualTo("state.amount")
            getValue("minimum").min().field().assert().isEqualTo("state.amount")
            getValue("maximum").max().field().assert().isEqualTo("state.amount")
        }
    }

    @Test
    fun `metric ties should preserve native composite group order`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse(
                "pit-2",
                listOf(metricBucket("2.0.0.1", 7.0), metricBucket("10.0.0.1", 7.0)),
            ),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.address", "product")
                sum("state.total", "total")
                sort { "total".desc() }
                limit(1)
            },
        )

        pager().execute(plan)
            .map { it.path("product").asString() }
            .test()
            .expectNext("2.0.0.1")
            .verifyComplete()
    }

    @Test
    fun `metric sort should retain exact bounded top N with complete tie sort`() {
        val rows = listOf(
            mapOf("product" to "c", "total" to 7.0).toObjectNode(),
            mapOf("product" to "a", "total" to 7.0).toObjectNode(),
            mapOf("product" to "b", "total" to 7.0).toObjectNode(),
            mapOf("product" to "d", "total" to 3.0).toObjectNode(),
        )

        selectTopRows(
            rows,
            listOf(Sort("total", Sort.Direction.DESC), Sort("product", Sort.Direction.ASC)),
            limit = 2,
        ).map { it.path("product").asString() }.assert().containsExactly("a", "b")
    }

    @Test
    fun `long sort above double precision should not fall through to tie sort`() {
        val rows = listOf(
            mapOf("product" to "z", "count" to 9_007_199_254_740_993L).toObjectNode(),
            mapOf("product" to "a", "count" to 9_007_199_254_740_992L).toObjectNode(),
        )

        selectTopRows(
            rows,
            listOf(Sort("count", Sort.Direction.DESC), Sort("product", Sort.Direction.ASC)),
            limit = 1,
        ).single().path("product").asString().assert().isEqualTo("z")
    }

    @Test
    fun `top rows should sort boolean and null values`() {
        val rows = listOf(
            mapOf("active" to true).toObjectNode(),
            mapOf("active" to null).toObjectNode(),
            mapOf("active" to false).toObjectNode(),
        )

        selectTopRows(rows, listOf(Sort("active", Sort.Direction.ASC)), limit = 3)
            .map { if (it.path("active").isNull) null else it.path("active").booleanValue() }
            .assert().containsExactly(null, false, true)
    }

    @Test
    fun `top rows should reject incomparable values`() {
        val rows = listOf(
            mapOf("value" to 1).toObjectNode(),
            mapOf("value" to "1").toObjectNode(),
        )

        assertThrows<IllegalStateException> {
            selectTopRows(rows, listOf(Sort("value", Sort.Direction.ASC)), limit = 2)
        }.message.assert().contains("Aggregation sort values must have comparable types")
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
                it.path("count").longValue().assert().isEqualTo(0L)
                it.has("total").assert().isTrue()
                it.path("total").isNull.assert().isTrue()
            }
            .verifyComplete()

        verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
    }

    @Test
    fun `group aggregation should not emit an empty row`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", emptyList(), "next"),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.product", "product")
                count("count")
            },
        )

        pager().execute(plan).test().verifyComplete()
        verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
    }

    @Test
    fun `group aggregation should normalize boolean keys`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse(
                "pit-2",
                listOf(bucket(FieldValue.TRUE, 1)),
            ),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.active", "product")
                count("count")
            },
        )

        pager().execute(plan).collectList().test()
            .assertNext { rows -> rows.map { it.path("product").booleanValue() }.assert().containsExactly(true) }
            .verifyComplete()
    }

    @Test
    fun `any metric should request one terms bucket and read its scalar key`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { client.search(capture(requests), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", listOf(anyBucket("alpha", stringTerms("Alpha")))),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.productId", "product")
                any("state.productName", "productName")
            },
        )

        pager().execute(plan).test()
            .assertNext { row -> row.path("productName").asString().assert().isEqualTo("Alpha") }
            .verifyComplete()

        requests.single().aggregations().values.single()
            .aggregations().getValue("productName").terms().apply {
                field().assert().isEqualTo("state.productName")
                size().assert().isEqualTo(1)
            }
    }

    @Test
    fun `any metrics should bound metric-sorted composite page size`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { client.search(capture(requests), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", emptyList()),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.productId", "product")
                any("state.productName", "productName")
                any("state.category", "category")
                sort { "productName".asc() }
            },
        )

        pager(batchSize = 10).execute(plan).test().verifyComplete()

        requests.single().aggregations().values.single().composite().size().assert().isEqualTo(3)
    }

    @Test
    fun `any metric should normalize boolean long double and empty terms buckets`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse(
                "pit-2",
                listOf(
                    anyBucket("a", booleanTerms(true)),
                    anyBucket("b", booleanTerms(false)),
                    anyBucket("c", longTerms(7L)),
                    anyBucket("d", doubleTerms(7.5)),
                    anyBucket("e", stringTerms(null)),
                    anyBucket("f", longTerms(null)),
                    anyBucket("g", doubleTerms(null)),
                ),
            ),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.productId", "product")
                any("state.value", "productName")
            },
        )

        pager().execute(plan).collectList().test()
            .assertNext { rows ->
                rows.map { it.path("productName") }.map { node ->
                    when {
                        node.isBoolean -> node.booleanValue()
                        node.isIntegralNumber -> node.longValue()
                        node.isNumber -> node.doubleValue()
                        node.isNull -> null
                        else -> error("unexpected node: $node")
                    }
                }.assert().containsExactly(true, false, 7L, 7.5, null, null, null)
            }
            .verifyComplete()
    }

    @Test
    fun `any metric should normalize unmapped and reject unsupported aggregates`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(
                groupResponse(
                    "pit-2",
                    listOf(
                        anyBucket(
                            "a",
                            Aggregate.of {
                                it.umterms { unmapped ->
                                    unmapped.buckets { buckets -> buckets.array(emptyList()) }
                                }
                            },
                        ),
                    ),
                ),
            ),
            Mono.just(
                groupResponse(
                    "pit-3",
                    listOf(anyBucket("a", Aggregate.of { it.sum { sum -> sum.value(1.0) } })),
                ),
            ),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.productId", "product")
                any("state.value", "productName")
            },
        )

        pager().execute(plan).test()
            .assertNext { row -> row.path("productName").isNull.assert().isTrue() }
            .verifyComplete()
        pager().execute(plan).test()
            .expectErrorMessage(
                "Aggregation ANY metric [productName] returned unsupported Elasticsearch aggregate [Sum].",
            )
            .verify()
    }

    @Test
    fun `group aggregation should stop after reaching the limit`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", listOf(bucket("a", 1), bucket("b", 1)), "b"),
        )
        val plan = compiler().compile(
            aggregation {
                terms("state.product", "product")
                count("count")
                limit(2)
            },
        )

        pager(batchSize = 2).execute(plan).test()
            .expectNextCount(2)
            .verifyComplete()
        verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
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
        val service = ElasticsearchSnapshotQueryBackend(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            elasticsearchClient = client,
            filterConverter = converter,
        )

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

    private fun compiler() = ElasticsearchAggregationCompiler(SnapshotFilterConverter)

    private fun pager(batchSize: Int? = null) = if (batchSize == null) {
        ElasticsearchAggregationPager(client, "test-index")
    } else {
        ElasticsearchAggregationPager(client, "test-index", batchSize)
    }

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

    private fun bucket(product: FieldValue, count: Long): CompositeBucket = CompositeBucket.of {
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

    private fun stringTerms(value: String?): Aggregate = Aggregate.of { aggregate ->
        aggregate.sterms { terms ->
            terms.buckets(
                Buckets.of<StringTermsBucket> { buckets ->
                    buckets.array(
                        value?.let {
                            listOf(StringTermsBucket.of { bucket -> bucket.key(it).docCount(1) })
                        }.orEmpty(),
                    )
                },
            )
        }
    }

    private fun booleanTerms(value: Boolean): Aggregate = Aggregate.of { aggregate ->
        aggregate.lterms { terms ->
            terms.buckets(
                Buckets.of<LongTermsBucket> { buckets ->
                    buckets.array(
                        listOf(
                            LongTermsBucket.of {
                                it.key(if (value) 1L else 0L)
                                    .keyAsString(value.toString())
                                    .docCount(1)
                            },
                        ),
                    )
                },
            )
        }
    }

    private fun longTerms(value: Long?): Aggregate = Aggregate.of { aggregate ->
        aggregate.lterms { terms ->
            terms.buckets(
                Buckets.of<LongTermsBucket> { buckets ->
                    buckets.array(
                        value?.let { listOf(LongTermsBucket.of { bucket -> bucket.key(it).docCount(1) }) }.orEmpty(),
                    )
                },
            )
        }
    }

    private fun doubleTerms(value: Double?): Aggregate = Aggregate.of { aggregate ->
        aggregate.dterms { terms ->
            terms.buckets(
                Buckets.of<DoubleTermsBucket> { buckets ->
                    buckets.array(
                        value?.let { listOf(DoubleTermsBucket.of { bucket -> bucket.key(it).docCount(1) }) }.orEmpty(),
                    )
                },
            )
        }
    }

    private fun anyBucket(product: String, productName: Aggregate): CompositeBucket = CompositeBucket.of {
        it.key("product", product)
            .docCount(1)
            .aggregations("productName", productName)
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
