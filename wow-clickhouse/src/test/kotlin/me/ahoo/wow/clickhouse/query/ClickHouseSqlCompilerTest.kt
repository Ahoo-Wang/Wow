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
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsFilter
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
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.toObjectNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.TimeUnit

/**
 * The SQL each filter operator, group, metric and HAVING form translates to, compiled against a table fixture
 * without a server. The container tests prove the SQL right on ClickHouse; these pin every branch of the translation.
 */
class ClickHouseSqlCompilerTest {
    private val json = JsonNodeFactory.instance

    @Test
    fun `system-field filters read the dataset's own columns`() {
        where(IdFilter("o1")).assert().isEqualTo("`id` = {p0:String}")
        where(IdsFilter(listOf("o1", "o2"))).assert().isEqualTo("`id` IN ({p0:String}, {p1:String})")
        where(TenantIdFilter("t1")).assert().isEqualTo("`tenant_id` = {p0:String}")
        where(OwnerIdFilter("u1")).assert().isEqualTo("`owner_id` = {p0:String}")
        where(SpaceIdFilter("s1")).assert().isEqualTo("`space_id` = {p0:String}")
    }

    @Test
    fun `comparisons bind typed parameters and NE and NOT_IN match NULL on a Nullable column`() {
        where(EqualFilter(CITY, json.stringNode("Hangzhou"))).assert().isEqualTo("`city` = {p0:String}")
        where(EqualFilter(NOTE, json.nullNode())).assert().isEqualTo("isNull(`note`)")
        where(NotEqualFilter(NOTE, json.nullNode())).assert().isEqualTo("isNotNull(`note`)")
        where(EqualFilter(PAID, json.booleanNode(true))).assert().isEqualTo("`paid` = {p0:Bool}")
        where(NotEqualFilter(CITY, json.stringNode("x"))).assert().isEqualTo("`city` != {p0:String}")
        where(
            NotEqualFilter(NOTE, json.stringNode("x"))
        ).assert().isEqualTo("(`note` != {p0:String} OR isNull(`note`))")
        where(GreaterThanFilter(AMOUNT, json.numberNode(java.math.BigDecimal("1.50"))))
            .assert().isEqualTo("`amount` > {p0:Decimal(18, 2)}")
        where(GreaterThanOrEqualFilter(QUANTITY, json.numberNode(1))).assert().isEqualTo("`quantity` >= {p0:Int64}")
        where(LessThanFilter(QUANTITY, json.numberNode(1))).assert().isEqualTo("`quantity` < {p0:Int64}")
        where(LessThanOrEqualFilter(QUANTITY, json.numberNode(1))).assert().isEqualTo("`quantity` <= {p0:Int64}")
        where(BetweenFilter(QUANTITY, json.numberNode(1), json.numberNode(2)))
            .assert().isEqualTo("`quantity` BETWEEN {p0:Int64} AND {p1:Int64}")
        where(InFilter(CITY, listOf(json.stringNode("a"), json.stringNode("b"))))
            .assert().isEqualTo("`city` IN ({p0:String}, {p1:String})")
        where(NotInFilter(CITY, listOf(json.stringNode("a")))).assert().isEqualTo("`city` NOT IN ({p0:String})")
        where(NotInFilter(NOTE, listOf(json.stringNode("a"))))
            .assert().isEqualTo("(`note` NOT IN ({p0:String}) OR isNull(`note`))")
    }

    @Test
    fun `literal matches fold case only when asked`() {
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
    fun `presence reads a NULL as missing and logic composes`() {
        where(IsNullFilter(NOTE)).assert().isEqualTo("isNull(`note`)")
        where(NotExistsFilter(NOTE)).assert().isEqualTo("isNull(`note`)")
        where(IsNotNullFilter(NOTE)).assert().isEqualTo("isNotNull(`note`)")
        where(ExistsFilter(NOTE)).assert().isEqualTo("isNotNull(`note`)")
        where(OrFilter(listOf(EqualFilter(CITY, json.stringNode("a")), EqualFilter(CITY, json.stringNode("b")))))
            .assert().isEqualTo("(`city` = {p0:String} OR `city` = {p1:String})")
        where(NorFilter(listOf(EqualFilter(CITY, json.stringNode("a")), EqualFilter(CITY, json.stringNode("b")))))
            .assert().isEqualTo("NOT (`city` = {p0:String} OR `city` = {p1:String})")
        where(AndFilter(listOf(TenantIdFilter("t"), EqualFilter(CITY, json.stringNode("a")))))
            .assert().isEqualTo("(`tenant_id` = {p0:String} AND `city` = {p1:String})")
    }

    @Test
    fun `date operands bind as epoch milliseconds whatever their form`() {
        val bound = "fromUnixTimestamp64Milli({p0:Int64}, 'UTC')"
        val expected = mapOf(
            "2025-01-01T00:00:00Z" to "1735689600000",
            "2025-01-01T08:00:00+08:00" to "1735689600000",
            "2025-01-01T00:00:00" to "1735689600000",
            "2025-01-01" to "1735689600000",
        )
        expected.forEach { (operand, millis) ->
            val statement = statement(GreaterThanFilter(CREATED_AT, json.stringNode(operand)))
            statement.sql.assert().isEqualTo("`created_at` > $bound")
            statement.parameters["p0"].assert().isEqualTo(millis)
        }
        statement(me.ahoo.wow.api.query.TodayFilter(CREATED_AT)).sql.assert().isEqualTo(
            "(`created_at` >= fromUnixTimestamp64Milli({p0:Int64}, 'UTC') AND " +
                "`created_at` < fromUnixTimestamp64Milli({p1:Int64}, 'UTC'))",
        )
        assertThrows<RuntimeException> { where(GreaterThanFilter(CREATED_AT, json.stringNode("yesterday"))) }
    }

    @Test
    fun `expression filters compute in Float64 and never match a missing value`() {
        val amountPlusOne = AggregationExpression.Binary(
            AggregationExpressionOperator.ADD,
            AggregationExpression.Field(AMOUNT),
            AggregationExpression.Constant(1.0),
        )
        val operators = mapOf(
            ComparisonOperator.EQ to "=",
            ComparisonOperator.NE to "!=",
            ComparisonOperator.GT to ">",
            ComparisonOperator.GTE to ">=",
            ComparisonOperator.LT to "<",
            ComparisonOperator.LTE to "<=",
        )
        operators.forEach { (comparison, operator) ->
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
    fun `groups translate to native keys and a date group reads its instant in the time zone`() {
        val units = mapOf(
            AggregationDateUnit.YEAR to "toStartOfYear",
            AggregationDateUnit.QUARTER to "toStartOfQuarter",
            AggregationDateUnit.MONTH to "toStartOfMonth",
            AggregationDateUnit.WEEK to "toMonday",
            AggregationDateUnit.DAY to "toStartOfDay",
            AggregationDateUnit.HOUR to "toStartOfHour",
            AggregationDateUnit.MINUTE to "toStartOfMinute",
            AggregationDateUnit.SECOND to "toDateTime",
        )
        units.forEach { (unit, function) ->
            grouped(AggregationGroup.DateHistogram(CREATED_AT, "g", unit, "Asia/Shanghai")).assert().contains(
                "toInt64(toUnixTimestamp(toDateTime($function(toTimeZone(toDateTime64(`created_at`, 3, 'UTC'), " +
                    "'Asia/Shanghai')), 'Asia/Shanghai'))) * 1000 AS __g0",
            )
        }
        val parts = mapOf(
            AggregationDatePart.DAY_OF_WEEK to "toDayOfWeek",
            AggregationDatePart.DAY_OF_MONTH to "toDayOfMonth",
            AggregationDatePart.HOUR_OF_DAY to "toHour",
            AggregationDatePart.MONTH_OF_YEAR to "toMonth",
        )
        parts.forEach { (part, function) ->
            grouped(AggregationGroup.DatePart(DAY, "g", part)).assert()
                .contains("$function(toTimeZone(toDateTime64(`day`, 3, 'UTC'), 'UTC')) AS __g0")
        }
        grouped(AggregationGroup.DateHistogram(EPOCH_MS, "g", AggregationDateUnit.DAY)).assert()
            .contains("fromUnixTimestamp64Milli(toInt64(`epoch_ms`), 'UTC')")
        grouped(AggregationGroup.DateHistogram(EPOCH_S, "g", AggregationDateUnit.DAY)).assert()
            .contains("fromUnixTimestamp64Milli(toInt64(`epoch_s`) * 1000, 'UTC')")
        grouped(
            AggregationGroup.Terms(CITY, "g")
        ).assert().contains("SELECT `city` AS __g0").doesNotContain("isNotNull(__g0)")
        grouped(
            AggregationGroup.Terms(NOTE, "g", missingKey = "none")
        ).assert().contains("ifNull(`note`, {p0:String}) AS __g0")
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
    fun `metrics aggregate natively, with an If combinator under a metric filter`() {
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
        ).forEach { sql.assert().contains(it) }
    }

    @Test
    fun `HAVING filters the outer query in double space and a NULL metric satisfies no comparison`() {
        val operators = mapOf(
            ComparisonOperator.EQ to "=",
            ComparisonOperator.NE to "!=",
            ComparisonOperator.GT to ">",
            ComparisonOperator.GTE to ">=",
            ComparisonOperator.LT to "<",
            ComparisonOperator.LTE to "<=",
        )
        operators.forEach { (comparison, operator) ->
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
    fun `the outer query sorts by aliases and limits only a first window`() {
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
            metrics = listOf(AggregationMetric.Count("orders")),
            sort = listOf(Sort(QueryField("orders"), Sort.Direction.DESC)),
        )
        compile(query, GroupWindow.First(5)).assert()
            .endsWith("ORDER BY `orders` DESC, `city` ASC LIMIT 5")
        compile(query, GroupWindow.All).assert().endsWith("ORDER BY `orders` DESC, `city` ASC")
        compile(AggregationQuery(metrics = listOf(AggregationMetric.Count("orders"))), GroupWindow.First(1)).assert()
            .isEqualTo("SELECT __m0 AS `orders` FROM (SELECT count() AS __m0 FROM `bi`.`orders` WHERE 1) LIMIT 1")
    }

    @Test
    fun `the table must confirm every declared field`() {
        val invalid = mapOf<String, me.ahoo.wow.query.schema.QueryDatasetBuilder.() -> Unit>(
            "has no column for" to { field("missing") { types(QueryValueType.STRING) } },
            "declares a nested field" to {
                field("city") {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
                field("profile.name") { types(QueryValueType.STRING) }
            },
            "unsupported type" to { field("tags") { types(QueryValueType.STRING) } },
            "columns are scalar" to { field("note") { kind(me.ahoo.wow.api.query.schema.QueryValueKind.OBJECT) } },
            "holds" to { field("amount") { types(QueryValueType.STRING) } },
            "TEMPORAL_DATE" to { field("day") { types(QueryValueType.STRING) } },
            "non-null, but its column is Nullable" to {
                field("note") {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            },
        )
        invalid.forEach { (message, block) ->
            val dataset = QueryDataset.of(DATASET.namedAggregate) {
                tenant("tenant_id")
                field("tenant_id") {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
                block()
            }
            assertThrows<QuerySchemaValidationException>(
                message
            ) { schema(dataset, ClickHouseDatasetStorage("bi", "orders")) }
                .message.assert().contains(message)
        }
        val nullableTenant = QueryDataset.of(DATASET.namedAggregate) {
            tenant("note")
            field("note") { types(QueryValueType.STRING) }
        }
        assertThrows<QuerySchemaValidationException> {
            schema(
                nullableTenant,
                ClickHouseDatasetStorage("bi", "orders")
            )
        }
            .message.assert().contains("keys on the Nullable column")
        val failing = ClickHouseQuerySchemaAdapter(DATASET, ClickHouseDatasetStorage("bi", "orders")) {
            Mono.error(IllegalStateException("down"))
        }
        assertThrows<me.ahoo.wow.query.schema.QuerySchemaUnavailableException> { schema(failing) }
    }

    @Test
    fun `a dataset storage the backend cannot read fails the compile`() {
        val storage = ClickHouseDatasetStorage("bi", "orders", ClickHouseDatasetStorage.Deduplicate.FINAL)
        val table = TABLE
        val adapter = ClickHouseQuerySchemaAdapter(DATASET, storage) { Mono.just(table) }
        assertThrows<QuerySchemaValidationException> { schema(adapter) }.message.assert().contains("FINAL")
    }

    private fun where(filter: FilterExpression): String = statement(filter).sql

    private fun statement(filter: FilterExpression): ClickHouseStatement {
        val admitted = QueryAdmission.Trusted.count(filter, SCHEMA)
        val parameters = SqlParameters()
        return parameters.statement(ClickHouseFilterCompiler(admitted, TABLE, parameters).compile(admitted.query))
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
        ClickHouseAggregationCompiler(QueryAdmission.Trusted.aggregate(query, SCHEMA), TABLE, TABLE.qualifiedName)
            .compile(window).sql

    private companion object {
        val CITY = QueryField("city")
        val NOTE = QueryField("note")
        val AMOUNT = QueryField("amount")
        val QUANTITY = QueryField("quantity")
        val CREATED_AT = QueryField("created_at")
        val PAID_AT = QueryField("paid_at")
        val PAID = QueryField("paid")
        val DAY = QueryField("day")
        val EPOCH_MS = QueryField("epoch_ms")
        val EPOCH_S = QueryField("epoch_s")

        val TABLE = ClickHouseTable(
            "bi",
            "orders",
            "MergeTree",
            listOf(
                ClickHouseColumn("paid", "Bool"),
                ClickHouseColumn("tags", "Array(String)"),
                ClickHouseColumn("id", "String"),
                ClickHouseColumn("tenant_id", "String"),
                ClickHouseColumn("owner_id", "String"),
                ClickHouseColumn("space_id", "String"),
                ClickHouseColumn("city", "LowCardinality(String)"),
                ClickHouseColumn("note", "Nullable(String)"),
                ClickHouseColumn("amount", "Decimal(18, 2)"),
                ClickHouseColumn("quantity", "Int64"),
                ClickHouseColumn("created_at", "DateTime64(3, 'UTC')"),
                ClickHouseColumn("paid_at", "Nullable(DateTime64(3, 'UTC'))"),
                ClickHouseColumn("day", "Date"),
                ClickHouseColumn("epoch_ms", "Int64"),
                ClickHouseColumn("epoch_s", "Int64"),
            ).associateBy(ClickHouseColumn::name),
        )

        val DATASET: QueryDataset = QueryDataset.of(MaterializedNamedAggregate("bi", "orders")) {
            tenant("tenant_id")
            owner("owner_id")
            space("space_id")
            identity("id")
            eventTime("created_at")
            storage("""{"table":"bi.orders"}""".toObjectNode())
            listOf("id", "tenant_id", "owner_id", "space_id", "city").forEach {
                field(it) {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
            field("note") { types(QueryValueType.STRING) }
            field("paid") {
                types(QueryValueType.BOOLEAN)
                nullable(false)
            }
            field("amount") {
                types(QueryValueType.DECIMAL)
                nullable(false)
            }
            field("quantity") {
                types(QueryValueType.INTEGER)
                nullable(false)
            }
            field("created_at") {
                types(QueryValueType.STRING)
                nullable(false)
                semantic(Temporal.Date)
            }
            field("paid_at") {
                types(QueryValueType.STRING)
                semantic(Temporal.Date)
            }
            field("day") {
                types(QueryValueType.STRING)
                nullable(false)
                semantic(Temporal.Date)
            }
            field("epoch_ms") {
                types(QueryValueType.INTEGER)
                nullable(false)
                temporalEpoch()
            }
            field("epoch_s") {
                types(QueryValueType.INTEGER)
                nullable(false)
                temporalEpoch(TimeUnit.SECONDS)
            }
        }

        fun schema(adapter: ClickHouseQuerySchemaAdapter, dataset: QueryDataset = DATASET): QueryModelSchema =
            QueryModelCompiler.of(datasets = QueryDatasets(listOf(dataset)))
                .compile(QuerySchemaContext(dataset.namedAggregate, QueryModel.DATASET), adapter)
                .schema().block()!!

        fun schema(dataset: QueryDataset, storage: ClickHouseDatasetStorage): QueryModelSchema {
            val table = TABLE
            return schema(ClickHouseQuerySchemaAdapter(dataset, storage) { Mono.just(table) }, dataset)
        }

        // Lazy: compiling blocks on a scheduler thread that reads TABLE, which a class initializer would still hold.
        val SCHEMA: QueryModelSchema by lazy {
            schema(ClickHouseQuerySchemaAdapter(DATASET, ClickHouseDatasetStorage("bi", "orders")) { Mono.just(TABLE) })
        }
    }
}
