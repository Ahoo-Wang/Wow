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
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.aggregation.PreviousStep
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.parseInstant
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * A PREVIOUS window moves a date bound back as the instant the core reads it as, whatever ISO form spells it (#6390):
 * a bound with `Z`, with an offset, and without one (read at UTC) reach one instant, across both daylight-saving
 * transitions, along every bucket length and by a period.
 */
internal class PreviousBoundFormsTest : InMemoryReferenceFixtures() {
    private fun histogram(unit: AggregationDateUnit) =
        AggregationGroup.DateHistogram(DAY, "day", unit, timeZone = "America/New_York")

    /** [bound], an instant, moved back by [step] in each of its ISO forms: each reaches [expected]. */
    private fun assertMovesTo(step: PreviousStep, bound: String, expected: String) {
        val instant = Instant.parse(bound)
        val forms = listOf(bound, instant.atOffset(EST).toString(), instant.toString().removeSuffix("Z"))
        forms.forEach { form ->
            val moved = (step.shift(GreaterThanOrEqualFilter(DAY, form), Temporal.Date) as GreaterThanOrEqualFilter)
            parseInstant(moved.value as String).assert().describedAs(form).isEqualTo(Instant.parse(expected))
        }
    }

    @Test
    fun `every form of a bound should move back to one instant, along each bucket length, across both transitions`() {
        // New York, 2026: EDT ends on November 1 (01:00 repeats), and begins on March 8 (02:00 is skipped).
        val hour = PreviousStep(histogram(AggregationDateUnit.HOUR), 1, null)
        assertMovesTo(hour, "2026-11-01T06:00:00Z", "2026-11-01T05:00:00Z")
        assertMovesTo(hour, "2026-03-08T07:00:00Z", "2026-03-08T06:00:00Z")
        val day = PreviousStep(histogram(AggregationDateUnit.DAY), 1, null)
        assertMovesTo(day, "2026-11-02T05:00:00Z", "2026-11-01T04:00:00Z")
        assertMovesTo(day, "2026-03-09T04:00:00Z", "2026-03-08T05:00:00Z")
        val week = PreviousStep(histogram(AggregationDateUnit.WEEK), 1, null)
        assertMovesTo(week, "2026-11-02T05:00:00Z", "2026-10-26T04:00:00Z")
        assertMovesTo(week, "2026-03-09T04:00:00Z", "2026-03-02T05:00:00Z")
        val month = PreviousStep(histogram(AggregationDateUnit.MONTH), 1, null)
        assertMovesTo(month, "2026-12-01T05:00:00Z", "2026-11-01T04:00:00Z")
        assertMovesTo(month, "2026-04-01T04:00:00Z", "2026-03-01T05:00:00Z")
    }

    @Test
    fun `every form of a bound should move back to one instant by a period, across both transitions`() {
        // The same local time a period before: midnight a day, or a week, before.
        val dayByWeek = PreviousStep(histogram(AggregationDateUnit.DAY), 1, AggregationDateUnit.WEEK)
        assertMovesTo(dayByWeek, "2026-11-02T05:00:00Z", "2026-10-26T04:00:00Z")
        assertMovesTo(dayByWeek, "2026-03-09T04:00:00Z", "2026-03-02T05:00:00Z")
        val hourByDay = PreviousStep(histogram(AggregationDateUnit.HOUR), 1, AggregationDateUnit.DAY)
        assertMovesTo(hourByDay, "2026-11-02T05:00:00Z", "2026-11-01T04:00:00Z")
        assertMovesTo(hourByDay, "2026-03-09T04:00:00Z", "2026-03-08T05:00:00Z")
    }

    @Test
    fun `a date bound should move back as its instant, and stay a date only while it reaches a midnight at UTC`() {
        val day = PreviousStep(histogram(AggregationDateUnit.DAY), 1, null)
        fun moved(bound: String): Any? {
            val shifted = day.shift(GreaterThanOrEqualFilter(DAY, bound), Temporal.Date)
            return (shifted as? GreaterThanOrEqualFilter)?.value
        }
        // 2026-11-02 is read as its midnight at UTC, 19:00 EST on November 1: back one New York day is 19:00 EDT.
        moved("2026-11-02").assert().isEqualTo("2026-10-31T23:00:00")
        moved("2026-11-02T00:00:00Z").assert().isEqualTo("2026-10-31T23:00:00Z")
        // In January, a day back reaches a midnight at UTC again.
        moved("2026-01-05").assert().isEqualTo("2026-01-04")
        // A bound moved back past the instants a date-time holds cannot be moved.
        moved("-999999999-01-01T00:00:00Z").assert().isNull()
        moved("yesterday").assert().isNull()
    }

    /** The PREVIOUS count of the New York day [start] to [end], over [records], with the bounds written as [form]. */
    private fun previous(records: List<String>, start: String, end: String, form: (String) -> String): Long =
        runBlocking {
            val rows = records.mapIndexed { index, at ->
                WowJson.mapper.readTree("""{"id":"r$index","tenant_id":"t1","day":"$at"}""") as ObjectNode
            }
            val target = InMemoryQueryBackend(backend.aggregateType, { rows.map(ObjectNode::deepCopy) }, Duration.ZERO)
            val day = histogram(AggregationDateUnit.DAY)
            val query = AggregationQuery(
                filter = AndFilter(
                    listOf(GreaterThanOrEqualFilter(DAY, form(start)), LessThanFilter(DAY, form(end))),
                ),
                groupBy = listOf(day),
                metrics = listOf(
                    AggregationMetric.Count("n"),
                    AggregationMetric.Derived("previous", DerivedExpression.Previous.of("n", "day")),
                ),
            )
            target.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().single()
                .path("previous").longValue()
        }

    @Test
    fun `a day-over-day count should read the whole previous day whatever the offset of its bounds`() {
        val utc: (String) -> String = { it }
        val est: (String) -> String = { Instant.parse(it).atOffset(EST).toString() }
        // November 1, 2026 runs from 04:00Z to 05:00Z the next day: its first hour and an hour inside it, then today.
        val fall = listOf("2026-11-01T04:30:00Z", "2026-11-01T05:30:00Z", "2026-11-02T05:30:00Z")
        listOf(utc, est).forEach { form ->
            previous(fall, "2026-11-02T05:00:00Z", "2026-11-03T05:00:00Z", form).assert().isEqualTo(2L)
        }
        // March 8, 2026 runs from 05:00Z to 04:00Z the next day: its first hour and its last, then today.
        val spring = listOf("2026-03-08T05:30:00Z", "2026-03-09T03:30:00Z", "2026-03-09T04:30:00Z")
        listOf(utc, est).forEach { form ->
            previous(spring, "2026-03-09T04:00:00Z", "2026-03-10T04:00:00Z", form).assert().isEqualTo(2L)
        }
        // A caller's bound names its offset: one without is refused at admission (the core's own moved bound,
        // which reads at UTC, is never admitted again).
        assertThrows<QuerySchemaValidationException> {
            previous(fall, "2026-11-02T05:00:00Z", "2026-11-03T05:00:00Z") { it.removeSuffix("Z") }
        }
    }

    private companion object {
        val EST: ZoneOffset = ZoneOffset.ofHours(-5)
    }
}
