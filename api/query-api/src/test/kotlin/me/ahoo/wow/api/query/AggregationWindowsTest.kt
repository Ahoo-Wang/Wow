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

class AggregationWindowsTest {
    private val province = AggregationGroup.Terms(QueryField("province"), "province")
    private val city = AggregationGroup.Terms(QueryField("city"), "city")
    private val month = AggregationGroup.DateHistogram(QueryField("createdAt"), "month", AggregationDateUnit.MONTH)
    private val gmv = AggregationMetric.Numeric(
        AggregationFunction.SUM,
        AggregationExpression.Field(QueryField("amount")),
        "gmv",
    )
    private val average = AggregationMetric.Numeric(
        AggregationFunction.AVG,
        AggregationExpression.Field(QueryField("amount")),
        "average",
    )
    private val byMonth = Sort(QueryField("month"), Sort.Direction.ASC)

    private fun windowed(vararg windows: DerivedExpression, groupBy: List<AggregationGroup> = listOf(province, month)) =
        AggregationQuery(
            groupBy = groupBy,
            metrics = listOf(gmv, average, AggregationMetric.Any(QueryField("city"), "sample")) +
                windows.mapIndexed { index, window -> AggregationMetric.Derived("w$index", window) },
        )

    private fun rejects(message: String, vararg windows: DerivedExpression) {
        assertThrows<IllegalArgumentException> { windowed(*windows) }.message.assert().contains(message)
    }

    @Test
    fun `windows accept what they can compute`() {
        windowed(
            DerivedExpression.Total("gmv", listOf("province")),
            DerivedExpression.Running("gmv", byMonth, listOf("province"), frame = 3),
            DerivedExpression.Running(
                "average",
                Sort(QueryField("gmv"), Sort.Direction.DESC),
                function = RunningFunction.AVG
            ),
            DerivedExpression.Previous("gmv", "month", shift = 2),
            DerivedExpression.Previous("average", "month", period = AggregationDateUnit.QUARTER),
            DerivedExpression.Rank("average", listOf("month"), Sort.Direction.ASC),
        ).metrics.assert().hasSize(9)
        AggregationQuery(
            metrics = listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Derived("t", DerivedExpression.Total("count"))
            )
        )
            .metrics.assert().hasSize(2)
    }

    @Test
    fun `a window reads a non-derived numeric metric declared before it`() {
        rejects("must read a metric declared before it, but was [unknown]", DerivedExpression.Rank("unknown"))
        rejects("must read a non-derived numeric metric, but was [sample]", DerivedExpression.Rank("sample"))
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(province),
                metrics = listOf(
                    gmv,
                    AggregationMetric.Derived("double", DerivedExpression.MetricRef("gmv")),
                    AggregationMetric.Derived("rank", DerivedExpression.Rank("double")),
                ),
            )
        }.message.assert().contains("non-derived numeric metric, but was [double]")
    }

    @Test
    fun `totals and running sums read only COUNT and SUM`() {
        rejects("TOTAL window of derived metric [w0] reads only COUNT or SUM", DerivedExpression.Total("average"))
        rejects(
            "RUNNING SUM window of derived metric [w0] reads only COUNT or SUM",
            DerivedExpression.Running("average", byMonth)
        )
    }

    @Test
    fun `windows name only groups of the query`() {
        rejects("must partition by groups of the query, but was [city]", DerivedExpression.Total("gmv", listOf("city")))
        rejects("must not repeat a partition group", DerivedExpression.Rank("gmv", listOf("province", "province")))
        rejects(
            "must order by a group or a non-derived numeric metric, but was [sample]",
            DerivedExpression.Running("gmv", Sort(QueryField("sample"), Sort.Direction.ASC)),
        )
        rejects(
            "must order by a group or a non-derived numeric metric, but was [unknown]",
            DerivedExpression.Running("gmv", Sort(QueryField("unknown"), Sort.Direction.ASC)),
        )
        rejects(
            "cannot order by its partition group [month]",
            DerivedExpression.Running("gmv", byMonth, listOf("month")),
        )
        rejects(
            "must step along a DATE_HISTOGRAM group, but was [province]",
            DerivedExpression.Previous("gmv", "province")
        )
        rejects(
            "must step along a DATE_HISTOGRAM group, but was [unknown]",
            DerivedExpression.Previous("gmv", "unknown")
        )
    }

    @Test
    fun `previous steps by a period its group divides`() {
        rejects(
            "cannot step a MONTH group by WEEK",
            DerivedExpression.Previous("gmv", "month", period = AggregationDateUnit.WEEK)
        )
        rejects(
            "cannot step a MONTH group by DAY",
            DerivedExpression.Previous("gmv", "month", period = AggregationDateUnit.DAY)
        )
        val week = AggregationGroup.DateHistogram(QueryField("createdAt"), "week", AggregationDateUnit.WEEK)
        assertThrows<IllegalArgumentException> {
            windowed(
                DerivedExpression.Previous("gmv", "week", period = AggregationDateUnit.MONTH),
                groupBy = listOf(week)
            )
        }
        val day = AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY)
        windowed(
            DerivedExpression.Previous("gmv", "day", period = AggregationDateUnit.WEEK),
            groupBy = listOf(city, day)
        )
            .metrics.assert().hasSize(4)
    }

    @Test
    fun `window parameters are bounded`() {
        assertThrows<IllegalArgumentException> { DerivedExpression.Previous("gmv", "month", shift = 0) }
        assertThrows<IllegalArgumentException> {
            DerivedExpression.Previous("gmv", "month", shift = DerivedExpression.MAX_SHIFT + 1)
        }
        assertThrows<IllegalArgumentException> { DerivedExpression.Running("gmv", byMonth, frame = 0) }
        assertThrows<IllegalArgumentException> {
            DerivedExpression.Running("gmv", byMonth, frame = DerivedExpression.MAX_FRAME + 1)
        }
    }

    @Test
    fun `an aggregation computes at most MAX_WINDOWS windows`() {
        val windows = List(DerivedExpression.MAX_WINDOWS) { DerivedExpression.Rank("gmv") }
        windowed(*windows.toTypedArray()).metrics.assert().hasSize(DerivedExpression.MAX_WINDOWS + 3)
        rejects(
            "at most ${DerivedExpression.MAX_WINDOWS} windows",
            *(windows + DerivedExpression.Rank("gmv")).toTypedArray(),
        )
    }
}
