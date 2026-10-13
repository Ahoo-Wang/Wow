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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class QueryValueDomainsTest {
    private val field = QueryField("state.at")
    private val nullValue = QueryValueSchema(QueryValueKind.NULL)

    private fun union(vararg alternatives: QueryValueSchema) =
        QueryValueSchema(QueryValueKind.UNION, alternatives = alternatives.toList())

    @Test
    fun `operations read one member layer of primitive arrays and flatten unions`() {
        val string = scalarFixture()
        val integer = scalarFixture(QueryValueType.INTEGER)
        string.operationValues().assert().containsExactly(string)
        arrayFixture(string).operationValues().assert().containsExactly(string)
        arrayFixture(union(string, integer)).operationValues().assert().containsExactly(string, integer)
        union(string, arrayFixture(integer)).operationValues().assert().containsExactly(string, integer)
        val nested = arrayFixture(arrayFixture(string))
        nested.operationValues().single().kind.assert().isEqualTo(QueryValueKind.ARRAY)
        union(
            string,
            union(integer, nullValue)
        ).alternativesOrSelf().assert().containsExactly(string, integer, nullValue)
        arrayFixture(string).hasArrayBranch().assert().isTrue()
        union(string, nullValue).hasArrayBranch().assert().isFalse()
    }

    @Test
    fun `the shared temporal is the one encoding every non-null value states`() {
        val epoch = scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.SECONDS))
        val date = scalarFixture(temporal = Temporal.Date)
        epoch.sharedTemporal().assert().isEqualTo(Temporal.Epoch(TimeUnit.SECONDS))
        union(epoch, nullValue).sharedTemporal().assert().isEqualTo(Temporal.Epoch(TimeUnit.SECONDS))
        arrayFixture(date).sharedTemporal().assert().isEqualTo(Temporal.Date)
        union(epoch, date).sharedTemporal().assert().isNull()
        union(epoch, scalarFixture(QueryValueType.INTEGER)).sharedTemporal().assert().isNull()
        scalarFixture().sharedTemporal().assert().isNull()

        Temporal.Date.encodesInstant.assert().isTrue()
        Temporal.Epoch().encodesInstant.assert().isTrue()
        Temporal.Formatted("yyyy-MM-dd").encodesInstant.assert().isFalse()
        (null as Temporal?).encodesInstant.assert().isFalse()
    }

    @Test
    fun `a value accepts operands of its declared types, nulls included`() {
        val integer = scalarFixture(QueryValueType.INTEGER)
        integer.accepts(listOf(1, 2L, BigDecimal("3.0"), null)).assert().isTrue()
        integer.accepts(listOf(1.5)).assert().isFalse()
        scalarFixture(QueryValueType.DECIMAL).accepts(listOf(1, 1.5)).assert().isTrue()
        scalarFixture(QueryValueType.BOOLEAN).accepts(listOf(true)).assert().isTrue()
        scalarFixture().accepts(listOf("a", JsonNodeFactory.instance.stringNode("b"))).assert().isTrue()
        scalarFixture().accepts(listOf(1)).assert().isFalse()
        objectFixture().accepts(listOf(mapOf("a" to 1))).assert().isTrue()
        objectFixture().accepts(listOf("a")).assert().isFalse()
        // A custom value type accepts any value a record can hold.
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType("UUID"))).accepts(listOf("x"))
            .assert().isTrue()
        // An array accepts its items' values; a union, the values of any of its branches.
        arrayFixture(integer).accepts(listOf(1)).assert().isTrue()
        union(integer, scalarFixture()).accepts(listOf(1, "a")).assert().isTrue()
        union(integer, nullValue).accepts(listOf("a")).assert().isFalse()
        // An unknown value has no domain to accept an operand of.
        QueryValueSchema(QueryValueKind.UNKNOWN).accepts(listOf("a")).assert().isFalse()
        QueryValueSchema(QueryValueKind.UNKNOWN).accepts(listOf(null)).assert().isTrue()
        // A runtime value with no JSON form never matches.
        scalarFixture().accepts(listOf(Any())).assert().isFalse()
    }

    @Test
    fun `a relative-time filter resolves against the encoding its field stores time with`() {
        val epoch = Temporal.Epoch(TimeUnit.SECONDS)
        val formatted = Temporal.Formatted("yyyy-MM-dd")
        TodayFilter(field).temporal(epoch).assert().isEqualTo(epoch)
        TodayFilter(field).temporal(Temporal.Date).assert().isEqualTo(Temporal.Date)
        TodayFilter(field).temporal(formatted).assert().isEqualTo(formatted)
        TodayFilter(field, datePattern = "yyyy-MM-dd").temporal(formatted).assert().isEqualTo(formatted)

        assertThrows<QuerySchemaValidationException> { TodayFilter(field).temporal(null) }
            .violation.assert().isEqualTo(QueryViolation.TemporalRepresentationRequired(field))
        assertThrows<QuerySchemaValidationException> {
            TodayFilter(field, dateFormatter = DateTimeFormatter.ISO_DATE).temporal(formatted)
        }.violation.assert().isEqualTo(QueryViolation.TemporalRepresentationRequired(field))
        assertThrows<QuerySchemaValidationException> {
            TodayFilter(field, datePattern = "yyyy-MM-dd").temporal(epoch, QueryField("at"))
        }.violation.assert().isEqualTo(QueryViolation.TemporalConfigurationConflict(QueryField("at")))
        assertThrows<QuerySchemaValidationException> {
            TodayFilter(field, datePattern = "yyyy/MM/dd").temporal(formatted)
        }
    }

    @Test
    fun `every relative-time filter takes on the encoding of its field`() {
        val filters: List<RelativeTimeFilter> = listOf(
            TodayFilter(field),
            BeforeTodayFilter(field, "10:00"),
            TomorrowFilter(field),
            ThisWeekFilter(field),
            NextWeekFilter(field),
            LastWeekFilter(field),
            ThisMonthFilter(field),
            LastMonthFilter(field),
            RecentDaysFilter(field, 1),
            EarlierDaysFilter(field, 1),
            YesterdayFilter(field),
            NextMonthFilter(field),
            LastYearFilter(field),
            ThisYearFilter(field),
            NextYearFilter(field),
            BeforeNowFilter(field),
            AfterNowFilter(field),
        )
        filters.forEach { filter ->
            val seconds = filter.withTemporal(Temporal.Epoch(TimeUnit.SECONDS))
            seconds.javaClass.assert().isEqualTo(filter.javaClass)
            seconds.timeUnit.assert().isEqualTo(TimeUnit.SECONDS)
            seconds.field.assert().isEqualTo(field)
            val formatted = filter.withTemporal(Temporal.Formatted("yyyy-MM-dd"))
            formatted.datePattern.assert().isEqualTo("yyyy-MM-dd")
            formatted.javaClass.assert().isEqualTo(filter.javaClass)
            filter.withTemporal(Temporal.Date).assert().isSameAs(filter)
            // An encoding the filter already states keeps the filter.
            filter.withTemporal(Temporal.Epoch(TimeUnit.MILLISECONDS)).assert().isSameAs(filter)
        }
    }
}
