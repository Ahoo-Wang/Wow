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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryBudgetFixtures.budget
import me.ahoo.wow.query.QueryBudgetFixtures.expectAllowed
import me.ahoo.wow.query.QueryBudgetFixtures.expectRejected
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What a budget allows an aggregation: its work, the combined scope quota, HAVING and metric filters, expensive constructs. */
class QueryAggregationBudgetTest {
    @Test
    fun validatesAggregationWorkAndCombinedScopeQuota() {
        val metricSort = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("count"), Sort.Direction.ASC)),
            limit = 1,
        )
        val computed = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.MULTIPLY,
                        AggregationExpression.Field(QueryField("state.price")),
                        AggregationExpression.Field(QueryField("state.quantity")),
                    ),
                    "total",
                ),
            ),
            limit = 1,
        )
        val element = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("state.orders"))),
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 1,
        )
        listOf(metricSort, computed, element).forEach {
            expectRejected(QueryType.AGGREGATION, it)
        }
        assertThrows<QueryRequestException> { budget().check(metricSort) }.bindingErrors.single().name
            .assert().isEqualTo("sort")
        assertThrows<QueryRequestException> { budget().check(element) }.message.assert()
            .isEqualTo("HTTP aggregation elements are disabled because expensive operators are not allowed.")
        assertThrows<QueryRequestException> { budget().check(computed) }.message.assert()
            .isEqualTo(
                "HTTP aggregation arithmetic expressions are disabled because expensive operators are not allowed."
            )
    }

    @Test
    fun validatesCombinedScopeQuotaOfAggregations() {
        val scoped = AggregationQuery(
            filter = EqualFilter(QueryField("state.status"), "ACTIVE"),
            elements = listOf(
                AggregationElement(
                    path = QueryField("state.orders"),
                    filter = EqualFilter(QueryField("status"), "PAID"),
                ),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 1,
        )
        expectRejected(
            QueryType.AGGREGATION,
            scoped,
            budget(maxFilterNodes = 2, allowExpensiveOperators = true),
            TenantIdFilter("tenant-id"),
        )
        expectAllowed(
            QueryType.AGGREGATION,
            scoped,
            budget(maxFilterNodes = 3, allowExpensiveOperators = true),
            TenantIdFilter("tenant-id"),
        )

        val valueHeavy = scoped.copy(
            filter = InFilter(QueryField("state.status"), listOf("ACTIVE", "PAID")),
            elements = emptyList(),
        )
        expectRejected(
            QueryType.AGGREGATION,
            valueHeavy,
            budget(maxFilterValues = 1, allowExpensiveOperators = true),
        )
        expectRejected(
            QueryType.AGGREGATION,
            AggregationQuery(metrics = listOf(AggregationMetric.Count("count")), limit = 1001),
        )
    }

    @Test
    fun `aggregation metric filters count toward filter value limits`() {
        expectRejected(
            QueryType.AGGREGATION,
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Count(
                        "statuses",
                        InFilter(QueryField("status"), listOf("PAID", "SHIPPED", "CANCELLED")),
                    ),
                ),
            ),
            budget(maxFilterValues = 2),
        )
    }

    @Test
    fun `having values count toward filter value limits`() {
        expectAllowed(
            QueryType.AGGREGATION,
            havingQuery("c", HavingExpression.In("c", listOf(1.0, 2.0))),
            budget(maxFilterValues = 2),
        )
        assertThrows<QueryRequestException> {
            budget(maxFilterValues = 2).check(havingQuery("c", HavingExpression.In("c", listOf(1.0, 2.0, 3.0))))
        }.run {
            code.assert().isEqualTo(QueryErrorCodes.FILTER_TOO_LARGE)
            bindingErrors.single().name.assert().isEqualTo("having")
            message.assert().isEqualTo("HTTP having values[3] must not exceed 2.")
        }
    }

    @Test
    fun `having nodes count toward filter node limits`() {
        expectAllowed(
            QueryType.AGGREGATION,
            havingQuery(
                "c",
                HavingExpression.And(listOf(gt("c", 1.0), lt("c", 2.0))),
            ),
            // The shared budget counts the root filter node (MatchAll counts 1): 1 + 3 having nodes = 4.
            budget(maxFilterNodes = 4),
        )
        assertThrows<QueryRequestException> {
            budget(maxFilterNodes = 2).check(
                havingQuery(
                    "c",
                    HavingExpression.And(
                        listOf(
                            gt("c", 1.0),
                            HavingExpression.And(
                                listOf(lt("c", 2.0), HavingExpression.Condition("c", ComparisonOperator.NE, 3.0)),
                            ),
                        ),
                    ),
                ),
            )
        }.message.assert().isEqualTo("HTTP filter and having nodes[3] must not exceed 2.")
    }

    @Test
    fun `having shares the node budget with filters`() {
        val budget = budget(maxFilterNodes = 5)
        val status = QueryField("state.status")
        val having = HavingExpression.And(listOf(gt("paid", 1.0), lt("paid", 5.0)))
        // metric filter And(2 leaves) = 3 nodes, having And(2 conditions) = 3 nodes:
        // each part fits the cap, the combined request (6) must not
        expectRejected(
            QueryType.AGGREGATION,
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(status, "status")),
                metrics = listOf(
                    AggregationMetric.Count(
                        "paid",
                        AndFilter(listOf(EqualFilter(status, "PAID"), EqualFilter(status, "SHIPPED"))),
                    ),
                ),
                having = having,
            ),
            budget,
        )
        expectAllowed(QueryType.AGGREGATION, havingQuery("paid", having), budget)
    }

    @Test
    fun rejectsArithmeticExpressionsOnAllExpressionBearingMetrics() {
        val distinctCountArithmetic = AggregationQuery(
            metrics = listOf(
                AggregationMetric.DistinctCount(
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.ADD,
                        AggregationExpression.Field(QueryField("state.amount")),
                        AggregationExpression.Constant(0.0),
                    ),
                    "amounts",
                ),
            ),
            limit = 1,
        )
        val percentileArithmetic = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Percentile(
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.MULTIPLY,
                        AggregationExpression.Field(QueryField("state.amount")),
                        AggregationExpression.Constant(1.0),
                    ),
                    95.0,
                    "p95",
                ),
            ),
            limit = 1,
        )
        listOf(distinctCountArithmetic, percentileArithmetic).forEach {
            expectRejected(QueryType.AGGREGATION, it)
        }

        val fieldOnly = AggregationQuery(
            metrics = listOf(
                AggregationMetric.DistinctCount(
                    AggregationExpression.Field(QueryField("state.amount")),
                    "amounts",
                ),
                AggregationMetric.Percentile(
                    AggregationExpression.Field(QueryField("state.amount")),
                    95.0,
                    "p95",
                ),
            ),
            limit = 1,
        )
        expectAllowed(QueryType.AGGREGATION, fieldOnly)
    }

    @Test
    fun `derived metrics count as arithmetic expressions`() {
        val derived = AggregationQuery(
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
        )
        expectRejected(QueryType.AGGREGATION, derived, budget(allowExpensiveOperators = false))
        expectAllowed(QueryType.AGGREGATION, derived, budget(allowExpensiveOperators = true))
    }

    @Test
    fun `date parts, dense fill and first or last are expensive aggregation constructs`() {
        val time = QueryField("state.createdAt")
        val count = AggregationMetric.Count("count")
        mapOf(
            "HTTP aggregation group[DATE_PART] is disabled because expensive operators are not allowed." to
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.DatePart(time, "hour", AggregationDatePart.HOUR_OF_DAY)),
                    metrics = listOf(count),
                ),
            "HTTP aggregation dense fill is disabled because expensive operators are not allowed." to
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.DateHistogram(time, "day", AggregationDateUnit.DAY, dense = true)),
                    metrics = listOf(count),
                ),
            "HTTP aggregation expression groups are disabled because expensive operators are not allowed." to
                AggregationQuery(
                    groupBy = listOf(
                        AggregationGroup.Histogram(
                            alias = "bucket",
                            interval = 1.0,
                            expression = AggregationExpression.Binary(
                                AggregationExpressionOperator.ADD,
                                AggregationExpression.Field(QueryField("state.price")),
                                AggregationExpression.Constant(1.0),
                            ),
                        ),
                    ),
                    metrics = listOf(count),
                ),
            "HTTP aggregation metric[FIRST] is disabled because expensive operators are not allowed." to
                AggregationQuery(metrics = listOf(AggregationMetric.First(QueryField("state.price"), "open"))),
            "HTTP aggregation metric[LAST] is disabled because expensive operators are not allowed." to
                AggregationQuery(metrics = listOf(AggregationMetric.Last(QueryField("state.price"), "close"))),
        ).forEach { (message, query) ->
            assertThrows<IllegalArgumentException> { budget().check(query) }.message.assert().isEqualTo(message)
            expectAllowed(QueryType.AGGREGATION, query, budget(allowExpensiveOperators = true))
        }
        expectAllowed(
            QueryType.AGGREGATION,
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(time, "day", AggregationDateUnit.DAY),
                    AggregationGroup.Terms(QueryField("state.status"), "status"),
                ),
                metrics = listOf(
                    count,
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("state.price")),
                        "sum",
                    ),
                ),
            ),
        )
    }

    private fun gt(metric: String, value: Double) = HavingExpression.Condition(metric, ComparisonOperator.GT, value)

    private fun lt(metric: String, value: Double) = HavingExpression.Condition(metric, ComparisonOperator.LT, value)

    private fun havingQuery(metric: String, having: HavingExpression) = AggregationQuery(
        groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
        metrics = listOf(AggregationMetric.Count(metric)),
        having = having,
    )
}
