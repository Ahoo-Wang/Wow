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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
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
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.DerivedExpression
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
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.QueryModelSchema
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Runs the same queries on MongoDB and on the in-memory reference backend, whose semantics are canonical, over
 * records that cover what the TCK leaves out: unicode and wildcards, values of the wrong type, scalars in array
 * fields, nested elements, epochs of other units, time zones, and every metric and HAVING form.
 */
class MongoDifferentialQueryTest {
    private val type = AggregateType("diff_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

    private class Side(val backend: QueryBackend, val schema: QueryModelSchema) {
        suspend fun ids(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> =
            backend.list(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort, limit = 100), schema)).toList()
                .map { it.path("aggregateId").stringValue() }

        suspend fun rows(query: ListQuery): List<ObjectNode> =
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()

        suspend fun aggregate(query: AggregationQuery): List<Map<String, Any?>> =
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().map { it.toMap() }

        suspend fun cursorIds(sort: List<Sort>, size: Int, filter: FilterExpression = MatchAllFilter): List<List<String>> {
            val pages = mutableListOf<List<String>>()
            var token: String? = null
            do {
                val page = backend.cursor(
                    QueryAdmission.Trusted.cursor(CursorQuery(filter, sort = sort, size = size, cursor = token), schema),
                )
                pages += page.list.map { it.path("aggregateId").stringValue() }
                token = page.nextCursor
            } while (token != null && pages.size < 20)
            return pages
        }
    }

    private val sides: Pair<Side, Side> by lazy {
        runBlocking {
            val orders = QueryFixtures.orders(type)
            val store = MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
            orders.forEach { store.save(it) }
            val mongo = MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context).binding(type)
            val memory = InMemoryQueryStorage().also { storage -> orders.forEach(storage::save) }.binding(type)
            Side(mongo!!.backend, QueryFixtures.schema(type, mongo!!.storage)) to
                Side(memory.backend, QueryFixtures.schema(type, memory.storage))
        }
    }

    private fun <T> same(name: String, run: suspend Side.() -> T): DynamicTest = DynamicTest.dynamicTest(name) {
        runBlocking {
            val (mongo, memory) = sides
            mongo.run().assert().describedAs(name).isEqualTo(memory.run())
        }
    }

    @TestFactory
    fun `filters match the reference semantics`(): List<DynamicTest> = FILTERS.map { (name, filter) ->
        same(name) { ids(filter).sorted() }
    }

    @TestFactory
    fun `sorts and cursors order as the reference`(): List<DynamicTest> = listOf(
        same("name ascending") { ids(MatchAllFilter, listOf(Sort(NAME, Sort.Direction.ASC))) },
        same("name descending") { ids(MatchAllFilter, listOf(Sort(NAME, Sort.Direction.DESC))) },
        // A value of another type than its field's sorts as null here; the reference orders it by its own type.
        same("flag then amount") {
            ids(ACTIVE, listOf(Sort(field("flag"), Sort.Direction.DESC), Sort(AMOUNT, Sort.Direction.ASC)))
        },
        same("version") { ids(MatchAllFilter, listOf(Sort(QueryField("version"), Sort.Direction.DESC))) },
        same("cursor by nullable status ascending") { cursorIds(listOf(Sort(STATUS, Sort.Direction.ASC)), 2) },
        same("cursor by nullable status descending") { cursorIds(listOf(Sort(STATUS, Sort.Direction.DESC)), 2) },
        same("cursor by flag and amount") {
            cursorIds(listOf(Sort(field("flag"), Sort.Direction.ASC), Sort(AMOUNT, Sort.Direction.DESC)), 1, ACTIVE)
        },
        same("cursor by lastEventTime") { cursorIds(listOf(Sort(QueryField("lastEventTime"), Sort.Direction.DESC)), 2) },
        same("cursor by version") { cursorIds(listOf(Sort(QueryField("version"), Sort.Direction.ASC)), 3) },
        same("cursor by an inexact decimal") { cursorIds(listOf(Sort(AMOUNT, Sort.Direction.ASC)), 2, ACTIVE) },
        same("cursor by tenant descending") { cursorIds(listOf(Sort(QueryField("tenantId"), Sort.Direction.DESC)), 2) },
        // An array sorts by its least element ascending and its greatest descending. MongoDB sorts an empty array
        // before a missing field, where the reference ties them, so the missing one is filtered out.
        same("array field ascending") { ids(ExistsFilter(KEYWORDS), listOf(Sort(KEYWORDS, Sort.Direction.ASC), BY_ID)) },
        same("array field descending") { ids(ExistsFilter(KEYWORDS), listOf(Sort(KEYWORDS, Sort.Direction.DESC), BY_ID)) },
        same("offset page with a bounded total") {
            val query = PagedQuery(ACTIVE, sort = listOf(Sort(AMOUNT, Sort.Direction.DESC)), pagination = Pagination(2, 2))
            backend.paged(QueryAdmission.Trusted.paged(query, schema), totalLimit = 3).let { page ->
                page.list.map { it.path("aggregateId").stringValue() } to (page.total to page.exact)
            }
        },
    )

    @TestFactory
    fun `projections keep what the reference keeps`(): List<DynamicTest> = listOf(
        Projection(include = listOf(QueryField("aggregateId"), QueryField("tags"))),
        Projection(include = listOf(QueryField("state.lines.sku"), QueryField("state.name"))),
        Projection(exclude = listOf(QueryField("state.lines"), QueryField("tags"))),
        Projection(include = listOf(QueryField("state")), exclude = listOf(QueryField("state.keywords"))),
        Projection(include = listOf(QueryField("state.lines.parts.name"), QueryField("tenantId"))),
        Projection(exclude = listOf(QueryField("state.lines.parts"), QueryField("deleted"), QueryField("lastEventTime"))),
    ).mapIndexed { index, projection ->
        same("projection $index") {
            rows(ListQuery(MatchAllFilter, projection = projection, sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC))))
                .map { it.toMap() }
        }
    }

    @TestFactory
    fun `aggregations compute what the reference computes`(): List<DynamicTest> = AGGREGATIONS.map { (name, query) ->
        same(name) { aggregate(query) }
    }

    companion object {
        private fun field(name: String) = QueryField("state.$name")
        private val NAME = field("name")
        private val STATUS = field("status")
        private val AMOUNT = field("amount")
        private val QUANTITY = field("quantity")
        private val KEYWORDS = field("keywords")
        private val SCORES = field("scores")
        private val CREATED = field("createdAt")
        private val REGION = QueryField("tags.region")
        private val ACTIVE = DeletionFilter(DeletionState.ACTIVE)
        private val BY_ID = Sort(QueryField("aggregateId"), Sort.Direction.ASC)

        private fun JsonNode.normalized(): Any? = when {
            isObject -> properties().associate { (name, value) -> name to value.normalized() }
            isArray -> toList().map { it.normalized() }
            isNumber -> BigDecimal(doubleValue()).setScale(6, RoundingMode.HALF_UP)
            isString -> stringValue()
            isBoolean -> booleanValue()
            else -> null
        }

        private fun ObjectNode.toMap(): Map<String, Any?> = normalized().let {
            @Suppress("UNCHECKED_CAST")
            it as Map<String, Any?>
        }

        val FILTERS: List<Pair<String, FilterExpression>> = listOf(
            "match none" to MatchNoneFilter,
            "id" to IdFilter("b"),
            "owner" to OwnerIdFilter("owner-1"),
            "space" to SpaceIdFilter("space-1"),
            "space is null" to IsNullFilter(QueryField("spaceId")),
            "owner exists" to ExistsFilter(QueryField("ownerId")),
            "deleted" to DeletionFilter(DeletionState.DELETED),
            "eq unicode" to EqualFilter(NAME, "Ärger"),
            "ne" to NotEqualFilter(NAME, "beta"),
            "in" to InFilter(STATUS, listOf("PAID", "NEW")),
            "not in" to NotInFilter(STATUS, listOf("PAID", "NEW")),
            "contains ignoring case" to ContainsFilter(NAME, "E", StringComparison.CASE_INSENSITIVE),
            "starts with unicode ignoring case" to StartsWithFilter(NAME, "ä", StringComparison.CASE_INSENSITIVE),
            "ends with" to EndsWithFilter(NAME, "lta"),
            "empty string" to IsEmptyStringFilter(NAME),
            "not empty string" to IsNotEmptyStringFilter(NAME),
            "null status" to IsNullFilter(STATUS),
            "not null status" to IsNotNullFilter(STATUS),
            "explicit null exists" to ExistsFilter(field("note")),
            "explicit null does not exist" to NotExistsFilter(field("note")),
            "decimal equality across scales" to EqualFilter(AMOUNT, BigDecimal("10.5")),
            "decimal range skips a string" to GreaterThanFilter(AMOUNT, 15),
            "decimal between" to BetweenFilter(AMOUNT, 15, 35.5),
            "integer in" to InFilter(QUANTITY, listOf(1, 4)),
            "boolean" to EqualFilter(field("flag"), true),
            "boolean in" to InFilter(field("flag"), listOf(false)),
            "boolean ne" to NotEqualFilter(field("flag"), true),
            "version" to LessThanOrEqualFilter(QueryField("version"), 2),
            "event time" to GreaterThanOrEqualFilter(QueryField("lastEventTime"), QueryFixtures.T0 + QueryFixtures.DAY),
            "epoch seconds" to LessThanFilter(field("createdSeconds"), QueryFixtures.T0 / 1000 + 1),
            "formatted date range" to GreaterThanFilter(field("code"), "2026-01-05"),
            "array element" to EqualFilter(KEYWORDS, "z"),
            "array equality" to EqualFilter(KEYWORDS, listOf("x", "y_1")),
            "array inequality" to NotEqualFilter(KEYWORDS, listOf("x", "y_1")),
            "array in" to InFilter(KEYWORDS, listOf("z", "x")),
            "array not in" to NotInFilter(KEYWORDS, listOf("z")),
            "array contains all" to ContainsAllFilter(KEYWORDS, listOf("y_1", "x")),
            "array literal underscore" to ContainsFilter(KEYWORDS, "_"),
            "array literal percent" to EndsWithFilter(KEYWORDS, "%"),
            "array literal ignoring case" to StartsWithFilter(KEYWORDS, "X", StringComparison.CASE_INSENSITIVE),
            "array range" to GreaterThanFilter(KEYWORDS, "y"),
            "array between" to BetweenFilter(SCORES, 3, 6),
            "numeric array element" to EqualFilter(SCORES, 7),
            "array empty" to IsEmptyFilter(KEYWORDS),
            "array null" to IsNullFilter(KEYWORDS),
            "tag" to EqualFilter(REGION, "west"),
            "tags contain all" to ContainsAllFilter(REGION, listOf("west", "east")),
            "tag missing" to NotExistsFilter(REGION),
            "nor" to NorFilter(listOf(EqualFilter(STATUS, "PAID"), ExistsFilter(NAME))),
            "or" to OrFilter(listOf(EqualFilter(STATUS, "PAID"), LessThanFilter(AMOUNT, 25))),
            "element match" to ElementMatchFilter(
                field("lines"),
                AndFilter(listOf(InFilter(QueryField("sku"), listOf("B", "C", "Z")), GreaterThanOrEqualFilter(QUANTITY.child(), 3))),
            ),
            "nested element match" to ElementMatchFilter(
                field("lines"),
                ElementMatchFilter(QueryField("parts"), EqualFilter(QueryField("name"), "nut")),
            ),
            "element nor" to ElementMatchFilter(field("lines"), NorFilter(listOf(EqualFilter(QueryField("sku"), "A")))),
            "expression" to ExpressionFilter(
                AggregationExpression.Binary(
                    AggregationExpressionOperator.DIVIDE,
                    AggregationExpression.Field(AMOUNT),
                    AggregationExpression.Field(QUANTITY),
                ),
                ComparisonOperator.GT,
                10.0,
            ),
            "expression by zero" to ExpressionFilter(
                AggregationExpression.Binary(
                    AggregationExpressionOperator.DIVIDE,
                    AggregationExpression.Field(AMOUNT),
                    AggregationExpression.Constant(0.0),
                ),
                ComparisonOperator.NE,
                1.0,
            ),
            "date difference" to ExpressionFilter(
                AggregationExpression.DateDiff(CREATED, QueryField("lastEventTime"), DateDiffUnit.HOUR),
                ComparisonOperator.LT,
                0.0,
            ),
            "double operand equals an inexact decimal" to EqualFilter(AMOUNT, 0.1),
            "decimal operand of another scale" to EqualFilter(AMOUNT, BigDecimal("0.100")),
            "inexact decimals in" to InFilter(AMOUNT, listOf(0.1, 20)),
            "inexact decimal range" to BetweenFilter(AMOUNT, 0.05, 0.1),
            "operator-like string" to EqualFilter(NAME, "${'$'}name"),
            "operator-like prefix" to StartsWithFilter(NAME, "${'$'}"),
            "regex metacharacters" to ContainsFilter(NAME, ".*"),
            "element match through a non-object item" to ElementMatchFilter(field("lines"), EqualFilter(QueryField("sku"), "F")),
            "nested element match in a single object" to ElementMatchFilter(
                field("lines"),
                NorFilter(listOf(ElementMatchFilter(QueryField("parts"), EqualFilter(QueryField("name"), "nut")))),
            ),
            "micro epoch difference" to ExpressionFilter(
                AggregationExpression.DateDiff(field("createdMicros"), field("createdSeconds"), DateDiffUnit.SECOND),
                ComparisonOperator.EQ,
                0.0,
            ),
        )

        private fun QueryField.child(): QueryField = QueryField(path.substringAfterLast('.'))

        private fun count(alias: String = "count", filter: FilterExpression = MatchAllFilter) = AggregationMetric.Count(alias, filter)

        private fun numeric(function: AggregationFunction, alias: String, field: QueryField = AMOUNT) =
            AggregationMetric.Numeric(function, AggregationExpression.Field(field), alias)

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
            "terms by an array's first value" to AggregationQuery(
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
