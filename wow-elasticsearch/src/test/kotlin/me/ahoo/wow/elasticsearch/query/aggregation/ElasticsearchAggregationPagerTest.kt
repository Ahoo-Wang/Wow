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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.aggregation.DenseDateGrid
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class ElasticsearchAggregationPagerTest {

    @Test
    fun `dense gap rows carry empty metric semantics and respect having`() {
        val dayGrid = DenseDateGrid(AggregationDateUnit.DAY, ZoneId.of("UTC"))
        val plan = basePlan(
            dense = DenseBucketPlan(
                alias = "day",
                grid = dayGrid,
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("amount")),
                        "total",
                    ),
                ),
            ),
        )
        val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val day4 = Instant.parse("2026-01-04T00:00:00Z").toEpochMilli()
        val rows = fillGapRows(day1, day4, plan)
        rows.assert().hasSize(2)
        rows.forEach { row ->
            row.path("day").longValue().assert().isGreaterThan(day1).isLessThan(day4)
            row.path("count").longValue().assert().isZero()
            row.path("total").isNull.assert().isTrue()
        }
        val filtered = fillGapRows(
            day1,
            day4,
            plan.copy(having = HavingExpression.Condition("count", ComparisonOperator.GT, 0.0))
        )
        filtered.assert().isEmpty()
    }

    private fun basePlan(
        dense: DenseBucketPlan? = null,
        having: HavingExpression? = null,
    ): ElasticsearchAggregationPlan = ElasticsearchAggregationPlan(
        rootQuery = Query.of { it.matchAll { m -> m } },
        elements = emptyList(),
        groupSources = emptyList(),
        metrics = emptyList(),
        runtimeMappings = emptyMap(),
        effectiveSort = emptyList(),
        limit = 100,
        metricSorted = false,
        having = having,
        dense = dense,
    )
}
