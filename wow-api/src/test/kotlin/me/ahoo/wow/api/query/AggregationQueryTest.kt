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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AggregationQueryTest {
    @Test
    fun `should create a valid multi-dimensional aggregation`() {
        val query = AggregationQuery(
            groupBy = listOf(
                AggregationGroup.Terms("state.country", "country"),
                AggregationGroup.DateHistogram(
                    field = "eventTime",
                    alias = "month",
                    unit = AggregationDateUnit.MONTH,
                    timeZone = "Asia/Shanghai",
                ),
                AggregationGroup.Histogram("state.totalAmount", "amountBand", 100.0),
            ),
            metrics = listOf(
                AggregationMetric.Count("orderCount"),
                AggregationMetric.Sum("state.totalAmount", "totalAmount"),
            ),
            sort = listOf(Sort("totalAmount", Sort.Direction.DESC)),
            limit = 10,
        )

        query.limit.assert().isEqualTo(10)
        query.groupBy.assert().hasSize(3)
        query.metrics.assert().hasSize(2)
        query.withCondition(Condition.id("id")).condition.assert().isEqualTo(Condition.id("id"))
    }

    @Test
    fun `should reject invalid aggregation contracts`() {
        assertThrows<IllegalArgumentException> {
            AggregationQuery(metrics = emptyList())
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.status", "value")),
                metrics = listOf(AggregationMetric.Count("value")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort("count", Sort.Direction.ASC)),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.status", "status")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort("missing", Sort.Direction.ASC)),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram("state.totalAmount", "band", 0.0)),
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.status", "bad.alias")),
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.status", "status")),
                metrics = listOf(AggregationMetric.Count("count")),
                limit = AggregationQuery.MAX_LIMIT + 1,
            )
        }
    }
}
