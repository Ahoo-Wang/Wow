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
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.HISTORY
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.ID
import me.ahoo.wow.query.tck.SnapshotAggregationCases.Companion.EXACT_PLAIN_AGGREGATE
import me.ahoo.wow.query.tck.SnapshotAggregationCases.Companion.OUT_OF_RANGE_REFUSED
import me.ahoo.wow.query.tck.SnapshotAggregationCases.Companion.TINY
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

    @Test
    fun `a plain decimal column's sum, average, least and greatest are in its decimals, or doubles where listed`() =
        blocking {
            val decimals = target(TENTH_AND_FIFTH)
            val functions = listOf(
                AggregationFunction.SUM,
                AggregationFunction.AVG,
                AggregationFunction.MIN,
                AggregationFunction.MAX,
            )
            val row = decimals.aggregate(
                AggregationQuery(MatchAllFilter, metrics = functions.map { numeric(it, AMOUNT, it.name.lowercase()) }),
            ).single()
            val exact = EXACT_PLAIN_AGGREGATE !in semanticDivergences
            val wanted = if (exact) {
                listOf("0.3", "0.15", "0.1", "0.2")
            } else {
                listOf("0.30000000000000004", "0.15000000000000002", "0.1", "0.2")
            }
            functions.map { row.path(it.name.lowercase()).decimalValue() }.zip(wanted).forEach { (value, expected) ->
                value.assert().describedAs("$row").isEqualByComparingTo(BigDecimal(expected))
            }
            Unit
        }

    @Test
    fun `a computed comparison over a decimal column`() = blocking {
        // As on the snapshot model: 0.1 × 3.0 is over 0.3 and 0.3 ÷ 0.1 under 3 in doubles, neither in decimals.
        val decimals = target(TENTHS)
        suspend fun ids(expression: AggregationExpression, comparison: ComparisonOperator, value: Double) =
            decimals.list(ListQuery(ExpressionFilter(expression, comparison, value))).map { it.path(ID).stringValue() }
        ids(computed(AggregationExpressionOperator.MULTIPLY, 3.0), ComparisonOperator.GT, 0.3).assert()
            .containsExactlyInAnyOrder("tenth", "fifth", "threeTenths")
        ids(computed(AggregationExpressionOperator.DIVIDE, 0.1), ComparisonOperator.LT, 3.0).assert()
            .containsExactlyInAnyOrder("tenth", "fifth", "threeTenths")
        Unit
    }

    @Test
    fun `a sum and an average over an expression of a decimal column are doubles on every backend`() = blocking {
        val tripled = computed(AggregationExpressionOperator.MULTIPLY, 3.0)
        val row = target(TENTH_AND_FIFTH).aggregate(
            AggregationQuery(
                MatchAllFilter,
                metrics = listOf(
                    AggregationMetric.Numeric(AggregationFunction.SUM, tripled, "sum"),
                    AggregationMetric.Numeric(AggregationFunction.AVG, tripled, "avg"),
                ),
            ),
        ).single()
        row.path("sum").decimalValue().assert().describedAs("$row")
            .isEqualByComparingTo(BigDecimal("0.9000000000000001"))
        row.path("avg").decimalValue().assert().describedAs("$row")
            .isEqualByComparingTo(BigDecimal("0.45000000000000007"))
        Unit
    }

    @Test
    fun `a computed value over a decimal column beyond a double is no value and an underflow 0, or refused`() =
        blocking {
            // As on the snapshot model: 2 × 1e308 is no value, either amount times 1e-200 twice underflows to 0.
            val decimals = target(TENTH_AND_TWO)
            val beyond = computed(AggregationExpressionOperator.MULTIPLY, 1.0E308)
            val under = AggregationExpression.Binary(
                AggregationExpressionOperator.MULTIPLY,
                computed(AggregationExpressionOperator.MULTIPLY, TINY),
                AggregationExpression.Constant(TINY),
            )
            suspend fun ids(expression: AggregationExpression, comparison: ComparisonOperator, value: Double) =
                decimals.list(ListQuery(ExpressionFilter(expression, comparison, value)))
                    .map { it.path(ID).stringValue() }
            val sum = AggregationQuery(
                MatchAllFilter,
                metrics = listOf(AggregationMetric.Numeric(AggregationFunction.SUM, beyond, "sum")),
            )
            val outcomes: List<suspend () -> Any> = listOf(
                { ids(beyond, ComparisonOperator.GT, 0.0) },
                { ids(under, ComparisonOperator.EQ, 0.0) },
                { decimals.aggregate(sum).single().path("sum").decimalValue() },
            )
            if (OUT_OF_RANGE_REFUSED in semanticDivergences) {
                outcomes.forEach { outcome ->
                    val refused = runCatching { outcome() }.exceptionOrNull()
                    (refused as? QueryError)?.errorCode.assert().describedAs("$refused")
                        .isEqualTo(QueryErrors.OUT_OF_RANGE)
                }
                return@blocking
            }
            outcomes[0]().assert().isEqualTo(listOf("tenth"))
            (outcomes[1]() as List<*>).assert().containsExactlyInAnyOrder("tenth", "two")
            (outcomes[2]() as BigDecimal).assert().isEqualByComparingTo(BigDecimal("1.0000000000000001E307"))
        }

    /** `amount` [operator] [operand], a computed value. */
    private fun computed(operator: AggregationExpressionOperator, operand: Double): AggregationExpression =
        AggregationExpression.Binary(
            operator,
            AggregationExpression.Field(AMOUNT),
            AggregationExpression.Constant(operand),
        )

    private companion object {
        /**
         * Two orders of [DatasetQueryBackendSpec.ORDERS], their amounts 0.1 and 0.2; made on first use, as
         * [DatasetQueryBackendSpec] is initialized after this class it extends.
         */
        val TENTH_AND_FIFTH by lazy { amounts("tenth_and_fifth", "tenth" to "0.1", "fifth" to "0.2") }

        /** Two orders of [DatasetQueryBackendSpec.ORDERS], their amounts 0.1 and 2. */
        val TENTH_AND_TWO by lazy { amounts("tenth_and_two", "tenth" to "0.1", "two" to "2") }

        /** Three orders of [DatasetQueryBackendSpec.ORDERS], their amounts 0.1, 0.2 and 0.3. */
        val TENTHS by lazy { amounts("tenths", "tenth" to "0.1", "fifth" to "0.2", "threeTenths" to "0.3") }

        /** A table named [name] of orders of [DatasetQueryBackendSpec.ORDERS], each its id and amount from [orders]. */
        fun amounts(name: String, vararg orders: Pair<String, String>) = DatasetTable(
            name = name,
            columns = DatasetQueryBackendSpec.ORDERS.columns,
            rows = orders.mapIndexed { index, (id, amount) ->
                DatasetQueryBackendSpec.ORDERS.rows[index] + mapOf(ID to id, AMOUNT.path to BigDecimal(amount))
            },
        )
    }
}
