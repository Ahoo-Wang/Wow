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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.QueryBudgetFixtures.budget
import me.ahoo.wow.query.QueryBudgetFixtures.expectAllowed
import me.ahoo.wow.query.QueryBudgetFixtures.expectRejected
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.admission.withDefaultLimit
import me.ahoo.wow.query.admission.withDefaultSize
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

class QueryBudgetTest {
    @Test
    fun rejectsInvalidConfiguration() {
        listOf(-1, -1, -1, -1, -1).forEachIndexed { index, value ->
            assertThrows<IllegalArgumentException> {
                QueryBudget(
                    label = "HTTP",
                    maxListSize = if (index == 0) value else 1,
                    maxPageSize = if (index == 1) value else 1,
                    maxPageWindow = if (index == 2) value.toLong() else 1,
                    maxFilterNodes = if (index == 3) value else 1,
                    maxFilterValues = if (index == 4) value else 1,
                )
            }
        }
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", maxResidualGroups = -1) }
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", countLimit = -1) }
            .message.assert().isEqualTo("countLimit must be greater than or equal to 0.")
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", timeout = (-1).seconds) }
            .message.assert().isEqualTo("timeout must not be negative.")
    }

    @Test
    fun `every rejection is a request violation naming its code and the request part it bounds`() {
        val size = QueryErrorCodes.SIZE_OUT_OF_RANGE
        val tooLarge = QueryErrorCodes.FILTER_TOO_LARGE
        val id = IdFilter("id")
        fun rule(check: QueryBudget.() -> Unit) = check
        listOf(
            rule { check(ListQuery(MatchAllFilter, limit = 1001)) } to
                Triple(size, "limit", "HTTP list query limit[1001] must be between 1 and 1000."),
            rule { check(PagedQuery(id, pagination = Pagination(index = 1, size = 101))) } to
                Triple(size, "pagination.size", "HTTP page size[101] must be between 1 and 100."),
            rule { check(PagedQuery(id, pagination = Pagination(index = 200, size = 100))) } to
                Triple(size, "pagination", "HTTP page window[20000] must not exceed 10000."),
            rule { check(CursorQuery(id, size = 101)) } to
                Triple(size, "size", "HTTP cursor size[101] must be between 1 and 100."),
            rule { check(ListQuery(ContainsFilter(QueryField("name"), "a"), limit = 1)) } to Triple(
                QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED,
                "filter",
                "HTTP query operator[CONTAINS] is disabled because expensive operators are not allowed.",
            ),
            rule { check(CountQuery(MatchAllFilter)) } to Triple(
                QueryErrorCodes.COUNT_REQUIRES_FILTER,
                "filter",
                "HTTP counting query must not match all documents.",
            ),
            rule { check(ListQuery(IdsFilter((0..1000).map(Int::toString)), limit = 1)) } to
                Triple(tooLarge, "filter", "HTTP query filter values[1001] must not exceed 1000."),
            rule { check(ListQuery(AndFilter(List(128) { IdFilter("$it") }), limit = 1)) } to
                Triple(tooLarge, "filter", "HTTP query filter nodes[129] must not exceed 128."),
        ).forEach { (check, expected) ->
            assertThrows<QueryRequestException> { budget().check() }
                .let { Triple(it.code, it.bindingErrors.single().name, it.message) }
                .assert().isEqualTo(expected)
        }
        // Rejections are request violations: invalid, with the violation they render.
        assertThrows<QueryRequestException> { budget().check(ListQuery(MatchAllFilter, limit = 1001)) }.run {
            errorCode.assert().isEqualTo(QueryErrors.INVALID)
            violation.assert().isInstanceOf(QueryViolation.SizeOutOfRange::class.java)
        }
    }

    @Test
    fun zeroCapsDisableInputLimits() {
        val budget = budget(maxListSize = 0, maxPageSize = 0)
        budget.check(ListQuery(MatchAllFilter))
        budget.check(PagedQuery(IdFilter("id"), pagination = Pagination(size = 1000)))
        budget.check(CursorQuery(IdFilter("id"), size = 1000))
        val ids = IdsFilter(List(1500) { "$it" })
        budget(maxFilterNodes = 0, maxFilterValues = 0).check(ListQuery(AndFilter(List(200) { ids }), limit = 1))
        budget(maxPageWindow = 0).check(PagedQuery(IdFilter("id"), pagination = Pagination(index = 10_000, size = 100)))
    }

    @Test
    fun messagesOpenWithTheBudgetLabel() {
        assertThrows<IllegalArgumentException> {
            QueryBudget("In-process", maxListSize = 1).check(ListQuery(MatchAllFilter, limit = 2))
        }
            .message.assert().isEqualTo("In-process list query limit[2] must be between 1 and 1.")
    }

    @Test
    fun `list default applies the configured size within the cap`() {
        budget().listDefault(10).assert().isEqualTo(10)
        budget().listDefault(5000).assert().isEqualTo(1000)
        budget().listDefault(0).assert().isNull()
        budget(maxListSize = 0).listDefault(10).assert().isNull()
    }

    @Test
    fun `single queries are budgeted on their filter and scope`() {
        expectAllowed(QueryType.SINGLE, SingleQuery(IdFilter("id")))
        expectRejected(QueryType.SINGLE, SingleQuery(ContainsFilter(QueryField("name"), "a")))
        expectRejected(
            QueryType.SINGLE,
            SingleQuery(MatchAllFilter),
            budget(maxFilterNodes = 1, allowExpensiveOperators = true),
            AndFilter(listOf(IdFilter("a"), IdFilter("b"))),
        )
        // A single query is not counting: matching everything is allowed.
        expectAllowed(QueryType.SINGLE, SingleQuery(MatchAllFilter))
    }

    @Test
    fun enforcesListAndFilterBudgetsBeforeInvokingGateway() {
        val aggregateId = QueryField("aggregateId")
        val allowed = ListQuery(AndFilter(List(127) { EqualFilter(aggregateId, it) }), limit = 1)
        expectAllowed(QueryType.LIST, allowed, budget())

        val rejected = listOf(
            ListQuery(MatchAllFilter),
            ListQuery(ContainsFilter(aggregateId, "wow"), limit = 1),
            ListQuery(EndsWithFilter(aggregateId, "wow"), limit = 1),
            ListQuery(StartsWithFilter(aggregateId, ""), limit = 1),
            ListQuery(StartsWithFilter(aggregateId, "wow", StringComparison.CASE_INSENSITIVE), limit = 1),
            ListQuery(NotEqualFilter(aggregateId, "aggregate-id"), limit = 1),
            ListQuery(NotInFilter(aggregateId, listOf("aggregate-id")), limit = 1),
            ListQuery(NorFilter(listOf(EqualFilter(aggregateId, "aggregate-id"))), limit = 1),
            ListQuery(IsNullFilter(aggregateId), limit = 1),
            ListQuery(IsNotNullFilter(aggregateId), limit = 1),
            ListQuery(NotExistsFilter(aggregateId), limit = 1),
            ListQuery(IsEmptyFilter(QueryField("state.items")), limit = 1),
            ListQuery(InFilter(aggregateId, List(1001) { it }), limit = 1),
            ListQuery(IdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(AggregateIdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(AndFilter(List(128) { EqualFilter(aggregateId, it) }), limit = 1),
        )
        rejected.forEach { expectRejected(QueryType.LIST, it) }
    }

    @Test
    fun enforcesPageCursorAndCountingBudgets() {
        listOf(
            Pagination(index = 1, size = 101),
            Pagination(index = 101, size = 100),
            Pagination(index = Int.MAX_VALUE, size = 100),
        ).forEach { pagination ->
            expectRejected(QueryType.PAGED, PagedQuery(MatchAllFilter, pagination = pagination))
        }
        expectRejected(
            QueryType.PAGED,
            PagedQuery(MatchAllFilter, pagination = Pagination(index = 1_500_000_000, size = 2)),
            budget(maxPageSize = 0, maxPageWindow = Long.MAX_VALUE),
        )
        expectAllowed(
            QueryType.CURSOR,
            CursorQuery(MatchAllFilter, size = 2),
            budget(maxPageSize = 2, maxPageWindow = 1),
        )
        expectRejected(QueryType.CURSOR, CursorQuery(MatchAllFilter, size = 3), budget(maxPageSize = 2))
        expectRejected(
            QueryType.CURSOR,
            CursorQuery(ContainsFilter(QueryField("state.name"), "x")),
        )

        expectRejected(QueryType.COUNT, CountQuery(MatchAllFilter))
        expectRejected(QueryType.COUNT, CountQuery(DeletionFilter(DeletionState.ALL)))
        expectAllowed(QueryType.COUNT, CountQuery(MatchAllFilter), scope = IdFilter("aggregate-id"))
        expectAllowed(QueryType.COUNT, CountQuery(MatchAllFilter), budget(allowExpensiveOperators = true))
    }

    @Test
    fun `a cursor page that names no size takes the entry's default list size, at most the budget's page size`() {
        QueryBudget(QueryBudget.HTTP_LABEL, maxPageSize = 100).cursorDefault(100).assert().isEqualTo(100)
        QueryBudget(QueryBudget.HTTP_LABEL, maxPageSize = 100).cursorDefault(20).assert().isEqualTo(20)
        // A default list size above the page size gives the page size.
        QueryBudget(QueryBudget.HTTP_LABEL, maxPageSize = 50).cursorDefault(100).assert().isEqualTo(50)
        QueryBudget(QueryBudget.HTTP_LABEL, maxPageSize = 0).cursorDefault(100).assert().isEqualTo(100)
        // No default list size: the page size; neither: none, so the gateway pages by its own size.
        QueryBudget(QueryBudget.HTTP_LABEL, maxPageSize = 50).cursorDefault(0).assert().isEqualTo(50)
        QueryBudget(QueryBudget.HTTP_LABEL, maxPageSize = 0).cursorDefault(0).assert().isNull()
        // A query that names no size is never out of the budget's range.
        QueryBudget(QueryBudget.HTTP_LABEL, maxPageSize = 3).check(CursorQuery())
    }

    @Test
    fun `a cursor query's size is filled in only when it names none`() {
        val query = CursorQuery(
            IdFilter("id"),
            Projection(include = listOf(QueryField("state.name"))),
            listOf(Sort(QueryField("state.name"), Sort.Direction.ASC)),
            cursor = "next",
        )
        query.withDefaultSize(7).assert().isEqualTo(
            CursorQuery(query.filter, query.projection, query.sort, 7, query.cursor),
        )
        query.withDefaultSize(null).assert().isSameAs(query)
        val sized = CursorQuery(size = 3)
        sized.withDefaultSize(7).assert().isSameAs(sized)
    }

    @Test
    fun `a list's limit is filled in only when it names none`() {
        val query = ListQuery(
            IdFilter("id"),
            Projection(include = listOf(QueryField("state.name"))),
            listOf(Sort(QueryField("state.name"), Sort.Direction.ASC)),
        )
        query.withDefaultLimit(7).assert().isEqualTo(ListQuery(query.filter, query.projection, query.sort, 7))
        query.withDefaultLimit(null).assert().isSameAs(query)
        val limited = ListQuery(limit = 3)
        limited.withDefaultLimit(7).assert().isSameAs(limited)
    }

    @Test
    fun `a count limit defaults to the budget's and is capped by it`() {
        QueryBudget.DEFAULT_COUNT_LIMIT.assert().isEqualTo(10_000)
        val budget = QueryBudget(QueryBudget.HTTP_LABEL)
        budget.countLimit.assert().isEqualTo(QueryBudget.DEFAULT_COUNT_LIMIT)
        budget.allowExactCount.assert().isFalse()
        budget.totalLimit.assert().isEqualTo(10_000)
        budget.countLimit(CountQuery(IdFilter("id"))).assert().isEqualTo(10_000)
        budget.countLimit(CountQuery(IdFilter("id"), limit = 5)).assert().isEqualTo(5)

        budget.check(CountQuery(IdFilter("id"), limit = 10_000))
        assertThrows<QueryRequestException> { budget.check(CountQuery(IdFilter("id"), limit = 10_001)) }.run {
            code.assert().isEqualTo(QueryErrorCodes.SIZE_OUT_OF_RANGE)
            bindingErrors.single().name.assert().isEqualTo("limit")
            message.assert().isEqualTo("HTTP count limit[10001] must be between 1 and 10000.")
        }
        val small = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 3)
        small.check(CountQuery(IdFilter("id"), limit = 3))
        assertThrows<QueryRequestException> { small.check(CountQuery(IdFilter("id"), limit = 4)) }
            .bindingErrors.single().name.assert().isEqualTo("limit")
    }

    @Test
    fun `a zero count limit counts exactly and accepts any limit`() {
        val unbounded = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 0)
        unbounded.totalLimit.assert().isNull()
        unbounded.countLimit(CountQuery(IdFilter("id"))).assert().isNull()
        unbounded.countLimit(CountQuery(IdFilter("id"), limit = 7)).assert().isEqualTo(7)
        unbounded.check(CountQuery(IdFilter("id"), limit = Int.MAX_VALUE))
        // Without a bound, an EXACT total is what every total is.
        unbounded.check(PagedQuery(IdFilter("id"), total = TotalMode.EXACT))
    }

    @Test
    fun `an exact total is an expensive operation unless the entry allows it`() {
        val exact = PagedQuery(IdFilter("id"), total = TotalMode.EXACT)
        assertThrows<QueryRequestException> { QueryBudget(QueryBudget.HTTP_LABEL).check(exact) }.run {
            code.assert().isEqualTo(QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED)
            bindingErrors.single().name.assert().isEqualTo("total")
            message.assert().isEqualTo("HTTP exact total is disabled: ask for a BOUNDED total, or count with a limit.")
            violation.assert().isEqualTo(QueryViolation.ExactCountDisabled(QueryBudget.HTTP_LABEL))
        }
        QueryBudget(QueryBudget.HTTP_LABEL, allowExactCount = true).check(exact)
        QueryBudget(QueryBudget.HTTP_LABEL).check(PagedQuery(IdFilter("id"), total = TotalMode.BOUNDED))
        QueryBudget(QueryBudget.HTTP_LABEL).check(PagedQuery(IdFilter("id"), total = TotalMode.NONE))
    }

    @Test
    fun `a paged query without a total is not a counting query`() {
        expectRejected(QueryType.PAGED, PagedQuery(MatchAllFilter))
        expectRejected(QueryType.PAGED, PagedQuery(MatchAllFilter, total = TotalMode.BOUNDED))
        expectAllowed(QueryType.PAGED, PagedQuery(MatchAllFilter, total = TotalMode.NONE))
    }

    @Test
    fun `the budget defaults`() {
        QueryBudget.HTTP_DEFAULT.run {
            label.assert().isEqualTo(QueryBudget.HTTP_LABEL)
            maxListSize.assert().isEqualTo(1000)
            maxPageSize.assert().isEqualTo(100)
            maxPageWindow.assert().isEqualTo(10_000L)
            maxFilterNodes.assert().isEqualTo(QueryBudget.DEFAULT_MAX_FILTER_NODES)
            maxFilterValues.assert().isEqualTo(1000)
            allowExpensiveOperators.assert().isTrue()
            maxResidualGroups.assert().isEqualTo(QueryBudget.DEFAULT_MAX_RESIDUAL_GROUPS)
            timeout.assert().isEqualTo(30.seconds)
        }
        QueryBudget.DEFAULT_TIMEOUT.assert().isEqualTo(30.seconds)
    }
}
