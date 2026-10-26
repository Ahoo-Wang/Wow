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

import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.api.query.YesterdayFilter
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * The values the golden wire file (`json/query-golden.tsv`) holds the JSON of, one per line by name. The 9.x half was
 * written by 9.x `JsonSerializer` from the same constructors (filter values as `JsonNode`); `v` is where 9.x wrapped a
 * value into a tree.
 */
internal object QueryJsonGoldenCases {
    private fun v(value: Any?): Any? = value
    private val sku = QueryField("state.sku")
    private val items = QueryField("state.items")
    private val created = QueryField("state.createTime")

    @Suppress("LongMethod")
    fun filterCases(): List<Pair<String, FilterExpression>> = listOf(
        "MATCH_ALL" to MatchAllFilter,
        "MATCH_NONE" to MatchNoneFilter,
        "ID" to IdFilter("id-1"),
        "IDS" to IdsFilter(listOf("id-1", "id-2")),
        "AGGREGATE_ID" to AggregateIdFilter("aggregate-1"),
        "AGGREGATE_IDS" to AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
        "TENANT_ID" to TenantIdFilter("tenant-1"),
        "OWNER_ID" to OwnerIdFilter("owner-1"),
        "SPACE_ID" to SpaceIdFilter("space-1"),
        "AND" to AndFilter(listOf(DeletionFilter(DeletionState.ACTIVE), EqualFilter(sku, v("PAID")))),
        "OR" to OrFilter(listOf(EqualFilter(sku, v(1)), EqualFilter(sku, v(2)))),
        "NOR" to NorFilter(listOf(IsNullFilter(sku))),
        "EQ" to EqualFilter(sku, v("PAID")),
        "NE" to NotEqualFilter(sku, v(true)),
        "GT" to GreaterThanFilter(sku, v(BigDecimal("1.50"))),
        "GTE" to GreaterThanOrEqualFilter(sku, v(10)),
        "LT" to LessThanFilter(sku, v(9007199254740993L)),
        "LTE" to LessThanOrEqualFilter(sku, v("2024-01-01")),
        "CONTAINS" to ContainsFilter(sku, "Wow", StringComparison.CASE_INSENSITIVE),
        "STARTS_WITH" to StartsWithFilter(sku, "Wow"),
        "ENDS_WITH" to EndsWithFilter(sku, "Wow"),
        "IN" to InFilter(sku, listOf(v(1), v(2))),
        "NOT_IN" to NotInFilter(sku, listOf(v("a"), v("b"))),
        "BETWEEN" to BetweenFilter(sku, v(1), v(BigInteger("123456789012345678901234567890"))),
        "CONTAINS_ALL" to ContainsAllFilter(sku, listOf(v("a"), v("b"))),
        "IS_EMPTY" to IsEmptyFilter(sku),
        "IS_EMPTY_STRING" to IsEmptyStringFilter(sku),
        "IS_NOT_EMPTY_STRING" to IsNotEmptyStringFilter(sku),
        "IS_NULL" to IsNullFilter(sku),
        "IS_NOT_NULL" to IsNotNullFilter(sku),
        "EXISTS" to ExistsFilter(sku),
        "NOT_EXISTS" to NotExistsFilter(sku),
        "DELETION" to DeletionFilter(DeletionState.DELETED),
        "ELEMENT_MATCH" to ElementMatchFilter(items, EqualFilter(QueryField("name"), v(null))),
        "SEARCH" to SearchFilter("event sourcing", setOf(QueryField("state.description")), SearchMode.PHRASE),
        "TODAY" to TodayFilter(created, "UTC", "yyyy-MM-dd"),
        "BEFORE_TODAY" to BeforeTodayFilter(created, "18:00", "Asia/Shanghai"),
        "TOMORROW" to TomorrowFilter(created, timeUnit = TimeUnit.SECONDS),
        "THIS_WEEK" to ThisWeekFilter(created),
        "NEXT_WEEK" to NextWeekFilter(created),
        "LAST_WEEK" to LastWeekFilter(created),
        "THIS_MONTH" to ThisMonthFilter(created),
        "LAST_MONTH" to LastMonthFilter(created),
        "RECENT_DAYS" to RecentDaysFilter(created, 7, "UTC"),
        "EARLIER_DAYS" to EarlierDaysFilter(created, 30, datePattern = "yyyyMMdd"),
        "YESTERDAY" to YesterdayFilter(created),
        "NEXT_MONTH" to NextMonthFilter(created),
        "LAST_YEAR" to LastYearFilter(created),
        "THIS_YEAR" to ThisYearFilter(created),
        "NEXT_YEAR" to NextYearFilter(created),
        "BEFORE_NOW" to BeforeNowFilter(created, "-PT30M"),
        "AFTER_NOW" to AfterNowFilter(created),
        "EXPRESSION" to ExpressionFilter(
            AggregationExpression.DateDiff(QueryField("state.paidAt"), QueryField("state.shippedAt"), DateDiffUnit.HOUR),
            ComparisonOperator.GT,
            24.0,
        ),
    )

    private val count = AggregationMetric.Count("count")
    private val amount = AggregationExpression.Field(QueryField("amount"))

    @Suppress("LongMethod")
    fun otherCases(): List<Pair<String, Any>> = listOf(
        "field" to QueryField("state.name"),
        "sort" to Sort(QueryField("state.createdAt"), Sort.Direction.DESC),
        "projection.include" to Projection(include = listOf(QueryField("state.name"))),
        "projection.all" to Projection.ALL,
        "projection.both" to Projection(listOf(QueryField("a")), listOf(QueryField("b"))),
        "pagination" to Pagination(2, 20),
        "list.default" to ListQuery(MatchAllFilter),
        "list.full" to ListQuery(
            IdFilter("id-1"),
            Projection(include = listOf(QueryField("state.name"))),
            listOf(Sort(QueryField("state.name"), Sort.Direction.ASC)),
            50,
        ),
        "paged.default" to PagedQuery(MatchAllFilter),
        "paged.full" to PagedQuery(
            TenantIdFilter("tenant-1"),
            Projection(exclude = listOf(QueryField("state.secret"))),
            listOf(Sort(QueryField("state.name"), Sort.Direction.DESC)),
            Pagination(3, 15),
        ),
        "single.default" to SingleQuery(MatchAllFilter),
        "single.full" to SingleQuery(
            AggregateIdFilter("aggregate-1"),
            sort = listOf(Sort(QueryField("version"), Sort.Direction.DESC)),
        ),
        "cursor.default" to CursorQuery(MatchAllFilter),
        "cursor.full" to CursorQuery(
            MatchNoneFilter,
            Projection(include = listOf(QueryField("state.name"))),
            listOf(Sort(QueryField("state.name"), Sort.Direction.ASC)),
            25,
            "cursor-1",
        ),
        "pagedList" to PagedList(150, listOf("one", "two")),
        "pagedList.empty" to PagedList.empty<String>(),
        "cursorPage" to CursorPage(listOf("one"), "next"),
        "cursorPage.end" to CursorPage(emptyList<String>(), null),
        "aggregation.minimal" to AggregationQuery(metrics = listOf(count)),
        "aggregation.full" to AggregationQuery(
            filter = EqualFilter(QueryField("state.status"), v("PAID")),
            elements = listOf(
                AggregationElement(QueryField("state.items")),
                AggregationElement(QueryField("lines"), GreaterThanFilter(QueryField("quantity"), v(1))),
            ),
            groupBy = listOf(
                AggregationGroup.DateHistogram(
                    QueryField("createTime"),
                    "day",
                    AggregationDateUnit.DAY,
                    scope = AggregationGroupScope.ROOT,
                ),
                AggregationGroup.Terms(QueryField("sku"), "sku", missingKey = "__missing__"),
                AggregationGroup.Terms(alias = "band", expression = amount),
                AggregationGroup.Histogram(QueryField("price"), "price", 10.0),
                AggregationGroup.Histogram(
                    alias = "total",
                    interval = 100.0,
                    expression = AggregationExpression.Binary(
                        AggregationExpressionOperator.MULTIPLY,
                        AggregationExpression.Field(QueryField("price")),
                        AggregationExpression.Field(QueryField("quantity")),
                    ),
                ),
                AggregationGroup.DatePart(
                    QueryField("createTime"),
                    "hour",
                    AggregationDatePart.HOUR_OF_DAY,
                    "Asia/Shanghai",
                    scope = AggregationGroupScope.ELEMENT,
                ),
            ),
            metrics = listOf(
                count,
                AggregationMetric.Count("paid", EqualFilter(QueryField("paid"), v(true))),
                AggregationMetric.Numeric(AggregationFunction.SUM, amount, "sum"),
                AggregationMetric.Numeric(
                    AggregationFunction.AVG,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.SUBTRACT,
                        amount,
                        AggregationExpression.Constant(1.5),
                    ),
                    "avg",
                    MatchAllFilter,
                ),
                AggregationMetric.Any(QueryField("name"), "name"),
                AggregationMetric.DistinctCount(QueryField("customer").let(AggregationExpression::Field), "customers"),
                AggregationMetric.Percentile(amount, 95.0, "p95"),
                AggregationMetric.Numeric(
                    AggregationFunction.MAX,
                    AggregationExpression.DateDiff(QueryField("paidAt"), QueryField("shippedAt"), DateDiffUnit.MINUTE),
                    "lead",
                ),
                AggregationMetric.First(QueryField("status"), "firstStatus"),
                AggregationMetric.Last(QueryField("status"), "lastStatus", QueryField("version"), IsNotNullFilter(QueryField("status"))),
                AggregationMetric.Derived(
                    "share",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.MetricRef("sum"),
                        DerivedExpression.Total("sum", listOf("day")),
                    ),
                ),
                AggregationMetric.Derived("constant", DerivedExpression.Constant(2.0)),
                AggregationMetric.Derived(
                    "running",
                    DerivedExpression.Running("sum", Sort(QueryField("day"), Sort.Direction.ASC), listOf("sku"), 3, RunningFunction.AVG),
                ),
                AggregationMetric.Derived("previous", DerivedExpression.Previous("sum", "day", 2, AggregationDateUnit.MONTH)),
                AggregationMetric.Derived("previousDay", DerivedExpression.Previous("sum", "day")),
                AggregationMetric.Derived("rank", DerivedExpression.Rank("sum", listOf("day"), Sort.Direction.ASC)),
                AggregationMetric.Derived("grand", DerivedExpression.Total("count")),
            ),
            sort = listOf(Sort(QueryField("day"), Sort.Direction.ASC), Sort(QueryField("count"), Sort.Direction.DESC)),
            limit = 500,
            having = HavingExpression.Or(
                listOf(
                    HavingExpression.And(
                        listOf(
                            HavingExpression.Condition("count", ComparisonOperator.GTE, 1.0),
                            HavingExpression.Between("sum", 0.0, 10.5),
                        ),
                    ),
                    HavingExpression.In("count", listOf(1.0, 2.0)),
                    HavingExpression.IsNull("avg"),
                    HavingExpression.IsNull("p95", negated = true),
                ),
            ),
        ),
        "group.dense" to AggregationGroup.DatePart(QueryField("createTime"), "weekday", AggregationDatePart.DAY_OF_WEEK, dense = true),
    )

    /** 10.x additions: no 9.x counterpart. */
    fun v10Cases(): List<Pair<String, Any>> = listOf(
        "paged.exact" to PagedQuery(MatchAllFilter, total = TotalMode.EXACT),
        "paged.none" to PagedQuery(MatchAllFilter, total = TotalMode.NONE),
        "pagedList.bounded" to PagedList(1000, listOf("one"), exact = false),
        "pagedList.uncounted" to PagedList(null, listOf("one")),
        "count.default" to CountQuery(MatchAllFilter),
        "count.limit" to CountQuery(EqualFilter(QueryField("state.status"), "PAID"), 1000),
        "countResult.exact" to CountResult(1234, true),
        "countResult.bounded" to CountResult(1000, false),
    )

    fun all(): List<Pair<String, Any>> = filterCases() + otherCases() + v10Cases()
}
