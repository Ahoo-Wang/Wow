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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.elasticsearch.query.planned

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorCause
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendPageWindow
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.BackendRequiredConsistency
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendSort
import me.ahoo.wow.query.backend.BackendSortOrigin
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.BackendTotalMode
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedSortDirection
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.RecordResultShape
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Consumer

class ElasticsearchSnapshotRecordQueryBackendTest {
    @Test
    fun `search response failures should preserve timeout incomplete and exact total categories`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val backend = backend(client)
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openPit("pit-1"))
        every { client.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.just(closePit())
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(response(timedOut = true)),
            Mono.just(response(failedShards = 1)),
            Mono.just(response(totalRelation = TotalHitsRelation.Gte)),
        )

        assertFailure(QueryBackendFailureKind.TIMEOUT) { backend.single(singlePlan, OPTIONS).block() }
        assertFailure(QueryBackendFailureKind.INCOMPLETE_RESULT) { backend.single(singlePlan, OPTIONS).block() }
        assertFailure(QueryBackendFailureKind.INCOMPLETE_RESULT) { backend.page(pagePlan, OPTIONS).block() }

        verify(exactly = 3) { client.search(any<SearchRequest>(), Map::class.java) }
        verify(exactly = 1) { client.openPointInTime(any<OpenPointInTimeRequest>()) }
        verify(exactly = 1) { client.closePointInTime(any<ClosePointInTimeRequest>()) }
    }

    @Test
    fun `deep page should traverse search after and close the latest PIT`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val searches = mutableListOf<SearchRequest>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openPit("pit-1"))
        every { client.search(capture(searches), Map::class.java) } returnsMany listOf(
            Mono.just(
                response(
                    totalRelation = TotalHitsRelation.Eq,
                    total = 1_001,
                    ids = (0 until 1_000).map(Int::toString),
                    pitId = "pit-2",
                ),
            ),
            Mono.just(
                response(
                    totalRelation = TotalHitsRelation.Eq,
                    total = 1_001,
                    ids = listOf("1000"),
                    pitId = "pit-3",
                ),
            ),
        )
        every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(closePit())

        val result = backend(client).page(pagePlan(1_000, 1), OPTIONS.copy(maxCursorPages = 2)).block()!!

        result.records.single().identity.assert().isEqualTo("1000")
        result.total.assert().isEqualTo(1_001)
        searches.assert().hasSize(2)
        searches.first().searchAfter().assert().isEmpty()
        searches.last().searchAfter().single().stringValue().assert().isEqualTo("999")
        closeRequest.captured.id().assert().isEqualTo("pit-3")
    }

    @Test
    fun `deep page cancellation should close the latest PIT`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openPit("pit-1"))
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(
                response(
                    totalRelation = TotalHitsRelation.Eq,
                    total = 1_001,
                    ids = (0 until 1_000).map(Int::toString),
                    pitId = "pit-2",
                ),
            ),
            Mono.never(),
        )
        every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(closePit())

        StepVerifier.create(backend(client).page(pagePlan(1_000, 1), OPTIONS.copy(maxCursorPages = 2)))
            .thenAwait(Duration.ofMillis(10))
            .thenCancel()
            .verify()

        verify(timeout = 1_000, exactly = 1) { client.closePointInTime(any<ClosePointInTimeRequest>()) }
        closeRequest.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `expired PIT should fail incomplete and close the latest lease without replacing the original error`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        val pitExpired = mockk<ElasticsearchException> {
            every { status() } returns NOT_FOUND
        }
        val closeFailure = IllegalStateException("close failed")
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openPit("pit-1"))
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(
                response(
                    totalRelation = TotalHitsRelation.Eq,
                    total = 1_001,
                    ids = (0 until 1_000).map(Int::toString),
                    pitId = "pit-2",
                ),
            ),
            Mono.error(pitExpired),
        )
        every { client.closePointInTime(capture(closeRequest)) } returns Mono.error(closeFailure)

        val error = runCatching {
            backend(client).page(pagePlan(1_000, 1), OPTIONS.copy(maxCursorPages = 2)).block()
        }.exceptionOrNull() as QueryBackendException

        error.kind.assert().isEqualTo(QueryBackendFailureKind.INCOMPLETE_RESULT)
        error.cause.assert().isSameAs(pitExpired)
        error.suppressed.any { suppressed -> suppressed === closeFailure }.assert().isTrue()
        closeRequest.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `expired PIT wrapped as search phase failure should fail incomplete`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val missingContext = mockk<ErrorCause> {
            every { type() } returns SEARCH_CONTEXT_MISSING_TYPE
            every { causedBy() } returns null
            every { rootCause() } returns emptyList()
            every { suppressed() } returns emptyList()
        }
        val searchPhaseFailure = mockk<ErrorCause> {
            every { type() } returns "search_phase_execution_exception"
            every { causedBy() } returns null
            every { rootCause() } returns listOf(missingContext)
            every { suppressed() } returns emptyList()
        }
        val pitExpired = mockk<ElasticsearchException> {
            every { status() } returns BAD_REQUEST
            every { error() } returns searchPhaseFailure
        }
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openPit("pit-1"))
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.error(pitExpired)
        every { client.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.just(closePit())

        val error = runCatching {
            backend(client).page(pagePlan(0, 1), OPTIONS).block()
        }.exceptionOrNull() as QueryBackendException

        error.kind.assert().isEqualTo(QueryBackendFailureKind.INCOMPLETE_RESULT)
        error.cause.assert().isSameAs(pitExpired)
        verify(exactly = 1) { client.closePointInTime(any<ClosePointInTimeRequest>()) }
    }

    @Test
    fun `expired PIT during successful cleanup should fail incomplete rather than unavailable`() {
        val client = mockk<ReactiveElasticsearchClient>()
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openPit("pit-1"))
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            response(totalRelation = TotalHitsRelation.Eq, total = 0),
        )
        every { client.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.empty()

        assertFailure(QueryBackendFailureKind.INCOMPLETE_RESULT) {
            backend(client).page(pagePlan(0, 1), OPTIONS).block()
        }
    }

    @Test
    fun `unsupported budgets and expired deadline should fail before Elasticsearch IO`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val backend = backend(client)

        assertFailure(QueryBackendFailureKind.UNSUPPORTED) {
            backend.single(singlePlan, OPTIONS.copy(maxScannedRecords = 1)).block()
        }
        assertFailure(QueryBackendFailureKind.TIMEOUT) {
            backend.single(singlePlan, OPTIONS.copy(deadline = NOW.minusMillis(1))).block()
        }

        confirmVerified(client)
    }

    @Test
    fun `bounded stream beyond the supported result window should fail before Elasticsearch IO`() {
        val client = mockk<ReactiveElasticsearchClient>()

        assertFailure(QueryBackendFailureKind.UNSUPPORTED) {
            backend(client).stream(
                streamPlan(10_001),
                OPTIONS.copy(maxReturnedRecords = 20_000),
            ).blockLast()
        }

        confirmVerified(client)
    }

    private fun backend(client: ReactiveElasticsearchClient) = ElasticsearchSnapshotRecordQueryBackend(
        client,
        binding.prepared,
        Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun response(
        timedOut: Boolean = false,
        failedShards: Int = 0,
        totalRelation: TotalHitsRelation? = null,
        total: Long = 0,
        ids: List<String> = emptyList(),
        pitId: String? = null,
    ): SearchResponse<Map<*, *>> = SearchResponse.of<Map<*, *>> { response ->
        response.took(1)
            .timedOut(timedOut)
            .shards { shards -> shards.failed(failedShards).successful(1).total(1 + failedShards) }
            .hits { hits ->
                hits.hits(
                    ids.map { id ->
                        Hit.of<Map<*, *>> { hit ->
                            hit.index("wow.sales.order.snapshot")
                                .id(id)
                                .source(mapOf(MessageRecords.AGGREGATE_ID to id))
                                .sort(id)
                        }
                    },
                )
                totalRelation?.let { relation ->
                    hits.total { totalHits -> totalHits.relation(relation).value(total) }
                }
                hits
            }
            .also { builder -> pitId?.let(builder::pitId) }
    }

    private fun openPit(id: String): OpenPointInTimeResponse =
        OpenPointInTimeResponse.of { response ->
            response.id(id).shards { shards -> shards.failed(0).successful(1).total(1) }
        }

    private fun closePit(): ClosePointInTimeResponse = ClosePointInTimeResponse.of { response ->
        response.succeeded(true).numFreed(1)
    }

    private fun assertFailure(kind: QueryBackendFailureKind, action: () -> Unit) {
        assertThrownBy<QueryBackendException>(action).satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(kind) },
        )
    }

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            QueryFieldSchema(
                identity,
                LogicalFieldType.Text,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                setOf(PredicateOperator.EQ),
                setOf(FieldCapability.EXACT, FieldCapability.SORTABLE),
            ),
        ),
        emptyList(),
    )
    private val binding = ElasticsearchSnapshotQueryBinding(
        schema,
        "wow.sales.order.snapshot",
        "order-query-v1",
        mapOf(
            identity to ElasticsearchFieldBinding(
                MessageRecords.AGGREGATE_ID,
                setOf(FieldCapability.EXACT, FieldCapability.SORTABLE),
                exactField = "_id",
                sortField = MessageRecords.AGGREGATE_ID,
            ),
        ),
    )
    private val filter = BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All)
    private val singlePlan = BackendSingleQueryPlan(
        target,
        schema.contractId,
        filter,
        RecordResultShape.DYNAMIC,
        BackendProjection.All,
        emptyList(),
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("1".repeat(64)),
    )
    private val pagePlan = pagePlan(0, 10)

    private fun streamPlan(limit: Int) = BackendStreamQueryPlan(
        target,
        schema.contractId,
        filter,
        RecordResultShape.DYNAMIC,
        BackendProjection.All,
        emptyList(),
        limit,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("3".repeat(64)),
    )

    private fun pagePlan(offset: Long, size: Int) = BackendPageQueryPlan(
        target,
        schema.contractId,
        filter,
        RecordResultShape.DYNAMIC,
        BackendProjection.All,
        listOf(BackendSort(identity, NormalizedSortDirection.ASC, BackendSortOrigin.STABILITY_TIE_BREAKER)),
        BackendPageWindow(offset, size),
        BackendTotalMode.EXACT,
        BackendRequiredConsistency.SAME_INPUT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("2".repeat(64)),
    )

    private companion object {
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val SEARCH_CONTEXT_MISSING_TYPE = "search_context_missing_exception"
        val NOW: Instant = Instant.parse("2026-08-08T00:00:00Z")
        val OPTIONS = QueryBackendExecutionOptions(NOW.plusSeconds(10), 100)
    }
}
