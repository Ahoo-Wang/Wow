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

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.exc.InputCoercionException
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.DeserializationConfig
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.cfg.MutableCoercionConfig
import tools.jackson.databind.deser.ValueDeserializerModifier
import tools.jackson.databind.deser.std.DelegatingDeserializer
import tools.jackson.databind.exc.InvalidFormatException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.type.ArrayType
import tools.jackson.databind.type.LogicalType
import java.net.URL
import java.time.DateTimeException
import java.time.Year
import java.time.ZoneId
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * What Wow's JSON refuses to read that Jackson 3 would take: a value of another JSON kind than its type's, which
 * Jackson coerces, and a value Jackson reads as another one. Each refusal is an input Wow never writes, so what Wow
 * writes reads back; the forms Wow's JSON wrote before 9.0, on Jackson 2, still read.
 *
 * - **An integer** (`int`, `short`, `long`, `byte`, `BigInteger` and their boxes) from a float (`1.5`, `1e2`, `1.0`)
 *   or a string (`"7"`) is refused, and a `byte` beyond `-128` to `127`, which Jackson takes up to `255` and wraps.
 * - **A boolean** from a number or a string; **a `String`** from a number or a boolean; **a `char`** from a number.
 * - **A float, a double or a `BigDecimal`** from a string, except `"NaN"`, `"Infinity"` and `"-Infinity"`, which Wow
 *   writes for those values. A number too large for a float or a double reads as infinity, as IEEE 754 rounds it.
 * - **An empty string** for a `Character`, a `URL`, a `Currency` or a `java.time` value, which Jackson reads as null.
 *   A `Locale`'s `""` is its root locale, which Wow writes so, and reads.
 * - **A date-time's text read loosely** (strict leniency): a `LocalDate` with a time, a `LocalDateTime` with an offset,
 *   and a `Date` or a `Calendar` from a date alone, `"2026-01-02"`, which `Instant` refuses too.
 * - **A number for a `LocalDate`** (an epoch day). The numbers and arrays Wow's JSON wrote before 9.0 still read: a
 *   decimal of epoch seconds for an `Instant`, an `OffsetDateTime`, a `ZonedDateTime` and a `Duration`, epoch
 *   milliseconds for a `Date` and a `Calendar`, an array for a `LocalDateTime`, a `LocalDate`, a `YearMonth`, a
 *   `LocalTime` and an `OffsetTime`. Any other number or array for them is refused.
 * - **A `Year` from a string**, and **a `byte[]` from an array** of numbers: one shape for each schema, the integer and
 *   the Base64 text Wow writes.
 * - **A `TimeZone`** is read through `ZoneId`, so an unknown id is refused where Jackson reads it as `GMT`, and an
 *   offset, `"+08:00"`, is that offset. A short id Java knows (`PST`) reads as the zone it names.
 */
internal object StrictReads {
    fun configure(builder: JsonMapper.Builder): JsonMapper.Builder = builder
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .withCoercionConfig(LogicalType.Integer) { it.fail(CoercionInputShape.Float, CoercionInputShape.String) }
        .withCoercionConfig(LogicalType.Boolean) { it.fail(CoercionInputShape.Integer, CoercionInputShape.String) }
        .withCoercionConfig(LogicalType.Textual) {
            it.fail(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)
        }
        .withCoercionConfig(LogicalType.Float) { it.fail(CoercionInputShape.String) }
        .withCoercionConfig(LogicalType.DateTime) { it.fail(CoercionInputShape.Integer) }
        .withCoercionConfig(Char::class.javaPrimitiveType!!) { it.fail(CoercionInputShape.Integer) }
        .withCoercionConfig(Character::class.java) {
            it.fail(CoercionInputShape.Integer, CoercionInputShape.EmptyString)
        }
        .withCoercionConfig(URL::class.java) { it.fail(CoercionInputShape.EmptyString) }
        .withCoercionConfig(Currency::class.java) { it.fail(CoercionInputShape.EmptyString) }
        .defaultLeniency(false)
        .addModule(module)

    private fun MutableCoercionConfig.fail(vararg shapes: CoercionInputShape) {
        shapes.forEach { setCoercion(it, CoercionAction.Fail) }
    }

    private val module: SimpleModule = SimpleModule("wow-strict-reads")
        .setDeserializerModifier(Modifier)
        .addDeserializer(TimeZone::class.java, TimeZones)

    private val BYTES = setOf(Byte::class.javaPrimitiveType, Byte::class.javaObjectType)
    private val DATES = setOf(Date::class.java, Calendar::class.java, GregorianCalendar::class.java)

    /** A date alone, as `StdDateFormat` reads one at UTC's midnight. */
    private val DATE_ALONE = Regex("""[+-]?\d{4,}-\d{2}-\d{2}""")

    private object Modifier : ValueDeserializerModifier() {
        override fun modifyDeserializer(
            config: DeserializationConfig,
            beanDesc: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ): ValueDeserializer<*> = when (val type = beanDesc.beanClass) {
            in BYTES -> Checked(deserializer, type, Check.BYTE)
            Year::class.java -> Checked(deserializer, type, Check.YEAR)
            in DATES -> Checked(deserializer, type, Check.DATE)
            else -> deserializer
        }

        override fun modifyArrayDeserializer(
            config: DeserializationConfig,
            valueType: ArrayType,
            beanDesc: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ): ValueDeserializer<*> = if (valueType.rawClass == ByteArray::class.java) {
            Checked(deserializer, ByteArray::class.java, Check.BYTES)
        } else {
            deserializer
        }
    }

    private enum class Check { BYTE, YEAR, DATE, BYTES }

    /** Jackson's reader of [type], refusing first what [check] says it takes and Wow does not. */
    private class Checked(delegate: ValueDeserializer<*>, private val type: Class<*>, private val check: Check) :
        DelegatingDeserializer(delegate) {
        override fun newDelegatingInstance(newDelegatee: ValueDeserializer<*>): ValueDeserializer<*> =
            Checked(newDelegatee, type, check)

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any? {
            val token = p.currentToken()
            when (check) {
                Check.BYTE -> if (token == JsonToken.VALUE_NUMBER_INT) checkByte(p)
                Check.YEAR -> if (token == JsonToken.VALUE_STRING) return ctxt.handleUnexpectedToken(type, p)
                Check.BYTES -> if (token == JsonToken.START_ARRAY) return ctxt.handleUnexpectedToken(type, p)
                Check.DATE -> if (token == JsonToken.VALUE_STRING && DATE_ALONE.matches(p.string.trim())) {
                    throw InvalidFormatException.from(p, "A date alone is not a ${type.simpleName}.", p.string, type)
                }
            }
            return super.deserialize(p, ctxt)
        }

        private fun checkByte(p: JsonParser) {
            val value = try {
                p.longValue
            } catch (_: InputCoercionException) {
                Long.MAX_VALUE
            }
            if (value !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                throw OutOfRange(p, "${p.string} is out of the range of a byte, -128 to 127.", p.string, type)
            }
        }
    }

    /** A [TimeZone] read through [ZoneId]: an unknown id is refused, an offset is that offset. */
    private object TimeZones : ValueDeserializer<TimeZone>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): TimeZone {
            if (p.currentToken() != JsonToken.VALUE_STRING) {
                return ctxt.handleUnexpectedToken(TimeZone::class.java, p) as TimeZone
            }
            val text = p.string
            val zone = try {
                ZoneId.of(text, ZoneId.SHORT_IDS)
            } catch (_: DateTimeException) {
                throw InvalidFormatException.from(p, "$text is not a time zone.", text, TimeZone::class.java)
            }
            // An id TimeZone knows is kept as written (PST stays PST); another, an offset, is the zone it names.
            return TimeZone.getTimeZone(text).takeIf { it.id == text } ?: TimeZone.getTimeZone(zone)
        }
    }
}

/** A number out of its type's range, which [JsonReadFailures] reports so. */
internal class OutOfRange(p: JsonParser, message: String, value: Any?, type: Class<*>) :
    InvalidFormatException(p, message, value, type)
