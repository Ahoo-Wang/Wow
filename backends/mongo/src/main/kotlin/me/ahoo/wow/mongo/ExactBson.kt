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
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonReader
import org.bson.BsonString
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.Document
import org.bson.types.Decimal128
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.DeserializationFeature
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
import java.io.StringWriter
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
    private const val NUL = '\u0000'

    /** The JSON object [text] as a BSON document. */
    fun document(text: String): Document {
        val node = mapper.readTree(text)
        require(node is ObjectNode) { "A stored JSON document must be an object, not ${node.nodeType}." }
        return document(node)
    }

    /**
     * The JSON object [text] as a BSON document, by the rules [document] follows, read token by token: no JSON tree and
     * no [Document] in between, and a value the driver writes without looking a codec up.
     */
    fun bson(text: String): BsonDocument = mapper.createParser(text).use { parser ->
        val first = parser.nextToken()
        require(first == JsonToken.START_OBJECT) { "A stored JSON document must be an object, not $first." }
        val document = documentOf(parser)
        // As the mapper reads a tree: what follows the object is a malformed document, not a value refused.
        if (mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) && parser.nextToken() != null) {
            throw StreamReadException(parser, "A stored JSON document is one object, with nothing after it.")
        }
        document
    }

    /** The object [parser] is at the start of, through its end. */
    private fun documentOf(parser: JsonParser): BsonDocument = BsonDocument().apply {
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            val name = parser.currentName()
            require(NUL !in name) { "The key ${name.replace(NUL, '?')} holds \\u0000, which a BSON key cannot." }
            parser.nextToken()
            put(name, valueOf(parser))
        }
    }

    private fun valueOf(parser: JsonParser): BsonValue = when (val token = parser.currentToken()) {
        JsonToken.START_OBJECT -> documentOf(parser)
        JsonToken.START_ARRAY -> BsonArray().apply {
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                add(valueOf(parser))
            }
        }

        JsonToken.VALUE_STRING -> BsonString(parser.string)
        JsonToken.VALUE_TRUE -> BsonBoolean.TRUE
        JsonToken.VALUE_FALSE -> BsonBoolean.FALSE
        JsonToken.VALUE_NULL -> BsonNull.VALUE
        JsonToken.VALUE_NUMBER_INT -> integerOf(parser)
        JsonToken.VALUE_NUMBER_FLOAT -> BsonDecimal128(decimal128(parser.decimalValue))
        else -> throw IllegalArgumentException("A stored JSON document holds no $token.")
    }

    /** The integer [parser] is at: an int32 or an int64 as it fits, beyond int64 a Decimal128 of scale 0. */
    private fun integerOf(parser: JsonParser): BsonValue = when (parser.numberType) {
        JsonParser.NumberType.INT -> BsonInt32(parser.intValue)
        JsonParser.NumberType.LONG -> BsonInt64(parser.longValue)
        else -> BsonDecimal128(decimal128(BigDecimal(parser.bigIntegerValue)))
    }

    /**
     * The document [reader] is at, as JSON text by the rules of [json]: read value by value and written as it is read,
     * with no [Document] or JSON tree in between. The reader ends past the document.
     */
    fun json(reader: BsonReader): String {
        val text = StringWriter()
        mapper.createGenerator(text).use { generator -> write(reader, generator) }
        return text.toString()
    }

    private fun write(reader: BsonReader, generator: JsonGenerator) {
        when (val type = reader.currentBsonType) {
            BsonType.DOCUMENT -> {
                reader.readStartDocument()
                generator.writeStartObject()
                while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
                    generator.writeName(reader.readName())
                    write(reader, generator)
                }
                reader.readEndDocument()
                generator.writeEndObject()
            }

            BsonType.ARRAY -> {
                reader.readStartArray()
                generator.writeStartArray()
                while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
                    write(reader, generator)
                }
                reader.readEndArray()
                generator.writeEndArray()
            }

            BsonType.STRING -> generator.writeString(reader.readString())
            BsonType.BOOLEAN -> generator.writeBoolean(reader.readBoolean())
            BsonType.NULL -> {
                reader.readNull()
                generator.writeNull()
            }

            else -> writeNumber(type, reader, generator)
        }
    }

    private fun writeNumber(type: BsonType, reader: BsonReader, generator: JsonGenerator) {
        when (type) {
            BsonType.INT32 -> generator.writeNumber(reader.readInt32())
            BsonType.INT64 -> generator.writeNumber(reader.readInt64())
            BsonType.DOUBLE -> generator.writeNumber(reader.readDouble())
            // A NaN or an infinity has no JSON number, as [number] reads it.
            BsonType.DECIMAL128 -> {
                val decimal = reader.readDecimal128()
                if (decimal.isNaN || decimal.isInfinite) {
                    generator.writeNull()
                } else {
                    generator.writeNumber(decimal.bigDecimalValue())
                }
            }

            else -> throw IllegalStateException("A stored $type is not JSON.")
        }
    }

    /** The BSON [document] as JSON text, its decimals as written. */
    fun json(document: Document): String = mapper.writeValueAsString(node(document))

    private fun document(node: ObjectNode): Document = Document().apply {
        for ((name, value) in node.properties()) {
            // A BSON key is a C string: refused here, as a value, not by the driver as it writes (finding A2-7).
            require(NUL !in name) { "The key ${name.replace(NUL, '?')} holds \\u0000, which a BSON key cannot." }
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
        // What Wow's mapper reads besides the nodes above is a decimal.
        else -> decimal128(node.decimalValue())
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

    /** A stored BSON [value] as JSON: a document as an object, a list as an array, a decimal as written. */
    fun node(value: Any?): JsonNode = when (value) {
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
        // A NaN or an infinity has no JSON number; an aggregation computes them only from values that are not JSON.
        is Decimal128 -> if (value.isNaN || value.isInfinite) {
            NullNode.instance
        } else {
            DecimalNode.valueOf(
                value.bigDecimalValue()
            )
        }
        else -> throw IllegalStateException("A stored ${value.javaClass.name} is not JSON.")
    }
}
