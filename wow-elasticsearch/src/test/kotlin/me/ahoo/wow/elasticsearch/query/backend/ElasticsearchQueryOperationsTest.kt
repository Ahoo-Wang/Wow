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

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPageSpec
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.PortableQueryResult
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class ElasticsearchQueryOperationsTest {
    private val typedShape = QueryPlanResultShape.Typed(
        PortableQueryResult::class.java,
        setOf(PortableQueryDataset.LOGICAL_ID),
    )

    @Test
    fun `single uses size one and list uses one bounded search`() {
        val transport = RecordingElasticsearchTransport(
            ElasticsearchSearchResult(page(hit("d01")), null),
            ElasticsearchSearchResult(page(hit("d01"), hit("d02")), null),
        )
        val backend = backend(transport)

        StepVerifier.create(backend.single<PortableQueryResult>(singlePlan()))
            .expectNext(PortableQueryResult("d01"))
            .verifyComplete()
        StepVerifier.create(backend.list<PortableQueryResult>(listPlan(limit = 2)))
            .expectNext(PortableQueryResult("d01"), PortableQueryResult("d02"))
            .verifyComplete()

        transport.searchRequests[0].size().assert().isOne()
        transport.searchRequests[1].size().assert().isEqualTo(2)
        transport.openCount.get().assert().isZero()
    }

    @Test
    fun `large list uses PIT without index on searches and closes exactly once`() {
        val transport = RecordingElasticsearchTransport(
            ElasticsearchSearchResult(page(hit("d01", 1)), null),
        )

        StepVerifier.create(backend(transport).list<PortableQueryResult>(listPlan(limit = 257)))
            .expectNext(PortableQueryResult("d01"))
            .verifyComplete()

        transport.openCount.get().assert().isOne()
        transport.closeCount.get().assert().isOne()
        transport.searchRequests.single().index().assert().isEmpty()
        transport.searchRequests.single().pit()!!.id().assert().isEqualTo("pit-id")
    }

    @Test
    fun `page uses one exact-total search and count uses count API`() {
        val transport = RecordingElasticsearchTransport(
            ElasticsearchSearchResult(page(hit("d02")), 9),
        ).also { it.countResult = 9 }
        val backend = backend(transport)

        StepVerifier.create(backend.page<PortableQueryResult>(pagePlan()))
            .assertNext { page ->
                page.items.assert().isEqualTo(listOf(PortableQueryResult("d02")))
                page.total.assert().isEqualTo(9)
            }
            .verifyComplete()
        StepVerifier.create(backend.count(countPlan())).expectNext(9).verifyComplete()

        transport.searchRequests.single().from().assert().isEqualTo(2)
        transport.searchRequests.single().size().assert().isEqualTo(2)
        transport.searchRequests.single().trackTotalHits()!!.enabled().assert().isTrue()
        transport.countRequests.size.assert().isOne()
    }

    @Test
    fun `page rejects a lower-bound total instead of presenting it as exact`() {
        val transport = RecordingElasticsearchTransport(
            ElasticsearchSearchResult(page(hit("d01")), total = 10_000, totalIsExact = false),
        )

        StepVerifier.create(backend(transport).page<PortableQueryResult>(pagePlan()))
            .expectErrorMatches { error ->
                error is me.ahoo.wow.api.query.error.QueryException &&
                    error.code == me.ahoo.wow.api.query.error.QueryErrorCode.INCOMPLETE_RESULT
            }
            .verify()
    }

    private fun backend(transport: ElasticsearchQueryTransport): ElasticsearchQueryBackend = ElasticsearchQueryBackend(
        client = mockk<ReactiveElasticsearchClient>(relaxed = true),
        index = "portable-query-document",
        binding = ElasticsearchQueryFieldBinding.bind(PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)),
        nativeTemplates = ElasticsearchNativeQueryTemplateRegistry(),
        descriptor = elasticsearchQueryBackendDescriptor(QueryBudgetLimit.UNBOUNDED),
        readinessRequirements = ElasticsearchQueryReadinessRequirements(
            false,
            emptySet(),
            emptySet(),
            emptySet(),
            emptySet(),
            0,
        ),
        transport = transport,
    )

    private fun singlePlan(): SingleQueryPlanV1<PortableQueryResult> = mockk {
        every { securedExpression } returns MatchAll
        every { sort } returns emptyList()
        every { authorizedResultShape } returns typedShape
    }

    private fun listPlan(limit: Int): ListQueryPlanV1<PortableQueryResult> = mockk {
        every { securedExpression } returns MatchAll
        every { sort } returns emptyList()
        every { authorizedResultShape } returns typedShape
        every { effectiveBudget } returns QueryBudgetLimit.UNBOUNDED
        every { this@mockk.limit } returns limit
    }

    private fun pagePlan(): PageQueryPlanV1<PortableQueryResult> = mockk {
        every { securedExpression } returns MatchAll
        every { sort } returns emptyList()
        every { authorizedResultShape } returns typedShape
        every { page } returns QueryPageSpec(index = 2, size = 2)
    }

    private fun countPlan(): CountQueryPlanV1 = mockk {
        every { securedExpression } returns MatchAll
    }

    private fun page(vararg hits: PitSearchHit<Map<String, Any?>>): PitSearchPage<Map<String, Any?>> =
        PitSearchPage(hits.toList())

    private fun hit(id: String, sort: Long? = null): PitSearchHit<Map<String, Any?>> = PitSearchHit(
        mapOf("logicalId" to id),
        sort?.let { listOf(FieldValue.of(it)) } ?: emptyList(),
    )
}

private class RecordingElasticsearchTransport(
    vararg results: ElasticsearchSearchResult,
) : ElasticsearchQueryTransport {
    private val results = ArrayDeque(results.toList())
    val searchRequests = mutableListOf<SearchRequest>()
    val countRequests = mutableListOf<CountRequest>()
    val openCount = AtomicInteger()
    val closeCount = AtomicInteger()
    var countResult: Long = 0

    override fun open(index: String): Mono<String> = Mono.fromSupplier {
        openCount.incrementAndGet()
        "pit-id"
    }

    override fun searchResult(request: SearchRequest): Mono<ElasticsearchSearchResult> = Mono.fromSupplier {
        searchRequests += request
        results.removeFirst()
    }

    override fun count(request: CountRequest): Mono<Long> = Mono.fromSupplier {
        countRequests += request
        countResult
    }

    override fun close(pitId: String): Mono<Void> = Mono.fromRunnable { closeCount.incrementAndGet() }
}
