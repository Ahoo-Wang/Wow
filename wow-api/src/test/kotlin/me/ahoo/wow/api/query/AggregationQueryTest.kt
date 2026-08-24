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
import tools.jackson.module.kotlin.jsonMapper
import java.time.DateTimeException

class AggregationQueryTest {
    private val jsonMapper = jsonMapper()

    @Test
    fun `field expression should be the default JSON subtype`() {
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

        val query = jsonMapper.readValue(json, AggregationQuery::class.java)
        val metric = query.metrics.single() as AggregationMetric.Numeric

        metric.expression.assert().isEqualTo(AggregationExpression.Field(LogicalField("amount")))
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

        query.elements.map { it.path.value }.assert().containsExactly("state.orders", "lines", "discounts")
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
}
