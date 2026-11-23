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
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.exc.InputCoercionException
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationConfig
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JavaType
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.cfg.MutableCoercionConfig
import tools.jackson.databind.deser.BeanDeserializerBuilder
import tools.jackson.databind.deser.ValueDeserializerModifier
import tools.jackson.databind.deser.jdk.EnumDeserializer
import tools.jackson.databind.deser.std.DelegatingDeserializer
import tools.jackson.databind.exc.InvalidFormatException
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.type.ArrayType
import tools.jackson.databind.type.LogicalType
import java.net.URL
import java.time.DateTimeException
import java.time.MonthDay
import java.time.Year
import java.time.ZoneId
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.GregorianCalendar
import java.util.IllformedLocaleException
import java.util.Locale
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
 * - **A computed property** (a getter alone) that Wow's JSON writes is known to its reader, which reads nothing into
 *   it: a reader that refuses an unknown property, a command's body's, reads what Wow wrote of the command.
 * - **A number for an enumeration**, or a number's text that is no name of it, which Jackson reads as the constant of
 *   that index: Wow writes an enumeration by its name. A `Month` reads its one-based number, and an enumeration
 *   written as a number (`@JsonFormat(shape = NUMBER)`, on the class or the property) reads its number, not its text.
 *   A text that names no constant is an unknown value, which `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE` or
 *   `READ_UNKNOWN_ENUM_VALUES_AS_NULL` reads where the application enables them, and which is refused otherwise.
 * - **A `Year` from a string**, and **a `byte[]` or a `MonthDay` from an array**: one shape for each schema, the
 *   integer and the text Wow writes. No release wrote a `MonthDay` as an array: 1.x to 6.x wrote `"--01-02"` too.
 * - **A `TimeZone`** is read through `ZoneId`, so an unknown id is refused where Jackson reads it as `GMT`, and an
 *   offset, `"+08:00"`, is that offset. A short id Java knows (`PST`) reads as the zone it names.
 * - **A `Locale` of one subtag** (a text with no `-` or `_`) is read as a BCP 47 tag, so one that is no tag,
 *   `"not a tag!"`, is refused. Jackson 3.1 reads such a text as a language of that name and 3.2 as a tag: where the
 *   minors Wow runs on answer one input differently, Wow answers as the stricter does, on every one of them.
 *
 * One input has one answer on every Jackson minor Wow runs on. Where a minor's reader differs, Wow decides the input
 * before it, as the `Locale` above; the enumeration's refusals are Jackson's own switches (`FAIL_ON_NUMBERS_FOR_ENUMS`
 * and a coercion), which every minor reads the same way, so the text Jackson takes for a constant is the one its own
 * lookup holds.
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
        // A number's text that is no name is refused by Jackson's reader itself, from its own lookup; a number stays
        // Jackson's to read, for an enumeration written as one, and [ByName] refuses it for the others.
        .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .withCoercionConfig(LogicalType.Enum) {
            it.setCoercion(CoercionInputShape.Integer, CoercionAction.TryConvert)
        }
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
            MonthDay::class.java -> Checked(deserializer, type, Check.NO_ARRAY)
            in DATES -> Checked(deserializer, type, Check.DATE)
            Locale::class.java -> Checked(deserializer, type, Check.LOCALE)
            else -> deserializer
        }

        /**
         * A computed property, a getter with no field, setter or creator parameter behind it, is one Wow's JSON writes
         * and reads nothing into: a reader that refuses an unknown property (a command's body) takes it as known.
         */
        override fun updateBuilder(
            config: DeserializationConfig,
            beanDesc: BeanDescription.Supplier,
            builder: BeanDeserializerBuilder,
        ): BeanDeserializerBuilder = builder.apply {
            beanDesc.get().findProperties()
                .filter { it.hasGetter() && !it.hasSetter() && !it.hasField() && !it.hasConstructorParameter() }
                .forEach { addIgnorable(it.name) }
        }

        override fun modifyEnumDeserializer(
            config: DeserializationConfig,
            type: JavaType,
            beanDesc: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ): ValueDeserializer<*> = if (deserializer.javaClass == EnumDeserializer::class.java && !numbered(type.rawClass)) {
            // Jackson's reader alone reads an index: a reader of Wow's ([WowEnums]), a `@JsonCreator`'s or the
            // domain's decides its own.
            ByName(deserializer, type.rawClass, isInteger(beanDesc.get().findJsonValueAccessor()?.rawType))
        } else {
            deserializer
        }

        override fun modifyArrayDeserializer(
            config: DeserializationConfig,
            valueType: ArrayType,
            beanDesc: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ): ValueDeserializer<*> = if (valueType.rawClass == ByteArray::class.java) {
            Checked(deserializer, ByteArray::class.java, Check.NO_ARRAY)
        } else {
            deserializer
        }
    }

    private enum class Check { BYTE, YEAR, DATE, NO_ARRAY, LOCALE }

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
                Check.NO_ARRAY -> if (token == JsonToken.START_ARRAY) return ctxt.handleUnexpectedToken(type, p)
                Check.DATE -> if (token == JsonToken.VALUE_STRING && DATE_ALONE.matches(p.string.trim())) {
                    throw InvalidFormatException.from(p, "A date alone is not a ${type.simpleName}.", p.string, type)
                }
                Check.LOCALE -> if (token == JsonToken.VALUE_STRING) subtag(p)?.let { return it }
            }
            return super.deserialize(p, ctxt)
        }

        /** The locale of a text of one subtag, read as a BCP 47 tag; null for any other text, which Jackson reads. */
        private fun subtag(p: JsonParser): Locale? {
            val text = p.string.trim()
            if (text.isEmpty() || text.any { it == '-' || it == '_' }) return null
            return try {
                Locale.Builder().setLanguageTag(text).build()
            } catch (_: IllformedLocaleException) {
                throw InvalidFormatException.from(p, "$text is not a language tag.", text, type)
            }
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

    /** Whether this shape writes an enumeration as its number, which it then reads as one. */
    private fun JsonFormat.Shape?.numbered(): Boolean =
        this == JsonFormat.Shape.NUMBER || this == JsonFormat.Shape.NUMBER_INT

    private fun numbered(type: Class<*>): Boolean = type.getAnnotation(JsonFormat::class.java)?.shape.numbered()

    /** The types of a `@JsonValue` Jackson reads a number by, as its `EnumResolver` decides: an integer's. */
    private val INTEGERS = setOf(
        Long::class.javaObjectType,
        Int::class.javaObjectType,
        Short::class.javaObjectType,
        Byte::class.javaObjectType,
    )

    private fun isInteger(type: Class<*>?): Boolean = type != null && type.kotlin.javaObjectType in INTEGERS

    /**
     * Jackson's enumeration reader, refusing a number where it would read the constant of that index. A `@JsonValue`
     * integer is a form of its own, which Jackson reads by its lookup ([numbers]); a property written as a number
     * (`@JsonFormat(shape = NUMBER)`) reads as one, and a class written so keeps Jackson's reader. A number's text that
     * is no form is refused by Jackson's reader itself (`FAIL_ON_NUMBERS_FOR_ENUMS`), from the forms it writes.
     */
    private class ByName(delegate: ValueDeserializer<*>, private val type: Class<*>, private val numbers: Boolean) :
        DelegatingDeserializer(delegate) {
        override fun newDelegatingInstance(newDelegatee: ValueDeserializer<*>): ValueDeserializer<*> =
            ByName(newDelegatee, type, numbers)

        override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): ValueDeserializer<*> {
            // A delegating reader's contextual one is of its own class: this one, or one over the new delegate.
            val contextual = super.createContextual(ctxt, property) as ByName
            val numbered = findFormatOverrides(ctxt, property, type).shape.numbered()
            return if (numbered) contextual._delegatee else contextual
        }

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any? {
            if (!numbers && p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                throw MismatchedInputException.from(p, type, "${type.simpleName} is read by name, not by number.")
            }
            return super.deserialize(p, ctxt)
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
