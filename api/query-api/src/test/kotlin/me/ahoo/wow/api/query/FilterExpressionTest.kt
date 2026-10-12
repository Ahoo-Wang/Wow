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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Duration
import java.time.format.DateTimeFormatter

class FilterExpressionTest {
    @Test
    fun `relative calendar filters should reject invalid common configuration`() {
        val field = QueryField("state.createTime")

        assertThrows<IllegalArgumentException> {
            YesterdayFilter(field, zoneId = "")
        }
        assertThrows<IllegalArgumentException> {
            NextMonthFilter(field, zoneId = "Not/AZone")
        }
        assertThrows<IllegalArgumentException> {
            ThisYearFilter(field, datePattern = "invalid")
        }
    }

    @Test
    fun `phrase search should preserve embedded quotes`() {
        val filter = SearchFilter("event \"sourcing\"", mode = SearchMode.PHRASE)

        filter.query.assert().isEqualTo("event \"sourcing\"")
    }

    @Test
    fun `plural metadata filters should reject empty values`() {
        assertThrows<IllegalArgumentException> { IdsFilter(emptyList()) }
        assertThrows<IllegalArgumentException> { AggregateIdsFilter(emptyList()) }
    }

    @Test
    fun `element match should accept a field search but no model-wide search`() {
        val search = SearchFilter("x", setOf(QueryField("title")))
        ElementMatchFilter(QueryField("state.items"), AndFilter(listOf(search))).predicate.assert()
            .isEqualTo(AndFilter(listOf(search)))
        assertThrows<IllegalArgumentException> {
            ElementMatchFilter(QueryField("state.items"), SearchFilter("x"))
        }
        // Aggregation element filters stay record-free and search-free.
        assertThrows<IllegalArgumentException> {
            AggregationElement(QueryField("state.items"), search)
        }
    }

    @Test
    fun `element match should reject root metadata filters`() {
        assertThrows<IllegalArgumentException> {
            ElementMatchFilter(QueryField("state.items"), TenantIdFilter("tenant-1"))
        }
    }

    @Test
    fun `new relative calendar filters should keep their common configuration`() {
        val field = QueryField("state.createTime")
        val filters = listOf<RelativeTimeFilter>(
            YesterdayFilter(field, "UTC", "yyyy-MM-dd"),
            NextMonthFilter(field, "UTC", "yyyy-MM-dd"),
            LastYearFilter(field, "UTC", "yyyy-MM-dd"),
            ThisYearFilter(field, "UTC", "yyyy-MM-dd"),
            NextYearFilter(field, "UTC", "yyyy-MM-dd"),
        )

        filters.forEach { filter ->
            filter.field.assert().isEqualTo(field)
            filter.zoneId.assert().isEqualTo("UTC")
            filter.datePattern.assert().isEqualTo("yyyy-MM-dd")
            filter.resolvedDateFormatter().assert().isNotNull()
            filter.resolvedZoneId().assert().isEqualTo(java.time.ZoneId.of("UTC"))
        }
    }

    @Test
    fun `a runtime formatter should take the place of the date pattern`() {
        val filter = YesterdayFilter(QueryField("state.createTime"), dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE)

        filter.datePattern.assert().isNull()
        filter.resolvedDateFormatter().assert().isSameAs(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    @Test
    fun `before today should require a local time`() {
        BeforeTodayFilter(QueryField("state.createTime"), "18:00:30").time.assert().isEqualTo("18:00:30")
        assertThrows<IllegalArgumentException> { BeforeTodayFilter(QueryField("state.createTime"), "6pm") }
    }

    @Test
    fun `day windows should require at least one day`() {
        assertThrows<IllegalArgumentException> { RecentDaysFilter(QueryField("state.createTime"), 0) }
        assertThrows<IllegalArgumentException> { EarlierDaysFilter(QueryField("state.createTime"), 0) }
    }

    @Test
    fun `relative-now filters default to a zero offset`() {
        BeforeNowFilter(QueryField("state.timeoutAt")).offsetDuration.assert().isEqualTo(Duration.ZERO)
        AfterNowFilter(QueryField("state.timeoutAt"), "-PT30M").offsetDuration.assert()
            .isEqualTo(Duration.ofMinutes(-30))
        assertThrows<IllegalArgumentException> { BeforeNowFilter(QueryField("f"), "30 minutes") }
    }

    @Test
    fun `search mode should default to terms`() {
        SearchFilter("wow", setOf(QueryField("state.name"))).mode.assert().isEqualTo(SearchMode.TERMS)
        assertThrows<IllegalArgumentException> { SearchFilter(" ") }
    }

    @Test
    fun `logical filters should reject empty operands`() {
        assertThrows<IllegalArgumentException> { AndFilter(emptyList()) }
        assertThrows<IllegalArgumentException> { OrFilter(emptyList()) }
        assertThrows<IllegalArgumentException> { NorFilter(emptyList()) }
    }

    @Test
    fun `appending a filter should replace match all and otherwise combine with AND`() {
        val tenant = TenantIdFilter("tenant")
        MatchAllFilter.appendFilter(tenant).assert().isEqualTo(tenant)
        IdFilter("id").appendFilter(tenant).assert().isEqualTo(AndFilter(listOf(IdFilter("id"), tenant)))
        ListQuery(MatchAllFilter).appendFilter(tenant).filter.assert().isEqualTo(tenant)
    }

    @Test
    fun `should reject non scalar predicate value`() {
        assertThrows<IllegalArgumentException> { EqualFilter(QueryField("state"), emptyMap<String, Any>()) }
        assertThrows<IllegalArgumentException> { EqualFilter(QueryField("state"), listOf(listOf("a"))) }
        assertThrows<IllegalArgumentException> { GreaterThanFilter(QueryField("state"), listOf(1)) }
        assertThrows<IllegalArgumentException> { InFilter(QueryField("state"), listOf(mapOf("a" to 1))) }
    }

    @Test
    fun `should accept scalar array equality value`() {
        EqualFilter(QueryField("state.tags"), listOf("a", "b")).value.assert().isEqualTo(listOf("a", "b"))
        NotEqualFilter(QueryField("state.tags"), listOf("a", "b")).value.assert().isEqualTo(listOf("a", "b"))
    }

    @Test
    fun `should accept scalar and runtime values`() {
        data class NativeValue(val id: String)
        val native = NativeValue("native-1")
        val field = QueryField("state.value")

        EqualFilter(field, null).value.assert().isNull()
        EqualFilter(field, native).value.assert().isSameAs(native)
        EqualFilter(
            field,
            listOf(native, "a", 1, true, null)
        ).value.assert().isEqualTo(listOf(native, "a", 1, true, null))
        GreaterThanFilter(field, BigDecimal.ONE).value.assert().isEqualTo(BigDecimal.ONE)
        BetweenFilter(field, 1, 2L).upperBound.assert().isEqualTo(2L)
        InFilter(field, listOf(SearchMode.TERMS, "a")).values.assert().containsExactly(SearchMode.TERMS, "a")
    }

    @Test
    fun `should reject null range value`() {
        assertThrows<IllegalArgumentException> { GreaterThanFilter(QueryField("state.version"), null) }
        assertThrows<IllegalArgumentException> { BetweenFilter(QueryField("state.version"), 1, null) }
    }

    @Test
    fun `should reject null or empty collection value`() {
        assertThrows<IllegalArgumentException> { InFilter(QueryField("state.status"), listOf(null)) }
        assertThrows<IllegalArgumentException> { NotInFilter(QueryField("state.status"), emptyList()) }
        assertThrows<IllegalArgumentException> { ContainsAllFilter(QueryField("state.status"), listOf("a", null)) }
    }

    @Test
    fun `expression filter should require a finite value and a field`() {
        val expression = AggregationExpression.Field(QueryField("state.amount"))
        ExpressionFilter(expression, ComparisonOperator.GT, 1.0).operator.assert().isEqualTo(FilterOperator.EXPRESSION)
        assertThrows<IllegalArgumentException> { ExpressionFilter(expression, ComparisonOperator.GT, Double.NaN) }
        assertThrows<IllegalArgumentException> {
            ExpressionFilter(AggregationExpression.Constant(1.0), ComparisonOperator.GT, 1.0)
        }
    }
}
