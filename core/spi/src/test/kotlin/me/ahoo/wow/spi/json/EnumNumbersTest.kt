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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.spi.json.JsonReadFailure.Reason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.readValue
import java.time.Month

/**
 * An enumeration is read by its name alone: a number, or its text, is refused where Jackson would take the constant
 * of that index. A month's one-based number reads, and an enumeration written as a number reads as one.
 */
class EnumNumbersTest {
    enum class Status { PAID, SHIPPED }

    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    enum class Level { LOW, HIGH }

    data class Order(
        val status: Status = Status.PAID,
        val month: Month = Month.JANUARY,
        val level: Level = Level.LOW,
        val byStatus: Map<Status, Int> = emptyMap(),
        val wire: CommandResult.Status? = null,
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
}
