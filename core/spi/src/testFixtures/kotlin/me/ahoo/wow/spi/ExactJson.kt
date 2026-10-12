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

package me.ahoo.wow.spi

import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * JSON compared as a backend may give it back: objects by field whatever their key order (PostgreSQL JSONB reorders
 * keys), numbers by exact value and scale, so `2.50` is not `2.5` and an integer is not a decimal.
 */
object ExactJson {
    /**
     * A JSON object whose numbers a backend must keep exactly (decision D122): a decimal's trailing zeros, a 30-digit
     * decimal, an integer beyond `long`, one beyond a double's precision, and decimals in a nested untyped map.
     */
    const val NUMBERS = """{"price":2.50,"precise":1234567890.12345678901234567890,""" +
        """"large":123456789012345678901234567890,"long":9007199254740993,"zero":0.00,""" +
        """"nested":{"rate":0.10,"untyped":{"list":[1.0,2,{"deep":-0.000001}]}}}"""

    /** A value of [text] that equals another's exactly when both hold the same JSON, numbers as written. */
    fun of(text: String): Any? = of(WowJson.mapper.readTree(text), exact = true)

    /**
     * A value of [text] that equals another's when both hold the same JSON whatever form their numbers are written
     * in: `1.0E10` is `10000000000` and `2.50` is `2.5`, as the kernel compares a snapshot's state with what it writes
     * back. A backend may give a number back in another form (PostgreSQL JSONB writes no exponent) but with its value.
     */
    fun valueOf(text: String): Any? = of(WowJson.mapper.readTree(text), exact = false)

    private fun of(node: JsonNode, exact: Boolean): Any? = when {
        node.isObject -> node.properties().associate { (name, value) -> name to of(value, exact) }
        node.isArray -> buildList {
            // ArrayNode has a map member of its own, so iterate rather than map.
            for (element in node) {
                add(of(element, exact))
            }
        }

        node.isNumber && !exact -> node.decimalValue().stripTrailingZeros()
        node.isNumber -> Number(node.isIntegralNumber, node.decimalValue())
        node.isString -> node.stringValue()
        node.isBoolean -> node.booleanValue()
        else -> null
    }

    /** A number by its kind and its exact value; [java.math.BigDecimal.equals] tells `2.50` from `2.5`. */
    private data class Number(val integral: Boolean, val value: BigDecimal)
}
