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

package me.ahoo.wow.spi.json.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.api.query.windowFunction
import org.junit.jupiter.api.Test

/** The JSON test of 9.x `AggregationWindowsTest`. */
class AggregationWindowsJsonTest {
    private val mapper = QueryProtocolJson.mapper
    private val byMonth = Sort(QueryField("month"), Sort.Direction.ASC)

    @Test
    fun `windows round trip with their defaults omitted`() {
        val json = """
            {
              "groupBy": [
                {"type": "TERMS", "field": "province", "alias": "province"},
                {"type": "DATE_HISTOGRAM", "field": "createdAt", "alias": "month", "unit": "MONTH"}
              ],
              "metrics": [
                {"type": "NUMERIC", "function": "SUM", "expression": {"field": "amount"}, "alias": "gmv"},
                {"type": "DERIVED", "alias": "share", "expression": {"type": "BINARY", "operator": "DIVIDE",
                  "left": {"type": "METRIC_REF", "metric": "gmv"},
                  "right": {"type": "TOTAL", "metric": "gmv", "within": ["province"]}}},
                {"type": "DERIVED", "alias": "cumulative", "expression":
                  {"type": "RUNNING", "metric": "gmv", "order": {"field": "month", "direction": "ASC"}}},
                {"type": "DERIVED", "alias": "yoy", "expression":
                  {"type": "PREVIOUS", "metric": "gmv", "along": "month", "period": "YEAR"}},
                {"type": "DERIVED", "alias": "rank", "expression": {"type": "RANK", "metric": "gmv"}}
              ]
            }
        """.trimIndent()
        val query = mapper.readValue(json, AggregationQuery::class.java)
        val expressions = query.metrics.filterIsInstance<AggregationMetric.Derived>().map { it.expression }
        expressions.assert().containsExactly(
            DerivedExpression.Binary(
                AggregationExpressionOperator.DIVIDE,
                DerivedExpression.MetricRef("gmv"),
                DerivedExpression.Total("gmv", listOf("province")),
            ),
            DerivedExpression.Running("gmv", byMonth),
            DerivedExpression.Previous("gmv", "month", period = AggregationDateUnit.YEAR),
            DerivedExpression.Rank("gmv"),
        )
        val wire = mapper.writeValueAsString(query)
        wire.assert().contains("""{"type":"TOTAL","metric":"gmv","within":["province"]}""")
        wire.assert().contains(
            """{"type":"RUNNING","metric":"gmv","order":{"field":"month","direction":"ASC"},"function":"SUM"}""",
        )
        wire.assert().contains("""{"type":"PREVIOUS","metric":"gmv","along":"month","shift":1,"period":"YEAR"}""")
        wire.assert().contains("""{"type":"RANK","metric":"gmv","direction":"DESC"}""")
        mapper.readValue(wire, AggregationQuery::class.java).assert().isEqualTo(query)
        expressions.filterIsInstance<DerivedExpression.Window>().map { it.windowFunction }.assert().containsExactly(
            WindowFunction.RUNNING,
            WindowFunction.PREVIOUS,
            WindowFunction.RANK,
        )
    }
}
