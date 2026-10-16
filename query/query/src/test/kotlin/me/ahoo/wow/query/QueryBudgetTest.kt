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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

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
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", maxResidualGroups = -1) }
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", countLimit = -1) }
            .message.assert().isEqualTo("countLimit must be greater than or equal to 0.")
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", timeout = (-1).seconds) }
            .message.assert().isEqualTo("timeout must not be negative.")
    }

    @Test
    fun `every rejection is a request violation naming its code and the request part it bounds`() {
        val size = QueryErrorCodes.SIZE_OUT_OF_RANGE
        val tooLarge = QueryErrorCodes.FILTER_TOO_LARGE
        val id = IdFilter("id")
        fun rule(check: QueryBudget.() -> Unit) = check
        listOf(
            rule { check(ListQuery(MatchAllFilter, limit = 1001)) } to
                Triple(size, "limit", "HTTP list query limit[1001] must be between 1 and 1000."),
            rule { check(PagedQuery(id, pagination = Pagination(index = 0))) } to
                Triple(size, "pagination.index", "HTTP page index[0] must be greater than or equal to 1."),
            rule { check(PagedQuery(id, pagination = Pagination(index = 1, size = 101))) } to
                Triple(size, "pagination.size", "HTTP page size[101] must be between 1 and 100."),
            rule { check(PagedQuery(id, pagination = Pagination(index = 200, size = 100))) } to
                Triple(size, "pagination", "HTTP page window[20000] must not exceed 10000."),
            rule { check(CursorQuery(id, size = 101)) } to
                Triple(size, "size", "HTTP cursor size[101] must be between 1 and 100."),
            rule { check(ListQuery(ContainsFilter(QueryField("name"), "a"), limit = 1)) } to Triple(
                QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED,
                "filter",
                "HTTP query operator[CONTAINS] is disabled because expensive operators are not allowed.",
            ),
            rule { check(CountQuery(MatchAllFilter)) } to Triple(
                QueryErrorCodes.COUNT_REQUIRES_FILTER,
                "filter",
                "HTTP counting query must not match all documents.",
            ),
            rule { check(ListQuery(IdsFilter((0..1000).map(Int::toString)), limit = 1)) } to
                Triple(tooLarge, "filter", "HTTP query filter values[1001] must not exceed 1000."),
            rule { check(ListQuery(AndFilter(List(128) { IdFilter("$it") }), limit = 1)) } to
                Triple(tooLarge, "filter", "HTTP query filter nodes[129] must not exceed 128."),
        ).forEach { (check, expected) ->
            assertThrows<QueryRequestException> { budget().check() }
                .let { Triple(it.code, it.bindingErrors.single().name, it.message) }
                .assert().isEqualTo(expected)
        }
        // Rejections are request violations: invalid, with the violation they render.
        assertThrows<QueryRequestException> { budget().check(ListQuery(MatchAllFilter, limit = 1001)) }.run {
            errorCode.assert().isEqualTo(QueryErrors.INVALID)
            violation.assert().isInstanceOf(QueryViolation.SizeOutOfRange::class.java)
        }
    }

    @Test
    fun zeroCapsDisableInputLimits() {
        val budget = budget(maxListSize = 0, maxPageSize = 0)
        budget.check(ListQuery(MatchAllFilter, limit = 0))
        budget.check(PagedQuery(IdFilter("id"), pagination = Pagination(size = 1000)))
        budget.check(CursorQuery(IdFilter("id"), size = 1000))
        val ids = IdsFilter(List(1500) { "$it" })
        budget(maxFilterNodes = 0, maxFilterValues = 0).check(ListQuery(AndFilter(List(200) { ids }), limit = 1))
        budget(maxPageWindow = 0).check(PagedQuery(IdFilter("id"), pagination = Pagination(index = 10_000, size = 100)))
    }

    @Test
    fun messagesOpenWithTheBudgetLabel() {
        assertThrows<IllegalArgumentException> {
            QueryBudget("In-process", maxListSize = 1).check(ListQuery(MatchAllFilter, limit = 2))
        }
            .message.assert().isEqualTo("In-process list query limit[2] must be between 1 and 1.")
    }

    @Test
    fun `list default applies the configured size within the cap`() {
        budget().listDefault(10).assert().isEqualTo(10)
        budget().listDefault(5000).assert().isEqualTo(1000)
        budget().listDefault(0).assert().isNull()
        budget(maxListSize = 0).listDefault(10).assert().isNull()
    }

    @Test
    fun `single queries are budgeted on their filter and scope`() {
        expectAllowed(QueryType.SINGLE, SingleQuery(IdFilter("id")))
        expectRejected(QueryType.SINGLE, SingleQuery(ContainsFilter(QueryField("name"), "a")))
        expectRejected(
            QueryType.SINGLE,
            SingleQuery(MatchAllFilter),
            budget(maxFilterNodes = 1, allowExpensiveOperators = true),
            AndFilter(listOf(IdFilter("a"), IdFilter("b"))),
        )
        // A single query is not counting: matching everything is allowed.
        expectAllowed(QueryType.SINGLE, SingleQuery(MatchAllFilter))
    }

    @Test
    fun enforcesListAndFilterBudgetsBeforeInvokingGateway() {
        val aggregateId = QueryField("aggregateId")
        val allowed = ListQuery(AndFilter(List(127) { EqualFilter(aggregateId, it) }), limit = 1)
        expectAllowed(QueryType.LIST, allowed, budget())

        val rejected = listOf(
            ListQuery(MatchAllFilter),
            ListQuery(ContainsFilter(aggregateId, "wow"), limit = 1),
            ListQuery(EndsWithFilter(aggregateId, "wow"), limit = 1),
            ListQuery(StartsWithFilter(aggregateId, ""), limit = 1),
            ListQuery(StartsWithFilter(aggregateId, "wow", StringComparison.CASE_INSENSITIVE), limit = 1),
            ListQuery(NotEqualFilter(aggregateId, "aggregate-id"), limit = 1),
            ListQuery(NotInFilter(aggregateId, listOf("aggregate-id")), limit = 1),
            ListQuery(NorFilter(listOf(EqualFilter(aggregateId, "aggregate-id"))), limit = 1),
            ListQuery(IsNullFilter(aggregateId), limit = 1),
            ListQuery(IsNotNullFilter(aggregateId), limit = 1),
            ListQuery(NotExistsFilter(aggregateId), limit = 1),
            ListQuery(IsEmptyFilter(QueryField("state.items")), limit = 1),
            ListQuery(InFilter(aggregateId, List(1001) { it }), limit = 1),
            ListQuery(IdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(AggregateIdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(AndFilter(List(128) { EqualFilter(aggregateId, it) }), limit = 1),
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

        expectRejected(QueryType.COUNT, CountQuery(MatchAllFilter))
        expectRejected(QueryType.COUNT, CountQuery(DeletionFilter(DeletionState.ALL)))
        expectAllowed(QueryType.COUNT, CountQuery(MatchAllFilter), scope = IdFilter("aggregate-id"))
        expectAllowed(QueryType.COUNT, CountQuery(MatchAllFilter), budget(allowExpensiveOperators = true))
    }

    @Test
    fun `a count limit defaults to the budget's and is capped by it`() {
        QueryBudget.DEFAULT_COUNT_LIMIT.assert().isEqualTo(10_000)
        val budget = QueryBudget(QueryBudget.HTTP_LABEL)
        budget.countLimit.assert().isEqualTo(QueryBudget.DEFAULT_COUNT_LIMIT)
        budget.allowExactCount.assert().isFalse()
        budget.totalLimit.assert().isEqualTo(10_000)
        budget.countLimit(CountQuery(IdFilter("id"))).assert().isEqualTo(10_000)
        budget.countLimit(CountQuery(IdFilter("id"), limit = 5)).assert().isEqualTo(5)

        budget.check(CountQuery(IdFilter("id"), limit = 10_000))
        assertThrows<QueryRequestException> { budget.check(CountQuery(IdFilter("id"), limit = 10_001)) }.run {
            code.assert().isEqualTo(QueryErrorCodes.SIZE_OUT_OF_RANGE)
            bindingErrors.single().name.assert().isEqualTo("limit")
            message.assert().isEqualTo("HTTP count limit[10001] must be between 1 and 10000.")
        }
        val small = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 3)
        small.check(CountQuery(IdFilter("id"), limit = 3))
        assertThrows<QueryRequestException> { small.check(CountQuery(IdFilter("id"), limit = 4)) }
            .bindingErrors.single().name.assert().isEqualTo("limit")
    }

    @Test
    fun `a zero count limit counts exactly and accepts any limit`() {
        val unbounded = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 0)
        unbounded.totalLimit.assert().isNull()
        unbounded.countLimit(CountQuery(IdFilter("id"))).assert().isNull()
        unbounded.countLimit(CountQuery(IdFilter("id"), limit = 7)).assert().isEqualTo(7)
        unbounded.check(CountQuery(IdFilter("id"), limit = Int.MAX_VALUE))
        // Without a bound, an EXACT total is what every total is.
        unbounded.check(PagedQuery(IdFilter("id"), total = TotalMode.EXACT))
    }

    @Test
    fun `an exact total is an expensive operation unless the entry allows it`() {
        val exact = PagedQuery(IdFilter("id"), total = TotalMode.EXACT)
        assertThrows<QueryRequestException> { QueryBudget(QueryBudget.HTTP_LABEL).check(exact) }.run {
            code.assert().isEqualTo(QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED)
            bindingErrors.single().name.assert().isEqualTo("total")
            message.assert().isEqualTo("HTTP exact total is disabled: ask for a BOUNDED total, or count with a limit.")
            violation.assert().isEqualTo(QueryViolation.ExactCountDisabled(QueryBudget.HTTP_LABEL))
        }
        QueryBudget(QueryBudget.HTTP_LABEL, allowExactCount = true).check(exact)
        QueryBudget(QueryBudget.HTTP_LABEL).check(PagedQuery(IdFilter("id"), total = TotalMode.BOUNDED))
        QueryBudget(QueryBudget.HTTP_LABEL).check(PagedQuery(IdFilter("id"), total = TotalMode.NONE))
    }

    @Test
    fun `a paged query without a total is not a counting query`() {
        expectRejected(QueryType.PAGED, PagedQuery(MatchAllFilter))
        expectRejected(QueryType.PAGED, PagedQuery(MatchAllFilter, total = TotalMode.BOUNDED))
        expectAllowed(QueryType.PAGED, PagedQuery(MatchAllFilter, total = TotalMode.NONE))
    }

    @Test
    fun `the budget defaults`() {
        QueryBudget.HTTP_DEFAULT.run {
            label.assert().isEqualTo(QueryBudget.HTTP_LABEL)
            maxListSize.assert().isEqualTo(1000)
            maxPageSize.assert().isEqualTo(100)
            maxPageWindow.assert().isEqualTo(10_000L)
            maxFilterNodes.assert().isEqualTo(QueryBudget.DEFAULT_MAX_FILTER_NODES)
            maxFilterValues.assert().isEqualTo(1000)
            allowExpensiveOperators.assert().isTrue()
            maxResidualGroups.assert().isEqualTo(QueryBudget.DEFAULT_MAX_RESIDUAL_GROUPS)
            timeout.assert().isEqualTo(30.seconds)
        }
        QueryBudget.DEFAULT_TIMEOUT.assert().isEqualTo(30.seconds)
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
        assertThrows<QueryRequestException> { budget.checkFor(queryType, query, scope) }
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
        QueryType.COUNT -> check(query as ICountQuery, scope)
        QueryType.AGGREGATION -> check(query as AggregationQuery, scope)
    }
}
