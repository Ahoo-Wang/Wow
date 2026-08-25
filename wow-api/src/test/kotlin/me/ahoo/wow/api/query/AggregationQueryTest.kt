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
import me.ahoo.wow.api.serialization.MissingTypeImplProblemHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.module.kotlin.jsonMapper
import java.time.DateTimeException
import java.time.ZoneId

class AggregationQueryTest {
    private val jsonMapper = jsonMapper()
    private val mapperWithMissingTypeImpl = jsonMapper {
        addHandler(MissingTypeImplProblemHandler())
    }

    @Test
    fun `bare mapper should reject missing field expression type`() {
        val json = """
            {
              "metrics": [{
                "type": "NUMERIC",
                "function": "SUM",
                "expression": {"field": "amount"},
                "alias": "total"
              }]
            }
        """.trimIndent()

        assertThrows<InvalidTypeIdException> {
            jsonMapper.readValue(json, AggregationQuery::class.java)
        }
    }

    @Test
    fun `unknown expression JSON subtype should fail`() {
        val json = """
            {
              "metrics": [{
                "type": "NUMERIC",
                "function": "SUM",
                "expression": {"type": "UNKNOWN", "field": "amount"},
                "alias": "total"
              }]
            }
        """.trimIndent()

        assertThrows<InvalidTypeIdException> {
            jsonMapper.readValue(json, AggregationQuery::class.java)
        }
    }

    @Test
    fun `arithmetic expression should round trip through JSON`() {
        val json = """
            {
              "metrics": [{
                "type": "NUMERIC",
                "function": "SUM",
                "expression": {
                  "type": "BINARY",
                  "operator": "SUBTRACT",
                  "left": {
                    "type": "BINARY",
                    "operator": "MULTIPLY",
                    "left": {"field": "price"},
                    "right": {"field": "quantity"}
                  },
                  "right": {"type": "CONSTANT", "value": 10.0}
                },
                "alias": "total"
              }]
            }
        """.trimIndent()

        val query = mapperWithMissingTypeImpl.readValue(json, AggregationQuery::class.java)
        val expression = (query.metrics.single() as AggregationMetric.Numeric).expression

        expression.assert().isEqualTo(
            AggregationExpression.Binary(
                AggregationExpressionOperator.SUBTRACT,
                AggregationExpression.Binary(
                    AggregationExpressionOperator.MULTIPLY,
                    AggregationExpression.Field(LogicalField("price")),
                    AggregationExpression.Field(LogicalField("quantity")),
                ),
                AggregationExpression.Constant(10.0),
            ),
        )
        mapperWithMissingTypeImpl.writeValueAsString(query).assert()
            .contains("\"type\":\"BINARY\"")
            .contains("\"type\":\"CONSTANT\"")
    }

    @Test
    fun `constant should require a finite double`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertThrows<IllegalArgumentException> { AggregationExpression.Constant(value) }
        }
    }

    @Test
    fun `query should enforce expression depth and total nodes`() {
        AggregationQuery(
            metrics = listOf(
                AggregationMetric.Numeric(AggregationFunction.SUM, nestedExpression(8), "total"),
            ),
        )
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Numeric(AggregationFunction.SUM, nestedExpression(9), "total"),
                ),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = List(AggregationQuery.MAX_METRICS) { index ->
                    AggregationMetric.Numeric(AggregationFunction.SUM, nestedExpression(3), "metric$index")
                },
            )
        }
    }

    @Test
    fun `query should reject unknown programmatic expression types`() {
        val unknown = object : AggregationExpression {}
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = listOf(AggregationMetric.Numeric(AggregationFunction.SUM, unknown, "total")),
            )
        }
    }

    private fun nestedExpression(depth: Int): AggregationExpression =
        (2..depth).fold<Int, AggregationExpression>(
            AggregationExpression.Field(LogicalField("amount")),
        ) { expression, _ ->
            AggregationExpression.Binary(
                AggregationExpressionOperator.ADD,
                expression,
                AggregationExpression.Constant(1.0),
            )
        }

    @Test
    fun `elements should preserve ordered relative paths`() {
        val query = AggregationQuery(
            elements = listOf(
                AggregationElement(LogicalField("state.orders")),
                AggregationElement(LogicalField("lines")),
                AggregationElement(LogicalField("discounts")),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        query.elements.map { it.path.name }.assert().containsExactly("state.orders", "lines", "discounts")
    }

    @Test
    fun `query should append an ABAC filter`() {
        val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))

        query.appendFilter(TenantIdFilter("tenant")).filter.assert().isEqualTo(TenantIdFilter("tenant"))
    }

    @Test
    fun `invalid aliases and root element filters should fail`() {
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                elements = listOf(AggregationElement(LogicalField("state.orders"), TenantIdFilter("tenant"))),
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(LogicalField("state.status"), "same")),
                metrics = listOf(AggregationMetric.Count("same")),
            )
        }
    }

    @Test
    fun `groups and metrics should reject internal aliases`() {
        assertThrows<IllegalArgumentException> {
            AggregationGroup.Terms(LogicalField("state.status"), "__wow_group")
        }
        assertThrows<IllegalArgumentException> {
            AggregationMetric.Count("__wow_count")
        }
        assertThrows<IllegalArgumentException> {
            AggregationMetric.Numeric(
                AggregationFunction.SUM,
                AggregationExpression.Field(LogicalField("state.amount")),
                "__wow_total",
            )
        }
    }

    @Test
    fun `query should enforce local shape limits and stable group sort`() {
        assertThrows<IllegalArgumentException> { AggregationQuery(metrics = emptyList()) }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                elements = List(AggregationQuery.MAX_ELEMENTS + 1) { AggregationElement(LogicalField("items$it")) },
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = listOf(AggregationMetric.Count("count")),
                limit = AggregationQuery.MAX_LIMIT + 1,
            )
        }

        AggregationQuery(
            groupBy = listOf(
                AggregationGroup.Terms(LogicalField("status"), "status"),
                AggregationGroup.Terms(LogicalField("category"), "category"),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort("category", Sort.Direction.DESC)),
        ).effectiveSort().assert().containsExactly(
            Sort("category", Sort.Direction.DESC),
            Sort("status", Sort.Direction.ASC),
        )
    }

    @Test
    fun `groups should reject invalid local histogram options`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { interval ->
            assertThrows<IllegalArgumentException> {
                AggregationGroup.Histogram(LogicalField("amount"), "band", interval)
            }
        }
        assertThrows<DateTimeException> {
            AggregationGroup.DateHistogram(LogicalField("createdAt"), "day", AggregationDateUnit.DAY, "invalid")
        }
    }

    @Test
    fun `date histogram should use logical field temporal type`() {
        val number = AggregationGroup.DateHistogram(
            field = LogicalField("snapshotTime"),
            alias = "day",
            unit = AggregationDateUnit.DAY,
        )
        number.field.temporalTypeOrDefault().assert()
            .isEqualTo(FieldType.Temporal.NumericEpoch())
        number.timeZone.assert().isEqualTo(ZoneId.systemDefault().id)

        AggregationGroup.DateHistogram(
            field = LogicalField("createdAt", FieldType.Temporal.Date),
            alias = "day",
            unit = AggregationDateUnit.DAY,
        )
    }

    @Test
    fun `date histogram should reject STRING`() {
        assertThrows<IllegalArgumentException> {
            AggregationGroup.DateHistogram(
                field = LogicalField(
                    "createdAt",
                    FieldType.Temporal.FormattedString(datePattern = "yyyy-MM-dd"),
                ),
                alias = "day",
                unit = AggregationDateUnit.DAY,
            )
        }
    }
}
