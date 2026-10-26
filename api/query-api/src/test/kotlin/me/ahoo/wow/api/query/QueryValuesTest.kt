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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The query protocol's types are plain values (finding R5.A-1): each is rebuilt equal, with the same hash and text,
 * by its public factory and by Wow's own copy, from its own properties.
 */
class QueryValuesTest {
    @TestFactory
    fun `every query value should be rebuilt equal by its factory and its copy`(): List<DynamicTest> =
        SAMPLES.mapIndexed { index, build ->
            DynamicTest.dynamicTest("sample $index") {
                val sample = build()
                val factory = sample.javaClass.methods
                    .filter { Modifier.isStatic(it.modifiers) && it.name == "of" }
                    .maxBy { it.parameterCount }
                val values = factory.parameters.map { property(sample, it.name) }.toTypedArray()
                val rebuilt = factory.invoke(null, *values)
                rebuilt.assert().isEqualTo(sample)
                (sample == sample).assert().isTrue()
                sample.equals(Any()).assert().isFalse()
                rebuilt.hashCode().assert().isEqualTo(sample.hashCode())
                rebuilt.toString().assert().isEqualTo(sample.toString())
                sample.javaClass.methods.singleOrNull { it.name == "copy" && !Modifier.isStatic(it.modifiers) }
                    ?.invoke(sample, *values)?.assert()?.isEqualTo(sample)
                // Every default: Wow's copy of nothing is the value itself; the shortest factory takes the rest.
                sample.javaClass.declaredMethods.singleOrNull { it.name == "copy\$default" }?.let { copy ->
                    val arguments = copy.parameterTypes.drop(1).dropLast(2).map(::zero)
                    copy.invoke(null, sample, *arguments.toTypedArray(), (1 shl arguments.size) - 1, null)
                        .assert().isEqualTo(sample)
                }
                val shortest = sample.javaClass.methods
                    .filter { Modifier.isStatic(it.modifiers) && it.name == "of" }
                    .minBy { it.parameterCount }
                runCatching {
                    shortest.invoke(
                        null,
                        *shortest.parameters.map { property(sample, it.name) }.toTypedArray()
                    )
                }
            }
        }

    private fun zero(type: Class<*>): Any? = when (type) {
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Double::class.javaPrimitiveType -> 0.0
        Boolean::class.javaPrimitiveType -> false
        else -> null
    }

    private fun property(target: Any, name: String): Any? {
        val capitalized = name.replaceFirstChar { it.uppercase() }
        val getter: Method = target.javaClass.methods.first {
            it.parameterCount == 0 && it.name in setOf("get$capitalized", name, "is$capitalized")
        }
        return getter.invoke(target)
    }

    private companion object {
        val SAMPLES: List<() -> Any> = listOf(
            { AggregationQuery(metrics = listOf(AggregationMetric.Count("c"))) },
            { AggregationElement(path = QueryField("a")) },
            { AggregationGroup.Terms(field = QueryField("a"), alias = "a") },
            { AggregationGroup.Histogram(field = QueryField("a"), alias = "a", interval = 1.0) },
            {
                AggregationGroup.DateHistogram(
                    field = QueryField("a"),
                    alias = "a",
                    unit = AggregationDateUnit.entries.first()
                )
            },
            {
                AggregationGroup.DatePart(
                    field = QueryField("a"),
                    alias = "a",
                    part = AggregationDatePart.entries.first()
                )
            },
            { AggregationExpression.Field(field = QueryField("a")) },
            { AggregationExpression.Constant(value = 1.0) },
            {
                AggregationExpression.Binary(
                    operator = AggregationExpressionOperator.entries.first(),
                    left = AggregationExpression.Field(QueryField("a")),
                    right = AggregationExpression.Field(QueryField("a"))
                )
            },
            {
                AggregationExpression.DateDiff(
                    from = QueryField("a"),
                    to = QueryField("a"),
                    unit = DateDiffUnit.entries.first()
                )
            },
            { AggregationMetric.Count(alias = "a") },
            {
                AggregationMetric.Numeric(
                    function = AggregationFunction.entries.first(),
                    expression = AggregationExpression.Field(QueryField("a")),
                    alias = "a"
                )
            },
            { AggregationMetric.Any(field = QueryField("a"), alias = "a") },
            { AggregationMetric.DistinctCount(expression = AggregationExpression.Field(QueryField("a")), alias = "a") },
            {
                AggregationMetric.Percentile(
                    expression = AggregationExpression.Field(QueryField("a")),
                    percentile = 1.0,
                    alias = "a"
                )
            },
            { AggregationMetric.Derived(alias = "a", expression = DerivedExpression.MetricRef("m")) },
            { AggregationMetric.First(field = QueryField("a"), alias = "a") },
            { AggregationMetric.Last(field = QueryField("a"), alias = "a") },
            { DerivedExpression.MetricRef(metric = "a") },
            { DerivedExpression.Constant(value = 1.0) },
            {
                DerivedExpression.Binary(
                    operator = AggregationExpressionOperator.entries.first(),
                    left = DerivedExpression.MetricRef("m"),
                    right = DerivedExpression.MetricRef("m")
                )
            },
            { DerivedExpression.Total(metric = "a") },
            { DerivedExpression.Running(metric = "a", order = Sort(QueryField("a"), Sort.Direction.ASC)) },
            { DerivedExpression.Previous(metric = "a", along = "a") },
            { DerivedExpression.Rank(metric = "a") },
            { HavingExpression.Condition(metric = "a", operator = ComparisonOperator.entries.first(), value = 1.0) },
            { HavingExpression.Between(metric = "a", lower = 1.0, upper = 1.0) },
            { HavingExpression.In(metric = "a", values = listOf(50.0)) },
            { HavingExpression.IsNull(metric = "a") },
            { HavingExpression.And(operands = listOf(HavingExpression.IsNull("m"), HavingExpression.IsNull("n"))) },
            { HavingExpression.Or(operands = listOf(HavingExpression.IsNull("m"), HavingExpression.IsNull("n"))) },
            { CountQuery(filter = MatchAllFilter) },
            { CountResult(count = 1L, exact = false) },
            { CursorPage(list = listOf("t"), nextCursor = "a") },
            { CursorQuery(filter = MatchAllFilter) },
            {
                ExpressionFilter(
                    expression = AggregationExpression.Field(QueryField("a")),
                    comparison = ComparisonOperator.entries.first(),
                    value = 1.0
                )
            },
            { AndFilter(operands = listOf(MatchAllFilter, MatchNoneFilter)) },
            { OrFilter(operands = listOf(MatchAllFilter, MatchNoneFilter)) },
            { NorFilter(operands = listOf(MatchAllFilter, MatchNoneFilter)) },
            { DeletionFilter(deletionState = DeletionState.entries.first()) },
            { ElementMatchFilter(field = QueryField("a"), predicate = MatchAllFilter) },
            { SearchFilter(query = "a") },
            { ListQuery(filter = MatchAllFilter) },
            { IdFilter(value = "a") },
            { IdsFilter(values = listOf("a")) },
            { AggregateIdFilter(value = "a") },
            { AggregateIdsFilter(values = listOf("a")) },
            { TenantIdFilter(value = "a") },
            { OwnerIdFilter(value = "a") },
            { SpaceIdFilter(value = "a") },
            { PagedList(total = 1L, list = listOf("t")) },
            { PagedQuery(filter = MatchAllFilter) },
            { EqualFilter(field = QueryField("a"), value = "v") },
            { NotEqualFilter(field = QueryField("a"), value = "v") },
            { GreaterThanFilter(field = QueryField("a"), value = "v") },
            { GreaterThanOrEqualFilter(field = QueryField("a"), value = "v") },
            { LessThanFilter(field = QueryField("a"), value = "v") },
            { LessThanOrEqualFilter(field = QueryField("a"), value = "v") },
            { ContainsFilter(field = QueryField("a"), value = "a") },
            { StartsWithFilter(field = QueryField("a"), value = "a") },
            { EndsWithFilter(field = QueryField("a"), value = "a") },
            { InFilter(field = QueryField("a"), values = listOf("v")) },
            { NotInFilter(field = QueryField("a"), values = listOf("v")) },
            { BetweenFilter(field = QueryField("a"), lowerBound = "v", upperBound = "v") },
            { ContainsAllFilter(field = QueryField("a"), values = listOf("v")) },
            { IsEmptyFilter(field = QueryField("a")) },
            { IsEmptyStringFilter(field = QueryField("a")) },
            { IsNotEmptyStringFilter(field = QueryField("a")) },
            { IsNullFilter(field = QueryField("a")) },
            { IsNotNullFilter(field = QueryField("a")) },
            { ExistsFilter(field = QueryField("a")) },
            { NotExistsFilter(field = QueryField("a")) },
            { Sort(field = QueryField("a"), direction = Sort.Direction.ASC) },
            { Pagination() },
            { Projection() },
            { TodayFilter(field = QueryField("a")) },
            { BeforeTodayFilter(field = QueryField("a"), time = "18:00") },
            { TomorrowFilter(field = QueryField("a")) },
            { ThisWeekFilter(field = QueryField("a")) },
            { NextWeekFilter(field = QueryField("a")) },
            { LastWeekFilter(field = QueryField("a")) },
            { ThisMonthFilter(field = QueryField("a")) },
            { LastMonthFilter(field = QueryField("a")) },
            { RecentDaysFilter(field = QueryField("a"), days = 1) },
            { EarlierDaysFilter(field = QueryField("a"), days = 1) },
            { YesterdayFilter(field = QueryField("a")) },
            { NextMonthFilter(field = QueryField("a")) },
            { LastYearFilter(field = QueryField("a")) },
            { ThisYearFilter(field = QueryField("a")) },
            { NextYearFilter(field = QueryField("a")) },
            { BeforeNowFilter(field = QueryField("a")) },
            { AfterNowFilter(field = QueryField("a")) },
            { SingleQuery(filter = MatchAllFilter) },
        )
    }
}
