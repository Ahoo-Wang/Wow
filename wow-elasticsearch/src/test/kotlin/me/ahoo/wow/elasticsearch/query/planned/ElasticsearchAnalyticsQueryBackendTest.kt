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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.BackendAnalyticsBucketOrder
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsCondition
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsDimension
import me.ahoo.wow.query.backend.BackendAnalyticsGrouping
import me.ahoo.wow.query.backend.BackendAnalyticsMetric
import me.ahoo.wow.query.backend.BackendAnalyticsMissingPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsNullPlacement
import me.ahoo.wow.query.backend.BackendAnalyticsPageWindow
import me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan
import me.ahoo.wow.query.backend.BackendAnalyticsTextCollation
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
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

class ElasticsearchAnalyticsQueryBackendTest {
    @Test
    fun `grouped response should use Elasticsearch response after key instead of deriving one`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val request = slot<SearchRequest>()
        every { client.search(capture(request), Map::class.java) } returns Mono.just(
            response(
                aggregation = composite(
                    listOf(bucket("PAID", 2)),
                    mapOf("status" to FieldValue.of("SHIPPED")),
                ),
            ),
        )

        val page = backend(client).analyze(groupedPlan(), OPTIONS).block()!!

        page.buckets.single().keys[STATUS_ALIAS].assert().isEqualTo(NormalizedValue.Text("PAID"))
        page.buckets.single().metrics[COUNT_ALIAS].assert().isEqualTo(NormalizedValue.Int64(2))
        page.afterKey!!.assert().containsExactly(NormalizedValue.Text("SHIPPED"))
        page.consistency.assert().isEqualTo(BackendAnalyticsConsistency.EVENTUAL)
        page.completeness.assert().isEqualTo(BackendAnalyticsCompleteness.EXACT)
        request.captured.size().assert().isZero()
        request.captured.aggregations().keys.assert().containsExactly(ANALYTICS_AGGREGATION)
    }

    @Test
    fun `global document count should require exact total and map zero safely`() {
        val client = mockk<ReactiveElasticsearchClient>()
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(response(total = 0, totalRelation = TotalHitsRelation.Eq)),
            Mono.just(response(total = 1, totalRelation = TotalHitsRelation.Gte)),
        )

        val page = backend(client).analyze(globalPlan(), OPTIONS).block()!!
        page.buckets.single().metrics[COUNT_ALIAS].assert().isEqualTo(NormalizedValue.Int64(0))
        page.afterKey.assert().isNull()
        assertFailure(QueryBackendFailureKind.INCOMPLETE_RESULT) {
            backend(client).analyze(globalPlan(), OPTIONS).block()
        }
    }

    @Test
    fun `timeout failed shards and malformed after key should fail closed`() {
        val client = mockk<ReactiveElasticsearchClient>()
        every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(response(timedOut = true)),
            Mono.just(response(failedShards = 1)),
            Mono.just(
                response(
                    aggregation = composite(
                        listOf(bucket("PAID", 1)),
                        mapOf("unexpected" to FieldValue.of("PAID")),
                    ),
                ),
            ),
        )

        assertFailure(QueryBackendFailureKind.TIMEOUT) {
            backend(client).analyze(groupedPlan(), OPTIONS).block()
        }
        assertFailure(QueryBackendFailureKind.INCOMPLETE_RESULT) {
            backend(client).analyze(groupedPlan(), OPTIONS).block()
        }
        assertFailure(QueryBackendFailureKind.MAPPING_FAILURE) {
            backend(client).analyze(groupedPlan(), OPTIONS).block()
        }
    }

    @Test
    fun `null composite bucket and response cursor should remain canonical null values`() {
        val client = mockk<ReactiveElasticsearchClient>()
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            response(
                aggregation = composite(
                    listOf(bucket(FieldValue.NULL, 2)),
                    mapOf("status" to FieldValue.NULL),
                ),
            ),
        )

        val page = backend(client).analyze(
            groupedPlan(BackendAnalyticsMissingPolicy.AS_NULL_BUCKET),
            OPTIONS,
        ).block()!!

        page.buckets.single().keys[STATUS_ALIAS].assert().isEqualTo(NormalizedValue.Null)
        page.buckets.single().metrics[COUNT_ALIAS].assert().isEqualTo(NormalizedValue.Int64(2))
        page.afterKey!!.assert().containsExactly(NormalizedValue.Null)
    }

    @Test
    fun `invalid contracts and unsupported budgets should fail before Elasticsearch IO`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val backend = backend(client)

        assertFailure(QueryBackendFailureKind.UNSUPPORTED) {
            backend.analyze(groupedPlan(), OPTIONS.copy(maxCandidateBuckets = 1)).block()
        }
        assertFailure(QueryBackendFailureKind.TIMEOUT) {
            backend.analyze(groupedPlan(), OPTIONS.copy(deadline = NOW.minusMillis(1))).block()
        }

        confirmVerified(client)
    }

    @Test
    fun `snapshot analytics should transfer the latest PIT state and close it through the lifecycle`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val firstRequest = slot<SearchRequest>()
        val continuedRequest = slot<SearchRequest>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openPit("pit-1"))
        every { client.search(capture(firstRequest), Map::class.java) } returns Mono.just(
            response(
                aggregation = composite(
                    listOf(bucket("PAID", 2)),
                    mapOf("status" to FieldValue.of("PAID")),
                ),
                pitId = "pit-2",
            ),
        )
        val backend = backend(client)

        val first = backend.analyze(snapshotGroupedPlan(), OPTIONS, null).block()!!
        first.consistency.assert().isEqualTo(BackendAnalyticsConsistency.SNAPSHOT)
        first.cursorState!!.payload().decodeToString().assert().isEqualTo("pit-2")
        firstRequest.captured.index().assert().isEmpty()
        firstRequest.captured.pit()!!.id().assert().isEqualTo("pit-1")
        verify(exactly = 0) { client.closePointInTime(any<ClosePointInTimeRequest>()) }

        every { client.search(capture(continuedRequest), Map::class.java) } returns Mono.just(
            response(
                aggregation = composite(listOf(bucket("SHIPPED", 1)), emptyMap()),
                pitId = "pit-3",
            ),
        )
        val continued = backend.analyze(
            snapshotGroupedPlan(listOf(NormalizedValue.Text("PAID"))),
            OPTIONS,
            first.cursorState,
        ).block()!!
        continued.afterKey.assert().isNull()
        continued.cursorState!!.payload().decodeToString().assert().isEqualTo("pit-3")
        continuedRequest.captured.pit()!!.id().assert().isEqualTo("pit-2")
        verify(exactly = 1) { client.openPointInTime(any<OpenPointInTimeRequest>()) }

        every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(
            ClosePointInTimeResponse.of { response -> response.succeeded(true).numFreed(1) },
        )
        backend.close(continued.cursorState!!).block()
        closeRequest.captured.id().assert().isEqualTo("pit-3")
    }

    @Test
    fun `snapshot analytics cancellation should close a PIT before ownership transfer`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openPit("pit-cancel"))
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.never()
        every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(
            ClosePointInTimeResponse.of { response -> response.succeeded(true).numFreed(1) },
        )

        StepVerifier.create(backend(client).analyze(snapshotGroupedPlan(), OPTIONS, null))
            .thenAwait(Duration.ofMillis(10))
            .thenCancel()
            .verify()

        closeRequest.captured.id().assert().isEqualTo("pit-cancel")
    }

    @Test
    fun `expired snapshot PIT should fail incomplete and consume the leased state`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        val pitExpired = mockk<co.elastic.clients.elasticsearch._types.ElasticsearchException> {
            every { status() } returns 404
        }
        every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.error(pitExpired)
        every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(
            ClosePointInTimeResponse.of { response -> response.succeeded(true).numFreed(0) },
        )

        val error = runCatching {
            backend(client).analyze(
                snapshotGroupedPlan(listOf(NormalizedValue.Text("PAID"))),
                OPTIONS,
                me.ahoo.wow.query.backend.BackendAnalyticsCursorState("pit-expired".encodeToByteArray()),
            ).block()
        }.exceptionOrNull() as QueryBackendException

        error.kind.assert().isEqualTo(QueryBackendFailureKind.INCOMPLETE_RESULT)
        error.cause.assert().isSameAs(pitExpired)
        closeRequest.captured.id().assert().isEqualTo("pit-expired")
    }

    private fun backend(client: ReactiveElasticsearchClient) = ElasticsearchAnalyticsQueryBackend(
        client,
        binding.prepared,
        Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun response(
        aggregation: Aggregate? = null,
        total: Long = 0,
        totalRelation: TotalHitsRelation? = null,
        timedOut: Boolean = false,
        failedShards: Int = 0,
        pitId: String? = null,
    ): SearchResponse<Map<*, *>> = SearchResponse.of<Map<*, *>> { response ->
        response.took(1)
            .timedOut(timedOut)
            .shards { shards -> shards.failed(failedShards).successful(1).total(1 + failedShards) }
            .hits { hits ->
                hits.hits(emptyList())
                totalRelation?.let { relation -> hits.total { value -> value.value(total).relation(relation) } }
                hits
            }
            .also { builder -> aggregation?.let { builder.aggregations(ANALYTICS_AGGREGATION, it) } }
            .also { builder -> pitId?.let(builder::pitId) }
    }

    private fun composite(
        buckets: List<CompositeBucket>,
        afterKey: Map<String, FieldValue>,
    ): Aggregate = Aggregate.of { aggregate ->
        aggregate.composite { composite -> composite.buckets { values -> values.array(buckets) }.afterKey(afterKey) }
    }

    private fun bucket(status: String, count: Long): CompositeBucket = CompositeBucket.of { bucket ->
        bucket.key("status", status).docCount(count)
    }

    private fun bucket(status: FieldValue, count: Long): CompositeBucket = CompositeBucket.of { bucket ->
        bucket.key("status", status).docCount(count)
    }

    private fun globalPlan() = plan(BackendAnalyticsGrouping.Global, BackendAnalyticsPageWindow(1))

    private fun groupedPlan(
        missingPolicy: BackendAnalyticsMissingPolicy = BackendAnalyticsMissingPolicy.EXCLUDE,
    ) = plan(
        BackendAnalyticsGrouping.By(
            listOf(BackendAnalyticsDimension(STATUS_ALIAS, status, missingPolicy)),
        ),
        BackendAnalyticsPageWindow(10),
    )

    private fun snapshotGroupedPlan(afterKey: List<NormalizedValue>? = null) = plan(
        BackendAnalyticsGrouping.By(
            listOf(BackendAnalyticsDimension(STATUS_ALIAS, status, BackendAnalyticsMissingPolicy.EXCLUDE)),
        ),
        BackendAnalyticsPageWindow(10, afterKey),
        BackendAnalyticsConsistency.SNAPSHOT,
    )

    private fun plan(
        grouping: BackendAnalyticsGrouping,
        window: BackendAnalyticsPageWindow,
        consistency: BackendAnalyticsConsistency = BackendAnalyticsConsistency.EVENTUAL,
    ) = BackendAnalyticsQueryPlan(
        target,
        schema.contractId,
        BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All),
        grouping,
        listOf(BackendAnalyticsMetric.DocumentCount(COUNT_ALIAS)),
        BackendAnalyticsCondition.All,
        when (grouping) {
            BackendAnalyticsGrouping.Global -> BackendAnalyticsBucketOrder.Global
            is BackendAnalyticsGrouping.By -> BackendAnalyticsBucketOrder.DimensionKeyAscending(
                BackendAnalyticsNullPlacement.FIRST,
                BackendAnalyticsTextCollation.BINARY,
            )
        },
        window,
        null,
        consistency,
        BackendAnalyticsCompleteness.EXACT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("a".repeat(64)),
    )

    private fun openPit(id: String): OpenPointInTimeResponse = OpenPointInTimeResponse.of { response ->
        response.id(id).shards { shards -> shards.total(1).successful(1).failed(0) }
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
    private val state = QueryFieldId.Path(listOf("state"))
    private val status = QueryFieldId.Path(listOf("state", "status"))
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            field(identity, LogicalFieldType.Text, setOf(FieldCapability.EXACT)),
            field(state, LogicalFieldType.Object, emptySet()),
            field(status, LogicalFieldType.Text, setOf(FieldCapability.AGGREGATABLE)),
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
                setOf(FieldCapability.EXACT),
                exactField = "_id",
            ),
            state to ElasticsearchFieldBinding("state", emptySet()),
            status to ElasticsearchFieldBinding(
                "state.status",
                setOf(FieldCapability.AGGREGATABLE),
                groupField = "state.status.exact",
                groupReadiness = GROUP_READINESS,
                keywordReadiness = ElasticsearchKeywordReadiness(128, 512, true, true),
            ),
        ),
    )

    private fun field(
        id: QueryFieldId,
        type: LogicalFieldType,
        capabilities: Set<FieldCapability>,
    ) = QueryFieldSchema(
        id,
        type,
        Presence.OPTIONAL,
        Nullability.NULLABLE,
        if (FieldCapability.EXACT in capabilities) setOf(PredicateOperator.EQ) else emptySet(),
        capabilities,
    )

    private companion object {
        const val ANALYTICS_AGGREGATION = "wow_analytics"
        val NOW: Instant = Instant.parse("2026-08-08T00:00:00Z")
        val OPTIONS = QueryBackendExecutionOptions(NOW.plusSeconds(10), null, maxReturnedBuckets = 10)
        val STATUS_ALIAS = AnalyticsAlias("status")
        val COUNT_ALIAS = AnalyticsAlias("count")
        val GROUP_READINESS = ElasticsearchGroupReadiness(historicalValuesAudited = true)
    }
}
