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

import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/**
 * Runs the same queries on MongoDB and on the in-memory reference backend, whose semantics are canonical, over
 * records that cover what the TCK leaves out: unicode and wildcards, values of the wrong type, scalars in array
 * fields, nested elements, epochs of other units, time zones, and every metric and HAVING form.
 */

internal class MongoDifferentialQueryTest : MongoDifferentialFixtures() {
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
        same("cursor by lastEventTime") {
            cursorIds(listOf(Sort(QueryField("lastEventTime"), Sort.Direction.DESC)), 2)
        },
        same("cursor by version") { cursorIds(listOf(Sort(QueryField("version"), Sort.Direction.ASC)), 3) },
        same("cursor by an inexact decimal") { cursorIds(listOf(Sort(AMOUNT, Sort.Direction.ASC)), 2, ACTIVE) },
        same("cursor by tenant descending") { cursorIds(listOf(Sort(QueryField("tenantId"), Sort.Direction.DESC)), 2) },
        // An array sorts by its least element ascending and its greatest descending. MongoDB sorts an empty array
        // before a missing field, where the reference ties them, so the missing one is filtered out.
        same("array field ascending") {
            ids(ExistsFilter(KEYWORDS), listOf(Sort(KEYWORDS, Sort.Direction.ASC), BY_ID))
        },
        same("array field descending") {
            ids(ExistsFilter(KEYWORDS), listOf(Sort(KEYWORDS, Sort.Direction.DESC), BY_ID))
        },
        same("offset page with a bounded total") {
            val query = PagedQuery(
                ACTIVE,
                sort = listOf(Sort(AMOUNT, Sort.Direction.DESC)),
                pagination = Pagination(2, 2)
            )
            backend.paged(QueryAdmission.Trusted.paged(query, schema), totalLimit = 3).let { page ->
                page.list.map { it.path("aggregateId").stringValue() } to (page.total to page.exact)
            }
        },
    )

    @TestFactory
    fun `projections keep what the reference keeps`(): List<DynamicTest> = listOf(
        Projection(include = listOf(QueryField("aggregateId"), QueryField("access"))),
        Projection(include = listOf(QueryField("state.lines.sku"), QueryField("state.name"))),
        Projection(exclude = listOf(QueryField("state.lines"), QueryField("access"))),
        Projection(include = listOf(QueryField("state")), exclude = listOf(QueryField("state.keywords"))),
        Projection(include = listOf(QueryField("state.lines.parts.name"), QueryField("tenantId"))),
        Projection(
            exclude = listOf(QueryField("state.lines.parts"), QueryField("deleted"), QueryField("lastEventTime"))
        ),
    ).mapIndexed { index, projection ->
        same("projection $index") {
            rows(
                ListQuery(
                    MatchAllFilter,
                    projection = projection,
                    sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC))
                )
            )
                .map { it.toMap() }
        }
    }

    companion object {
        private val SCORES = field("scores")
        private val REGION = QueryField("access.region")
        private val BY_ID = Sort(QueryField("aggregateId"), Sort.Direction.ASC)

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
    }
}
