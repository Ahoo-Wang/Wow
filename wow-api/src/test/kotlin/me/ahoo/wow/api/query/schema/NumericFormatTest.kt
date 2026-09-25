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

package me.ahoo.wow.api.query.schema

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jsonMapper

class NumericFormatTest {
    private val jsonMapper = jsonMapper()

    private fun read(json: String): QuerySemanticType = jsonMapper.readValue(json, QuerySemanticType::class.java)

    @Test
    fun `decimal and money round trip through semantic JSON`() {
        listOf(
            NumericFormat.Decimal(2),
            NumericFormat.Money(currency = "CNY", scale = 2),
            NumericFormat.Money(currencyField = "currency", scale = 4),
        ).forEach { format ->
            read(jsonMapper.writeValueAsString(format)).assert().isEqualTo(format)
        }
        jsonMapper.writeValueAsString(NumericFormat.Money(currency = "CNY", scale = 2)).assert()
            .isEqualTo("""{"type":"MONEY","currency":"CNY","scale":2}""")
    }

    @Test
    fun `a fixed currency defaults to its standard fraction digits`() {
        read("""{"type":"MONEY","currency":"CNY"}""").assert().isEqualTo(NumericFormat.Money("CNY", null, 2))
        read("""{"type":"MONEY","currency":"JPY"}""").assert().isEqualTo(NumericFormat.Money("JPY", null, 0))
        read("""{"type":"MONEY","currency":"USD","scale":4}""").assert().isEqualTo(NumericFormat.Money("USD", null, 4))
        read("""{"type":"DECIMAL","scale":3}""").assert().isEqualTo(NumericFormat.Decimal(3))
    }

    @Test
    fun `money names exactly one currency source and a currency field needs a scale`() {
        assertThrows<IllegalArgumentException> { NumericFormat.Money.of() }
        assertThrows<IllegalArgumentException> { NumericFormat.Money.of("CNY", "currency", 2) }
        assertThrows<IllegalArgumentException> { NumericFormat.Money.of(currencyField = "currency") }
        assertThrows<IllegalArgumentException> { NumericFormat.Money.of(currency = "cny") }
        assertThrows<IllegalArgumentException> { NumericFormat.Money.of(currency = "ZZZ") }
        // Precious metals have no standard fraction digits.
        assertThrows<IllegalArgumentException> { NumericFormat.Money.of(currency = "XAU") }
        NumericFormat.Money.of(currency = "XAU", scale = 3).scale.assert().isEqualTo(3)
        assertThrows<IllegalArgumentException> { NumericFormat.Money.of(currencyField = "a.b", scale = 2) }
    }

    @Test
    fun `scale lies within a 128-bit decimal`() {
        assertThrows<IllegalArgumentException> { NumericFormat.Decimal(-1) }
        assertThrows<IllegalArgumentException> { NumericFormat.Decimal(NumericFormat.MAX_SCALE + 1) }
        NumericFormat.Decimal(NumericFormat.MAX_SCALE).scale.assert().isEqualTo(NumericFormat.MAX_SCALE)
    }
}
