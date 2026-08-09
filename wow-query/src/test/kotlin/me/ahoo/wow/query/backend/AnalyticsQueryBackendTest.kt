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

package me.ahoo.wow.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryTarget
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.function.Consumer

@OptIn(
    ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)
class AnalyticsQueryBackendTest {
    @Test
    fun `analytics plan and result should freeze every collection boundary`() {
        val dimensions = mutableListOf(
            BackendAnalyticsDimension(alias, amount, BackendAnalyticsMissingPolicy.AS_NULL_BUCKET),
        )
        val metrics = mutableListOf<BackendAnalyticsMetric>(
            BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
            BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), amount),
        )
        val afterKey = mutableListOf<NormalizedValue>(NormalizedValue.Decimal("1.00".toBigDecimal()))
        val plan = analyticsPlan(dimensions, metrics, afterKey)
        dimensions.clear()
        metrics.clear()
        afterKey.clear()

        (plan.grouping as BackendAnalyticsGrouping.By).dimensions.assert().hasSize(1)
        plan.metrics.assert().hasSize(2)
        plan.bucketWindow.afterKey!!.assert().containsExactly(NormalizedValue.Decimal("1".toBigDecimal()))
        @Suppress("UNCHECKED_CAST")
        assertThrownBy<UnsupportedOperationException> {
            (plan.metrics as MutableList<BackendAnalyticsMetric>).clear()
        }

        val keys = linkedMapOf(alias to NormalizedValue.Decimal("1.0".toBigDecimal()))
        val values = linkedMapOf(AnalyticsAlias("count") to NormalizedValue.Int64(2))
        val buckets = mutableListOf(BackendAnalyticsBucket(keys, values))
        val page = BackendAnalyticsPage(
            buckets,
            listOf(NormalizedValue.Decimal("1.0".toBigDecimal())),
            BackendAnalyticsConsistency.EVENTUAL,
            BackendAnalyticsCompleteness.EXACT,
        )
        keys.clear()
        values.clear()
        buckets.clear()

        page.buckets.assert().hasSize(1)
        page.buckets.single().keys.assert().containsEntry(alias, NormalizedValue.Decimal("1".toBigDecimal()))
        page.afterKey!!.assert().hasSize(1)
    }

    @Test
    fun `analytics aliases should be safe Mongo field names`() {
        listOf("", "a.b", "\$metric", "a\u0000b").forEach { invalid ->
            assertThrownBy<IllegalArgumentException> { AnalyticsAlias(invalid) }
        }
    }

    @Test
    fun `analytics plan should make global and grouped paging invariants explicit`() {
        val valid = analyticsPlan(
            listOf(BackendAnalyticsDimension(alias, amount, BackendAnalyticsMissingPolicy.EXCLUDE)),
            listOf(BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
            listOf(NormalizedValue.Decimal(BigDecimal.ONE)),
        )

        assertThrownBy<IllegalArgumentException> {
            rebuildPlan(
                valid,
                BackendAnalyticsGrouping.Global,
                BackendAnalyticsBucketOrder.Global,
                BackendAnalyticsPageWindow(2),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            rebuildPlan(
                valid,
                valid.grouping,
                BackendAnalyticsBucketOrder.Global,
                BackendAnalyticsPageWindow(1),
            )
        }
    }

    @Test
    fun `analytics contribution should require an explicit analytics backend`() {
        assertThrownBy<IllegalArgumentException> {
            contribution(null)
        }
        contribution(AnalyticsQueryBackend { _, _ -> reactor.core.publisher.Mono.empty() })
            .supportedOperations.assert().containsExactly(QueryOperation.ANALYZE, QueryOperation.COUNT)
    }

    @Test
    fun `analytics cursor state should be opaque immutable and rejected by a stateless backend`() {
        val source = byteArrayOf(1, 2, 3)
        val state = BackendAnalyticsCursorState(source)
        source[0] = 9
        state.payload().contentEquals(byteArrayOf(1, 2, 3)).assert().isTrue()

        val returned = state.payload()
        returned[1] = 9
        state.payload().contentEquals(byteArrayOf(1, 2, 3)).assert().isTrue()
        BackendAnalyticsCursorState(byteArrayOf(1, 2, 3)).assert().isEqualTo(state)

        val backend = AnalyticsQueryBackend { _, _ -> Mono.empty() }
        assertThrownBy<QueryBackendException> {
            backend.analyze(
                analyticsPlan(
                    listOf(BackendAnalyticsDimension(alias, amount, BackendAnalyticsMissingPolicy.EXCLUDE)),
                    listOf(BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
                    listOf(NormalizedValue.Decimal(BigDecimal.ONE)),
                ),
                QueryBackendExecutionOptions(null, null),
                state,
            ).block()
        }.satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.UNSUPPORTED) },
        )
    }

    private fun analyticsPlan(
        dimensions: Iterable<BackendAnalyticsDimension>,
        metrics: Iterable<BackendAnalyticsMetric>,
        afterKey: Iterable<NormalizedValue>,
    ) = BackendAnalyticsQueryPlan(
        target,
        schema.contractId,
        BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All),
        BackendAnalyticsGrouping.By(dimensions),
        metrics,
        BackendAnalyticsCondition.All,
        BackendAnalyticsBucketOrder.DimensionKeyAscending(
            BackendAnalyticsNullPlacement.FIRST,
            BackendAnalyticsTextCollation.BINARY,
        ),
        BackendAnalyticsPageWindow(10, afterKey),
        BackendAnalyticsNumericPolicy(
            BackendAnalyticsNumericPromotion.DECIMAL128,
            34,
            2,
            RoundingMode.HALF_UP,
            BackendAnalyticsOverflowPolicy.REJECT,
        ),
        BackendAnalyticsConsistency.EVENTUAL,
        BackendAnalyticsCompleteness.EXACT,
        BackendRequiredCapabilities(mapOf(amount to setOf(FieldCapability.AGGREGATABLE))),
        SemanticTier.PORTABLE,
        PlanFingerprint("1".repeat(64)),
    )

    private fun rebuildPlan(
        source: BackendAnalyticsQueryPlan,
        grouping: BackendAnalyticsGrouping,
        order: BackendAnalyticsBucketOrder,
        window: BackendAnalyticsPageWindow,
    ) = BackendAnalyticsQueryPlan(
        source.target,
        source.schemaContractId,
        source.filter,
        grouping,
        source.metrics,
        source.having,
        order,
        window,
        source.numericPolicy,
        source.requiredConsistency,
        source.requiredCompleteness,
        source.requiredCapabilities,
        source.semanticTier,
        source.fingerprint,
    )

    private fun contribution(analytics: AnalyticsQueryBackend?): RecordQueryBackendContribution =
        RecordQueryBackendContribution(
            schema,
            BackendId("test"),
            setOf(QueryOperation.COUNT, QueryOperation.ANALYZE),
            BackendStreamSupport.NONE,
            setOf(SemanticTier.PORTABLE),
            mapOf(amount to setOf(FieldCapability.AGGREGATABLE)),
            backend = NO_OP_BACKEND,
            analyticsBackend = analytics,
        )

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val state = QueryFieldId.Path(listOf("state"))
    private val amount = QueryFieldId.Path(listOf("state", "amount"))
    private val alias = AnalyticsAlias("amount")
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            QueryFieldSchema(
                state,
                LogicalFieldType.Object,
                Presence.OPTIONAL,
                Nullability.NULLABLE,
                emptyList(),
                emptyList(),
            ),
            QueryFieldSchema(
                amount,
                LogicalFieldType.Decimal,
                Presence.OPTIONAL,
                Nullability.NULLABLE,
                emptyList(),
                listOf(FieldCapability.AGGREGATABLE),
            ),
        ),
        emptyList(),
    )

    private companion object {
        val NO_OP_BACKEND = object : RecordQueryBackend {
            override fun single(
                plan: BackendSingleQueryPlan,
                options: QueryBackendExecutionOptions,
            ): Mono<BackendRecord> = Mono.empty()

            override fun stream(
                plan: BackendStreamQueryPlan,
                options: QueryBackendExecutionOptions,
            ): Flux<BackendRecord> = Flux.empty()

            override fun count(plan: BackendCountQueryPlan, options: QueryBackendExecutionOptions): Mono<Long> =
                Mono.just(0)
        }
    }
}
