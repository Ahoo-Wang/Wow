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
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class LogicalFieldTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `untyped logical field should use string JSON`() {
        val field = LogicalField("state.createdAt")

        mapper.writeValueAsString(field).assert().isEqualTo("\"state.createdAt\"")
        mapper.readValue("\"state.createdAt\"", LogicalField::class.java).assert().isEqualTo(field)
    }

    @Test
    fun `typed logical field should use name and type object JSON`() {
        val field = LogicalField(
            "snapshotTime",
            FieldType.Temporal.NumericEpoch(TimeUnit.MILLISECONDS),
        )

        val json = mapper.writeValueAsString(field)
        json.assert()
            .contains("\"name\":\"snapshotTime\"")
            .contains("\"type\":{\"type\":\"NUMBER\",\"timeUnit\":\"MILLISECONDS\"}")
        mapper.readValue(json, LogicalField::class.java).assert().isEqualTo(field)
    }

    @Test
    fun `logical field should reject invalid JSON shapes`() {
        assertThrows<JacksonException> {
            mapper.readValue("1", LogicalField::class.java)
        }
        assertThrows<JacksonException> {
            mapper.readValue("""{"type":{"type":"DATE"}}""", LogicalField::class.java)
        }
    }

    @Test
    fun `field type should round trip and formatted string should require exactly one formatter`() {
        val values = listOf<FieldType>(
            FieldType.Temporal.Date,
            FieldType.Temporal.NumericEpoch(TimeUnit.SECONDS),
            FieldType.Temporal.FormattedString(datePattern = "yyyy-MM-dd"),
        )

        values.forEach { value ->
            val json = mapper.writeValueAsString(value)
            mapper.readValue(json, FieldType::class.java).assert().isEqualTo(value)
        }

        assertThrows<IllegalArgumentException> { FieldType.Temporal.FormattedString() }
        assertThrows<IllegalArgumentException> {
            FieldType.Temporal.FormattedString(
                datePattern = "yyyy-MM-dd",
                dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE,
            )
        }
    }
}
