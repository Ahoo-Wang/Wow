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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryBudgetTest {
    @Test
    fun rejectsInvalidConfiguration() {
        listOf(-1, -1, -1, -1, -1).forEachIndexed { index, value ->
            assertThrows<IllegalArgumentException> {
                QueryBudget(
                    label = "HTTP",
                    maxListSize = if (index == 0) value else 1,
                    maxPageSize = if (index == 1) value else 1,
                    maxPageWindow = if (index == 2) value.toLong() else 1,
                    maxFilterNodes = if (index == 3) value else 1,
                    maxFilterValues = if (index == 4) value else 1,
                )
            }
        }
    }

    @Test
    fun zeroCapsDisableInputLimits() {
        val budget = budget(maxListSize = 0, maxPageSize = 0)
        budget.check(ListQuery(MatchAllFilter, limit = 0))
        budget.check(PagedQuery(IdFilter("id"), pagination = Pagination(size = 1000)))
    }

    @Test
    fun messagesOpenWithTheBudgetLabel() {
        assertThrows<IllegalArgumentException> {
            QueryBudget("In-process", maxListSize = 1).check(ListQuery(MatchAllFilter, limit = 2))
        }
            .message.assert().isEqualTo("In-process list query limit[2] must be between 1 and 1.")
    }

    @Test
    fun enforcesListAndFilterBudgetsBeforeInvokingGateway() {
        val allowed = ListQuery(
            filterExpression { repeat(127) { MessageRecords.AGGREGATE_ID eq it } },
            limit = 1,
        )
        expectAllowed(QueryType.LIST, allowed, budget())

        val rejected = listOf(
            ListQuery(MatchAllFilter),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.containsText("wow") }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.endsWithText("wow") }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.startsWithText("") }, limit = 1),
            ListQuery(
                filterExpression {
                    MessageRecords.AGGREGATE_ID.startsWithText("wow", StringComparison.CASE_INSENSITIVE)
                },
                limit = 1,
            ),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID ne "aggregate-id" }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID notIn listOf("aggregate-id") }, limit = 1),
            ListQuery(filterExpression { nor { MessageRecords.AGGREGATE_ID eq "aggregate-id" } }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.isNull() }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.isNotNull() }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.notExists() }, limit = 1),
            ListQuery(IsEmptyFilter(QueryField("state.items")), limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID isIn List(1001) { it } }, limit = 1),
            ListQuery(IdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(AggregateIdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(filterExpression { repeat(128) { MessageRecords.AGGREGATE_ID eq it } }, limit = 1),
        )
        rejected.forEach { expectRejected(QueryType.LIST, it) }
    }

    @Test
    fun enforcesPageCursorAndCountingBudgets() {
        listOf(
            Pagination(index = 0, size = 1),
            Pagination(index = 1, size = 101),
            Pagination(index = 101, size = 100),
            Pagination(index = Int.MAX_VALUE, size = 100),
        ).forEach { pagination ->
            expectRejected(QueryType.PAGED, PagedQuery(MatchAllFilter, pagination = pagination))
        }
        expectRejected(
            QueryType.PAGED,
            PagedQuery(MatchAllFilter, pagination = Pagination(index = 1_500_000_000, size = 2)),
            budget(maxPageSize = 0, maxPageWindow = Long.MAX_VALUE),
        )
        expectAllowed(
            QueryType.CURSOR,
            CursorQuery(MatchAllFilter, size = 2),
            budget(maxPageSize = 2, maxPageWindow = 1),
        )
        expectRejected(QueryType.CURSOR, CursorQuery(MatchAllFilter, size = 3), budget(maxPageSize = 2))
        expectRejected(
            QueryType.CURSOR,
            CursorQuery(ContainsFilter(QueryField("state.name"), "x")),
        )

        expectRejected(QueryType.COUNT, MatchAllFilter)
        expectRejected(QueryType.COUNT, DeletionFilter(DeletionState.ALL))
        expectAllowed(QueryType.COUNT, MatchAllFilter, scope = IdFilter("aggregate-id"))
        expectAllowed(QueryType.COUNT, MatchAllFilter, budget(allowExpensiveOperators = true))
    }

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

        val scoped = AggregationQuery(
            filter = filterExpression { "state.status" eq "ACTIVE" },
            elements = listOf(
                AggregationElement(
                    path = QueryField("state.orders"),
                    filter = filterExpression { "status" eq "PAID" },
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

        val valueHeavy = scoped.copy(
            filter = filterExpression { "state.status" isIn listOf("ACTIVE", "PAID") },
            elements = emptyList(),
        )
        expectRejected(
            QueryType.AGGREGATION,
            valueHeavy,
            budget(maxFilterValues = 1, allowExpensiveOperators = true),
        )
    }

    @Test
    fun `aggregation metric filters count toward filter value limits`() {
        expectRejected(
            QueryType.AGGREGATION,
            aggregation {
                count("statuses") { "status" isIn listOf("PAID", "SHIPPED", "CANCELLED") }
            },
            budget(maxFilterValues = 2),
        )
    }

    @Test
    fun `having values count toward filter value limits`() {
        expectAllowed(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("c")
                having { "c".isIn(listOf(1.0, 2.0)) }
            },
            budget(maxFilterValues = 2),
        )
        expectRejected(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("c")
                having { "c".isIn(listOf(1.0, 2.0, 3.0)) }
            },
            budget(maxFilterValues = 2),
        )
    }

    @Test
    fun `having nodes count toward filter node limits`() {
        expectAllowed(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("c")
                having { ("c" gt 1.0) and ("c" lt 2.0) }
            },
            // 共享预算含根过滤器节点（默认 MatchAll 计 1）：1 + having 3 = 4
            budget(maxFilterNodes = 4),
        )
        expectRejected(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("c")
                having { ("c" gt 1.0) and (("c" lt 2.0) and ("c" ne 3.0)) }
            },
            budget(maxFilterNodes = 2),
        )
    }

    @Test
    fun `having shares the node budget with filters`() {
        val budget = budget(maxFilterNodes = 5)
        // metric filter And(2 leaves) = 3 nodes, having And(2 conditions) = 3 nodes:
        // each part fits the cap, the combined request (6) must not
        expectRejected(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("paid") {
                    and {
                        "state.status" eq "PAID"
                        "state.status" eq "SHIPPED"
                    }
                }
                having { ("paid" gt 1.0) and ("paid" lt 5.0) }
            },
            budget,
        )
        expectAllowed(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("paid")
                having { ("paid" gt 1.0) and ("paid" lt 5.0) }
            },
            budget,
        )
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
        val derived = aggregation {
            count("total")
            derived("half") { ref("total") / constant(2.0) }
        }
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
                groupBy = listOf(AggregationGroup.DateHistogram(time, "day", AggregationDateUnit.DAY)),
                metrics = listOf(count),
            ),
        )
    }

    private fun budget(
        maxListSize: Int = 1000,
        maxPageSize: Int = 100,
        maxPageWindow: Long = 10_000,
        maxFilterNodes: Int = QueryBudget.DEFAULT_MAX_FILTER_NODES,
        maxFilterValues: Int = 1000,
        allowExpensiveOperators: Boolean = false,
    ) = QueryBudget(
        label = QueryBudget.HTTP_LABEL,
        maxListSize = maxListSize,
        maxPageSize = maxPageSize,
        maxPageWindow = maxPageWindow,
        maxFilterNodes = maxFilterNodes,
        maxFilterValues = maxFilterValues,
        allowExpensiveOperators = allowExpensiveOperators,
    )

    private fun expectRejected(
        queryType: QueryType,
        query: Any,
        budget: QueryBudget = budget(),
        scope: FilterExpression = MatchAllFilter,
    ) {
        assertThrows<IllegalArgumentException> { budget.checkFor(queryType, query, scope) }
    }

    private fun expectAllowed(
        queryType: QueryType,
        query: Any,
        budget: QueryBudget = budget(),
        scope: FilterExpression = MatchAllFilter,
    ) {
        budget.checkFor(queryType, query, scope)
    }

    /** Routes a test query to the typed check its gateway method uses. */
    private fun QueryBudget.checkFor(queryType: QueryType, query: Any, scope: FilterExpression) = when (queryType) {
        QueryType.SINGLE -> check(query as ISingleQuery, scope)
        QueryType.LIST -> check(query as IListQuery, scope)
        QueryType.PAGED -> check(query as IPagedQuery, scope)
        QueryType.CURSOR -> check(query as ICursorQuery, scope)
        QueryType.COUNT -> checkCount(query as FilterExpression, scope)
        QueryType.AGGREGATION -> check(query as AggregationQuery, scope)
    }
}
