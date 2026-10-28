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
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.databind.exc.InvalidTypeIdException

/** The 17 JSON tests of 9.x `AggregationQueryTest`, read and written by [QueryProtocolJson.mapper]. */
@Suppress("LargeClass")
class AggregationQueryJsonTest {
    private val mapper = QueryProtocolJson.mapper

    private fun read(json: String): AggregationQuery = mapper.readValue(json, AggregationQuery::class.java)

    @Test
    fun `projection and sort should keep string json shapes`() {
        val projection = Projection(include = listOf(QueryField("state.name")))
        val sort = Sort(QueryField("state.createdAt"), Sort.Direction.DESC)

        mapper.writeValueAsString(projection).assert().isEqualTo("{\"include\":[\"state.name\"]}")
        mapper.writeValueAsString(sort).assert().isEqualTo("{\"field\":\"state.createdAt\",\"direction\":\"desc\"}")
    }

    @Test
    fun `omitted query properties should take the constructor defaults`() {
        val query = read("""{"metrics":[{"type":"count","alias":"count"}]}""")

        query.filter.assert().isEqualTo(MatchAllFilter)
        query.elements.assert().isEmpty()
        query.groupBy.assert().isEmpty()
        query.sort.assert().isEmpty()
        query.limit.assert().isEqualTo(100)
        query.having.assert().isNull()
    }

    @Test
    fun `omitted group and window properties should take the constructor defaults`() {
        val query = read(
            """
                {
                  "groupBy": [{"type": "date-histogram", "field": "createdAt", "alias": "day", "unit": "day"}],
                  "metrics": [
                    {"type": "numeric", "function": "sum", "expression": {"field": "amount"}, "alias": "gmv"},
                    {"type": "derived", "alias": "previous", "expression": {"type": "previous", "metric": "gmv", "along": "day"}},
                    {"type": "derived", "alias": "running",
                     "expression": {"type": "running", "metric": "gmv", "order": {"field": "day", "direction": "asc"}}},
                    {"type": "derived", "alias": "rank", "expression": {"type": "rank", "metric": "gmv"}}
                  ]
                }
            """.trimIndent(),
        )

        val day = query.groupBy.single() as AggregationGroup.DateHistogram
        day.timeZone.assert().isEqualTo("UTC")
        day.dense.assert().isFalse()
        val windows = query.metrics.filterIsInstance<AggregationMetric.Derived>().map { it.expression }
        windows.assert().containsExactly(
            DerivedExpression.Previous("gmv", "day", shift = 1),
            DerivedExpression.Running(
                "gmv",
                Sort(QueryField("day"), Sort.Direction.ASC),
                function = RunningFunction.SUM
            ),
            DerivedExpression.Rank("gmv", direction = Sort.Direction.DESC),
        )
    }

    @Test
    fun `explicit null should be rejected`() {
        listOf(
            """{"filter": null, "metrics": [{"type": "count", "alias": "count"}]}""",
            """{"limit": null, "metrics": [{"type": "count", "alias": "count"}]}""",
            """{"metrics": null}""",
            """{"metrics": [{"type": "count", "alias": "count", "filter": null}]}""",
        ).forEach { json ->
            assertThrows<JacksonException> { read(json) }
        }
    }

    @Test
    fun `missing metrics should be rejected`() {
        assertThrows<JacksonException> { read("{}") }
    }

    @Test
    fun `an expression without type should read as a field`() {
        val query = read(
            """
                {
                  "metrics": [{
                    "type": "numeric",
                    "function": "sum",
                    "expression": {"field": "amount"},
                    "alias": "total"
                  }]
                }
            """.trimIndent(),
        )

        (query.metrics.single() as AggregationMetric.Numeric).expression
            .assert().isEqualTo(AggregationExpression.Field(QueryField("amount")))
        mapper.readValue("""{"field":"amount"}""", AggregationExpression::class.java)
            .assert().isEqualTo(AggregationExpression.Field(QueryField("amount")))
    }

    @Test
    fun `a missing type of the other hierarchies should fail`() {
        listOf(
            """{"metrics": [{"alias": "count"}]}""",
            """{"groupBy": [{"field": "status", "alias": "status"}], "metrics": [{"type": "count", "alias": "count"}]}""",
        ).forEach { json ->
            assertThrows<InvalidTypeIdException> { read(json) }
        }
    }

    @Test
    fun `unknown expression JSON subtype should fail`() {
        val json = """
            {
              "metrics": [{
                "type": "numeric",
                "function": "sum",
                "expression": {"type": "unknown", "field": "amount"},
                "alias": "total"
              }]
            }
        """.trimIndent()

        assertThrows<InvalidTypeIdException> { read(json) }
    }

    @Test
    fun `arithmetic expression should round trip through JSON`() {
        val json = """
            {
              "metrics": [{
                "type": "numeric",
                "function": "sum",
                "expression": {
                  "type": "binary",
                  "operator": "subtract",
                  "left": {
                    "type": "binary",
                    "operator": "multiply",
                    "left": {"field": "price"},
                    "right": {"field": "quantity"}
                  },
                  "right": {"type": "constant", "value": 10.0}
                },
                "alias": "total"
              }]
            }
        """.trimIndent()

        val query = read(json)
        val expression = (query.metrics.single() as AggregationMetric.Numeric).expression

        expression.assert().isEqualTo(
            AggregationExpression.Binary(
                AggregationExpressionOperator.SUBTRACT,
                AggregationExpression.Binary(
                    AggregationExpressionOperator.MULTIPLY,
                    AggregationExpression.Field(QueryField("price")),
                    AggregationExpression.Field(QueryField("quantity")),
                ),
                AggregationExpression.Constant(10.0),
            ),
        )
        mapper.writeValueAsString(query).assert().isEqualTo(
            """{"filter":{"op":"match-all"},"metrics":[{"type":"numeric","function":"sum","expression":""" +
                """{"type":"binary","operator":"subtract","left":{"type":"binary","operator":"multiply",""" +
                """"left":{"type":"field","field":"price"},"right":{"type":"field","field":"quantity"}},""" +
                """"right":{"type":"constant","value":10.0}},"alias":"total"}],"limit":100}""",
        )
    }

    @Test
    fun `any metric should round trip through JSON`() {
        val json = """{"metrics": [{"type": "any", "field": "state.productName", "alias": "productName"}]}"""

        val query = read(json)

        query.metrics.assert().containsExactly(AggregationMetric.Any(QueryField("state.productName"), "productName"))
        mapper.writeValueAsString(
            query
        ).assert().contains("""{"type":"any","field":"state.productName","alias":"productName"}""")
    }

    @Test
    fun `distinct count and percentile metrics should round trip through JSON`() {
        val json = """
            {
              "metrics": [
                {"type": "distinct-count", "expression": {"field": "state.customerId"}, "alias": "customers"},
                {"type": "percentile", "expression": {"field": "state.amount"}, "percentile": 95.0, "alias": "p95"},
                {"type": "numeric", "function": "stddev", "expression": {"field": "state.amount"}, "alias": "stddev"},
                {"type": "numeric", "function": "variance", "expression": {"field": "state.amount"}, "alias": "variance"}
              ]
            }
        """.trimIndent()

        val query = read(json)

        query.metrics.assert().containsExactly(
            AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField("state.customerId")), "customers"),
            AggregationMetric.Percentile(AggregationExpression.Field(QueryField("state.amount")), 95.0, "p95"),
            AggregationMetric.Numeric(
                AggregationFunction.STDDEV,
                AggregationExpression.Field(QueryField("state.amount")),
                "stddev",
            ),
            AggregationMetric.Numeric(
                AggregationFunction.VARIANCE,
                AggregationExpression.Field(QueryField("state.amount")),
                "variance",
            ),
        )
        mapper.writeValueAsString(query).assert()
            .contains("\"type\":\"distinct-count\"")
            .contains("\"type\":\"percentile\"")
        read(mapper.writeValueAsString(query)).assert().isEqualTo(query)
    }

    @Test
    fun `unknown metric JSON subtype should fail`() {
        val json = """{"metrics": [{"type": "QUANTILE", "expression": {"field": "amount"}, "alias": "q"}]}"""

        assertThrows<InvalidTypeIdException> { read(json) }
    }

    @Test
    fun `metric filters should round trip and omit defaults`() {
        val json = """
            {
              "metrics": [
                {"type": "count", "alias": "total"},
                {"type": "count", "filter": {"op": "eq", "field": "status", "value": "PAID"}, "alias": "paid"},
                {"type": "numeric", "function": "sum", "expression": {"field": "amount"},
                 "filter": {"op": "eq", "field": "status", "value": "PAID"}, "alias": "paidAmount"}
              ]
            }
        """.trimIndent()

        val query = read(json)
        query.metrics.filterIsInstance<AggregationMetric.Count>().assert().hasSize(2)
        query.metrics[1].filter.assert().isEqualTo(EqualFilter(QueryField("status"), "PAID"))
        query.metrics[2].filter.assert().isEqualTo(EqualFilter(QueryField("status"), "PAID"))
        query.metrics[0].filter.assert().isEqualTo(MatchAllFilter)
        mapper.writeValueAsString(query).assert().isEqualTo(
            """{"filter":{"op":"match-all"},"metrics":[{"type":"count","alias":"total"},""" +
                """{"type":"count","alias":"paid","filter":{"field":"status","value":"PAID","op":"eq"}},""" +
                """{"type":"numeric","function":"sum","expression":{"type":"field","field":"amount"},""" +
                """"alias":"paidAmount","filter":{"field":"status","value":"PAID","op":"eq"}}],"limit":100}""",
        )
    }

    @Test
    fun `explicit MATCH_ALL metric filters should deserialize and re-serialize omitted`() {
        val json = """
            {
              "metrics": [
                {"type": "count", "alias": "total", "filter": {"op": "match-all"}},
                {"type": "numeric", "function": "sum", "expression": {"field": "amount"}, "alias": "sumAmount",
                 "filter": {"op": "match-all"}}
              ]
            }
        """.trimIndent()

        val explicit = read(json)
        explicit.metrics.forEach { metric -> metric.filter.assert().isEqualTo(MatchAllFilter) }
        val omitted = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Count("total"),
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field(QueryField("amount")),
                    "sumAmount",
                ),
            ),
        )

        mapper.writeValueAsString(explicit).assert().isEqualTo(mapper.writeValueAsString(omitted))
        mapper.writeValueAsString(explicit).assert()
            .doesNotContain("\"alias\":\"total\",\"filter\"")
            .doesNotContain("\"alias\":\"sumAmount\",\"filter\"")
    }

    @Test
    fun `derived metrics should round trip with expression subtypes`() {
        val json = """
            {
              "metrics": [
                {"type": "count", "alias": "paid"},
                {"type": "numeric", "function": "sum", "expression": {"field": "amount"}, "alias": "x"},
                {"type": "derived", "alias": "aov", "filter": {"op": "match-none"},
                 "expression": {"type": "binary", "operator": "divide",
                   "left": {"type": "metric-ref", "metric": "paid"},
                   "right": {"type": "constant", "value": 2.0}}}
              ]
            }
        """.trimIndent()

        val query = read(json)
        val derived = query.metrics.filterIsInstance<AggregationMetric.Derived>().single()

        derived.expression.assert().isEqualTo(
            DerivedExpression.Binary(
                AggregationExpressionOperator.DIVIDE,
                DerivedExpression.MetricRef("paid"),
                DerivedExpression.Constant(2.0),
            ),
        )
        derived.filter.assert().isEqualTo(MatchAllFilter)
        val wire = mapper.writeValueAsString(query)
        wire.assert().contains(
            """{"type":"derived","alias":"aov","expression":{"type":"binary","operator":"divide",""" +
                """"left":{"type":"metric-ref","metric":"paid"},"right":{"type":"constant","value":2.0}}}""",
        )
        wire.substring(wire.indexOf("\"derived\"")).assert().doesNotContain("\"filter\"")
    }

    @Test
    fun `having should round trip and omit when absent`() {
        val json = """
            {
              "groupBy": [{"type": "terms", "field": "status", "alias": "status"}],
              "metrics": [{"type": "count", "alias": "paid"}],
              "having": {"type": "and", "operands": [
                {"type": "condition", "metric": "paid", "operator": "gt", "value": 10.0},
                {"type": "is-null", "metric": "paid", "negated": true}
              ]}
            }
        """.trimIndent()

        val query = read(json)
        val having = requireNotNull(query.having) as HavingExpression.And

        having.operands.assert().containsExactly(
            HavingExpression.Condition("paid", ComparisonOperator.GT, 10.0),
            HavingExpression.IsNull("paid", negated = true),
        )
        mapper.writeValueAsString(query).assert().contains(
            """"having":{"type":"and","operands":[{"type":"condition","metric":"paid","operator":"gt","value":10.0},""" +
                """{"type":"is-null","metric":"paid","negated":true}]}""",
        )
        read(
            """{"groupBy":[{"type":"terms","field":"status","alias":"status"}],"metrics":[{"type":"count","alias":"paid"}],"having":{"type":"is-null","metric":"paid"}}"""
        )
            .having.assert().isEqualTo(HavingExpression.IsNull("paid"))

        val plain = mapper.writeValueAsString(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
                metrics = listOf(AggregationMetric.Count("paid")),
            ),
        )
        plain.assert().doesNotContain("\"having\"")
    }

    @Test
    fun `a group's scope is written only when it reads the root`() {
        val root = AggregationGroup.DateHistogram(
            QueryField("createTime"),
            "day",
            AggregationDateUnit.DAY,
            scope = AggregationGroupScope.ROOT,
        )
        val element = AggregationGroup.Terms(QueryField("name"), "name")
        val json = mapper.writeValueAsString(
            AggregationQuery(groupBy = listOf(root, element), metrics = listOf(AggregationMetric.Count("n"))),
        )
        json.assert().contains("\"scope\":\"root\"")
        json.split("\"scope\"").size.assert().isEqualTo(2)
        read(json).groupBy.map { it.scope }.assert().containsExactly(AggregationGroupScope.ROOT, null)
    }

    @Test
    fun `bucket options should keep wire shapes additive`() {
        mapper.writeValueAsString(AggregationGroup.Terms(QueryField("productId"), "product"))
            .assert().isEqualTo("""{"type":"terms","field":"productId","alias":"product"}""")
        mapper.writeValueAsString(
            AggregationGroup.Terms(QueryField("productId"), "product", missingKey = "__missing__"),
        ).assert().isEqualTo("""{"type":"terms","field":"productId","alias":"product","missingKey":"__missing__"}""")
        mapper.writeValueAsString(
            AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY),
        ).assert().isEqualTo(
            """{"type":"date-histogram","field":"createdAt","alias":"day","unit":"day","timeZone":"UTC"}""",
        )
        mapper.writeValueAsString(
            AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY, dense = true),
        ).assert().isEqualTo(
            """{"type":"date-histogram","field":"createdAt","alias":"day","unit":"day","timeZone":"UTC","dense":true}""",
        )
    }

    @Test
    fun `bucket options should round trip through json`() {
        val termsJson = """
            {
              "groupBy": [
                {"type": "terms", "field": "productName", "alias": "name", "missingKey": "UNKNOWN"}
              ],
              "metrics": [{"type": "count", "alias": "count"}]
            }
        """.trimIndent()
        val denseJson = """
            {
              "groupBy": [
                {"type": "date-histogram", "field": "createdAt", "alias": "day", "unit": "month",
                 "timeZone": "Asia/Shanghai", "dense": true}
              ],
              "metrics": [{"type": "count", "alias": "count"}]
            }
        """.trimIndent()
        (read(termsJson).groupBy[0] as AggregationGroup.Terms).missingKey.assert().isEqualTo("UNKNOWN")
        (read(denseJson).groupBy[0] as AggregationGroup.DateHistogram).let { dense ->
            dense.timeZone.assert().isEqualTo("Asia/Shanghai")
            dense.dense.assert().isTrue()
        }
    }
}
