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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.api.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class FilterDslTest {
    private val status = QueryField("state.status")
    private val created = QueryField("state.createdAt")
    private val utc = ZoneOffset.UTC.id

    @Test
    fun `a block of one node is that node, of several their AND`() {
        filter { "state.status" eq "PAID" }.assert().isEqualTo(EqualFilter(status, "PAID"))
        filter {
            status eq "PAID"
            "state.amount" gt 10
        }.assert().isEqualTo(
            AndFilter(listOf(EqualFilter(status, "PAID"), GreaterThanFilter(QueryField("state.amount"), 10))),
        )
    }

    @Test
    fun `a filter block without expressions is rejected`() {
        assertThrows<IllegalArgumentException> { filter { } }
        assertThrows<IllegalArgumentException> { filter { and { } } }
        assertThrows<IllegalArgumentException> { filter { not { } } }
        assertThrows<IllegalArgumentException> { filter { "state".path { } } }
        assertThrows<IllegalArgumentException> { filter { "state.items".elementMatch { } } }
    }

    @Test
    fun `combinators take their blocks as operands, and not is a NOR of one`() {
        val paid = EqualFilter(status, "PAID")
        val new = EqualFilter(status, "NEW")
        filter {
            and {
                status eq "PAID"
                status eq "NEW"
            }
        }.assert().isEqualTo(AndFilter(listOf(paid, new)))
        filter {
            or {
                status eq "PAID"
                status eq "NEW"
            }
        }.assert().isEqualTo(OrFilter(listOf(paid, new)))
        filter {
            nor {
                status eq "PAID"
                status eq "NEW"
            }
        }.assert().isEqualTo(NorFilter(listOf(paid, new)))
        filter { not { status eq "PAID" } }.assert().isEqualTo(NorFilter(listOf(paid)))
        filter {
            not {
                status eq "PAID"
                status eq "NEW"
            }
        }.assert()
            .isEqualTo(NorFilter(listOf(AndFilter(listOf(paid, new)))))
    }

    @Test
    fun `eq and ne of null are the null checks`() {
        filter { status eq null }.assert().isEqualTo(IsNullFilter(status))
        filter { "state.status" ne null }.assert().isEqualTo(IsNotNullFilter(status))
        filter { status ne "PAID" }.assert().isEqualTo(NotEqualFilter(status, "PAID"))
    }

    @Test
    fun `values pass as they are, runtime values included`() {
        filter { status eq Sort.Direction.ASC }.assert().isEqualTo(EqualFilter(status, Sort.Direction.ASC))
        filter { status isIn listOf("PAID", "NEW") }.assert().isEqualTo(InFilter(status, listOf("PAID", "NEW")))
    }

    @Test
    fun `between takes two bounds or a closed range`() {
        val price = QueryField("state.price")
        filter { "state.price".between(1, 5) }.assert().isEqualTo(BetweenFilter(price, 1, 5))
        filter { price.between(1, 5) }.assert().isEqualTo(BetweenFilter(price, 1, 5))
        filter { "state.price" between 1..5 }.assert().isEqualTo(BetweenFilter(price, 1, 5))
        filter { price between 1.5..2.5 }.assert().isEqualTo(BetweenFilter(price, 1.5, 2.5))
    }

    @Test
    fun `system field filters`() {
        val expression = filter {
            id("id-1")
            ids("id-1", "id-2")
            ids(listOf("id-3"))
            aggregateId("aggregate-1")
            aggregateIds("aggregate-1", "aggregate-2")
            aggregateIds(listOf("aggregate-3"))
            tenantId("tenant-1")
            ownerId("owner-1")
            spaceId("space-1")
            deletion(DeletionState.ALL)
            matchAll()
            matchNone()
        } as AndFilter
        expression.operands.assert().containsExactly(
            IdFilter("id-1"),
            IdsFilter(listOf("id-1", "id-2")),
            IdsFilter(listOf("id-3")),
            AggregateIdFilter("aggregate-1"),
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
            AggregateIdsFilter(listOf("aggregate-3")),
            TenantIdFilter("tenant-1"),
            OwnerIdFilter("owner-1"),
            SpaceIdFilter("space-1"),
            DeletionFilter(DeletionState.ALL),
            MatchAllFilter,
            MatchNoneFilter,
        )
    }

    @Test
    fun `an element match reads fields under the element relative to it`() {
        val items = QueryField("state.items")
        val productId = QueryField("state.items.productId")
        val expected = ElementMatchFilter(items, EqualFilter(QueryField("productId"), "p1"))
        filter { "state.items".elementMatch { "productId" eq "p1" } }.assert().isEqualTo(expected)
        filter { items.elementMatch { productId eq "p1" } }.assert().isEqualTo(expected)
        filter { items.elementMatch { "state.items.productId" eq "p1" } }.assert().isEqualTo(expected)
    }

    @Test
    fun `a nested element match is relative to the outer element`() {
        val expression = filter {
            QueryField("state.lines").elementMatch {
                QueryField("state.lines.parts").elementMatch {
                    QueryField("state.lines.parts.sku") eq "A"
                    or { "quantity" gt 1 }
                    not { "state.lines.parts.sku" eq "B" }
                }
            }
        }
        expression.assert().isEqualTo(
            ElementMatchFilter(
                QueryField("state.lines"),
                ElementMatchFilter(
                    QueryField("parts"),
                    AndFilter(
                        listOf(
                            EqualFilter(QueryField("sku"), "A"),
                            OrFilter(listOf(GreaterThanFilter(QueryField("quantity"), 1))),
                            NorFilter(listOf(EqualFilter(QueryField("sku"), "B"))),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a path scope prefixes relative fields once`() {
        val expression = filter {
            "state".path {
                "status" eq "PAID"
                status eq "NEW"
                "items".path { "productId" eq "p1" }
            }
            QueryField("state.address").path { "city" eq "Shanghai" }
        } as AndFilter
        expression.operands.assert().containsExactly(
            AndFilter(
                listOf(
                    EqualFilter(status, "PAID"),
                    EqualFilter(status, "NEW"),
                    EqualFilter(QueryField("state.items.productId"), "p1"),
                ),
            ),
            EqualFilter(QueryField("state.address.city"), "Shanghai"),
        )
    }

    @Test
    fun `a prebuilt expression cannot enter a path scope, and a system filter is one`() {
        val prebuilt = filter { status eq "PAID" }
        assertThrows<IllegalArgumentException> { filter { "state".path { expression(prebuilt) } } }
        assertThrows<IllegalArgumentException> { filter { "state".path { deletion(DeletionState.DELETED) } } }
        filter { or { expression(prebuilt) } }.assert().isEqualTo(OrFilter(listOf(prebuilt)))
    }

    @Test
    fun `an expression filter compares a computed value`() {
        val hours = AggregationExpression.DateDiff(
            QueryField("state.paidAt"),
            QueryField("state.shippedAt"),
            DateDiffUnit.HOUR,
        )
        filter { expression(hours, ComparisonOperator.GT, 24.0) }.assert()
            .isEqualTo(ExpressionFilter(hours, ComparisonOperator.GT, 24.0))
    }

    @Test
    fun `search names its fields or none`() {
        val title = QueryField("state.title")
        filter { search("wow") }.assert().isEqualTo(SearchFilter("wow"))
        filter { search("event sourcing", SearchMode.PHRASE, "state.title") }.assert()
            .isEqualTo(SearchFilter("event sourcing", setOf(title), SearchMode.PHRASE))
        filter { search("wow", SearchMode.TERMS, title) }.assert().isEqualTo(SearchFilter("wow", setOf(title)))
        filter { "state.title" search "wow" }.assert().isEqualTo(SearchFilter("wow", setOf(title)))
        filter { title search "wow" }.assert().isEqualTo(SearchFilter("wow", setOf(title)))
    }

    @Test
    fun `every field operator takes a string or a query field alike`() {
        val byString = filter { fieldOperators { it } }
        val byField = filter { fieldOperators { QueryField(it) } }
        byField.assert().isEqualTo(byString)
        (byString as AndFilter).operands.assert().hasSize(FIELD_OPERATORS)
        byString.operands.map { it.operator }.toSet().assert().hasSize(FIELD_OPERATORS)
    }

    @Test
    fun `relative time filters carry their zone, pattern and unit`() {
        filter { created.today(ZoneOffset.UTC, "yyyy-MM-dd", TimeUnit.SECONDS) }.assert()
            .isEqualTo(TodayFilter(created, utc, "yyyy-MM-dd", timeUnit = TimeUnit.SECONDS))
        filter { "state.createdAt".beforeToday(LocalTime.NOON) }.assert()
            .isEqualTo(BeforeTodayFilter(created, "12:00"))
        filter { created.recentDays(7, ZoneOffset.UTC) }.assert().isEqualTo(RecentDaysFilter(created, 7, utc))
        filter { created.beforeNow(Duration.ofMinutes(-30)) }.assert()
            .isEqualTo(BeforeNowFilter(created, "PT-30M"))
        filter { "state.createdAt".afterNow() }.assert().isEqualTo(AfterNowFilter(created))
    }

    @Suppress("LongMethod")
    @Test
    fun `each optional argument may be given alone or with the others`() {
        val at = QueryField("state.at")
        val zone = ZoneOffset.UTC
        val pattern = "yyyy-MM-dd"
        val unit = TimeUnit.SECONDS
        val offset = Duration.ofHours(1)
        val expression = filter {
            "state.at".today(zoneId = zone)
            "state.at".today(datePattern = pattern)
            "state.at".today(timeUnit = unit)
            "state.at".today(zone, pattern, unit)
            "state.at".yesterday(zoneId = zone)
            "state.at".yesterday(datePattern = pattern)
            "state.at".yesterday(timeUnit = unit)
            "state.at".yesterday(zone, pattern, unit)
            "state.at".tomorrow(zoneId = zone)
            "state.at".tomorrow(datePattern = pattern)
            "state.at".tomorrow(timeUnit = unit)
            "state.at".tomorrow(zone, pattern, unit)
            "state.at".thisWeek(zoneId = zone)
            "state.at".thisWeek(datePattern = pattern)
            "state.at".thisWeek(timeUnit = unit)
            "state.at".thisWeek(zone, pattern, unit)
            "state.at".nextWeek(zoneId = zone)
            "state.at".nextWeek(datePattern = pattern)
            "state.at".nextWeek(timeUnit = unit)
            "state.at".nextWeek(zone, pattern, unit)
            "state.at".lastWeek(zoneId = zone)
            "state.at".lastWeek(datePattern = pattern)
            "state.at".lastWeek(timeUnit = unit)
            "state.at".lastWeek(zone, pattern, unit)
            "state.at".thisMonth(zoneId = zone)
            "state.at".thisMonth(datePattern = pattern)
            "state.at".thisMonth(timeUnit = unit)
            "state.at".thisMonth(zone, pattern, unit)
            "state.at".lastMonth(zoneId = zone)
            "state.at".lastMonth(datePattern = pattern)
            "state.at".lastMonth(timeUnit = unit)
            "state.at".lastMonth(zone, pattern, unit)
            "state.at".nextMonth(zoneId = zone)
            "state.at".nextMonth(datePattern = pattern)
            "state.at".nextMonth(timeUnit = unit)
            "state.at".nextMonth(zone, pattern, unit)
            "state.at".lastYear(zoneId = zone)
            "state.at".lastYear(datePattern = pattern)
            "state.at".lastYear(timeUnit = unit)
            "state.at".lastYear(zone, pattern, unit)
            "state.at".thisYear(zoneId = zone)
            "state.at".thisYear(datePattern = pattern)
            "state.at".thisYear(timeUnit = unit)
            "state.at".thisYear(zone, pattern, unit)
            "state.at".nextYear(zoneId = zone)
            "state.at".nextYear(datePattern = pattern)
            "state.at".nextYear(timeUnit = unit)
            "state.at".nextYear(zone, pattern, unit)
            "state.at".beforeToday(LocalTime.NOON, zoneId = zone)
            "state.at".beforeToday(LocalTime.NOON, datePattern = pattern)
            "state.at".beforeToday(LocalTime.NOON, timeUnit = unit)
            "state.at".beforeToday(LocalTime.NOON, zone, pattern, unit)
            "state.at".recentDays(2, zoneId = zone)
            "state.at".recentDays(2, datePattern = pattern)
            "state.at".recentDays(2, timeUnit = unit)
            "state.at".recentDays(2, zone, pattern, unit)
            "state.at".earlierDays(2, zoneId = zone)
            "state.at".earlierDays(2, datePattern = pattern)
            "state.at".earlierDays(2, timeUnit = unit)
            "state.at".earlierDays(2, zone, pattern, unit)
            "state.at".beforeNow(offset = offset)
            "state.at".beforeNow(zoneId = zone)
            "state.at".beforeNow(datePattern = pattern)
            "state.at".beforeNow(timeUnit = unit)
            "state.at".beforeNow(offset, zone, pattern, unit)
            "state.at".afterNow(offset = offset)
            "state.at".afterNow(zoneId = zone)
            "state.at".afterNow(datePattern = pattern)
            "state.at".afterNow(timeUnit = unit)
            "state.at".afterNow(offset, zone, pattern, unit)
            "state.at".containsText("a")
            "state.at".containsText("a", StringComparison.CASE_INSENSITIVE)
            "state.at".startsWithText("a")
            "state.at".startsWithText("a", StringComparison.CASE_INSENSITIVE)
            "state.at".endsWithText("a")
            "state.at".endsWithText("a", StringComparison.CASE_INSENSITIVE)
            at.today(zoneId = zone)
            at.today(datePattern = pattern)
            at.today(timeUnit = unit)
            at.today(zone, pattern, unit)
            at.yesterday(zoneId = zone)
            at.yesterday(datePattern = pattern)
            at.yesterday(timeUnit = unit)
            at.yesterday(zone, pattern, unit)
            at.tomorrow(zoneId = zone)
            at.tomorrow(datePattern = pattern)
            at.tomorrow(timeUnit = unit)
            at.tomorrow(zone, pattern, unit)
            at.thisWeek(zoneId = zone)
            at.thisWeek(datePattern = pattern)
            at.thisWeek(timeUnit = unit)
            at.thisWeek(zone, pattern, unit)
            at.nextWeek(zoneId = zone)
            at.nextWeek(datePattern = pattern)
            at.nextWeek(timeUnit = unit)
            at.nextWeek(zone, pattern, unit)
            at.lastWeek(zoneId = zone)
            at.lastWeek(datePattern = pattern)
            at.lastWeek(timeUnit = unit)
            at.lastWeek(zone, pattern, unit)
            at.thisMonth(zoneId = zone)
            at.thisMonth(datePattern = pattern)
            at.thisMonth(timeUnit = unit)
            at.thisMonth(zone, pattern, unit)
            at.lastMonth(zoneId = zone)
            at.lastMonth(datePattern = pattern)
            at.lastMonth(timeUnit = unit)
            at.lastMonth(zone, pattern, unit)
            at.nextMonth(zoneId = zone)
            at.nextMonth(datePattern = pattern)
            at.nextMonth(timeUnit = unit)
            at.nextMonth(zone, pattern, unit)
            at.lastYear(zoneId = zone)
            at.lastYear(datePattern = pattern)
            at.lastYear(timeUnit = unit)
            at.lastYear(zone, pattern, unit)
            at.thisYear(zoneId = zone)
            at.thisYear(datePattern = pattern)
            at.thisYear(timeUnit = unit)
            at.thisYear(zone, pattern, unit)
            at.nextYear(zoneId = zone)
            at.nextYear(datePattern = pattern)
            at.nextYear(timeUnit = unit)
            at.nextYear(zone, pattern, unit)
            at.beforeToday(LocalTime.NOON, zoneId = zone)
            at.beforeToday(LocalTime.NOON, datePattern = pattern)
            at.beforeToday(LocalTime.NOON, timeUnit = unit)
            at.beforeToday(LocalTime.NOON, zone, pattern, unit)
            at.recentDays(2, zoneId = zone)
            at.recentDays(2, datePattern = pattern)
            at.recentDays(2, timeUnit = unit)
            at.recentDays(2, zone, pattern, unit)
            at.earlierDays(2, zoneId = zone)
            at.earlierDays(2, datePattern = pattern)
            at.earlierDays(2, timeUnit = unit)
            at.earlierDays(2, zone, pattern, unit)
            at.beforeNow(offset = offset)
            at.beforeNow(zoneId = zone)
            at.beforeNow(datePattern = pattern)
            at.beforeNow(timeUnit = unit)
            at.beforeNow(offset, zone, pattern, unit)
            at.afterNow(offset = offset)
            at.afterNow(zoneId = zone)
            at.afterNow(datePattern = pattern)
            at.afterNow(timeUnit = unit)
            at.afterNow(offset, zone, pattern, unit)
            at.containsText("a")
            at.containsText("a", StringComparison.CASE_INSENSITIVE)
            at.startsWithText("a")
            at.startsWithText("a", StringComparison.CASE_INSENSITIVE)
            at.endsWithText("a")
            at.endsWithText("a", StringComparison.CASE_INSENSITIVE)
        } as AndFilter
        expression.operands.assert().hasSize(152)
        expression.operands.filterIsInstance<RelativeTimeFilter>().filter { it.zoneId == utc }.assert().isNotEmpty()
    }

    /** Every field operator of [FilterDsl], on the field [at] makes of a path: string receivers or query fields. */
    @Suppress("LongMethod")
    private fun FilterDsl.fieldOperators(at: (String) -> Any) {
        fun f(path: String) = at(path)
        when (val field = f("state.name")) {
            is String -> {
                field eq "a"
                field ne "a"
                field gt 1
                field gte 1
                field lt 1
                field lte 1
                field.containsText("a", StringComparison.CASE_INSENSITIVE)
                field.startsWithText("a")
                field.endsWithText("a")
                field isIn listOf(1)
                field notIn listOf(1)
                field.between(1, 2)
                field containsAll listOf(1)
                field.isEmptyCollection()
                field.isEmptyString()
                field.isNotEmptyString()
                field.isNull()
                field.isNotNull()
                field.exists()
                field.notExists()
                field.elementMatch { "x" eq 1 }
                field search "a"
                field.today()
                field.beforeToday(LocalTime.NOON)
                field.tomorrow()
                field.thisWeek()
                field.nextWeek()
                field.lastWeek()
                field.thisMonth()
                field.lastMonth()
                field.recentDays(1)
                field.earlierDays(1)
                field.yesterday()
                field.nextMonth()
                field.lastYear()
                field.thisYear()
                field.nextYear()
                field.beforeNow()
                field.afterNow()
            }

            is QueryField -> {
                field eq "a"
                field ne "a"
                field gt 1
                field gte 1
                field lt 1
                field lte 1
                field.containsText("a", StringComparison.CASE_INSENSITIVE)
                field.startsWithText("a")
                field.endsWithText("a")
                field isIn listOf(1)
                field notIn listOf(1)
                field.between(1, 2)
                field containsAll listOf(1)
                field.isEmptyCollection()
                field.isEmptyString()
                field.isNotEmptyString()
                field.isNull()
                field.isNotNull()
                field.exists()
                field.notExists()
                field.elementMatch { "x" eq 1 }
                field search "a"
                field.today()
                field.beforeToday(LocalTime.NOON)
                field.tomorrow()
                field.thisWeek()
                field.nextWeek()
                field.lastWeek()
                field.thisMonth()
                field.lastMonth()
                field.recentDays(1)
                field.earlierDays(1)
                field.yesterday()
                field.nextMonth()
                field.lastYear()
                field.thisYear()
                field.nextYear()
                field.beforeNow()
                field.afterNow()
            }
        }
    }

    private companion object {
        const val FIELD_OPERATORS = 39
    }
}
