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

package me.ahoo.wow.query.tck
import kotlinx.coroutines.flow.single
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
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
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.HISTORY
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.ID
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The aggregation cases of the dataset contract: groups, FIRST and LAST, histograms, HAVING, windows, PREVIOUS and
 * value metrics. They read only [DatasetQueryFixtures]'s fixtures and hooks; see [DatasetQueryFixtures] for the chain
 * of families it is part of.
 */
abstract class DatasetAggregationCases internal constructor() : DatasetRecordCases() {
    @Test
    fun `groups count, sum, average, bound, distinct-count and pick the first and last records`() = blocking {
        val rows = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
                metrics = listOf(
                    AggregationMetric.Count("orders"),
                    numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                    numeric(AggregationFunction.AVG, QueryField("quantity"), "quantity"),
                    numeric(AggregationFunction.MIN, AMOUNT, "smallest"),
                    numeric(AggregationFunction.MAX, AMOUNT, "largest"),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField(ID)), "ids"),
                    AggregationMetric.First(QueryField(ID), "first", orderBy = CREATED_AT),
                    AggregationMetric.Last(QueryField(ID), "last", orderBy = CREATED_AT),
                ),
                sort = listOf(Sort(QueryField("orders"), Sort.Direction.DESC)),
            ),
        )
        rows.map { it.path("city").stringValue() }.assert().containsExactly("Beijing", "Hangzhou", "Shanghai")
        val beijing = rows.first()
        beijing.get("orders").asLong().assert().isEqualTo(2)
        beijing.get("gmv").decimalValue().assert().isEqualByComparingTo(BigDecimal("35.00"))
        beijing.get("quantity").asDouble().assert().isEqualTo(2.0)
        beijing.get("smallest").decimalValue().assert().isEqualByComparingTo(BigDecimal("5.00"))
        beijing.get("largest").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.00"))
        beijing.get("ids").asLong().assert().isEqualTo(2)
        beijing.get("first").stringValue().assert().isEqualTo("o3")
        beijing.get("last").stringValue().assert().isEqualTo("o4")
        rows[1].get("gmv").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.75"))
        Unit
    }

    @Test
    fun `first and last pick among the records that hold a value`() = blocking {
        val rows = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
                metrics = listOf(
                    AggregationMetric.First(QueryField("discount"), "first", orderBy = CREATED_AT),
                    AggregationMetric.Last(QueryField("discount"), "last", orderBy = CREATED_AT),
                ),
            ),
        ).associateBy { it.path("city").stringValue() }
        // Hangzhou's earliest order and Beijing's latest hold no discount: each edge is the nearest order with one.
        rows.getValue("Hangzhou").get("first").decimalValue().assert().isEqualByComparingTo(BigDecimal("0.10"))
        rows.getValue("Hangzhou").get("last").decimalValue().assert().isEqualByComparingTo(BigDecimal("0.10"))
        rows.getValue("Beijing").get("first").decimalValue().assert().isEqualByComparingTo(BigDecimal("0.20"))
        rows.getValue("Beijing").get("last").decimalValue().assert().isEqualByComparingTo(BigDecimal("0.20"))
        Unit
    }

    @Test
    fun `a date histogram buckets instants before 1970 at their own start`() = blocking {
        val history = target(HISTORY)
        fun buckets(unit: AggregationDateUnit, zone: String) = blocking {
            history.aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.DateHistogram(CREATED_AT, "bucket", unit, zone)),
                    metrics = listOf(AggregationMetric.Count("count")),
                    sort = listOf(Sort(QueryField("bucket"), Sort.Direction.ASC)),
                ),
            ).map { it.path("bucket").longValue() to it.path("count").longValue() }
        }
        buckets(AggregationDateUnit.HOUR, "UTC").assert().containsExactly(
            Instant.parse("1969-12-31T12:00:00Z").toEpochMilli() to 2L,
            0L to 1L,
        )
        buckets(AggregationDateUnit.DAY, "America/New_York").assert()
            .containsExactly(Instant.parse("1969-12-31T05:00:00Z").toEpochMilli() to 3L)
        buckets(AggregationDateUnit.YEAR, "UTC").assert().containsExactly(
            Instant.parse("1969-01-01T00:00:00Z").toEpochMilli() to 2L,
            0L to 1L,
        )
        Unit
    }

    @Test
    fun `having filters groups before the metric sort and limit`() = blocking {
        val rows = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
                metrics = listOf(numeric(AggregationFunction.SUM, AMOUNT, "gmv")),
                having = HavingExpression.Condition("gmv", ComparisonOperator.GT, 10.0),
                sort = listOf(Sort(QueryField("gmv"), Sort.Direction.ASC)),
                limit = 1,
            ),
        )
        rows.map { it.path("city").stringValue() }.assert().containsExactly("Hangzhou")
        Unit
    }

    @Test
    fun `groups without a sort refuse more than their limit, and come back whole within it`() = blocking {
        fun byCity(limit: Int) = AggregationQuery(
            filter = EqualFilter(TENANT_COLUMN, "t1"),
            groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
            metrics = listOf(AggregationMetric.Count("orders")),
            limit = limit,
        )
        val refused = runCatching { orders.aggregate(byCity(2)) }.exceptionOrNull()
        refused.assert().isInstanceOf(QueryRequestException::class.java)
        (refused as QueryRequestException).violation.assert().isEqualTo(QueryViolation.TooManyGroups(2))
        orders.aggregate(byCity(3)).map { it.path("city").stringValue() }
            .assert().containsExactly("Beijing", "Hangzhou", "Shanghai")
        Unit
    }

    @Test
    fun `date histograms key buckets by the local start of the unit`() = blocking {
        val zone = ZoneId.of("Asia/Shanghai")
        val rows = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                groupBy = listOf(AggregationGroup.DateHistogram(CREATED_AT, "day", AggregationDateUnit.DAY, zone.id)),
                metrics = listOf(AggregationMetric.Count("orders")),
            ),
        )
        fun day(month: Int, day: Int) = LocalDate.of(2025, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
        rows.map { it.get("day").asLong() to it.get("orders").asLong() }.assert().containsExactly(
            day(1, 1) to 1L,
            day(1, 2) to 2L,
            day(2, 1) to 1L,
            day(2, 15) to 1L,
        )
        Unit
    }

    @Test
    fun `windows read every group before HAVING, alike in the storage and in the core`() = blocking {
        val query = AggregationQuery(
            filter = EqualFilter(TENANT_COLUMN, "t1"),
            groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
            metrics = listOf(
                numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                AggregationMetric.Count("orders"),
                AggregationMetric.Derived(
                    "share",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.MetricRef("gmv"),
                        DerivedExpression.Total("gmv"),
                    ),
                ),
                AggregationMetric.Derived("rank", DerivedExpression.Rank("gmv")),
                AggregationMetric.Derived(
                    "cumulative",
                    DerivedExpression.Running("orders", Sort(QueryField("gmv"), Sort.Direction.DESC)),
                ),
            ),
            having = HavingExpression.Condition("rank", ComparisonOperator.LTE, 2.0),
            sort = listOf(Sort(QueryField("gmv"), Sort.Direction.DESC)),
        )
        listOf(false, true).forEach { residual ->
            val rows = orders.aggregate(query, residual)
            rows.map { it.path("city").stringValue() }.assert().containsExactly("Beijing", "Hangzhou")
            rows.map { it.get("share").asDouble() }.assert().containsExactly(35.0 / 73.5, 30.75 / 73.5)
            rows.map { it.get("rank").asDouble() }.assert().containsExactly(1.0, 2.0)
            rows.map { it.get("cumulative").asDouble() }.assert().containsExactly(2.0, 4.0)
        }
    }

    @Test
    fun `running windows follow their order within each partition, alike in the storage and in the core`() = blocking {
        val month = Sort(QueryField("month"), Sort.Direction.ASC)
        val query = AggregationQuery(
            filter = EqualFilter(TENANT_COLUMN, "t1"),
            groupBy = listOf(
                AggregationGroup.Terms(CITY, "city"),
                AggregationGroup.DateHistogram(CREATED_AT, "month", AggregationDateUnit.MONTH),
            ),
            metrics = listOf(
                numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                AggregationMetric.Derived("cumulative", DerivedExpression.Running("gmv", month, listOf("city"))),
                AggregationMetric.Derived(
                    "moving",
                    DerivedExpression.Running("gmv", month, frame = 2, function = RunningFunction.AVG),
                ),
            ),
        )
        listOf(false, true).forEach { residual ->
            val rows = orders.aggregate(query, residual)
            rows.map { it.path("city").stringValue() }.assert()
                .containsExactly("Beijing", "Beijing", "Hangzhou", "Shanghai")
            rows.map { it.get("cumulative").asDouble() }.assert().containsExactly(30.0, 35.0, 30.75, 7.75)
            // Along the month, ties by city: Beijing Jan, Hangzhou Jan, Beijing Feb, Shanghai Feb.
            rows.map { it.get("moving").asDouble() }.assert()
                .containsExactly(30.0, (30.75 + 5.0) / 2, (30.0 + 30.75) / 2, (5.0 + 7.75) / 2)
        }
    }

    @Test
    fun `previous reads the earlier period over the shifted range`() = blocking {
        val rows = orders.aggregate(
            AggregationQuery(
                filter = AndFilter(
                    listOf(
                        EqualFilter(TENANT_COLUMN, "t1"),
                        GreaterThanOrEqualFilter(CREATED_AT, "2025-02-01T00:00:00Z"),
                    ),
                ),
                groupBy = listOf(
                    AggregationGroup.Terms(CITY, "city"),
                    AggregationGroup.DateHistogram(CREATED_AT, "month", AggregationDateUnit.MONTH),
                ),
                metrics = listOf(
                    numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                    AggregationMetric.Derived("previous", DerivedExpression.Previous("gmv", "month")),
                ),
            ),
        )
        rows.map { it.path("city").stringValue() }.assert().containsExactly("Beijing", "Shanghai")
        rows.map { it.get("previous").takeUnless { node -> node.isNull }?.asDouble() }.assert()
            .containsExactly(30.0, null)
        Unit
    }

    @Test
    fun `value metrics skip missing values and an empty input keeps the empty summary`() = blocking {
        val discount = QueryField("discount")
        val discounts = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                metrics = listOf(
                    numeric(AggregationFunction.AVG, discount, "discount"),
                    AggregationMetric.Count("discounted", IsNotNullFilter(discount)),
                ),
            ),
        ).single()
        (discounts.get("discount").asDouble() - 0.35 / 3).assert().isBetween(-EPSILON, EPSILON)
        discounts.get("discounted").asLong().assert().isEqualTo(3)
        val empty = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(CITY, "Nowhere"),
                metrics = listOf(
                    AggregationMetric.Count("orders"),
                    numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField(ID)), "ids"),
                ),
            ),
        ).single()
        empty.get("orders").asLong().assert().isEqualTo(0)
        empty.get("gmv").isNull.assert().isTrue()
        empty.get("ids").asLong().assert().isEqualTo(0)
        Unit
    }
}
