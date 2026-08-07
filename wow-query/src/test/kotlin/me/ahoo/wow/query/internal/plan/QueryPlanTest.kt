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

package me.ahoo.wow.query.internal.plan

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsDimension
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.RecordResultShape
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.PathBasis
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test

class QueryPlanTest {

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val field = LogicalField.Path(listOf("state", "amount"), PathBasis.ROOT)

    @Test
    fun `record plans should expose their operation without backend state`() {
        val plans = listOf(
            SingleQueryPlan(target, NormalizedCondition.All, RecordResultShape.TYPED),
            StreamQueryPlan(target, NormalizedCondition.All, RecordResultShape.DYNAMIC, StreamLimit.Unbounded),
            PageQueryPlan(
                target,
                NormalizedCondition.All,
                RecordResultShape.TYPED,
                PageWindow(offset = 0, size = 20),
            ),
            CountQueryPlan(target, NormalizedCondition.All),
        )

        plans.map { it.operation }.assert().containsExactly(
            QueryOperation.SINGLE,
            QueryOperation.STREAM,
            QueryOperation.PAGE,
            QueryOperation.COUNT,
        )
        plans.filterIsInstance<RecordQueryPlan>().map { it.resultShape }.assert().containsExactly(
            RecordResultShape.TYPED,
            RecordResultShape.DYNAMIC,
            RecordResultShape.TYPED,
        )
    }

    @Test
    fun `bounded stream and page window should reject invalid values`() {
        assertThrownBy<IllegalArgumentException> {
            StreamLimit.Bounded(0)
        }
        assertThrownBy<IllegalArgumentException> {
            PageWindow(offset = -1, size = 10)
        }
        assertThrownBy<IllegalArgumentException> {
            PageWindow(offset = 0, size = 0)
        }
    }

    @Test
    fun `global analytics should be valid without a dimension`() {
        val plan = AnalyticsQueryPlan(
            target = target,
            preFilter = NormalizedCondition.All,
            grouping = AnalyticsGrouping.Global,
            metrics = NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
        )

        plan.operation.assert().isEqualTo(QueryOperation.ANALYZE)
        plan.grouping.assert().isEqualTo(AnalyticsGrouping.Global)
    }

    @Test
    fun `grouped analytics should require at least one dimension`() {
        NonEmptyList.from<AnalyticsDimension>(emptyList()).assert().isNull()

        val grouping = AnalyticsGrouping.By(
            NonEmptyList.of(AnalyticsDimension(AnalyticsAlias("amount"), field)),
        )

        grouping.dimensions.values.assert().hasSize(1)
    }

    @Test
    fun `analytics aliases should be unique across dimensions and metrics`() {
        val alias = AnalyticsAlias("amount")
        val grouping = AnalyticsGrouping.By(NonEmptyList.of(AnalyticsDimension(alias, field)))

        assertThrownBy<IllegalArgumentException> {
            AnalyticsQueryPlan(
                target = target,
                preFilter = NormalizedCondition.All,
                grouping = grouping,
                metrics = NonEmptyList.of(AnalyticsMetric.Sum(alias, field)),
            )
        }
    }
}
