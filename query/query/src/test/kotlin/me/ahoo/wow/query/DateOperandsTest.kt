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
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.resolve.DateOperands
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant

/**
 * A date field's operands as the query language reads them: a date-time with its offset or a date alone, any other
 * text refused; and every comparison at the operand's millisecond, as a range on the raw value.
 */
class DateOperandsTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "ownerId" to scalarFixture(),
            "spaceId" to scalarFixture(),
            "deleted" to scalarFixture(QueryValueType.BOOLEAN),
            "at" to scalarFixture(QueryValueType.STRING, Temporal.Date),
            "count" to scalarFixture(QueryValueType.INTEGER),
        ),
    )
    private val at = QueryField("at")

    private fun admitted(filter: FilterExpression): FilterExpression =
        QueryAdmission.Trusted.count(CountQuery(filter), schema).query.filter

    private fun refusal(filter: FilterExpression): QueryViolation =
        checkNotNull(assertThrows<QuerySchemaValidationException> { admitted(filter) }.violation)

    @Test
    fun `a date-time with its offset and a date alone should be read, and any other text refused`() {
        listOf(
            "2026-10-13T08:00:00Z",
            "2026-10-13t08:00:00z",
            "2026-10-13T16:00:00+08:00",
            "2026-10-13T08:00:00.123456789Z",
            "2026-10-13",
        ).forEach { text -> admitted(EqualFilter(at, text)) }
        listOf("2026-10-13T08:00:00", "1760342400000", "2026-10", "garbage", "").forEach { text ->
            refusal(EqualFilter(at, text)).assert().describedAs(text).isEqualTo(QueryViolation.ValueMismatch(at))
            refusal(GreaterThanFilter(at, text)).assert().describedAs(text).isEqualTo(QueryViolation.ValueMismatch(at))
        }
        refusal(InFilter(at, listOf("2026-10-13", "2026-10"))).assert().isEqualTo(QueryViolation.ValueMismatch(at))
        refusal(BetweenFilter(at, "2026-10-13", "soon")).assert().isEqualTo(QueryViolation.ValueMismatch(at))
        // A runtime instant is read as the text it is written as.
        admitted(EqualFilter(at, Instant.parse("2026-10-13T08:00:00Z")))
    }

    @Test
    fun `a comparison should hold at the operand's millisecond, as a range on the raw value`() {
        val first = "2026-10-13T08:00:00Z"
        val last = "2026-10-13T08:00:00.000999999Z"
        val next = "2026-10-13T08:00:00.001Z"
        val within = BetweenFilter(at, first, last)
        admitted(EqualFilter(at, "2026-10-13T08:00:00.0005Z")).assert().isEqualTo(within)
        admitted(EqualFilter(at, "2026-10-13T16:00:00+08:00")).assert().isEqualTo(within)
        admitted(NotEqualFilter(at, first)).assert().isEqualTo(NorFilter(listOf(within)))
        admitted(InFilter(at, listOf(first))).assert().isEqualTo(within)
        admitted(InFilter(at, listOf(first, next))).assert()
            .isEqualTo(OrFilter(listOf(within, BetweenFilter(at, next, "2026-10-13T08:00:00.001999999Z"))))
        admitted(NotInFilter(at, listOf(first))).assert().isEqualTo(NorFilter(listOf(within)))
        admitted(GreaterThanFilter(at, "2026-10-13T08:00:00.0002Z")).assert()
            .isEqualTo(GreaterThanOrEqualFilter(at, next))
        admitted(GreaterThanOrEqualFilter(at, "2026-10-13T08:00:00.0002Z")).assert()
            .isEqualTo(GreaterThanOrEqualFilter(at, first))
        admitted(LessThanFilter(at, "2026-10-13T08:00:00.0002Z")).assert().isEqualTo(LessThanFilter(at, first))
        admitted(LessThanOrEqualFilter(at, "2026-10-13T08:00:00.0002Z")).assert().isEqualTo(LessThanFilter(at, next))
        admitted(BetweenFilter(at, "2026-10-13T08:00:00.0002Z", "2026-10-13T08:00:00.0007Z")).assert()
            .isEqualTo(within)
        // A date alone is its day's first instant at UTC; equality with null asks for presence.
        admitted(EqualFilter(at, "2026-10-13")).assert()
            .isEqualTo(BetweenFilter(at, "2026-10-13T00:00:00Z", "2026-10-13T00:00:00.000999999Z"))
        admitted(EqualFilter(at, null)).assert().isEqualTo(IsNullFilter(at))
    }

    @Test
    fun `an integer field should take an integer, not a decimal's form`() {
        val count = QueryField("count")
        admitted(EqualFilter(count, 7)).assert().isEqualTo(EqualFilter(count, 7))
        // A decimal of any scale, from JSON's `7.0`, `7e0` or `1e2`, or in process, is no integer.
        val decimals = listOf(java.math.BigDecimal("7"), java.math.BigDecimal("7.0"), java.math.BigDecimal("1E+2"), 7.5)
        decimals.forEach { value ->
            refusal(EqualFilter(count, value)).assert().describedAs("$value")
                .isEqualTo(QueryViolation.ValueMismatch(count))
        }
    }

    @Test
    fun `an operand that names no instant should be left as it is, and a JSON string read as its text`() {
        val first = "2026-10-13T08:00:00Z"
        // Admission refuses each of these on a caller's query; lowering, which may meet one first, leaves it alone.
        listOf(
            EqualFilter(at, 7),
            NotEqualFilter(at, null),
            InFilter(at, listOf(first, null)),
            NotInFilter(at, listOf("2026-10")),
            GreaterThanFilter(at, null),
            GreaterThanOrEqualFilter(at, 7),
            LessThanFilter(at, "soon"),
            LessThanOrEqualFilter(at, null),
            BetweenFilter(at, "soon", first),
            BetweenFilter(at, first, "soon"),
            IsNullFilter(at),
        ).forEach { filter -> DateOperands.lower(filter).assert().describedAs("$filter").isSameAs(filter) }
        val json = JsonNodeFactory.instance
        DateOperands.lower(EqualFilter(at, json.stringNode(first))).assert()
            .isEqualTo(BetweenFilter(at, first, "2026-10-13T08:00:00.000999999Z"))
        DateOperands.accepts(Temporal.Date, listOf(null, first, json.stringNode(first))).assert().isTrue()
        DateOperands.accepts(Temporal.Date, listOf(7)).assert().isFalse()
        DateOperands.accepts(Temporal.Date, listOf(json.numberNode(7))).assert().isFalse()
        DateOperands.accepts(Temporal.Date, listOf("soon")).assert().isFalse()
        DateOperands.accepts(null, listOf(7)).assert().isTrue()
    }
}
