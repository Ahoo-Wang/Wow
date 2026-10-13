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
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NotEqualFilter
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
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.filter.QueryType
import org.junit.jupiter.api.Test

class QueryFingerprintTest {
    private val amount = QueryField("state.amount")
    private val createTime = QueryField("createTime")

    private fun count(filter: FilterExpression) = fingerprintOf(QueryType.COUNT, CountQuery(filter))

    private fun between(lower: Int, upper: Int) = BetweenFilter(amount, lower, upper)

    @Test
    fun `filter values never reach the shape and never change the fingerprint`() {
        val sameShapes = listOf(
            between(1234, 5678) to between(1, 2),
            RecentDaysFilter(createTime, 7, zoneId = "Asia/Shanghai") to RecentDaysFilter(createTime, 30),
            BeforeTodayFilter(createTime, "18:45") to BeforeTodayFilter(createTime, "09:00"),
            BeforeNowFilter(createTime, "-PT45M") to BeforeNowFilter(createTime),
            ContainsFilter(amount, "secret-text") to ContainsFilter(amount, "other"),
            SearchFilter("secret phrase") to SearchFilter("other"),
            TenantIdFilter("secret-tenant") to TenantIdFilter("other"),
            InFilter(amount, listOf((4321), (8765))) to
                InFilter(amount, listOf((1), (2))),
            ExpressionFilter(AggregationExpression.Field(amount), ComparisonOperator.GT, 2468.0) to
                ExpressionFilter(AggregationExpression.Field(amount), ComparisonOperator.GT, 1.0),
        )
        sameShapes.forEach { (left, right) -> count(left).assert().isEqualTo(count(right)) }
        val shape = queryShapeOf(QueryType.COUNT, CountQuery(AndFilter(sameShapes.map { it.first })))
        shape.assert().doesNotContain(
            "1234", "5678", "18:45", "PT45M", "Asia", "secret", "4321", "8765", "2468",
        )
    }

    @Test
    fun `a different structure has a different fingerprint`() {
        count(between(1, 2)).assert()
            .isNotEqualTo(count(GreaterThanFilter(amount, (1))))
            .isNotEqualTo(count(BetweenFilter(createTime, 1, 2)))
        count(InFilter(amount, listOf((1)))).assert()
            .isNotEqualTo(count(InFilter(amount, listOf((1), (2)))))
        count(RecentDaysFilter(createTime, 7)).assert()
            .isNotEqualTo(count(RecentDaysFilter(amount, 7)))
        fingerprintOf(QueryType.LIST, ListQuery(MatchAllFilter)).assert()
            .isNotEqualTo(
                fingerprintOf(
                    QueryType.LIST,
                    ListQuery(MatchAllFilter, sort = listOf(Sort(amount, Sort.Direction.ASC)))
                )
            )
            .isNotEqualTo(count(MatchAllFilter))
    }

    @Test
    fun `the page index and cursor are values while the paging kind and size are shape`() {
        fun paged(index: Int, size: Int) =
            fingerprintOf(QueryType.PAGED, PagedQuery(MatchAllFilter, pagination = Pagination(index, size)))
        paged(1, 10).assert().isEqualTo(paged(7, 10)).isNotEqualTo(paged(1, 20))
        fun cursor(value: String?) = fingerprintOf(QueryType.CURSOR, CursorQuery(MatchAllFilter, cursor = value))
        cursor("secret-cursor").assert().isEqualTo(cursor("other")).isNotEqualTo(cursor(null))
        queryShapeOf(QueryType.CURSOR, CursorQuery(MatchAllFilter, cursor = "secret-cursor")).assert()
            .doesNotContain("secret")
    }

    @Test
    fun `having bounds, constants and aliases never change an aggregation fingerprint`() {
        fun aggregation(alias: String, lower: Double, upper: Double, constant: Double, percentile: Double) =
            AggregationQuery(
                filter = BetweenFilter(amount, lower.toInt(), upper.toInt()),
                groupBy = listOf(
                    AggregationGroup.Terms(QueryField("state.region"), "${alias}Region", missingKey = "secret-key"),
                    AggregationGroup.Histogram(amount, "${alias}Bucket", interval = constant),
                ),
                metrics = listOf(
                    AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(amount), alias),
                    AggregationMetric.Percentile(AggregationExpression.Field(amount), percentile, "${alias}P"),
                    AggregationMetric.Derived(
                        "${alias}Scaled",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            DerivedExpression.MetricRef(alias),
                            DerivedExpression.Constant(constant),
                        ),
                    ),
                ),
                sort = listOf(Sort(QueryField(alias), Sort.Direction.DESC)),
                having = HavingExpression.And(
                    listOf(
                        HavingExpression.Between(alias, lower, upper),
                        HavingExpression.Condition("${alias}P", ComparisonOperator.GT, constant),
                        HavingExpression.In(alias, listOf(lower, upper)),
                    ),
                ),
            )
        val secret = aggregation("secretAlias", 1357.0, 9753.0, 8642.0, 95.0)
        fingerprintOf(QueryType.AGGREGATION, secret).assert()
            .isEqualTo(fingerprintOf(QueryType.AGGREGATION, aggregation("total", 1.0, 2.0, 3.0, 50.0)))
        queryShapeOf(QueryType.AGGREGATION, secret).assert()
            .doesNotContain("secret", "1357", "9753", "8642", "95")
        val otherHaving = secret.copy(having = HavingExpression.Condition("secretAlias", ComparisonOperator.LT, 1.0))
        fingerprintOf(QueryType.AGGREGATION, otherHaving).assert()
            .isNotEqualTo(fingerprintOf(QueryType.AGGREGATION, secret))
    }

    @Test
    @Suppress("LongMethod")
    fun `every aggregation node kind is its own shape`() {
        val region = QueryField("state.region")
        val total = AggregationMetric.Count("total")
        val byRegion = AggregationGroup.Terms(region, "region")
        fun shapeOf(
            groupBy: List<AggregationGroup> = emptyList(),
            metrics: List<AggregationMetric> = listOf(total),
            having: HavingExpression? = null,
            elements: List<AggregationElement> = emptyList(),
        ) = fingerprintOf(
            QueryType.AGGREGATION,
            AggregationQuery(groupBy = groupBy, metrics = metrics, having = having, elements = elements),
        )
        val dateDiff = AggregationExpression.DateDiff(createTime, QueryField("updateTime"), DateDiffUnit.DAY)
        val shapes = listOf(
            shapeOf(),
            shapeOf(groupBy = listOf(AggregationGroup.Terms(region, "region"))),
            shapeOf(groupBy = listOf(AggregationGroup.Terms(region, "region", missingKey = "none"))),
            shapeOf(groupBy = listOf(AggregationGroup.Terms(alias = "days", expression = dateDiff))),
            shapeOf(groupBy = listOf(AggregationGroup.Histogram(amount, "bucket", interval = 10.0))),
            shapeOf(
                groupBy = listOf(AggregationGroup.Histogram(alias = "bucket", interval = 10.0, expression = dateDiff))
            ),
            shapeOf(groupBy = listOf(AggregationGroup.DateHistogram(createTime, "month", AggregationDateUnit.MONTH))),
            shapeOf(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(createTime, "month", AggregationDateUnit.MONTH, dense = true)
                ),
            ),
            shapeOf(
                groupBy = listOf(AggregationGroup.DatePart(createTime, "weekday", AggregationDatePart.DAY_OF_WEEK))
            ),
            shapeOf(
                groupBy = listOf(
                    AggregationGroup.DatePart(createTime, "weekday", AggregationDatePart.DAY_OF_WEEK, dense = true),
                ),
            ),
            shapeOf(metrics = listOf(AggregationMetric.Any(region, "anyRegion"))),
            shapeOf(metrics = listOf(AggregationMetric.DistinctCount(AggregationExpression.Field(region), "regions"))),
            shapeOf(metrics = listOf(AggregationMetric.First(amount, "first", orderBy = createTime))),
            shapeOf(metrics = listOf(AggregationMetric.Last(amount, "last"))),
            shapeOf(
                metrics = listOf(
                    AggregationMetric.Numeric(
                        AggregationFunction.AVG,
                        AggregationExpression.Binary(
                            AggregationExpressionOperator.DIVIDE,
                            AggregationExpression.Field(amount),
                            AggregationExpression.Constant(2.0),
                        ),
                        "half",
                    ),
                ),
            ),
            shapeOf(metrics = listOf(AggregationMetric.Numeric(AggregationFunction.MAX, dateDiff, "longest"))),
            shapeOf(groupBy = listOf(byRegion), having = HavingExpression.IsNull("total")),
            shapeOf(groupBy = listOf(byRegion), having = HavingExpression.IsNull("total", negated = true)),
            shapeOf(groupBy = listOf(byRegion), having = HavingExpression.Or(listOf(HavingExpression.IsNull("total")))),
            shapeOf(elements = listOf(AggregationElement(QueryField("state.items")))),
        )

        shapes.toSet().assert().hasSize(shapes.size)
        queryShapeOf(
            QueryType.AGGREGATION,
            AggregationQuery(
                groupBy = listOf(byRegion),
                metrics = listOf(total),
                having = HavingExpression.IsNull("total", negated = true),
            ),
        ).assert().contains("IS_NOT_NULL")
    }

    @Test
    fun `equality arity is shape, its value is not`() {
        fun eq(value: Any?) = count(EqualFilter(amount, value))
        eq("a").assert().isEqualTo(eq("b")).isNotEqualTo(eq(listOf("a", "b")))
        fun ne(value: Any?) = count(NotEqualFilter(amount, value))
        ne("a").assert().isEqualTo(ne("b")).isNotEqualTo(ne(listOf("a", "b"))).isNotEqualTo(eq("a"))
    }

    @Test
    fun `the count limit, the total mode and the deletion state are shape`() {
        fingerprintOf(QueryType.COUNT, CountQuery(MatchAllFilter, 10)).assert()
            .isEqualTo(fingerprintOf(QueryType.COUNT, CountQuery(MatchAllFilter, 10)))
            .isNotEqualTo(fingerprintOf(QueryType.COUNT, CountQuery(MatchAllFilter, 20)))
            .isNotEqualTo(count(MatchAllFilter))
        fun paged(total: TotalMode) = fingerprintOf(QueryType.PAGED, PagedQuery(MatchAllFilter, total = total))
        TotalMode.entries.map(::paged).toSet().assert().hasSize(TotalMode.entries.size)
        count(DeletionFilter(DeletionState.ACTIVE)).assert().isNotEqualTo(count(DeletionFilter(DeletionState.ALL)))
        count(SearchFilter("a", setOf(amount))).assert()
            .isNotEqualTo(count(SearchFilter("a", setOf(amount), SearchMode.PHRASE)))
            .isNotEqualTo(count(SearchFilter("a")))
    }

    @Test
    fun `string comparison, projection and single queries are shape`() {
        listOf(
            count(ContainsFilter(amount, "a")),
            count(ContainsFilter(amount, "a", StringComparison.CASE_INSENSITIVE)),
            count(StartsWithFilter(amount, "a")),
            count(EndsWithFilter(amount, "a")),
        ).toSet().assert().hasSize(4)
        fun single(projection: Projection) = fingerprintOf(QueryType.SINGLE, SingleQuery(MatchAllFilter, projection))
        single(Projection.ALL).assert()
            .isNotEqualTo(single(Projection(include = listOf(amount))))
            .isNotEqualTo(single(Projection(exclude = listOf(amount))))
        queryShapeOf(QueryType.SINGLE, SingleQuery(MatchAllFilter, Projection(include = listOf(amount)))).assert()
            .contains("state.amount")
    }

    @Test
    fun `every window kind is its own shape and its shift and frame are values`() {
        val month = AggregationGroup.DateHistogram(createTime, "month", AggregationDateUnit.MONTH)
        val region = AggregationGroup.Terms(QueryField("state.region"), "region")
        val total = AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(amount), "total")
        fun shape(window: DerivedExpression) = fingerprintOf(
            QueryType.AGGREGATION,
            AggregationQuery(
                groupBy = listOf(region, month),
                metrics = listOf(total, AggregationMetric.Derived("window", window)),
            ),
        )
        val byMonth = Sort(QueryField("month"), Sort.Direction.ASC)
        val shapes = listOf(
            shape(DerivedExpression.Total("total")),
            shape(DerivedExpression.Total("total", listOf("region"))),
            shape(DerivedExpression.Running("total", byMonth)),
            shape(DerivedExpression.Running("total", byMonth, listOf("region"))),
            shape(DerivedExpression.Running("total", byMonth, frame = 3)),
            shape(DerivedExpression.Running("total", byMonth, function = RunningFunction.AVG)),
            shape(DerivedExpression.Previous("total", "month")),
            shape(DerivedExpression.Previous("total", "month", period = AggregationDateUnit.YEAR)),
            shape(DerivedExpression.Rank("total")),
            shape(DerivedExpression.Rank("total", listOf("region"))),
            shape(DerivedExpression.Rank("total", direction = Sort.Direction.ASC)),
        )
        shapes.toSet().assert().hasSize(shapes.size)
        shape(DerivedExpression.Running("total", byMonth, frame = 3)).assert()
            .isEqualTo(shape(DerivedExpression.Running("total", byMonth, frame = 7)))
        shape(DerivedExpression.Previous("total", "month", shift = 1)).assert()
            .isEqualTo(shape(DerivedExpression.Previous("total", "month", shift = 2)))
    }
}
