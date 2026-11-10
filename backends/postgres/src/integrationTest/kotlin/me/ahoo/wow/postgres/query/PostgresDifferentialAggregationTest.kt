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

package me.ahoo.wow.postgres.query

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
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.aggregate
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** The aggregations of the same differential: every metric, group and HAVING form, on PostgreSQL and on the reference. */
internal class PostgresDifferentialAggregationTest : PostgresDifferentialFixtures() {
    @TestFactory
    fun `aggregations compute what the reference computes`(): List<DynamicTest> = AGGREGATIONS.map { (name, query) ->
        same(name) { aggregate(query) }
    }

    companion object {
        private fun count(alias: String = "count") = AggregationMetric.Count(alias)

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
            "empty summary" to AggregationQuery(
                filter = EqualFilter(STATUS, "NONE"),
                metrics = listOf(count(), numeric(AggregationFunction.AVG, "average")),
            ),
        )
    }
}
