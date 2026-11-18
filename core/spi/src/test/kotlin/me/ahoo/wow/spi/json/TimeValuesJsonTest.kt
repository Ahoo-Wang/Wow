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

package me.ahoo.wow.spi.json

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.readValue
import java.time.Month
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** A `java.time.Month` and a `kotlin.time.Duration` as Wow's JSON writes and reads them ([MonthNames], [KotlinDurations]). */
class TimeValuesJsonTest {
    data class Calendar(
        val month: Month = Month.JANUARY,
        val months: List<Month> = listOf(Month.MARCH),
        val byMonth: Map<Month, Int> = mapOf(Month.MAY to 1),
    )

    data class Timing(
        val duration: Duration = 90.minutes,
        val optional: Duration? = 2.minutes,
        val absent: Duration? = null,
        val durations: List<Duration> = listOf(1.hours),
        val byName: Map<String, Duration> = mapOf("a" to 3.seconds),
    )

    data class JavaTiming(val duration: java.time.Duration)

    data class Edge(val value: Duration, val values: List<Duration>)

    @Test
    fun `a month should be written by its name, a map's key too, and read back`() {
        val text = WowJson.mapper.writeValueAsString(Calendar())
        text.assert().isEqualTo("""{"month":"JANUARY","months":["MARCH"],"byMonth":{"MAY":1}}""")
        WowJson.mapper.readValue<Calendar>(text).assert().isEqualTo(Calendar())
    }

    @Test
    fun `a month's number, as a 10_0 beta wrote it, should read one-based, and zero should be refused`() {
        WowJson.mapper.readValue<Calendar>("""{"month":1}""").month.assert().isEqualTo(Month.JANUARY)
        WowJson.mapper.readValue<Calendar>("""{"month":12}""").month.assert().isEqualTo(Month.DECEMBER)
        assertThrows<JacksonException> { WowJson.mapper.readValue<Calendar>("""{"month":0}""") }
    }

    @Test
    fun `a Kotlin duration should be ISO-8601 text, held as a long, a class, an element or a map's value`() {
        val text = WowJson.mapper.writeValueAsString(Timing())
        text.assert().isEqualTo(
            """{"duration":"PT1H30M","optional":"PT2M","absent":null,"durations":["PT1H"],"byName":{"a":"PT3S"}}""",
        )
        WowJson.mapper.readValue<Timing>(text).assert().isEqualTo(Timing())
    }

    @Test
    fun `a Kotlin duration's edges should be written as java_time_Duration writes them, and read back equal`() {
        mapOf(
            Duration.ZERO to "PT0S",
            -(1.hours + 30.minutes) to "PT-1H-30M",
            1.seconds + 1.nanoseconds to "PT1.000000001S",
            // An infinite duration is written as the largest java.time.Duration the conversion makes, which Kotlin's
            // parser, saturating beyond its range, reads back as infinite.
            Duration.INFINITE to "PT2562047788015215H30M7S",
            -Duration.INFINITE to "PT-2562047788015215H-30M-8S",
        ).forEach { (duration, text) ->
            val written = WowJson.mapper.writeValueAsString(Edge(duration, listOf(duration)))
            written.assert().describedAs("$duration").isEqualTo("""{"value":"$text","values":["$text"]}""")
            WowJson.mapper.readValue<Edge>(written).assert().isEqualTo(Edge(duration, listOf(duration)))
        }
        // Kotlin's own form of a negative duration reads too; text with surrounding spaces is refused, as a
        // java.time.Duration refuses it.
        WowJson.mapper.readValue<Edge>("""{"value":"-PT1H30M","values":[]}""").value
            .assert().isEqualTo(-(1.hours + 30.minutes))
        assertThrows<JacksonException> { WowJson.mapper.readValue<Edge>("""{"value":" PT1H ","values":[]}""") }
    }

    @Test
    fun `a number for a Kotlin duration should be refused, naming its text, while a Java duration reads seconds`() {
        // 10800000000000 is how a 10.0 beta wrote 1h30m: Kotlin's internal encoding, which no unit reads.
        listOf("""{"duration":10800000000000}""", """{"durations":[5]}""", """{"byName":{"a":5}}""").forEach { text ->
            assertThrows<JacksonException>(text) { WowJson.mapper.readValue<Timing>(text) }.message.assert()
                .contains("A kotlin.time.Duration is written as ISO-8601 text, such as PT1H30M, not as")
        }
        assertThrows<JacksonException> { WowJson.mapper.readValue<Timing>("""{"duration":"an hour"}""") }
        WowJson.mapper.readValue<JavaTiming>("""{"duration":90}""").duration
            .assert().isEqualTo(java.time.Duration.ofSeconds(90))
    }
}
