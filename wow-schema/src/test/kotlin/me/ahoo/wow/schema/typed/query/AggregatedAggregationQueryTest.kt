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

package me.ahoo.wow.schema.typed.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.schema.TestAggregate
import me.ahoo.wow.schema.typed.SnapshotAggregatedFields
import org.junit.jupiter.api.Test

class AggregatedAggregationQueryTest {
    private val field = object : SnapshotAggregatedFields<TestAggregate> {}

    @Test
    fun `should construct every aggregation schema variant`() {
        val query = AggregatedAggregationQuery(
            groupBy = listOf(
                AggregatedAggregationGroup.Terms(field, "terms"),
                AggregatedAggregationGroup.Histogram(field, "histogram", 1.0),
                AggregatedAggregationGroup.DateHistogram(field, "day", AggregationDateUnit.DAY),
            ),
            metrics = listOf(
                AggregatedAggregationMetric.Count("count"),
                AggregatedAggregationMetric.Sum(field, "sum"),
                AggregatedAggregationMetric.Avg(field, "avg"),
                AggregatedAggregationMetric.Min(field, "min"),
                AggregatedAggregationMetric.Max(field, "max"),
            ),
        )

        query.condition.operator.assert().isEqualTo(Operator.ALL)
        query.groupBy.assert().hasSize(3)
        (query.groupBy[1] as AggregatedAggregationGroup.Histogram).offset.assert().isEqualTo(0.0)
        (query.groupBy[2] as AggregatedAggregationGroup.DateHistogram).timeZone.assert().isEqualTo("UTC")
        query.metrics.map { it.alias }.assert().containsExactly("count", "sum", "avg", "min", "max")
        query.sort.assert().isEmpty()
        query.limit.assert().isEqualTo(AggregationQuery.DEFAULT_LIMIT)
    }
}
