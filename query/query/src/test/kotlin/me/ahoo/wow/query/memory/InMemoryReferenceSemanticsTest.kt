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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
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
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.error.QueryExecutionException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The reference backend's own semantics, beyond what the backend specs hold every backend to: each filter operator on
 * values of every shape, the sort order across kinds, the page windows, projections through objects and arrays, and
 * every group, metric and expression of an aggregation. It runs on one in-memory table whose rows hold their fields at
 * the root.
 */

internal class InMemoryReferenceSemanticsTest : InMemoryReferenceFixtures() {
    // Filters

    @Test
    fun `logical filters and the system fields`() {
        matches(OrFilter(listOf(EqualFilter(NAME, "Alpha"), EqualFilter(NAME, "Beta")))).assert()
            .containsExactlyInAnyOrder("r1", "r2")
        matches(NorFilter(listOf(EqualFilter(NAME, "Alpha"), EqualFilter(NAME, "Beta")))).assert()
            .containsExactlyInAnyOrder("r3", "r4", "r5")
        matches(MatchNoneFilter).assert().isEmpty()
        matches(IdFilter("r3")).assert().containsExactly("r3")
        matches(IdsFilter(listOf("r1", "r5"))).assert().containsExactlyInAnyOrder("r1", "r5")
        matches(TenantIdFilter("t2")).assert().containsExactly("r5")
    }

    @Test
    fun `equality and membership by value, on scalars and arrays`() {
        matches(EqualFilter(PRICE, 10)).assert().containsExactly("r1")
        matches(EqualFilter(TAGS, "b")).assert().containsExactlyInAnyOrder("r1", "r2")
        matches(NotEqualFilter(TAGS, "b")).assert().containsExactlyInAnyOrder("r3", "r4", "r5")
        matches(InFilter(QTY, listOf(1, 3))).assert().containsExactlyInAnyOrder("r1", "r3")
        matches(NotInFilter(QTY, listOf(1, 3))).assert().containsExactlyInAnyOrder("r2", "r4", "r5")
        matches(ContainsAllFilter(TAGS, listOf("a", "b"))).assert().containsExactly("r1")
        matches(EqualFilter(FLAG, true)).assert().containsExactlyInAnyOrder("r1", "r3")
    }

    @Test
    fun `ranges compare numbers by value and dates as instants`() {
        matches(GreaterThanFilter(PRICE, 20)).assert().containsExactlyInAnyOrder("r3", "r5")
        matches(GreaterThanOrEqualFilter(PRICE, 20)).assert().containsExactlyInAnyOrder("r2", "r3", "r5")
        matches(LessThanFilter(PRICE, 20)).assert().containsExactly("r1")
        matches(LessThanOrEqualFilter(QTY, 2)).assert().containsExactlyInAnyOrder("r1", "r2", "r5")
        matches(BetweenFilter(DAY, "2026-01-06", "2026-01-07T23:59:59Z")).assert()
            .containsExactlyInAnyOrder("r2", "r3")
        matches(GreaterThanFilter(DAY, "2026-01-07T00:00:00+08:00")).assert()
            .containsExactlyInAnyOrder("r3", "r4")
        // A stored day that is no instant, or a bound that is none, orders against nothing.
        matches(GreaterThanFilter(DAY, "not a day")).assert().isEmpty()
    }

    @Test
    fun `text predicates, case sensitive and not`() {
        matches(ContainsFilter(NAME, "lph")).assert().containsExactly("r1")
        matches(ContainsFilter(NAME, "LPH", StringComparison.CASE_INSENSITIVE)).assert().containsExactly("r1")
        matches(StartsWithFilter(NAME, "be", StringComparison.CASE_INSENSITIVE)).assert().containsExactly("r2")
        matches(StartsWithFilter(NAME, "be")).assert().isEmpty()
        matches(EndsWithFilter(NAME, "ma")).assert().containsExactly("r3")
        matches(ContainsFilter(TAGS, "c")).assert().containsExactly("r3")
    }

    @Test
    fun `presence and emptiness`() {
        matches(IsNullFilter(NOTE)).assert().containsExactlyInAnyOrder("r3", "r4", "r5")
        matches(IsNotNullFilter(NOTE)).assert().containsExactlyInAnyOrder("r1", "r2")
        matches(ExistsFilter(NOTE)).assert().containsExactlyInAnyOrder("r1", "r2", "r3")
        matches(NotExistsFilter(NOTE)).assert().containsExactlyInAnyOrder("r4", "r5")
        matches(IsEmptyFilter(TAGS)).assert().containsExactly("r4")
        matches(IsEmptyStringFilter(NOTE)).assert().containsExactly("r2")
        matches(IsNotEmptyStringFilter(NOTE)).assert().containsExactly("r1")
    }

    @Test
    fun `an element match reads each element's fields together`() {
        val line = ElementMatchFilter(
            LINES,
            AndFilter(listOf(EqualFilter(SKU, "A"), GreaterThanFilter(ELEMENT_PRICE, 6))),
        )
        matches(line).assert().containsExactly("r2")
    }

    @Test
    fun `an expression filter compares its value by each operator`() {
        val total = AggregationExpression.Binary(
            AggregationExpressionOperator.MULTIPLY,
            AggregationExpression.Field(PRICE),
            AggregationExpression.Field(QTY),
        )
        matches(ExpressionFilter(total, ComparisonOperator.EQ, 10.0)).assert().containsExactly("r1")
        matches(ExpressionFilter(total, ComparisonOperator.NE, 10.0)).assert()
            .containsExactlyInAnyOrder("r2", "r3", "r5")
        matches(ExpressionFilter(total, ComparisonOperator.GT, 40.0)).assert().containsExactly("r3")
        matches(ExpressionFilter(total, ComparisonOperator.GTE, 40.0)).assert().containsExactlyInAnyOrder("r2", "r3")
        matches(ExpressionFilter(total, ComparisonOperator.LT, 40.0)).assert().containsExactlyInAnyOrder("r1", "r5")
        matches(ExpressionFilter(total, ComparisonOperator.LTE, 10.0)).assert().containsExactlyInAnyOrder("r1", "r5")
        val sum = AggregationExpression.Binary(
            AggregationExpressionOperator.ADD,
            AggregationExpression.Field(PRICE),
            AggregationExpression.Constant(1.0),
        )
        matches(ExpressionFilter(sum, ComparisonOperator.EQ, 11.0)).assert().containsExactly("r1")
        val less = AggregationExpression.Binary(
            AggregationExpressionOperator.SUBTRACT,
            AggregationExpression.Field(PRICE),
            AggregationExpression.Field(QTY),
        )
        matches(ExpressionFilter(less, ComparisonOperator.EQ, 9.0)).assert().containsExactly("r1")
        // A division by zero has no value, so it matches nothing.
        val ratio = AggregationExpression.Binary(
            AggregationExpressionOperator.DIVIDE,
            AggregationExpression.Field(PRICE),
            AggregationExpression.Field(QTY),
        )
        matches(
            ExpressionFilter(ratio, ComparisonOperator.EQ, 10.0)
        ).assert().containsExactlyInAnyOrder("r1", "r2", "r3")
        matches(ExpressionFilter(ratio, ComparisonOperator.GTE, 0.0)).assert().doesNotContain("r5")
        val span = AggregationExpression.DateDiff(DAY, AT, DateDiffUnit.HOUR)
        matches(ExpressionFilter(span, ComparisonOperator.EQ, 1.0)).assert().containsExactly("r1")
    }

    @Test
    fun `what admission keeps from a backend fails as an execution error`() {
        assertThrows<QueryExecutionException> { direct(SearchFilter("alpha")) }
        assertThrows<QueryExecutionException> { direct(TodayFilter(DAY)) }
    }

    // Sort and pages

    @Test
    fun `sorts by every key in turn, a missing value lowest`() {
        ids(MatchAllFilter, listOf(Sort(FLAG, Sort.Direction.DESC), Sort(PRICE, Sort.Direction.ASC))).assert()
            .containsExactly("r1", "r3", "r2", "r5", "r4")
        ids(MatchAllFilter, listOf(Sort(TAGS, Sort.Direction.DESC))).assert().first().isEqualTo("r3")
        ids(MatchAllFilter, listOf(Sort(NOTE, Sort.Direction.ASC), Sort(ID, Sort.Direction.DESC))).assert()
            .containsExactly("r5", "r4", "r3", "r2", "r1")
    }

    @Test
    fun `offset pages count their total, bounded or not`() = runBlocking {
        val query = QueryAdmission.Trusted.list(
            ListQuery(MatchAllFilter, sort = listOf(Sort(ID, Sort.Direction.ASC))),
            schema
        )
        val page = backend.page(query, PageWindow.Offset(offset = 1, limit = 2, withTotal = true))
        page.rows.map { it.path("id").stringValue() }.assert().containsExactly("r2", "r3")
        page.total.assert().isEqualTo(5L)
        backend.page(query, PageWindow.Offset(0, 2, withTotal = true, totalLimit = 3)).total.assert().isEqualTo(4L)
        backend.page(query, PageWindow.Offset(0, 2, withTotal = false)).total.assert().isNull()
        backend.count(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter, 2), schema)).assert().isEqualTo(3L)
        backend.count(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema)).assert().isEqualTo(5L)
    }

    @Test
    fun `keyset pages continue after a position of every kind`() = runBlocking {
        val sort = listOf(Sort(PRICE, Sort.Direction.DESC), Sort(ID, Sort.Direction.ASC))
        val query = QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, sort = sort), schema)
        val first = backend.page(query, PageWindow.Keyset(null, 2))
        first.rows.map { it.path("id").stringValue() }.assert().containsExactly("r3", "r5")
        val second = backend.page(query, PageWindow.Keyset(first.positions!!.last(), 10))
        second.rows.map { it.path("id").stringValue() }.assert().containsExactly("r2", "r1", "r4")
        second.positions!!.last().values.assert().containsExactly(null, "r4")
        val after = backend.page(query, PageWindow.Keyset(CursorPosition(listOf(20, "r2")), 10))
        after.rows.map { it.path("id").stringValue() }.assert().containsExactly("r1", "r4")
        val byFlag = QueryAdmission.Trusted.list(
            ListQuery(MatchAllFilter, sort = listOf(Sort(FLAG, Sort.Direction.ASC), Sort(ID, Sort.Direction.ASC))),
            schema
        )
        backend.page(byFlag, PageWindow.Keyset(CursorPosition(listOf(false, "r2")), 10)).rows
            .map { it.path("id").stringValue() }.assert().containsExactly("r1", "r3")
        backend.page(byFlag, PageWindow.Keyset(null, 1)).positions!!.single().values.assert()
            .containsExactly(null, "r4")
    }

    @Test
    fun `projections include and exclude through objects and arrays`() = runBlocking {
        val include = QueryAdmission.Trusted.list(
            ListQuery(
                IdFilter("r1"),
                projection = Projection(include = listOf(ID, NAME, LINE_SKU, QueryField("missing.path"))),
            ),
            schema,
        )
        val included = backend.stream(include).toList().single()
        included.path("name").stringValue().assert().isEqualTo("Alpha")
        included.path("lines").toList().map { it.path("sku").stringValue() }.assert().containsExactly("A", "B")
        included.path("lines").first().has("price").assert().isFalse()
        included.has("price").assert().isFalse()
        val exclude = QueryAdmission.Trusted.list(
            ListQuery(IdFilter("r1"), projection = Projection(exclude = listOf(PRICE, LINE_PRICE, QueryField("x.y")))),
            schema,
        )
        val excluded = backend.stream(exclude).toList().single()
        excluded.has("price").assert().isFalse()
        excluded.path("lines").first().has("price").assert().isFalse()
        excluded.path("lines").first().path("sku").stringValue().assert().isEqualTo("A")
        val both = QueryAdmission.Trusted.list(
            ListQuery(IdFilter("r1"), projection = Projection(include = listOf(LINES), exclude = listOf(LINE_PRICE))),
            schema,
        )
        backend.stream(both).toList().single().path("lines").first().has("price").assert().isFalse()
        backend.stream(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, limit = 2), schema)).toList()
            .size.assert().isEqualTo(2)
    }

    private companion object {
        val ID = QueryField("id")
        val LINE_SKU = QueryField("lines.sku")
        val LINE_PRICE = QueryField("lines.price")
    }
}
