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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * A snapshot's state loads when it writes back as the same JSON value, whatever form a backend gave it back in, and is
 * refused when its value does not survive the round trip.
 */
class StateRoundTripTest {
    class Measured {
        var amount: Double = 0.0
        var tiny: Double = 0.0
        var price: BigDecimal = BigDecimal.ZERO
        var count: Long = 0
        var extra: Map<String, Any?> = emptyMap()
    }

    private val codec = EventCodec()
    private val model = aggregate<Measured>("test", "measured") { initial { Measured() } }

    private fun measured() = Measured().apply {
        amount = 1.0E10
        tiny = 1.0E-7
        price = BigDecimal("2.50")
        count = 3
        extra = mapOf("rate" to BigDecimal("0.10"), "list" to listOf(1, BigDecimal("2.0")))
    }

    private fun sameJson(left: String, right: String): Boolean =
        codec.sameJson(WowJson.mapper.readTree(left), WowJson.mapper.readTree(right))

    @Test
    fun `a state holding 1_0E10 loads back from its own text`() {
        val text = codec.stateText(measured())
        text.assert().contains("1.0E10")
        val state = codec.decodeState(model, text)
        state.amount.assert().isEqualTo(1.0E10)
        state.price.assert().isEqualTo(BigDecimal("2.50"))
    }

    @Test
    fun `a state given back with its keys reordered and its numbers normalised loads`() {
        // As PostgreSQL JSONB gives it back: no exponent, its own key order and whitespace.
        val normalised = """{"extra": {"list": [1, 2.0], "rate": 0.10}, "count": 3, "price": 2.50,""" +
            """ "tiny": 0.00000010, "amount": 10000000000}"""
        val state = codec.decodeState(model, normalised)
        state.amount.assert().isEqualTo(1.0E10)
        state.tiny.assert().isEqualTo(1.0E-7)
        state.price.assert().isEqualTo(BigDecimal("2.50"))
        state.count.assert().isEqualTo(3L)
        state.extra.assert().isEqualTo(mapOf("rate" to BigDecimal("0.10"), "list" to listOf(1, BigDecimal("2.0"))))
    }

    @Test
    fun `a number its property cannot hold is refused`() {
        // A double holds 9007199254740992, not 9007199254740993: the state would decide on another value.
        val text = """{"amount":9007199254740993,"tiny":0,"price":0,"count":0,"extra":{}}"""
        assertThrows<IllegalArgumentException> { codec.decodeState(model, text) }
    }

    @Test
    fun `numbers are the same JSON when their values are`() {
        sameJson("1.0E10", "10000000000").assert().isTrue()
        sameJson("2.50", "2.5").assert().isTrue()
        sameJson("0", "0.00").assert().isTrue()
        sameJson("1E-7", "0.00000010").assert().isTrue()
        sameJson("123456789012345678901234567890", "1.2345678901234567890123456789E29").assert().isTrue()
        sameJson("9007199254740993", "9007199254740992").assert().isFalse()
        sameJson("2.5", "2.51").assert().isFalse()
    }

    @Test
    fun `objects compare by field and arrays by position`() {
        sameJson("""{"a":1,"b":{"c":[1,2]}}""", """{"b":{"c":[1.0,2]},"a":1}""").assert().isTrue()
        sameJson("""{"a":1}""", """{"a":1,"b":null}""").assert().isFalse()
        sameJson("""{"a":1,"b":null}""", """{"a":1,"c":null}""").assert().isFalse()
        sameJson("""{"a":1}""", """{"a":2}""").assert().isFalse()
        sameJson("[1,2]", "[2,1]").assert().isFalse()
        sameJson("[1,2]", "[1,2,3]").assert().isFalse()
        sameJson("[]", "{}").assert().isFalse()
    }

    @Test
    fun `values other than numbers compare as they are`() {
        sameJson(""""1"""", "1").assert().isFalse()
        sameJson("true", "true").assert().isTrue()
        sameJson("true", "false").assert().isFalse()
        sameJson("null", "null").assert().isTrue()
        sameJson(""""a"""", """"a"""").assert().isTrue()
        sameJson("null", "0").assert().isFalse()
    }
}
