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

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.spi.json.JsonReadFailure.Reason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.databind.EnumNamingStrategies
import tools.jackson.databind.annotation.EnumNaming
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.module.kotlin.readValue
import java.time.Month

/**
 * An enumeration is read by its name alone: a number, or its text, is refused where Jackson would take the constant
 * of that index. A month's one-based number reads, and an enumeration written as a number reads as one. What Wow
 * writes for a constant reads back whatever writes it: a `@JsonValue` number or text, a naming strategy, a `toString`.
 * An enumeration a `@JsonCreator` reads keeps the reader Jackson makes for it, which these refusals leave alone.
 */
class EnumNumbersTest {
    enum class Status { PAID, SHIPPED }

    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    enum class Level { LOW, HIGH }

    enum class Grade { PASS, MERIT }

    enum class Coded(@get:JsonValue val code: Int) { LOW(1), HIGH(2) }

    enum class Http(@get:JsonValue val code: String) { NOT_FOUND("404"), GONE("410") }

    @EnumNaming(EnumNamingStrategies.LowerCamelCaseStrategy::class)
    enum class Phase { FIRST_STEP, LAST_STEP }

    enum class Tagged {
        SEVENTH,
        EIGHTH,
        ;

        override fun toString(): String = if (this == SEVENTH) "7" else "eighth"
    }

    enum class Code {
        @JsonProperty("7")
        SEVEN,
        PLAIN,
    }

    data class Order(
        val status: Status = Status.PAID,
        val month: Month = Month.JANUARY,
        val level: Level = Level.LOW,
        val byStatus: Map<Status, Int> = emptyMap(),
        val wire: CommandResult.Status? = null,
        @JsonFormat(shape = JsonFormat.Shape.NUMBER)
        val grade: Grade = Grade.PASS,
        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        val rank: Grade = Grade.PASS,
        val code: Code = Code.PLAIN,
        @JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
        val loose: Status = Status.PAID,
        val coded: Coded = Coded.LOW,
        val http: Http = Http.NOT_FOUND,
        val phase: Phase = Phase.FIRST_STEP,
        val tagged: Tagged = Tagged.EIGHTH,
    )

    private val failures = JsonReadFailures(WowJson.mapper)

    private fun refusal(json: String): JsonReadFailure {
        val failure = assertThrows<JacksonException>(json) { WowJson.mapper.readValue<Order>(json) }
        return failures.of(WowJson.mapper.readTree(json), Order::class.java, failure)!!
    }

    @Test
    fun `a number for an enumeration should be refused, its text too`() {
        refusal("""{"status":1}""").run {
            path.assert().isEqualTo("status")
            reason.assert().isEqualTo(Reason.WRONG_KIND)
            message.assert().isEqualTo("Expected a string, given an integer.")
        }
        refusal("""{"status":"1"}""").run {
            path.assert().isEqualTo("status")
            reason.assert().isEqualTo(Reason.NOT_IN_ENUMERATION)
        }
        refusal("""{"status":true}""").path.assert().isEqualTo("status")
        refusal("""{"wire":1}""").message.assert().isEqualTo("Expected a string, given an integer.")
        assertThrows<JacksonException> { WowJson.mapper.readValue<Order>("""{"byStatus":{"1":1}}""") }
        WowJson.mapper.readValue<Order>("""{"status":"SHIPPED","byStatus":{"PAID":1}}""").run {
            status.assert().isEqualTo(Status.SHIPPED)
            byStatus.assert().isEqualTo(mapOf(Status.PAID to 1))
        }
    }

    @Test
    fun `a month's one-based number and an enumeration written as a number should read`() {
        WowJson.mapper.readValue<Order>("""{"month":2}""").month.assert().isEqualTo(Month.FEBRUARY)
        WowJson.mapper.readValue<Order>("""{"month":"2"}""").month.assert().isEqualTo(Month.FEBRUARY)
        val written = WowJson.mapper.writeValueAsString(Order(level = Level.HIGH))
        written.assert().contains("\"level\":1")
        WowJson.mapper.readValue<Order>(written).level.assert().isEqualTo(Level.HIGH)
    }

    @Test
    fun `a property written as a number should read its number, on the class or the property`() {
        val written = WowJson.mapper.writeValueAsString(Order(grade = Grade.MERIT, rank = Grade.MERIT))
        written.assert().contains("\"grade\":1").contains("\"rank\":1")
        WowJson.mapper.readValue<Order>(written).run {
            grade.assert().isEqualTo(Grade.MERIT)
            rank.assert().isEqualTo(Grade.MERIT)
        }
        WowJson.mapper.readValue<Order>("""{"grade":1,"rank":1}""").rank.assert().isEqualTo(Grade.MERIT)
        refusal("""{"status":1}""").reason.assert().isEqualTo(Reason.WRONG_KIND)
    }

    @Test
    fun `a constant named with digits should read by that name, and an index's text for it should be refused`() {
        WowJson.mapper.writeValueAsString(Order(code = Code.SEVEN)).assert().contains("\"code\":\"7\"")
        WowJson.mapper.readValue<Order>("""{"code":"7"}""").code.assert().isEqualTo(Code.SEVEN)
        WowJson.mapper.readValue<Order>("""{"code":"PLAIN"}""").code.assert().isEqualTo(Code.PLAIN)
        refusal("""{"code":"1"}""").run {
            path.assert().isEqualTo("code")
            reason.assert().isEqualTo(Reason.NOT_IN_ENUMERATION)
        }
    }

    @Test
    fun `a property read case-insensitively should read its names so, and refuse a number still`() {
        WowJson.mapper.readValue<Order>("""{"loose":"shipped"}""").loose.assert().isEqualTo(Status.SHIPPED)
        refusal("""{"loose":1}""").reason.assert().isEqualTo(Reason.WRONG_KIND)
        refusal("""{"loose":"1"}""").reason.assert().isEqualTo(Reason.NOT_IN_ENUMERATION)
    }

    @Test
    fun `what Wow writes for a constant should read back, and an index no written form names should be refused`() {
        val order = Order(coded = Coded.HIGH, http = Http.GONE, phase = Phase.LAST_STEP, tagged = Tagged.SEVENTH)
        val written = WowJson.mapper.writeValueAsString(order)
        written.assert().contains("\"coded\":2", "\"http\":\"410\"", "\"phase\":\"lastStep\"", "\"tagged\":\"7\"")
        WowJson.mapper.readValue<Order>(written).assert().isEqualTo(order)
        // A number no constant's value names, the index 0 included, is refused.
        listOf("""{"coded":3}""", """{"coded":0}""").forEach { json -> refusal(json).path.assert().isEqualTo("coded") }
        listOf("http", "phase", "tagged").forEach { field ->
            refusal("""{"$field":"1"}""").run {
                path.assert().isEqualTo(field)
                reason.assert().isEqualTo(Reason.NOT_IN_ENUMERATION)
            }
        }
    }

    @Test
    fun `a reader by name should read a name, and refuse an index's text`() {
        val byName = WowJson.mapper.rebuild().disable(EnumFeature.READ_ENUMS_USING_TO_STRING).build()
        byName.readValue<Order>("""{"tagged":"SEVENTH"}""").tagged.assert().isEqualTo(Tagged.SEVENTH)
        assertThrows<JacksonException> { byName.readValue<Order>("""{"tagged":"1"}""") }
    }
}
