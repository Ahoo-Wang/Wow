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

package me.ahoo.wow.mongo.query

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
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.backend.aggregate
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** The aggregations of the same differential: every metric, group and HAVING form, on MongoDB and on the reference. */
internal class MongoDifferentialAggregationTest : MongoDifferentialFixtures() {
    @TestFactory
    fun `aggregations compute what the reference computes`(): List<DynamicTest> = AGGREGATIONS.map { (name, query) ->
        same(name) { aggregate(query) }
    }

    companion object {
        private fun count(alias: String = "count", filter: FilterExpression = MatchAllFilter) =
            AggregationMetric.Count(alias, filter)

        private val BY_STATUS = AggregationGroup.Terms(STATUS, "status")

        val AGGREGATIONS: List<Pair<String, AggregationQuery>> = listOf(
            "every numeric function" to AggregationQuery(
                metrics = AggregationFunction.entries.map { numeric(it, it.name.lowercase()) } +
                    numeric(AggregationFunction.SUM, "versions", QueryField("version")),
            ),
            "terms with a missing key" to AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(STATUS, "status", missingKey = "none")),
                metrics = listOf(count()),
            ),
            "boolean terms" to AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(field("flag"), "flag")),
                metrics = listOf(count()),
            ),
            "terms of an expression" to AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Terms(
                        alias = "double",
                        expression = AggregationExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            AggregationExpression.Field(QUANTITY),
                            AggregationExpression.Constant(2.0),
                        ),
                    ),
                ),
                metrics = listOf(count()),
            ),
            "histogram" to AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram(AMOUNT, "bucket", 20.0)),
                metrics = listOf(count()),
            ),
            "histogram of an expression" to AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Histogram(
                        alias = "bucket",
                        interval = 2.0,
                        expression = AggregationExpression.Binary(
                            AggregationExpressionOperator.SUBTRACT,
                            AggregationExpression.Field(QUANTITY),
                            AggregationExpression.Constant(0.5),
                        ),
                    ),
                ),
                metrics = listOf(count()),
            ),
            "date histogram by day in a region" to AggregationQuery(
                groupBy = listOf(AggregationGroup.DateHistogram(CREATED, "day", AggregationDateUnit.DAY, "Asia/Shanghai")),
                metrics = listOf(count()),
            ),
            "date histogram by week at an offset" to AggregationQuery(
                groupBy = listOf(AggregationGroup.DateHistogram(CREATED, "week", AggregationDateUnit.WEEK, "-05:00")),
                metrics = listOf(count()),
            ),
            "date histogram of seconds by month" to AggregationQuery(
                groupBy = listOf(AggregationGroup.DateHistogram(field("createdSeconds"), "month", AggregationDateUnit.MONTH)),
                metrics = listOf(count()),
            ),
            "date part hour at a half-hour offset" to AggregationQuery(
                groupBy = listOf(AggregationGroup.DatePart(CREATED, "hour", AggregationDatePart.HOUR_OF_DAY, "+05:30")),
                metrics = listOf(count()),
            ),
            "date part day of week" to AggregationQuery(
                groupBy = listOf(AggregationGroup.DatePart(CREATED, "weekday", AggregationDatePart.DAY_OF_WEEK, "America/New_York")),
                metrics = listOf(count()),
            ),
            "dense date histogram" to AggregationQuery(
                filter = GreaterThanOrEqualFilter(CREATED, QueryFixtures.T0),
                groupBy = listOf(AggregationGroup.DateHistogram(CREATED, "day", AggregationDateUnit.DAY, dense = true)),
                metrics = listOf(count()),
            ),
            "percentile, distinct, any, first and last" to AggregationQuery(
                groupBy = listOf(BY_STATUS),
                metrics = listOf(
                    AggregationMetric.Percentile(AggregationExpression.Field(AMOUNT), 50.0, "median"),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QUANTITY), "quantities"),
                    AggregationMetric.DistinctCount(
                        AggregationExpression.Binary(
                            AggregationExpressionOperator.ADD,
                            AggregationExpression.Field(QUANTITY),
                            AggregationExpression.Constant(1.0),
                        ),
                        "shifted",
                    ),
                    AggregationMetric.Any(NAME, "anyName"),
                    AggregationMetric.First(NAME, "firstName", orderBy = CREATED),
                    AggregationMetric.Last(AMOUNT, "lastAmount", orderBy = QueryField("version")),
                ),
            ),
            "metric filters" to AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Count("flagged", EqualFilter(field("flag"), true)),
                    AggregationMetric.Numeric(AggregationFunction.MAX, AggregationExpression.Field(AMOUNT), "maxNew", EqualFilter(STATUS, "NEW")),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(STATUS), "statuses", ExistsFilter(NAME)),
                    AggregationMetric.Percentile(AggregationExpression.Field(QUANTITY), 90.0, "p90", LessThanFilter(QUANTITY, 4)),
                    AggregationMetric.Any(STATUS, "anyStatus", EqualFilter(QUANTITY, 4)),
                    AggregationMetric.First(STATUS, "firstStatus", orderBy = AMOUNT, filter = EqualFilter(field("flag"), true)),
                ),
            ),
            "derived metrics" to AggregationQuery(
                groupBy = listOf(BY_STATUS),
                metrics = listOf(
                    count(),
                    numeric(AggregationFunction.SUM, "total"),
                    AggregationMetric.Derived(
                        "average",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.DIVIDE,
                            DerivedExpression.MetricRef("total"),
                            DerivedExpression.MetricRef("count"),
                        ),
                    ),
                    AggregationMetric.Derived(
                        "scaled",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            DerivedExpression.MetricRef("average"),
                            DerivedExpression.Constant(2.0),
                        ),
                    ),
                    AggregationMetric.Derived(
                        "byZero",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.DIVIDE,
                            DerivedExpression.Binary(
                                AggregationExpressionOperator.SUBTRACT,
                                DerivedExpression.MetricRef("total"),
                                DerivedExpression.MetricRef("total"),
                            ),
                            DerivedExpression.Binary(
                                AggregationExpressionOperator.ADD,
                                DerivedExpression.Constant(-1.0),
                                DerivedExpression.Constant(1.0),
                            ),
                        ),
                    ),
                ),
                sort = listOf(Sort(QueryField("scaled"), Sort.Direction.DESC)),
                having = HavingExpression.IsNull("average", negated = true),
            ),
            "having forms" to AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QUANTITY, "quantity")),
                metrics = listOf(count(), numeric(AggregationFunction.SUM, "total")),
                having = HavingExpression.Or(
                    listOf(
                        HavingExpression.And(
                            listOf(
                                HavingExpression.Between("total", 10.0, 35.0),
                                HavingExpression.Condition("count", ComparisonOperator.LTE, 1.0),
                            ),
                        ),
                        HavingExpression.In("count", listOf(2.0, 3.0)),
                        HavingExpression.IsNull("total"),
                    ),
                ),
            ),
            "top groups by a metric" to AggregationQuery(
                groupBy = listOf(BY_STATUS),
                metrics = listOf(numeric(AggregationFunction.MAX, "max")),
                sort = listOf(Sort(QueryField("max"), Sort.Direction.ASC)),
                limit = 2,
            ),
            "elements and root groups" to AggregationQuery(
                filter = DeletionFilter(DeletionState.ACTIVE),
                elements = listOf(AggregationElement(field("lines"), GreaterThanFilter(QueryField("price"), 1))),
                groupBy = listOf(
                    AggregationGroup.Terms(STATUS, "status", scope = AggregationGroupScope.ROOT),
                    AggregationGroup.Terms(QueryField("sku"), "sku"),
                ),
                metrics = listOf(
                    count("lines"),
                    AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(QueryField("quantity")), "quantity"),
                ),
            ),
            "nested elements" to AggregationQuery(
                elements = listOf(AggregationElement(field("lines")), AggregationElement(QueryField("parts"))),
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "part")),
                metrics = listOf(count("parts")),
            ),
            "terms by each of an array's values" to AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(KEYWORDS, "keyword")),
                metrics = listOf(count()),
            ),
            "missing key that looks like a field" to AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(STATUS, "status", missingKey = "${'$'}name")),
                metrics = listOf(count()),
            ),
            "exact decimal sums" to AggregationQuery(
                elements = listOf(AggregationElement(field("lines"))),
                groupBy = listOf(AggregationGroup.Terms(QueryField("tenantId"), "tenant", scope = AggregationGroupScope.ROOT)),
                metrics = listOf(
                    AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(QueryField("price")), "linePrices"),
                    AggregationMetric.Numeric(AggregationFunction.MIN, AggregationExpression.Field(QueryField("price")), "minPrice"),
                ),
            ),
            "metric filter shapes" to AggregationQuery(
                groupBy = listOf(BY_STATUS),
                metrics = listOf(
                    count("all"),
                    count("notNew", NotEqualFilter(STATUS, "NEW")),
                    count("nullStatus", IsNullFilter(STATUS)),
                    count("someStatus", IsNotNullFilter(STATUS)),
                    count("named", ExistsFilter(NAME)),
                    count("unnamed", NotExistsFilter(NAME)),
                    count("large", GreaterThanFilter(AMOUNT, 15)),
                    count("small", LessThanOrEqualFilter(QUANTITY, 2)),
                    count("listed", InFilter(QUANTITY, listOf(1, 4))),
                    count("unlisted", NotInFilter(QUANTITY, listOf(1, 4))),
                    count("withE", ContainsFilter(NAME, "E", StringComparison.CASE_INSENSITIVE)),
                    count("literal", EqualFilter(NAME, "${'$'}name")),
                    count("either", OrFilter(listOf(EqualFilter(field("flag"), true), LessThanFilter(QUANTITY, 2)))),
                    count("neither", NorFilter(listOf(EqualFilter(field("flag"), true), LessThanFilter(QUANTITY, 2)))),
                    count("both", AndFilter(listOf(ExistsFilter(NAME), GreaterThanFilter(QUANTITY, 1)))),
                    count(
                        "computed",
                        ExpressionFilter(
                            AggregationExpression.Binary(
                                AggregationExpressionOperator.MULTIPLY,
                                AggregationExpression.Field(QUANTITY),
                                AggregationExpression.Constant(2.0),
                            ),
                            ComparisonOperator.GTE,
                            6.0,
                        ),
                    ),
                ),
            ),
            "native windows" to AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QUANTITY, "quantity"), AggregationGroup.Terms(field("flag"), "flag")),
                metrics = listOf(
                    count(),
                    numeric(AggregationFunction.SUM, "total"),
                    AggregationMetric.Derived("share", DerivedExpression.Total("count", within = listOf("flag"))),
                    AggregationMetric.Derived(
                        "runningTotal",
                        DerivedExpression.Running("total", Sort(QueryField("quantity"), Sort.Direction.DESC), within = listOf("flag")),
                    ),
                    AggregationMetric.Derived(
                        "movingAverage",
                        DerivedExpression.Running(
                            "total",
                            Sort(QueryField("quantity"), Sort.Direction.ASC),
                            frame = 2,
                            function = RunningFunction.AVG,
                        ),
                    ),
                    AggregationMetric.Derived("rank", DerivedExpression.Rank("total", direction = Sort.Direction.ASC)),
                    AggregationMetric.Derived("rankWithin", DerivedExpression.Rank("count", within = listOf("flag"))),
                ),
                sort = listOf(Sort(QueryField("quantity"), Sort.Direction.ASC), Sort(QueryField("flag"), Sort.Direction.ASC)),
            ),
            "dense hours at a half-hour offset" to AggregationQuery(
                filter = AndFilter(
                    listOf(GreaterThanOrEqualFilter(CREATED, QueryFixtures.T0), LessThanFilter(CREATED, QueryFixtures.T0 + 2 * QueryFixtures.DAY)),
                ),
                groupBy = listOf(AggregationGroup.DateHistogram(CREATED, "hour", AggregationDateUnit.HOUR, "+05:30", dense = true)),
                metrics = listOf(count(), numeric(AggregationFunction.SUM, "total")),
            ),
            "dense weeks in a region" to AggregationQuery(
                groupBy = listOf(AggregationGroup.DateHistogram(CREATED, "week", AggregationDateUnit.WEEK, "Europe/Berlin", dense = true)),
                metrics = listOf(count()),
            ),
            "hours, days and months" to AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(CREATED, "hour", AggregationDateUnit.HOUR, "Asia/Kolkata"),
                    AggregationGroup.DatePart(CREATED, "day", AggregationDatePart.DAY_OF_MONTH),
                    AggregationGroup.DatePart(CREATED, "month", AggregationDatePart.MONTH_OF_YEAR, "UTC"),
                ),
                metrics = listOf(count()),
            ),
            "empty summary" to AggregationQuery(
                filter = EqualFilter(STATUS, "NONE"),
                metrics = listOf(count(), numeric(AggregationFunction.AVG, "average")),
            ),
        )
    }
}
