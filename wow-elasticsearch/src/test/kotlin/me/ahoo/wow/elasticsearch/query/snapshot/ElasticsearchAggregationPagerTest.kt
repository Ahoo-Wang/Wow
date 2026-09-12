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
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch._types.aggregations.DoubleTermsBucket
import co.elastic.clients.elasticsearch._types.aggregations.FiltersBucket
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.json.JsonData
import co.elastic.clients.util.NamedValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterCompiler
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.aggregation.DenseBucketPlan
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationMetric
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPager
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPlan
import me.ahoo.wow.elasticsearch.query.aggregation.SUMMARY_BUCKET_AGGREGATION
import me.ahoo.wow.elasticsearch.query.aggregation.SUMMARY_BUCKET_KEY
import me.ahoo.wow.elasticsearch.query.aggregation.selectTopRows
import me.ahoo.wow.elasticsearch.query.toObjectNode
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.aggregation.DenseDateGrid
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val DAY_MILLIS = 86_400_000L

private val AGGREGATION_SCHEMA = me.ahoo.wow.elasticsearch.query.aggregationTestSchema()

private fun compileAggregation(query: AggregationQuery) =
    ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(query, AGGREGATION_SCHEMA)

private fun List<SearchRequest>.assertGroupedPointInTimeRequests() {
    forEach { request ->
        request.index().assert().isEmpty()
        request.allowPartialSearchResults().assert().isEqualTo(false)
        request.pit().assert().isNotNull()
    }
    first().pit()!!.id().assert().isEqualTo("pit-1")
}

private fun SearchRequest.assertSummaryRequest(plan: me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPlan) {
    index().assert().containsExactly("test-index")
    pit().assert().isNull()
    allowPartialSearchResults().assert().isEqualTo(false)
    size().assert().isEqualTo(0)
    trackTotalHits()!!.enabled().assert().isFalse()
    query().assert().isEqualTo(plan.rootQuery)
}

private fun ReactiveElasticsearchClient.verifyNoPointInTimeCalls() {
    verify(exactly = 0) { openPointInTime(any<OpenPointInTimeRequest>()) }
    verify(exactly = 0) { closePointInTime(any<ClosePointInTimeRequest>()) }
}

@Suppress("LargeClass")
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
        val plan = compileAggregation(
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
        requests.assertGroupedPointInTimeRequests()
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
        val plan = compileAggregation(
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
        val plan = compileAggregation(
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
        val plan = compileAggregation(
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
            listOf(Sort(QueryField("total"), Sort.Direction.DESC), Sort(QueryField("product"), Sort.Direction.ASC)),
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
            listOf(Sort(QueryField("count"), Sort.Direction.DESC), Sort(QueryField("product"), Sort.Direction.ASC)),
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

        selectTopRows(rows, listOf(Sort(QueryField("active"), Sort.Direction.ASC)), limit = 3)
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
            selectTopRows(rows, listOf(Sort(QueryField("value"), Sort.Direction.ASC)), limit = 2)
        }.message.assert().contains("Aggregation sort values must have comparable types")
    }

    @Test
    fun `top rows should continue to tie breaker when primary values are both null`() {
        val rows = listOf(
            mapOf("id" to "a", "value" to null).toObjectNode(),
            mapOf("id" to "b", "value" to null).toObjectNode(),
        )

        selectTopRows(
            rows,
            listOf(Sort(QueryField("value"), Sort.Direction.ASC), Sort(QueryField("id"), Sort.Direction.DESC)),
            limit = 2,
        ).map { it.path("id").asString() }.assert().containsExactly("b", "a")
    }

    @Test
    fun `top rows should reject object and array sort values`() {
        val rows = listOf(
            mapOf("value" to mapOf("nested" to 1)).toObjectNode(),
            mapOf("value" to listOf(1)).toObjectNode(),
        )

        assertThrows<IllegalStateException> {
            selectTopRows(rows, listOf(Sort(QueryField("value"), Sort.Direction.ASC)), limit = 2)
        }.message.assert().contains("Aggregation sort values must have comparable types")
    }

    @Test
    fun `summary should request once and normalize empty values`() {
        stubPointInTime()
        val request = slot<SearchRequest>()
        every { client.search(capture(request), Map::class.java) } returns Mono.just(summaryResponse())
        val plan = compileAggregation(
            aggregation {
                count("count")
                sum("state.total", "total")
            },
        )

        val result = pager().execute(plan)
        verify(exactly = 0) { client.search(any<SearchRequest>(), Map::class.java) }
        client.verifyNoPointInTimeCalls()
        result.test()
            .assertNext {
                it.path("count").longValue().assert().isEqualTo(0L)
                it.has("total").assert().isTrue()
                it.path("total").isNull.assert().isTrue()
            }
            .verifyComplete()

        request.captured.assertSummaryRequest(plan)
        verify(exactly = 1) { client.search(any<SearchRequest>(), Map::class.java) }
        client.verifyNoPointInTimeCalls()
    }

    @Test
    fun `derived summaries wrap metrics in a keyed filters bucket`() {
        stubPointInTime()
        val request = slot<SearchRequest>()
        every { client.search(capture(request), Map::class.java) } returns Mono.just(derivedSummaryResponse())
        val plan = compileAggregation(
            aggregation {
                count("count")
                derived("one") { constant(1.0) }
                derived("next") { ref("count") + constant(1.0) }
            },
        )

        pager().execute(plan)
            .test()
            .assertNext {
                it.path("count").longValue().assert().isEqualTo(0L)
                it.path("one").doubleValue().assert().isEqualTo(1.0)
                it.path("next").doubleValue().assert().isEqualTo(1.0)
            }
            .verifyComplete()

        request.captured.aggregations().values.single().aggregations()
            .getValue(SUMMARY_BUCKET_AGGREGATION)
            .aggregations().apply {
                getValue("one").bucketScript().assert().isNotNull()
                getValue("next").bucketScript().assert().isNotNull()
            }
    }

    @Test
    fun `group aggregation should not emit an empty row`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", emptyList(), "next"),
        )
        val plan = compileAggregation(
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
        val plan = compileAggregation(
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
    fun `group aggregation should normalize null keys`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", listOf(bucket(FieldValue.NULL, 1))),
        )
        val plan = compileAggregation(
            aggregation {
                terms("state.value", "product")
                count("count")
            },
        )

        pager().execute(plan).test()
            .assertNext { row -> row.path("product").isNull.assert().isTrue() }
            .verifyComplete()
    }

    @Test
    fun `group aggregation should reject unsupported key values`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", listOf(bucket(FieldValue.of(JsonData.of("unsupported")), 1))),
        )
        val plan = compileAggregation(
            aggregation {
                terms("state.value", "product")
                count("count")
            },
        )

        pager().execute(plan).test()
            .expectErrorMessage("Unsupported Elasticsearch aggregation key [Any].")
            .verify()
    }

    @Test
    fun `any metric should request one terms bucket and read its scalar key`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { client.search(capture(requests), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", listOf(anyBucket("alpha", stringTerms("Alpha")))),
        )
        val plan = compileAggregation(
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
        val plan = compileAggregation(
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
    fun `filtered metrics should bound composite page size by filter wrappers`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { client.search(capture(requests), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", emptyList()),
        )
        val plan = compileAggregation(
            aggregation {
                terms("state.productId", "product")
                count("active") { "deleted" eq false }
                sum("state.amount", "total") { "deleted" eq false }
                sort { "total".desc() }
            },
        )

        pager(batchSize = 10).execute(plan).test().verifyComplete()

        // each filtered metric adds one filter-aggregation bucket per composite bucket
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
        val plan = compileAggregation(
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
        val plan = compileAggregation(
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
        val plan = compileAggregation(
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
    fun `group sort with having should keep paging past fully filtered pages`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        // first page: two buckets fully filtered by having; second page: one surviving bucket
        every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(groupResponse("pit-2", listOf(metricBucket("a", 1.0), metricBucket("b", 1.0)), "b")),
            Mono.just(groupResponse("pit-3", listOf(metricBucket("c", 9.0)))),
        )
        val plan = compileAggregation(
            aggregation {
                terms("state.product", "product")
                sum("state.total", "total")
                having { "total" gte 5.0 }
                sort { "product".asc() } // group sort path (not metric sort)
                limit(1)
            },
        )

        pager(batchSize = 2).execute(plan)
            .map { it.path("product").asString() }
            .test()
            .assertNext { it.assert().isEqualTo("c") }
            .verifyComplete()

        // a fully-filtered first page is not bucket exhaustion; only an empty after key stops paging
        requests.assert().hasSize(2)
    }

    @Test
    fun `group sort with having should request uncapped page sizes`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(groupResponse("pit-2", listOf(metricBucket("a", 9.0)), "a")),
            Mono.just(groupResponse("pit-3", emptyList())),
        )
        val plan = compileAggregation(
            aggregation {
                terms("state.product", "product")
                sum("state.total", "total")
                having { "total" gte 5.0 }
                sort { "product".asc() }
                limit(1)
            },
        )

        pager(batchSize = 10).execute(plan)
            .map { it.path("product").asString() }
            .test()
            .expectNext("a")
            .verifyComplete()

        // having filters client-side, so over-fetching is allowed: composite size stays at page capacity
        // (10 / bucketWidth 1) instead of being capped to limit - fetched (1 - 0)
        requests.single().aggregations().values.single().composite().size().assert().isEqualTo(10)
    }

    @Test
    fun `group sort with having should truncate survivors at limit`() {
        stubPointInTime()
        // single terminal page (no after key): two having survivors but limit(1)
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", listOf(metricBucket("a", 9.0), metricBucket("b", 9.0))),
        )
        val plan = compileAggregation(
            aggregation {
                terms("state.product", "product")
                sum("state.total", "total")
                having { "total" gte 5.0 }
                sort { "product".asc() } // group sort path (not metric sort)
                limit(1)
            },
        )

        pager(batchSize = 10).execute(plan)
            .map { it.path("product").asString() }
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `metric sort with having should filter rows before top N`() {
        val requests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(groupResponse("pit-2", listOf(metricBucket("a", 4.0), metricBucket("b", 6.0), metricBucket("c", 5.0)), "c")),
            Mono.just(groupResponse("pit-3", emptyList())),
        )
        val plan = compileAggregation(
            aggregation {
                terms("state.product", "product")
                sum("state.total", "total")
                having { "total" gte 5.0 }
                sort { "total".desc() } // metric sort path
                limit(2)
            },
        )

        pager(batchSize = 3).execute(plan)
            .map { it.path("product").asString() }
            .test()
            // a (4.0) fails having and never enters the bounded top N; without filtering the
            // top-2 would be [b, a] — only survivors [b (6.0), c (5.0)] compete
            .expectNext("b", "c")
            .verifyComplete()

        requests.assert().hasSize(2)
    }

    @Test
    fun `non finite metric should fail the aggregation`() {
        stubPointInTime()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            groupResponse("pit-2", listOf(metricBucket("a", Double.POSITIVE_INFINITY))),
        )
        val plan = compileAggregation(
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
    fun `dense date histogram should fill gaps within one composite page`() {
        stubPointInTime()
        val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val day4 = Instant.parse("2026-01-04T00:00:00Z").toEpochMilli()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            denseGroupResponse("pit-2", listOf(dayBucket(day1, 2), dayBucket(day4, 5))),
        )

        pager().execute(densePlan())
            .map { it.path("day").longValue() to it.path("count").longValue() }
            .collectList()
            .test()
            .assertNext { rows ->
                // composite emits only actual buckets; both interior days are client-side fills
                rows.assert().containsExactly(
                    day1 to 2L,
                    (day1 + DAY_MILLIS) to 0L,
                    (day4 - DAY_MILLIS) to 0L,
                    day4 to 5L
                )
            }
            .verifyComplete()
    }

    @Test
    fun `dense date histogram should bridge the gap across composite pages`() {
        stubPointInTime()
        val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val day2 = day1 + DAY_MILLIS
        val day4 = day1 + 3 * DAY_MILLIS
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(denseGroupResponse("pit-2", listOf(dayBucket(day1, 1), dayBucket(day2, 2)), afterDay = day2)),
            Mono.just(denseGroupResponse("pit-3", listOf(dayBucket(day4, 4)))),
        )

        pager(batchSize = 2).execute(densePlan())
            .map { it.path("day").longValue() }
            .collectList()
            .test()
            .assertNext { days ->
                // day3 is filled between the previous page's last bucket and this page's first bucket
                days.assert().containsExactly(day1, day2, day2 + DAY_MILLIS, day4)
            }
            .verifyComplete()
    }

    @Test
    fun `dense date histogram should count filled rows toward the limit`() {
        stubPointInTime()
        val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val day4 = Instant.parse("2026-01-04T00:00:00Z").toEpochMilli()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            denseGroupResponse("pit-2", listOf(dayBucket(day1, 2), dayBucket(day4, 5))),
        )

        pager().execute(densePlan(limit = 3))
            .map { it.path("day").longValue() }
            .collectList()
            .test()
            .assertNext { days -> days.assert().containsExactly(day1, day1 + DAY_MILLIS, day4 - DAY_MILLIS) }
            .verifyComplete()
    }

    @Test
    fun `dense date histogram should fill gaps in descending stream direction`() {
        stubPointInTime()
        val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val day4 = Instant.parse("2026-01-04T00:00:00Z").toEpochMilli()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            denseGroupResponse("pit-2", listOf(dayBucket(day4, 5), dayBucket(day1, 2))),
        )

        pager().execute(densePlan(sortDesc = true))
            .map { it.path("day").longValue() }
            .collectList()
            .test()
            .assertNext { days ->
                days.assert().containsExactly(day4, day4 - DAY_MILLIS, day1 + DAY_MILLIS, day1)
            }
            .verifyComplete()
    }

    @Test
    fun `dense metric sort should rank filled gaps against real buckets in the bounded top N`() {
        stubPointInTime()
        val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val day2 = day1 + DAY_MILLIS
        val day3 = day1 + 2 * DAY_MILLIS
        val day4 = day1 + 3 * DAY_MILLIS
        val day5 = day1 + 4 * DAY_MILLIS
        val day6 = day1 + 5 * DAY_MILLIS
        val day7 = day1 + 6 * DAY_MILLIS
        val day8 = day1 + 7 * DAY_MILLIS
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(denseGroupResponse("pit-2", listOf(dayBucket(day1, 5), dayBucket(day3, 2)), afterDay = day3)),
            Mono.just(denseGroupResponse("pit-3", listOf(dayBucket(day6, 4), dayBucket(day8, 7)), afterDay = day8)),
            Mono.just(denseGroupResponse("pit-4", emptyList())),
        )

        pager(batchSize = 2).execute(denseMetricPlan(limit = 10))
            .map { it.path("day").longValue() to it.path("count").longValue() }
            .collectList()
            .test()
            .assertNext { rows ->
                // fills (count=0) compete in the bounded top N exactly like real buckets: they rank
                // below every real row under count DESC and order among themselves by the day ASC
                // tiebreak — day2 fills within page 1, day4/day5 bridge the pages, day7 fills page 2
                rows.assert().containsExactly(
                    day8 to 7L,
                    day1 to 5L,
                    day6 to 4L,
                    day3 to 2L,
                    day2 to 0L,
                    day4 to 0L,
                    day5 to 0L,
                    day7 to 0L,
                )
            }
            .verifyComplete()
    }

    @Test
    fun `dense metric sort should keep a filled gap row within a small limit`() {
        stubPointInTime()
        val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val day2 = day1 + DAY_MILLIS
        val day3 = day1 + 2 * DAY_MILLIS
        val day6 = day1 + 5 * DAY_MILLIS
        val day8 = day1 + 7 * DAY_MILLIS
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(denseGroupResponse("pit-2", listOf(dayBucket(day1, 5), dayBucket(day3, 2)), afterDay = day3)),
            Mono.just(denseGroupResponse("pit-3", listOf(dayBucket(day6, 4), dayBucket(day8, 7)), afterDay = day8)),
            Mono.just(denseGroupResponse("pit-4", emptyList())),
        )

        pager(batchSize = 2).execute(denseMetricPlan(limit = 5))
            .map { it.path("day").longValue() to it.path("count").longValue() }
            .collectList()
            .test()
            .assertNext { rows ->
                // only 4 real buckets exist, yet the limit-5 result keeps 5 rows: the last slot
                // stays occupied by the best fill (count=0, day ASC tiebreak) instead of truncating
                // to the raw bucket count
                rows.assert().containsExactly(
                    day8 to 7L,
                    day1 to 5L,
                    day6 to 4L,
                    day3 to 2L,
                    day2 to 0L,
                )
            }
            .verifyComplete()
    }

    @Test
    fun `dense date histogram should bridge gaps across pages in descending order`() {
        stubPointInTime()
        val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val day2 = day1 + DAY_MILLIS
        val day3 = day1 + 2 * DAY_MILLIS
        val day4 = day1 + 3 * DAY_MILLIS
        val day5 = day1 + 4 * DAY_MILLIS
        val day6 = day1 + 5 * DAY_MILLIS
        val day7 = day1 + 6 * DAY_MILLIS
        val day8 = day1 + 7 * DAY_MILLIS
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(denseGroupResponse("pit-2", listOf(dayBucket(day8, 7), dayBucket(day6, 4)), afterDay = day6)),
            Mono.just(denseGroupResponse("pit-3", listOf(dayBucket(day3, 2), dayBucket(day1, 1)))),
        )

        pager(batchSize = 2).execute(densePlan(sortDesc = true))
            .map { it.path("day").longValue() }
            .collectList()
            .test()
            .assertNext { days ->
                // day7 fills within page 1, day5/day4 bridge the page boundary, day2 fills page 2:
                // the merged stream stays strictly descending and every gap key appears exactly once
                days.assert().containsExactly(day8, day7, day6, day5, day4, day3, day2, day1)
                days.zipWithNext().all { (left, right) -> left > right }.assert().isTrue()
            }
            .verifyComplete()
    }

    @Test
    fun `snapshot service with custom compiler should fail aggregation before Elasticsearch access`() {
        val compiler = mockk<AbstractElasticsearchFilterCompiler> {
            every { compile(any(), any()) } returns
                co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll { it }
        }
        val service = ElasticsearchSnapshotQueryBackend(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            elasticsearchClient = client,
            filterCompiler = compiler,
        )

        val gateway = DefaultSnapshotQueryGateway<Any>(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            binding = QueryBackendBinding(
                service,
                object : QueryModelSchemaProvider {
                    override fun schema(): Mono<QueryModelSchema> = unavailable()

                    override fun refresh(): Mono<QueryModelSchema> = unavailable()

                    private fun unavailable(): Mono<QueryModelSchema> = Mono.error(
                        QuerySchemaUnavailableException(
                            "Elasticsearch query schema is unavailable for custom filter compilers.",
                        ),
                    )
                },
            ),

            targetType = JsonSerializer.typeFactory.constructParametricType(
                MaterializedSnapshot::class.java,
                Any::class.java,
            ),
        )

        gateway.aggregate(
            aggregation {
                count("count")
                sum("physical.total", "total")
            },
        ).test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
                error.message.assert().isEqualTo(
                    "Elasticsearch query schema is unavailable for custom filter compilers.",
                )
            }
            .verify()

        verify(exactly = 0) { client.search(any<SearchRequest>(), Map::class.java) }
        verify(exactly = 0) { client.indices() }
    }

    private fun pager(batchSize: Int? = null) = if (batchSize == null) {
        ElasticsearchAggregationPager(client, "test-index")
    } else {
        ElasticsearchAggregationPager(client, "test-index", batchSize)
    }

    private fun densePlan(
        limit: Int = 100,
        sortDesc: Boolean = false,
    ): ElasticsearchAggregationPlan {
        val direction = if (sortDesc) Sort.Direction.DESC else Sort.Direction.ASC
        return ElasticsearchAggregationPlan(
            rootQuery = co.elastic.clients.elasticsearch._types.query_dsl.Query.of {
                it.matchAll { matchAll -> matchAll }
            },
            elements = emptyList(),
            groupSources = listOf(
                NamedValue.of(
                    "day",
                    CompositeAggregationSource.of {
                        it.dateHistogram { dateHistogram ->
                            dateHistogram.field("createdAt").calendarInterval { interval -> interval.time("day") }
                        }
                    },
                )
            ),
            metrics = listOf(ElasticsearchAggregationMetric.Count("count")),
            runtimeMappings = emptyMap(),
            effectiveSort = listOf(Sort(QueryField("day"), direction)),
            limit = limit,
            metricSorted = false,
            having = null,
            dense = DenseBucketPlan(
                alias = "day",
                grid = DenseDateGrid(AggregationDateUnit.DAY, ZoneId.of("UTC")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
    }

    /**
     * Dense plan with a metric-first effective sort: "count" is a metric alias, so the compiler
     * marks the plan metricSorted and the pager routes it through the bounded top-N accumulation.
     */
    private fun denseMetricPlan(limit: Int): ElasticsearchAggregationPlan = densePlan(limit).copy(
        effectiveSort = listOf(
            Sort(QueryField("count"), Sort.Direction.DESC),
            Sort(QueryField("day"), Sort.Direction.ASC),
        ),
        metricSorted = true,
    )

    private fun dayBucket(day: Long, count: Long): CompositeBucket = CompositeBucket.of {
        it.key("day", FieldValue.of(day)).docCount(count)
    }

    private fun denseGroupResponse(
        pitId: String,
        buckets: List<CompositeBucket>,
        afterDay: Long? = null,
    ): SearchResponse<Map<*, *>> = response(pitId) { aggregate ->
        aggregate.composite { composite ->
            composite.buckets(Buckets.of<CompositeBucket> { it.array(buckets) }).apply {
                if (afterDay != null) afterKey("day", FieldValue.of(afterDay))
            }
        }
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

    private fun derivedSummaryResponse(): SearchResponse<Map<*, *>> = response("pit-2") { aggregate ->
        aggregate.filter { filter ->
            filter.docCount(0).aggregations(
                SUMMARY_BUCKET_AGGREGATION,
                Aggregate.of { bucket ->
                    bucket.filters { filters ->
                        filters.buckets { buckets ->
                            buckets.keyed(
                                mapOf(
                                    SUMMARY_BUCKET_KEY to FiltersBucket.of { keyed ->
                                        keyed.docCount(0)
                                            .aggregations(
                                                "one",
                                                Aggregate.of { value -> value.simpleValue { it.value(1.0) } },
                                            )
                                            .aggregations(
                                                "next",
                                                Aggregate.of { value -> value.simpleValue { it.value(1.0) } },
                                            )
                                    },
                                ),
                            )
                        }
                    }
                },
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
