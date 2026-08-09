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
import java.util.function.Consumer

class ElasticsearchAnalyticsQueryCompilerTest {
    private val compiler: ElasticsearchAnalyticsQueryCompiler
        get() = ElasticsearchAnalyticsQueryCompiler(binding.prepared)

    @Test
    fun `grouped exact document count should compile composite sources and response after key`() {
        val compiled = compiler.compile(
            plan(
                BackendAnalyticsGrouping.By(
                    listOf(
                        BackendAnalyticsDimension(
                            AnalyticsAlias("status"),
                            status,
                            BackendAnalyticsMissingPolicy.EXCLUDE,
                        ),
                    ),
                ),
                BackendAnalyticsPageWindow(10, listOf(NormalizedValue.Text("PAID"))),
            ),
        )

        compiled.aggregation!!.composite().size().assert().isEqualTo(10)
        compiled.aggregation.composite().sources().single().name().assert().isEqualTo("status")
        val terms = compiled.aggregation.composite().sources().single().value().terms()
        terms.field().assert().isEqualTo("state.status.exact")
        terms.missingBucket().assert().isFalse()
        terms.missingOrder().assert().isNull()
        compiled.aggregation.composite().after()["status"]!!.stringValue().assert().isEqualTo("PAID")
    }

    @Test
    fun `unsupported precision contracts should fail before IO`() {
        assertUnsupported {
            compiler.compile(
                plan(
                    BackendAnalyticsGrouping.Global,
                    BackendAnalyticsPageWindow(1),
                    listOf(BackendAnalyticsMetric.Sum(AnalyticsAlias("sum"), amount)),
                ),
            )
        }
    }

    @Test
    fun `snapshot consistency should reuse the same logical aggregation compilation`() {
        val compiled = compiler.compile(
            plan(
                BackendAnalyticsGrouping.Global,
                BackendAnalyticsPageWindow(1),
                consistency = BackendAnalyticsConsistency.SNAPSHOT,
            ),
        )

        compiled.aggregation.assert().isNull()
    }

    @Test
    fun `missing and explicit null should share one composite bucket and cursor value`() {
        val compiled = compiler.compile(
            plan(
                BackendAnalyticsGrouping.By(
                    listOf(
                        BackendAnalyticsDimension(
                            AnalyticsAlias("status"),
                            status,
                            BackendAnalyticsMissingPolicy.AS_NULL_BUCKET,
                        ),
                    ),
                ),
                BackendAnalyticsPageWindow(10, listOf(NormalizedValue.Null)),
            ),
        )

        val terms = compiled.aggregation!!.composite().sources().single().value().terms()
        terms.missingBucket().assert().isTrue()
        terms.missingOrder()!!.jsonValue().assert().isEqualTo("first")
        compiled.aggregation.composite().after()["status"]!!.isNull.assert().isTrue()
    }

    private fun plan(
        grouping: BackendAnalyticsGrouping,
        window: BackendAnalyticsPageWindow,
        metrics: List<BackendAnalyticsMetric> = listOf(
            BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
        ),
        consistency: BackendAnalyticsConsistency = BackendAnalyticsConsistency.EVENTUAL,
    ) = BackendAnalyticsQueryPlan(
        target,
        schema.contractId,
        BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All),
        grouping,
        metrics,
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
        PlanFingerprint("9".repeat(64)),
    )

    private fun assertUnsupported(action: () -> Unit) {
        assertThrownBy<QueryBackendException>(action).satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.UNSUPPORTED) },
        )
    }

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val state = QueryFieldId.Path(listOf("state"))
    private val status = QueryFieldId.Path(listOf("state", "status"))
    private val amount = QueryFieldId.Path(listOf("state", "amount"))
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            field(identity, LogicalFieldType.Text, setOf(FieldCapability.EXACT)),
            field(state, LogicalFieldType.Object, emptySet()),
            field(status, LogicalFieldType.Text, setOf(FieldCapability.AGGREGATABLE)),
            field(amount, LogicalFieldType.Int64, setOf(FieldCapability.AGGREGATABLE)),
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
            amount to ElasticsearchFieldBinding(
                "state.amount",
                setOf(FieldCapability.AGGREGATABLE),
                groupField = "state.amount",
                groupReadiness = GROUP_READINESS,
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
        val GROUP_READINESS = ElasticsearchGroupReadiness(historicalValuesAudited = true)
    }
}
