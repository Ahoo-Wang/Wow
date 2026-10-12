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

@Suppress("LargeClass")
class AggregationQueryTest {
    @Test
    fun `aggregation sort should compare query field paths with string aliases`() {
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("status"), Sort.Direction.DESC)),
        )

        query.effectiveSort().assert().containsExactly(Sort(QueryField("status"), Sort.Direction.DESC))
    }

    @Test
    fun `any metric should reject internal aliases`() {
        assertThrows<IllegalArgumentException> {
            AggregationMetric.Any(QueryField("state.productName"), "__wow_productName")
        }
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

    private fun nestedExpression(depth: Int): AggregationExpression =
        (2..depth).fold<Int, AggregationExpression>(
            AggregationExpression.Field(QueryField("amount")),
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
                AggregationElement(QueryField("state.orders")),
                AggregationElement(QueryField("lines")),
                AggregationElement(QueryField("discounts")),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        query.elements.map { it.path.path }.assert().containsExactly("state.orders", "lines", "discounts")
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
                elements = listOf(AggregationElement(QueryField("state.orders"), TenantIdFilter("tenant"))),
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "same")),
                metrics = listOf(AggregationMetric.Count("same")),
            )
        }
    }

    @Test
    fun `groups and metrics should reject internal aliases`() {
        assertThrows<IllegalArgumentException> {
            AggregationGroup.Terms(QueryField("state.status"), "__wow_group")
        }
        assertThrows<IllegalArgumentException> {
            AggregationMetric.Count("__wow_count")
        }
        assertThrows<IllegalArgumentException> {
            AggregationMetric.Numeric(
                AggregationFunction.SUM,
                AggregationExpression.Field(QueryField("state.amount")),
                "__wow_total",
            )
        }
    }

    @Test
    fun `query should enforce local shape limits and stable group sort`() {
        assertThrows<IllegalArgumentException> { AggregationQuery(metrics = emptyList()) }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                elements = List(AggregationQuery.MAX_ELEMENTS + 1) { AggregationElement(QueryField("items$it")) },
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
                AggregationGroup.Terms(QueryField("status"), "status"),
                AggregationGroup.Terms(QueryField("category"), "category"),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("category"), Sort.Direction.DESC)),
        ).effectiveSort().assert().containsExactly(
            Sort(QueryField("category"), Sort.Direction.DESC),
            Sort(QueryField("status"), Sort.Direction.ASC),
        )
    }

    @Test
    fun `with elements the ROOT groups sort before the element groups`() {
        val elements = listOf(AggregationElement(QueryField("state.events")))
        val day = AggregationGroup.DateHistogram(
            QueryField("createTime"),
            "day",
            AggregationDateUnit.DAY,
            scope = AggregationGroupScope.ROOT,
        )
        val name = AggregationGroup.Terms(QueryField("name"), "name")
        val count = listOf(AggregationMetric.Count("n"))
        AggregationQuery(elements = elements, groupBy = listOf(day, name), metrics = count)
            .groupBy.size.assert().isEqualTo(2)
        // A metric may lead: the groups keep their order behind it.
        AggregationQuery(
            elements = elements,
            groupBy = listOf(day, name),
            metrics = count,
            sort = listOf(Sort(QueryField("n"), Sort.Direction.DESC)),
        ).sort.size.assert().isEqualTo(1)
        assertThrows<IllegalArgumentException> {
            AggregationQuery(elements = elements, groupBy = listOf(name, day), metrics = count)
        }.message.assert().isEqualTo("groups with a ROOT scope must sort before the element groups.")
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                elements = elements,
                groupBy = listOf(day, name),
                metrics = count,
                sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC)),
            )
        }
        // Without elements a ROOT group reads like any other.
        AggregationQuery(groupBy = listOf(name, day), metrics = count).groupBy.size.assert().isEqualTo(2)
    }

    @Test
    fun `a ROOT group names a field, not an expression`() {
        val input = AggregationExpression.Field(QueryField("amount"))
        assertThrows<IllegalArgumentException> {
            AggregationGroup.Terms(alias = "t", expression = input, scope = AggregationGroupScope.ROOT)
        }
        assertThrows<IllegalArgumentException> {
            AggregationGroup.Histogram(
                alias = "h",
                interval = 1.0,
                expression = input,
                scope = AggregationGroupScope.ROOT
            )
        }
        AggregationGroup.Histogram(QueryField("amount"), "h", 1.0, scope = AggregationGroupScope.ROOT)
            .scope.assert().isEqualTo(AggregationGroupScope.ROOT)
        AggregationGroup.DatePart(
            QueryField("createTime"),
            "hour",
            AggregationDatePart.HOUR_OF_DAY,
            scope = AggregationGroupScope.ELEMENT,
        ).scope.assert().isEqualTo(AggregationGroupScope.ELEMENT)
    }

    @Test
    fun `groups should reject invalid local histogram options`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { interval ->
            assertThrows<IllegalArgumentException> {
                AggregationGroup.Histogram(QueryField("amount"), "band", interval)
            }
        }
        assertThrows<IllegalArgumentException> {
            AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY, "invalid")
        }
    }

    @Test
    fun `distinct count and percentile should reject internal aliases`() {
        assertThrows<IllegalArgumentException> {
            AggregationMetric.DistinctCount(
                AggregationExpression.Field(QueryField("state.customerId")),
                "__wow_customers",
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationMetric.Percentile(
                AggregationExpression.Field(QueryField("state.amount")),
                95.0,
                "__wow_p95",
            )
        }
    }

    @Test
    fun `percentile should require a finite ratio within exclusive bounds`() {
        listOf(0.0, 100.0, -1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { p ->
            assertThrows<IllegalArgumentException> {
                AggregationMetric.Percentile(
                    AggregationExpression.Field(QueryField("state.amount")),
                    p,
                    "p95",
                )
            }
        }
    }

    @Test
    fun `new metric expressions should count toward expression limits`() {
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = listOf(AggregationMetric.DistinctCount(nestedExpression(9), "customers")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = listOf(AggregationMetric.Percentile(nestedExpression(9), 50.0, "median")),
            )
        }
    }

    @Test
    fun `derived references must be declared earlier`() {
        fun derivedOf(vararg metrics: AggregationMetric) = AggregationQuery(metrics = metrics.toList())
        val paid = AggregationMetric.Count("paid")
        val aov = AggregationMetric.Derived(
            "aov",
            DerivedExpression.Binary(
                AggregationExpressionOperator.ADD,
                DerivedExpression.MetricRef("paid"),
                DerivedExpression.Constant(1.0),
            ),
        )

        assertThrows<IllegalArgumentException> { derivedOf(aov, paid) }.message.assert()
            .contains("must reference a metric declared before it")
        assertThrows<IllegalArgumentException> {
            derivedOf(paid, AggregationMetric.Derived("x", DerivedExpression.MetricRef("unknown")))
        }.message.assert().contains("unknown")

        derivedOf(paid, AggregationMetric.Derived("x", DerivedExpression.MetricRef("paid")))
    }

    @Test
    fun `derived references to any metrics and group aliases are rejected`() {
        val any = AggregationMetric.Any(QueryField("status"), "sample")
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
                metrics = listOf(any, AggregationMetric.Derived("x", DerivedExpression.MetricRef("sample"))),
            )
        }.message.assert().contains("cannot reference ANY metric")
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
                metrics = listOf(
                    AggregationMetric.Count("total"),
                    AggregationMetric.Derived("x", DerivedExpression.MetricRef("status")),
                ),
            )
        }
    }

    @Test
    fun `derived expressions are depth and node bounded`() {
        fun nest(depth: Int): DerivedExpression =
            (1 until depth).fold(DerivedExpression.Constant(1.0) as DerivedExpression) { acc, _ ->
                DerivedExpression.Binary(AggregationExpressionOperator.ADD, acc, DerivedExpression.Constant(1.0))
            }

        assertThrows<IllegalArgumentException> {
            AggregationQuery(metrics = listOf(AggregationMetric.Count("c"), AggregationMetric.Derived("d", nest(9))))
        }.message.assert().contains("depth")
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = List(AggregationQuery.MAX_METRICS) { index ->
                    AggregationMetric.Derived("d$index", nest(AggregationQuery.MAX_EXPRESSION_DEPTH))
                },
            )
        }.message.assert().contains("at most ${AggregationQuery.MAX_EXPRESSION_NODES} nodes")
        assertThrows<IllegalArgumentException> {
            DerivedExpression.Constant(Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `having references and bounds are validated at construction`() {
        fun query(
            having: HavingExpression?,
            groupBy: List<AggregationGroup> = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
        ) = AggregationQuery(
            groupBy = groupBy,
            metrics = listOf(AggregationMetric.Count("paid")),
            having = having,
        )
        val gt = HavingExpression.Condition("paid", ComparisonOperator.GT, 1.0)

        query(HavingExpression.And(listOf(gt, HavingExpression.IsNull("paid"))))
        query(HavingExpression.Condition("paid", ComparisonOperator.GTE, 0.0))

        assertThrows<IllegalArgumentException> { query(gt, groupBy = emptyList()) }
            .message.assert().contains("groupBy")
        assertThrows<IllegalArgumentException> {
            query(HavingExpression.Condition("unknown", ComparisonOperator.GT, 1.0))
        }.message.assert().contains("declared metric alias")
        assertThrows<IllegalArgumentException> {
            query(HavingExpression.Condition("status", ComparisonOperator.GT, 1.0))
        }.message.assert().contains("declared metric alias")
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
                metrics = listOf(AggregationMetric.Any(QueryField("state"), "sample"), AggregationMetric.Count("paid")),
                having = HavingExpression.Condition("sample", ComparisonOperator.GT, 1.0),
            )
        }.message.assert().contains("ANY")
        assertThrows<IllegalArgumentException> {
            query(HavingExpression.Condition("paid", ComparisonOperator.GT, Double.POSITIVE_INFINITY))
        }
        assertThrows<IllegalArgumentException> { query(HavingExpression.Between("paid", 5.0, 1.0)) }
        assertThrows<IllegalArgumentException> { query(HavingExpression.In("paid", emptyList())) }
        assertThrows<IllegalArgumentException> { query(HavingExpression.And(emptyList())) }
    }

    @Test
    fun `having may reference derived metrics regardless of declaration order`() {
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
            metrics = listOf(
                AggregationMetric.Count("total"),
                AggregationMetric.Derived(
                    "half",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.MetricRef("total"),
                        DerivedExpression.Constant(2.0),
                    ),
                ),
            ),
            having = HavingExpression.Condition("half", ComparisonOperator.GTE, 0.5),
        )

        (query.having as HavingExpression.Condition).metric.assert().isEqualTo("half")
    }

    @Test
    fun `terms should reject blank missing key`() {
        val error = assertThrows<IllegalArgumentException> {
            AggregationGroup.Terms(QueryField("productId"), "product", missingKey = " ")
        }
        error.message.assert().isEqualTo("terms missingKey must not be blank.")
    }

    @Test
    fun `dense should require date histogram to be the only group by`() {
        val error = assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        QueryField("createdAt"),
                        "day",
                        AggregationDateUnit.DAY,
                        dense = true
                    ),
                    AggregationGroup.Terms(QueryField("productId"), "product"),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        error.message.assert().isEqualTo("dense requires DATE_HISTOGRAM to be the only groupBy.")
    }
}
