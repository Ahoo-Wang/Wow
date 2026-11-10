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

package me.ahoo.wow.query.memory

import me.ahoo.test.asserts.assert
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
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.GroupWindow
import org.junit.jupiter.api.Test

/** The reference backend's aggregation: every group, metric and expression, over the same table. */
internal class InMemoryAggregationSemanticsTest : InMemoryReferenceFixtures() {
    // Aggregation

    @Test
    fun `terms, histograms and their keys of missing values`() {
        val rows = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(NOTE, alias = "note", missingKey = "(none)")),
                metrics = listOf(AggregationMetric.Count("n")),
                sort = listOf(Sort(QueryField("note"), Sort.Direction.ASC)),
            ),
        )
        rows.associate { it.path("note").stringValue() to it.path("n").longValue() }.assert()
            .containsEntry("(none)", 3L).containsEntry("", 1L).containsEntry("x", 1L)
        val nullKeys = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(NOTE, alias = "note")),
                metrics = listOf(AggregationMetric.Count("n")),
            ),
        )
        nullKeys.count { it.path("note").isNull }.assert().isEqualTo(1)
        val byExpression = aggregate(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Terms(
                        alias = "double",
                        expression = AggregationExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            AggregationExpression.Field(QTY),
                            AggregationExpression.Constant(2.0),
                        ),
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("n")),
                sort = listOf(Sort(QueryField("double"), Sort.Direction.DESC)),
            ),
            GroupWindow.First(2),
        )
        byExpression.map { it.path("double").doubleValue() }.assert().containsExactly(8.0, 6.0)
        val histogram = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram(PRICE, alias = "band", interval = 20.0)),
                metrics = listOf(AggregationMetric.Count("n")),
                sort = listOf(Sort(QueryField("band"), Sort.Direction.ASC)),
            ),
        )
        histogram.map { it.path("band").let { band -> if (band.isNull) null else band.doubleValue() } }.assert()
            .containsExactly(null, 0.0, 20.0, 40.0)
        val expressionHistogram = aggregate(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Histogram(
                        alias = "band",
                        interval = 2.0,
                        expression = AggregationExpression.Field(QTY),
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("n")),
            ),
        )
        expressionHistogram.size.assert().isEqualTo(3)
    }

    @Test
    fun `a histogram over an expression without a value puts the record in the null bucket, as over the field`() {
        val histogram = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram(PRICE, alias = "band", interval = 20.0)),
                metrics = listOf(AggregationMetric.Count("n")),
                sort = listOf(Sort(QueryField("band"), Sort.Direction.ASC)),
            ),
        )
        val valuelessExpression = aggregate(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Histogram(
                        alias = "band",
                        interval = 20.0,
                        expression = AggregationExpression.Field(PRICE),
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("n")),
                sort = listOf(Sort(QueryField("band"), Sort.Direction.ASC)),
            ),
        )
        valuelessExpression.toString().assert().isEqualTo(histogram.toString())
    }

    @Test
    fun `terms over an array join a group per value, combined with the other groups`() {
        // r1 [a, b], r2 [b], r3 [c]; r4's list is empty and r5 has none, so both are in the null group.
        val byTagAndTenant = aggregate(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Terms(TAGS, alias = "tag"),
                    AggregationGroup.Terms(TENANT, alias = "tenant")
                ),
                metrics = listOf(AggregationMetric.Count("n")),
            ),
        )
        byTagAndTenant.map {
            Triple(it.path("tag").stringValue(null), it.path("tenant").stringValue(), it.path("n").longValue())
        }.assert().containsExactlyInAnyOrder(
            Triple("a", "t1", 1L),
            Triple("b", "t1", 2L),
            Triple("c", "t1", 1L),
            Triple(null, "t1", 1L),
            Triple(null, "t2", 1L),
        )
        // A record without an instant joins no date group, whatever its tags: r5 is left out.
        val byTagAndDay = aggregate(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Terms(TAGS, alias = "tag"),
                    AggregationGroup.DateHistogram(AT, alias = "day", unit = AggregationDateUnit.DAY),
                ),
                metrics = listOf(AggregationMetric.Count("n")),
            ),
        )
        byTagAndDay.sumOf { it.path("n").longValue() }.assert().isEqualTo(5L)
    }

    @Test
    fun `date histograms bucket by every unit, and date parts by every part`() {
        val counts = AggregationDateUnit.entries.associateWith { unit ->
            aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.DateHistogram(AT, alias = "bucket", unit = unit)),
                    metrics = listOf(AggregationMetric.Count("n")),
                ),
            ).size
        }
        counts.getValue(AggregationDateUnit.YEAR).assert().isEqualTo(1)
        counts.getValue(AggregationDateUnit.QUARTER).assert().isEqualTo(1)
        counts.getValue(AggregationDateUnit.MONTH).assert().isEqualTo(1)
        counts.getValue(AggregationDateUnit.WEEK).assert().isEqualTo(1)
        counts.getValue(AggregationDateUnit.DAY).assert().isEqualTo(3)
        counts.getValue(AggregationDateUnit.SECOND).assert().isEqualTo(4)
        val parts = AggregationDatePart.entries.associateWith { part ->
            aggregate(
                AggregationQuery(
                    groupBy = listOf(
                        AggregationGroup.DatePart(DAY, alias = "part", part = part, timeZone = "Asia/Shanghai"),
                    ),
                    metrics = listOf(AggregationMetric.Count("n")),
                    sort = listOf(Sort(QueryField("part"), Sort.Direction.ASC)),
                ),
            ).map { it.path("part").intValue() }
        }
        parts.getValue(AggregationDatePart.DAY_OF_WEEK).assert().containsExactly(1, 2, 3, 4)
        parts.getValue(AggregationDatePart.DAY_OF_MONTH).assert().containsExactly(5, 6, 7, 8)
        parts.getValue(AggregationDatePart.MONTH_OF_YEAR).assert().containsExactly(1)
        parts.getValue(AggregationDatePart.HOUR_OF_DAY).assert().isNotEmpty()
    }

    @Test
    fun `every metric over the groups, filtered metrics included`() {
        val price = AggregationExpression.Field(PRICE)
        val rows = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(TENANT, alias = "tenant")),
                metrics = AggregationFunction.entries.map { AggregationMetric.Numeric(it, price, it.name) } + listOf(
                    AggregationMetric.Count("flagged", filter = EqualFilter(FLAG, true)),
                    AggregationMetric.Any(NAME, "anyName"),
                    AggregationMetric.Any(NOTE, "anyNote", filter = NotExistsFilter(NOTE)),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(TAGS), "tagCount"),
                    AggregationMetric.DistinctCount(
                        AggregationExpression.Binary(AggregationExpressionOperator.DIVIDE, price, price),
                        "ones",
                    ),
                    AggregationMetric.Percentile(price, 50.0, "median"),
                    AggregationMetric.Percentile(price, 50.0, "none", filter = MatchNoneFilter),
                    AggregationMetric.Numeric(AggregationFunction.SUM, price, "nothing", filter = MatchNoneFilter),
                    AggregationMetric.First(NAME, "first", orderBy = PRICE),
                    AggregationMetric.Last(NAME, "last", orderBy = PRICE),
                    AggregationMetric.First(NAME, "noFirst", orderBy = PRICE, filter = MatchNoneFilter),
                    derived("perFlag", AggregationExpressionOperator.DIVIDE, ref("SUM"), ref("flagged")),
                    derived("plus", AggregationExpressionOperator.ADD, ref("MIN"), DerivedExpression.Constant(1.0)),
                    derived("minus", AggregationExpressionOperator.SUBTRACT, ref("MAX"), ref("MIN")),
                    derived("times", AggregationExpressionOperator.MULTIPLY, ref("AVG"), DerivedExpression.Constant(2.0)),
                    derived("byZero", AggregationExpressionOperator.DIVIDE, ref("SUM"), DerivedExpression.Constant(0.0)),
                    derived("ofNull", AggregationExpressionOperator.ADD, ref("nothing"), DerivedExpression.Constant(1.0)),
                ),
                sort = listOf(Sort(QueryField("tenant"), Sort.Direction.ASC)),
            ),
        )
        val t1 = rows.first()
        t1.path("tenant").stringValue().assert().isEqualTo("t1")
        t1.path("SUM").doubleValue().assert().isEqualTo(60.0)
        t1.path("AVG").doubleValue().assert().isEqualTo(20.0)
        t1.path("MIN").doubleValue().assert().isEqualTo(10.0)
        t1.path("MAX").doubleValue().assert().isEqualTo(30.0)
        t1.path("VARIANCE").doubleValue().assert().isCloseTo(66.666, org.assertj.core.data.Offset.offset(0.01))
        t1.path("STDDEV").doubleValue().assert().isCloseTo(8.165, org.assertj.core.data.Offset.offset(0.01))
        t1.path("flagged").longValue().assert().isEqualTo(2L)
        t1.path("anyName").stringValue().assert().isEqualTo("Alpha")
        t1.path("anyNote").isNull.assert().isTrue()
        t1.path("tagCount").longValue().assert().isEqualTo(3L)
        t1.path("ones").longValue().assert().isEqualTo(1L)
        t1.path("median").doubleValue().assert().isEqualTo(20.0)
        t1.path("none").isNull.assert().isTrue()
        t1.path("nothing").isNull.assert().isTrue()
        t1.path("first").stringValue().assert().isEqualTo("Alpha")
        t1.path("last").stringValue().assert().isEqualTo("Gamma")
        t1.path("noFirst").isNull.assert().isTrue()
        t1.path("perFlag").doubleValue().assert().isEqualTo(30.0)
        t1.path("plus").doubleValue().assert().isEqualTo(11.0)
        t1.path("minus").doubleValue().assert().isEqualTo(20.0)
        t1.path("times").doubleValue().assert().isEqualTo(40.0)
        t1.path("byZero").isNull.assert().isTrue()
        t1.path("ofNull").isNull.assert().isTrue()
    }

    @Test
    fun `elements expand into groups, a root group keyed by its record`() {
        val rows = aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(LINES, GreaterThanFilter(ELEMENT_PRICE, 1))),
                groupBy = listOf(
                    AggregationGroup.Terms(SKU, alias = "sku"),
                    AggregationGroup.Terms(NAME, alias = "name", scope = AggregationGroupScope.ROOT),
                ),
                metrics = listOf(
                    AggregationMetric.Count("n"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(ELEMENT_PRICE),
                        "sum"
                    ),
                ),
                sort = listOf(
                    Sort(QueryField("name"), Sort.Direction.DESC),
                    Sort(QueryField("sku"), Sort.Direction.ASC),
                ),
            ),
        )
        rows.map { it.path("sku").stringValue() + "/" + it.path("name").stringValue() }.assert()
            .containsExactly("A/Beta", "A/Alpha", "B/Alpha")
        aggregate(
            AggregationQuery(filter = MatchNoneFilter, metrics = listOf(AggregationMetric.Count("n"))),
        ).assert().isEmpty()
    }

    private fun ref(metric: String): DerivedExpression = DerivedExpression.MetricRef(metric)

    private fun derived(
        alias: String,
        operator: AggregationExpressionOperator,
        left: DerivedExpression,
        right: DerivedExpression,
    ) = AggregationMetric.Derived(alias, DerivedExpression.Binary(operator, left, right))

    private companion object {
        val TENANT = QueryField("tenant_id")
    }
}
