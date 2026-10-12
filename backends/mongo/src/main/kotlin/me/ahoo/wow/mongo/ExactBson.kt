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

package me.ahoo.wow.mongo

import me.ahoo.wow.spi.json.WowJson
import org.bson.Document
import org.bson.types.Decimal128
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.BooleanNode
import tools.jackson.databind.node.DecimalNode
import tools.jackson.databind.node.DoubleNode
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.LongNode
import tools.jackson.databind.node.NullNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.math.BigDecimal

/**
 * Turns a JSON document (an event payload, a snapshot state) into a BSON document and back without losing a number
 * (decision D122): `Document.parse` would store a decimal as a double.
 *
 * - A decimal is a `Decimal128`, which keeps its digits and its scale: `2.50` reads back as `2.50`. One with more
 *   than 34 significant digits, or an exponent `Decimal128` cannot hold, is refused, never rounded.
 * - An integer is an int32 or an int64 as it fits; beyond int64 it is a `Decimal128` of scale 0, refused like a
 *   decimal beyond 34 digits.
 * - Strings, booleans, nulls, objects and arrays are their BSON counterparts; field order is kept.
 *
 * Reading accepts what a JSON document turns into, a BSON double included (one stored before decimals were exact).
 */
internal object ExactBson {
    private val mapper = WowJson.mapper

    /** Decimal128 holds 34 significant digits. */
    private const val DECIMAL128_DIGITS = 34

    /** The JSON object [text] as a BSON document. */
    fun document(text: String): Document {
        val node = mapper.readTree(text)
        require(node is ObjectNode) { "A stored JSON document must be an object, not ${node.nodeType}." }
        return document(node)
    }

    /** The BSON [document] as JSON text, its decimals as written. */
    fun json(document: Document): String = mapper.writeValueAsString(node(document))

    private fun document(node: ObjectNode): Document = Document().apply {
        for ((name, value) in node.properties()) {
            put(name, value(value))
        }
    }

    private fun value(node: JsonNode): Any? = when {
        node is ObjectNode -> document(node)
        node is ArrayNode -> ArrayList<Any?>(node.size()).apply {
            // ArrayNode has a map member of its own, so iterate rather than map.
            for (element in node) {
                add(value(element))
            }
        }

        node.isString -> node.stringValue()
        node.isBoolean -> node.booleanValue()
        node.isNull -> null
        node.isInt -> node.intValue()
        node.isLong -> node.longValue()
        node.isBigInteger -> decimal128(BigDecimal(node.bigIntegerValue()))
        node.isBigDecimal -> decimal128(node.decimalValue())
        // Wow's mapper reads every JSON value as one of the nodes above.
        else -> throw IllegalArgumentException("A ${node.nodeType} node is not JSON a document can store.")
    }

    /** [value] as a Decimal128 of the same digits and scale; refused when it has no such Decimal128. */
    fun decimal128(value: BigDecimal): Decimal128 {
        require(value.precision() <= DECIMAL128_DIGITS) {
            "$value has ${value.precision()} significant digits; MongoDB stores at most $DECIMAL128_DIGITS exactly."
        }
        val decimal = try {
            Decimal128(value)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("$value is beyond what MongoDB stores exactly: ${e.message}", e)
        }
        // Decimal128 clamps an exponent out of its range by padding or dropping zeros, which would change the scale.
        require(decimal.bigDecimalValue() == value) { "$value is beyond what MongoDB stores exactly." }
        return decimal
    }

    private fun node(value: Any?): JsonNode = when (value) {
        null -> NullNode.instance
        is Document -> mapper.createObjectNode().apply {
            for ((name, field) in value) {
                set(name, node(field))
            }
        }

        is List<*> -> mapper.createArrayNode().apply {
            value.forEach { add(node(it)) }
        }

        is String -> StringNode.valueOf(value)
        is Boolean -> BooleanNode.valueOf(value)
        else -> number(value)
    }

    private fun number(value: Any): JsonNode = when (value) {
        is Int -> IntNode.valueOf(value)
        is Long -> LongNode.valueOf(value)
        is Double -> DoubleNode.valueOf(value)
        is Decimal128 -> DecimalNode.valueOf(value.bigDecimalValue())
        else -> throw IllegalStateException("A stored ${value.javaClass.name} is not JSON.")
    }
}
