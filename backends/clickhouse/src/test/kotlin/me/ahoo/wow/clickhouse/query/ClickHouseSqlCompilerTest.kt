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

package me.ahoo.wow.clickhouse.query

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
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DerivedExpression
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
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.SCHEMA
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.TABLE
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.schema.QueryCapability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * The SQL each filter operator, group, metric and HAVING form translates to, compiled against a table fixture
 * without a server. The container tests prove the SQL right on ClickHouse; these pin every branch of the translation.
 */
class ClickHouseSqlCompilerTest {
    @Test
    fun `system-field filters should read the dataset's own columns`() {
        where(IdFilter("o1")).assert().isEqualTo("`id` = {p0:String}")
        where(IdsFilter(listOf("o1", "o2"))).assert().isEqualTo("`id` IN ({p0:String}, {p1:String})")
        where(TenantIdFilter("t1")).assert().isEqualTo("`tenant_id` = {p0:String}")
        where(OwnerIdFilter("u1")).assert().isEqualTo("`owner_id` = {p0:String}")
        where(SpaceIdFilter("s1")).assert().isEqualTo("`space_id` = {p0:String}")
        where(MatchNoneFilter).assert().isEqualTo("0")
    }

    @Test
    fun `comparisons should bind typed parameters and NE and NOT_IN should match NULL on a Nullable column`() {
        where(EqualFilter(CITY, "Hangzhou")).assert().isEqualTo("`city` = {p0:String}")
        where(EqualFilter(NOTE, null)).assert().isEqualTo("isNull(`note`)")
        where(NotEqualFilter(NOTE, null)).assert().isEqualTo("isNotNull(`note`)")
        where(EqualFilter(PAID, true)).assert().isEqualTo("`paid` = {p0:Bool}")
        where(NotEqualFilter(CITY, "x")).assert().isEqualTo("`city` != {p0:String}")
        where(NotEqualFilter(NOTE, "x")).assert().isEqualTo("(`note` != {p0:String} OR isNull(`note`))")
        statement(GreaterThanFilter(AMOUNT, BigDecimal("1.50"))).let {
            it.sql.assert().isEqualTo("`amount` > {p0:Decimal(18, 2)}")
            it.parameters["p0"].assert().isEqualTo("1.50")
        }
        where(GreaterThanOrEqualFilter(QUANTITY, 1)).assert().isEqualTo("`quantity` >= {p0:Int64}")
        where(LessThanFilter(QUANTITY, 1)).assert().isEqualTo("`quantity` < {p0:Int64}")
        where(LessThanOrEqualFilter(QUANTITY, 1)).assert().isEqualTo("`quantity` <= {p0:Int64}")
        where(BetweenFilter(QUANTITY, 1, 2)).assert().isEqualTo("`quantity` BETWEEN {p0:Int64} AND {p1:Int64}")
        where(InFilter(CITY, listOf("a", "b"))).assert().isEqualTo("`city` IN ({p0:String}, {p1:String})")
        where(NotInFilter(CITY, listOf("a"))).assert().isEqualTo("`city` NOT IN ({p0:String})")
        where(NotInFilter(NOTE, listOf("a"))).assert().isEqualTo("(`note` NOT IN ({p0:String}) OR isNull(`note`))")
    }

    @Test
    fun `literal matches should fold case only when asked`() {
        where(ContainsFilter(CITY, "a")).assert().isEqualTo("position(`city`, {p0:String}) > 0")
        where(ContainsFilter(CITY, "a", StringComparison.CASE_INSENSITIVE))
            .assert().isEqualTo("positionCaseInsensitiveUTF8(`city`, {p0:String}) > 0")
        where(StartsWithFilter(CITY, "a")).assert().isEqualTo("startsWith(`city`, {p0:String})")
        where(StartsWithFilter(CITY, "a", StringComparison.CASE_INSENSITIVE))
            .assert().isEqualTo("startsWith(lowerUTF8(`city`), lowerUTF8({p0:String}))")
        where(EndsWithFilter(CITY, "a")).assert().isEqualTo("endsWith(`city`, {p0:String})")
        where(EndsWithFilter(CITY, "a", StringComparison.CASE_INSENSITIVE))
            .assert().isEqualTo("endsWith(lowerUTF8(`city`), lowerUTF8({p0:String}))")
    }

    @Test
    fun `presence should read a NULL as missing and logic should compose`() {
        where(IsNullFilter(NOTE)).assert().isEqualTo("isNull(`note`)")
        where(NotExistsFilter(NOTE)).assert().isEqualTo("isNull(`note`)")
        where(IsNotNullFilter(NOTE)).assert().isEqualTo("isNotNull(`note`)")
        where(ExistsFilter(NOTE)).assert().isEqualTo("isNotNull(`note`)")
        where(OrFilter(listOf(EqualFilter(CITY, "a"), EqualFilter(CITY, "b"))))
            .assert().isEqualTo("(`city` = {p0:String} OR `city` = {p1:String})")
        where(NorFilter(listOf(EqualFilter(CITY, "a"), EqualFilter(CITY, "b"))))
            .assert().isEqualTo("NOT (`city` = {p0:String} OR `city` = {p1:String})")
        where(AndFilter(listOf(TenantIdFilter("t"), EqualFilter(CITY, "a"))))
            .assert().isEqualTo("(`tenant_id` = {p0:String} AND `city` = {p1:String})")
    }

    @Test
    fun `an array column should match by element and read an empty array as missing`() {
        val two = listOf("a", "b")
        where(EqualFilter(TAGS, "a")).assert().isEqualTo("has(`tags`, {p0:String})")
        where(NotEqualFilter(TAGS, "a")).assert().isEqualTo("NOT has(`tags`, {p0:String})")
        where(InFilter(TAGS, two)).assert().isEqualTo("hasAny(`tags`, [{p0:String}, {p1:String}])")
        where(NotInFilter(TAGS, two)).assert().isEqualTo("NOT hasAny(`tags`, [{p0:String}, {p1:String}])")
        where(ContainsAllFilter(TAGS, two)).assert().isEqualTo("hasAll(`tags`, [{p0:String}, {p1:String}])")
        where(IsEmptyFilter(TAGS)).assert().isEqualTo("empty(`tags`)")
        where(IsNullFilter(TAGS)).assert().isEqualTo("empty(`tags`)")
        where(NotExistsFilter(TAGS)).assert().isEqualTo("empty(`tags`)")
        where(IsNotNullFilter(TAGS)).assert().isEqualTo("notEmpty(`tags`)")
        where(ExistsFilter(TAGS)).assert().isEqualTo("notEmpty(`tags`)")
        SCHEMA.field(TAGS)!!.capabilities.assert()
            .containsExactlyInAnyOrder(QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE)
    }

    @Test
    fun `date operands should bind as epoch milliseconds whatever their form`() {
        val bound = "fromUnixTimestamp64Milli({p0:Int64}, 'UTC')"
        mapOf(
            "2025-01-01T00:00:00Z" to "1735689600000",
            "2025-01-01T08:00:00+08:00" to "1735689600000",
            "2025-01-01T00:00:00" to "1735689600000",
            "2025-01-01" to "1735689600000",
        ).forEach { (operand, millis) ->
            val statement = statement(GreaterThanFilter(CREATED_AT, operand))
            statement.sql.assert().isEqualTo("`created_at` > $bound")
            statement.parameters["p0"].assert().isEqualTo(millis)
        }
        statement(TodayFilter(CREATED_AT)).sql.assert().isEqualTo(
            "(`created_at` >= fromUnixTimestamp64Milli({p0:Int64}, 'UTC') AND " +
                "`created_at` < fromUnixTimestamp64Milli({p1:Int64}, 'UTC'))",
        )
        assertThrows<RuntimeException> { where(GreaterThanFilter(CREATED_AT, "yesterday")) }
    }

    @Test
    fun `expression filters should compute in Float64 and never match a missing value`() {
        val amountPlusOne = AggregationExpression.Binary(
            AggregationExpressionOperator.ADD,
            AggregationExpression.Field(AMOUNT),
            AggregationExpression.Constant(1.0),
        )
        COMPARISONS.forEach { (comparison, operator) ->
            where(ExpressionFilter(amountPlusOne, comparison, 2.0)).assert().isEqualTo(
                "(isNotNull((toFloat64(`amount`) + toFloat64(1.0))) AND " +
                    "(toFloat64(`amount`) + toFloat64(1.0)) $operator toFloat64(2.0))",
            )
        }
        val elapsed = AggregationExpression.DateDiff(CREATED_AT, PAID_AT, DateDiffUnit.HOUR)
        where(ExpressionFilter(elapsed, ComparisonOperator.GT, 1.0)).assert().contains(
            "(toFloat64(toUnixTimestamp64Milli(toDateTime64(`paid_at`, 3, 'UTC')) - " +
                "toUnixTimestamp64Milli(toDateTime64(`created_at`, 3, 'UTC'))) / 3600000)",
        )
        val ratio = AggregationExpression.Binary(
            AggregationExpressionOperator.DIVIDE,
            AggregationExpression.Binary(
                AggregationExpressionOperator.MULTIPLY,
                AggregationExpression.Field(QUANTITY),
                AggregationExpression.Binary(
                    AggregationExpressionOperator.SUBTRACT,
                    AggregationExpression.Field(AMOUNT),
                    AggregationExpression.Constant(1.0),
                ),
            ),
            AggregationExpression.Field(QUANTITY),
        )
        where(ExpressionFilter(ratio, ComparisonOperator.GT, 1.0)).assert()
            .contains("if(toFloat64(`quantity`) = 0, NULL,")
            .contains("(toFloat64(`amount`) - toFloat64(1.0))")
    }

    @Test
    fun `groups should translate to native keys and a date group should read its instant in the time zone`() {
        mapOf(
            AggregationDateUnit.YEAR to "toStartOfYear",
            AggregationDateUnit.QUARTER to "toStartOfQuarter",
            AggregationDateUnit.MONTH to "toStartOfMonth",
            AggregationDateUnit.WEEK to "toMonday",
            AggregationDateUnit.DAY to "toStartOfDay",
            AggregationDateUnit.HOUR to "toStartOfHour",
            AggregationDateUnit.MINUTE to "toStartOfMinute",
            AggregationDateUnit.SECOND to "toDateTime",
        ).forEach { (unit, function) ->
            grouped(AggregationGroup.DateHistogram(CREATED_AT, "g", unit, "Asia/Shanghai")).assert().contains(
                "toInt64(toUnixTimestamp(toDateTime($function(toTimeZone(toDateTime64(`created_at`, 3, 'UTC'), " +
                    "'Asia/Shanghai')), 'Asia/Shanghai'))) * 1000 AS __g0",
            )
        }
        mapOf(
            AggregationDatePart.DAY_OF_WEEK to "toDayOfWeek",
            AggregationDatePart.DAY_OF_MONTH to "toDayOfMonth",
            AggregationDatePart.HOUR_OF_DAY to "toHour",
            AggregationDatePart.MONTH_OF_YEAR to "toMonth",
        ).forEach { (part, function) ->
            grouped(AggregationGroup.DatePart(DAY, "g", part)).assert()
                .contains("$function(toTimeZone(toDateTime64(`day`, 3, 'UTC'), 'UTC')) AS __g0")
        }
        grouped(AggregationGroup.DateHistogram(EPOCH_MS, "g", AggregationDateUnit.DAY)).assert()
            .contains("fromUnixTimestamp64Milli(toInt64(`epoch_ms`), 'UTC')")
        grouped(AggregationGroup.DateHistogram(EPOCH_S, "g", AggregationDateUnit.DAY)).assert()
            .contains("fromUnixTimestamp64Milli(toInt64(`epoch_s`) * 1000, 'UTC')")
        assertThrows<RuntimeException> {
            grouped(
                AggregationGroup.DateHistogram(EPOCH_US, "g", AggregationDateUnit.DAY)
            )
        }
        grouped(AggregationGroup.Terms(CITY, "g")).assert().contains("SELECT `city` AS __g0")
            .doesNotContain("isNotNull(__g0)")
        grouped(AggregationGroup.Terms(NOTE, "g", missingKey = "none")).assert()
            .contains("ifNull(`note`, {p0:String}) AS __g0")
        grouped(AggregationGroup.Terms(alias = "g", expression = AggregationExpression.Field(QUANTITY))).assert()
            .contains("toFloat64(`quantity`) AS __g0").contains("WHERE isNotNull(__g0)")
        grouped(AggregationGroup.Histogram(AMOUNT, "g", 10.0)).assert()
            .contains("floor(toFloat64(`amount`) / toFloat64(10.0)) * toFloat64(10.0) AS __g0")
        grouped(
            AggregationGroup.Histogram(alias = "g", interval = 2.0, expression = AggregationExpression.Field(QUANTITY))
        )
            .assert().contains("floor(toFloat64(`quantity`) / toFloat64(2.0))")
    }

    // One table of every metric's translation reads best as a single list.
    @Suppress("LongMethod")
    @Test
    fun `metrics should aggregate natively, with an If combinator under a metric filter`() {
        val paid = IsNotNullFilter(PAID_AT)
        val sql = aggregate(
            metrics = listOf(
                AggregationMetric.Count("all"),
                AggregationMetric.Count("paid", paid),
                AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(AMOUNT), "sum"),
                AggregationMetric.Numeric(AggregationFunction.AVG, AggregationExpression.Field(QUANTITY), "avg", paid),
                AggregationMetric.Numeric(AggregationFunction.MIN, AggregationExpression.Field(QUANTITY), "min"),
                AggregationMetric.Numeric(AggregationFunction.MAX, AggregationExpression.Field(AMOUNT), "max"),
                AggregationMetric.Numeric(AggregationFunction.STDDEV, AggregationExpression.Field(AMOUNT), "stddev"),
                AggregationMetric.Numeric(
                    AggregationFunction.VARIANCE,
                    AggregationExpression.Field(AMOUNT),
                    "variance"
                ),
                AggregationMetric.DistinctCount(AggregationExpression.Field(CITY), "cities"),
                AggregationMetric.DistinctCount(AggregationExpression.Field(CITY), "paidCities", paid),
                AggregationMetric.DistinctCount(
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.ADD,
                        AggregationExpression.Field(QUANTITY),
                        AggregationExpression.Constant(0.0),
                    ),
                    "quantities",
                ),
                AggregationMetric.Percentile(AggregationExpression.Field(AMOUNT), 95.0, "p95"),
                AggregationMetric.Percentile(AggregationExpression.Field(AMOUNT), 50.0, "paidMedian", paid),
                AggregationMetric.Any(CITY, "anyCity"),
                AggregationMetric.Any(CITY, "anyPaidCity", paid),
                AggregationMetric.First(CITY, "first", CREATED_AT),
                AggregationMetric.Last(CITY, "last", CREATED_AT, paid),
                AggregationMetric.Derived(
                    "derived",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.ADD,
                            DerivedExpression.MetricRef("sum"),
                            DerivedExpression.Binary(
                                AggregationExpressionOperator.SUBTRACT,
                                DerivedExpression.Binary(
                                    AggregationExpressionOperator.MULTIPLY,
                                    DerivedExpression.MetricRef("max"),
                                    DerivedExpression.Constant(2.0),
                                ),
                                DerivedExpression.MetricRef("all"),
                            ),
                        ),
                        DerivedExpression.MetricRef("paid"),
                    ),
                ),
                AggregationMetric.Derived(
                    "twice",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.MULTIPLY,
                        DerivedExpression.MetricRef("derived"),
                        DerivedExpression.Constant(2.0),
                    ),
                ),
            ),
        )
        listOf(
            "count() AS __m0",
            "countIf(isNotNull(`paid_at`)) AS __m1",
            "sumOrNull(`amount`) AS __m2",
            "avgIfOrNull(`quantity`, isNotNull(`paid_at`)) AS __m3",
            "minOrNull(`quantity`) AS __m4",
            "maxOrNull(`amount`) AS __m5",
            "stddevPopOrNull(`amount`) AS __m6",
            "varPopOrNull(`amount`) AS __m7",
            "uniqExact(`city`) AS __m8",
            "uniqExactIf(`city`, isNotNull(`paid_at`)) AS __m9",
            "uniqExact((toFloat64(`quantity`) + toFloat64(0.0))) AS __m10",
            "quantileTDigestOrNull(toFloat64(0.95))(toFloat64(`amount`)) AS __m11",
            "quantileTDigestIfOrNull(toFloat64(0.5))(toFloat64(`amount`), isNotNull(`paid_at`)) AS __m12",
            "anyOrNull(`city`) AS __m13",
            "anyIfOrNull(`city`, isNotNull(`paid_at`)) AS __m14",
            "argMinIfOrNull(`city`, `created_at`, isNotNull(`city`) AND isNotNull(`created_at`)) AS __m15",
            "argMaxIfOrNull(`city`, `created_at`, isNotNull(`city`) AND isNotNull(`created_at`) AND " +
                "isNotNull(`paid_at`)) AS __m16",
            "if(toFloat64(__m1) = 0, NULL, (toFloat64(__m2) + ((toFloat64(__m5) * toFloat64(2.0)) - " +
                "toFloat64(__m0))) / toFloat64(__m1)) AS `derived`",
            "(if(toFloat64(__m1) = 0, NULL, ",
        ).forEach { sql.assert().contains(it) }
    }

    @Test
    fun `HAVING should filter the outer query in double space and a NULL metric should satisfy no comparison`() {
        COMPARISONS.forEach { (comparison, operator) ->
            aggregate(having = HavingExpression.Condition("orders", comparison, 1.0)).assert()
                .contains("WHERE ifNull(toFloat64(`orders`) $operator toFloat64(1.0), 0)")
        }
        val composed = HavingExpression.Or(
            listOf(
                HavingExpression.And(
                    listOf(
                        HavingExpression.Between("orders", 1.0, 2.0),
                        HavingExpression.In("orders", listOf(1.0, 2.0)),
                    ),
                ),
                HavingExpression.IsNull("orders"),
                HavingExpression.IsNull("orders", negated = true),
            ),
        )
        aggregate(having = composed).assert().contains(
            "WHERE ((ifNull(toFloat64(`orders`) BETWEEN toFloat64(1.0) AND toFloat64(2.0), 0) AND " +
                "ifNull(toFloat64(`orders`) IN (toFloat64(1.0), toFloat64(2.0)), 0)) OR " +
                "isNull(toFloat64(`orders`)) OR isNotNull(toFloat64(`orders`)))",
        )
    }

    @Test
    fun `the outer query should sort by aliases, NULLs as the core orders them, and limit only a first window`() {
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
            metrics = listOf(AggregationMetric.Count("orders")),
            sort = listOf(Sort(QueryField("orders"), Sort.Direction.DESC)),
        )
        compile(query, GroupWindow.First(5)).assert()
            .endsWith("ORDER BY `orders` DESC NULLS LAST, `city` ASC NULLS FIRST LIMIT 5")
        compile(query, GroupWindow.All).assert().endsWith("ORDER BY `orders` DESC NULLS LAST, `city` ASC NULLS FIRST")
        compile(AggregationQuery(metrics = listOf(AggregationMetric.Count("orders"))), GroupWindow.First(1)).assert()
            .isEqualTo("SELECT __m0 AS `orders` FROM (SELECT count() AS __m0 FROM `bi`.`orders` WHERE 1) LIMIT 1")
    }

    @Test
    fun `windows, PREVIOUS and element scopes should never reach the compiler`() {
        val windowed = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
            metrics = listOf(
                AggregationMetric.Count("orders"),
                AggregationMetric.Derived("rank", DerivedExpression.Rank("orders")),
            ),
        )
        // The schema keeps windows residual, so admission sends them; the core strips them before the backend.
        assertThrows<QueryExecutionException> {
            ClickHouseAggregationCompiler(admit(windowed), TABLE, TABLE.qualifiedName).compile(GroupWindow.All)
        }
        ClickHouseQuerySchemaAdapter.STORAGE_SUPPORT.aggregation.windows.values.toSet().assert()
            .containsExactly(me.ahoo.wow.query.schema.SupportMode.RESIDUAL)
        val elements = AggregationQuery(
            elements = listOf(AggregationElement(TAGS)),
            metrics = listOf(AggregationMetric.Count("orders")),
        )
        assertThrows<RuntimeException> { compile(elements) }
    }

    private fun admit(query: AggregationQuery): AdmittedQuery<AggregationQuery> = QueryAdmission.Trusted.aggregate(
        query,
        SCHEMA
    )

    private fun where(filter: FilterExpression): String = statement(filter).sql

    private fun statement(filter: FilterExpression): ClickHouseStatement {
        val admitted = QueryAdmission.Trusted.count(CountQuery(filter), SCHEMA)
        val parameters = SqlParameters()
        return parameters.statement(
            ClickHouseFilterCompiler(admitted, TABLE, parameters).compile(admitted.query.filter)
        )
    }

    private fun grouped(group: AggregationGroup): String =
        compile(AggregationQuery(groupBy = listOf(group), metrics = listOf(AggregationMetric.Count("orders"))))

    private fun aggregate(
        metrics: List<AggregationMetric> = listOf(AggregationMetric.Count("orders")),
        having: HavingExpression? = null,
    ): String = compile(
        AggregationQuery(groupBy = listOf(AggregationGroup.Terms(CITY, "city")), metrics = metrics, having = having),
    )

    private fun compile(query: AggregationQuery, window: GroupWindow = GroupWindow.First(10)): String =
        ClickHouseAggregationCompiler(admit(query), TABLE, TABLE.qualifiedName).compile(window).sql

    private companion object {
        val CITY = QueryField("city")
        val NOTE = QueryField("note")
        val AMOUNT = QueryField("amount")
        val QUANTITY = QueryField("quantity")
        val CREATED_AT = QueryField("created_at")
        val PAID_AT = QueryField("paid_at")
        val PAID = QueryField("paid")
        val DAY = QueryField("day")
        val TAGS = QueryField("tags")
        val EPOCH_MS = QueryField("epoch_ms")
        val EPOCH_S = QueryField("epoch_s")
        val EPOCH_US = QueryField("epoch_us")

        val COMPARISONS = mapOf(
            ComparisonOperator.EQ to "=",
            ComparisonOperator.NE to "!=",
            ComparisonOperator.GT to ">",
            ComparisonOperator.GTE to ">=",
            ComparisonOperator.LT to "<",
            ComparisonOperator.LTE to "<=",
        )
    }
}
