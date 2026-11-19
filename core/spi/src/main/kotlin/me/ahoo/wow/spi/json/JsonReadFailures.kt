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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.json.JsonReadFailure.Reason
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.exc.InputCoercionException
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.DatabindException
import tools.jackson.databind.DeserializationConfig
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.ValueDeserializerModifier
import tools.jackson.databind.deser.std.DelegatingDeserializer
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.node.MissingNode
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Why a JSON value did not read as its type, in Wow's words: the property, by its [path] in the JSON
 * (`items[0].quantity`, empty for the value itself), and one [reason] of a small set with its [message]. Never
 * Jackson's message, which can name a class of the application.
 */
@InternalWowApi
class JsonReadFailure(val path: String, val reason: Reason, val message: String) {
    /** Why a value does not read, each with the [code] a violation carries. */
    @InternalWowApi
    enum class Reason(val code: String) {
        /** The value is of another JSON kind than its type's: a string for a number, say. */
        WRONG_KIND("wrong-kind"),

        /** A number beyond its type's range. */
        OUT_OF_RANGE("out-of-range"),

        /** A value of the right kind that is not a valid value of its format: a text that is no date, say. */
        INVALID_FORMAT("invalid-format"),

        /** A text that is not one of its enumeration's values. */
        NOT_IN_ENUMERATION("not-in-enumeration"),
    }
}

/**
 * Describes the failures of [mapper]'s reads ([JsonReadFailure]). A number out of range, which Jackson reports with no
 * path, is located by reading the value again, on this error path only, with readers that name the path.
 */
@InternalWowApi
class JsonReadFailures(private val mapper: JsonMapper) {
    private val located: JsonMapper by lazy { mapper.rebuild().addModule(LOCATED_NUMBERS).build() }

    /**
     * Why [json] did not read as [type], given the [failure] of reading it; null when [failure] is not about a value,
     * a JSON syntax error when it was text, say.
     */
    fun of(json: JsonNode, type: Class<*>, failure: JacksonException): JsonReadFailure? {
        val databind = when (failure) {
            is DatabindException -> failure
            is InputCoercionException -> relocated(json, type) ?: return JsonReadFailure("", Reason.OUT_OF_RANGE, RANGE)
            else -> return null
        }
        val (reason, message) = describe(json, databind)
        return JsonReadFailure(pathOf(databind), reason, message)
    }

    /**
     * The reason and message of [failure]: the kind expected only where it is known, for a scalar class Wow's JSON
     * describes or an enumeration; for any other class, a value class or one read by a reader of its own, say, the kind
     * given alone.
     */
    private fun describe(json: JsonNode, failure: DatabindException): Pair<Reason, String> {
        if (failure is OutOfRange) {
            return Reason.OUT_OF_RANGE to rangeOf(failure.targetType)
        }
        val given = kindOf(nodeAt(json, failure))
        if (given == Kind.NONE || given == Kind.NULL) {
            return Reason.WRONG_KIND to "A value is required, given ${given.words}."
        }
        val target = (failure as? MismatchedInputException)?.targetType
        val enumeration = target?.isEnum == true
        val expected = target?.let { KINDS[it] } ?: STRING.takeIf { enumeration }
        return when {
            expected == null -> Reason.INVALID_FORMAT to "Not a valid value of its type, given ${given.words}."
            given !in expected -> Reason.WRONG_KIND to "Expected ${expected.first().words}, given ${given.words}."
            enumeration -> Reason.NOT_IN_ENUMERATION to "Not one of the values of its enumeration."
            else -> Reason.INVALID_FORMAT to "Not a valid value of its format."
        }
    }

    /** The failure of reading [json] as [type] again with readers that locate a number out of range. */
    private fun relocated(json: JsonNode, type: Class<*>): DatabindException? =
        runCatching { located.treeToValue(json, type) }.exceptionOrNull() as? DatabindException

    /** A JSON kind, by the words a message gives it; not an enumeration, which would be a wire name. */
    private class Kind private constructor(val words: String) {
        companion object {
            val OBJECT = Kind("an object")
            val ARRAY = Kind("an array")
            val STRING = Kind("a string")
            val INTEGER = Kind("an integer")
            val NUMBER = Kind("a number")
            val BOOLEAN = Kind("a boolean")
            val NULL = Kind("null")
            val NONE = Kind("none")
        }
    }

    private companion object {
        const val RANGE = "Out of the range of its type."

        val INTEGER = setOf(Kind.INTEGER)
        val NUMBER = setOf(Kind.NUMBER, Kind.INTEGER)
        val STRING = setOf(Kind.STRING)

        /**
         * The kinds each scalar class Wow's JSON describes reads, the first the one Wow writes; the others, forms
         * written before 9.0, or a month's number.
         */
        val KINDS: Map<Class<*>, Set<Kind>> = buildMap {
            put(Boolean::class.javaPrimitiveType!!, setOf(Kind.BOOLEAN))
            put(Boolean::class.javaObjectType, setOf(Kind.BOOLEAN))
            listOf(Byte::class, Short::class, Int::class, Long::class).forEach {
                put(it.javaPrimitiveType!!, INTEGER)
                put(it.javaObjectType, INTEGER)
            }
            put(BigInteger::class.java, INTEGER)
            put(Year::class.java, INTEGER)
            listOf(Float::class, Double::class).forEach {
                put(it.javaPrimitiveType!!, NUMBER)
                put(it.javaObjectType, NUMBER)
            }
            put(BigDecimal::class.java, NUMBER)
            listOf(
                String::class.java,
                Char::class.javaPrimitiveType!!,
                Char::class.javaObjectType,
                ByteArray::class.java,
                Period::class.java,
                MonthDay::class.java,
                ZoneId::class.java,
                ZoneOffset::class.java,
                TimeZone::class.java,
                Locale::class.java,
                Currency::class.java,
                UUID::class.java,
                URI::class.java,
                URL::class.java,
                kotlin.time.Duration::class.java,
            ).forEach { put(it, STRING) }
            put(Month::class.java, setOf(Kind.STRING, Kind.INTEGER))
            listOf(Instant::class.java, OffsetDateTime::class.java, ZonedDateTime::class.java, Duration::class.java)
                .forEach { put(it, setOf(Kind.STRING, Kind.NUMBER, Kind.INTEGER)) }
            listOf(Date::class.java, Calendar::class.java).forEach { put(it, setOf(Kind.STRING, Kind.INTEGER)) }
            listOf(
                LocalDateTime::class.java,
                LocalDate::class.java,
                YearMonth::class.java,
                LocalTime::class.java,
                OffsetTime::class.java,
            ).forEach { put(it, setOf(Kind.STRING, Kind.ARRAY)) }
        }

        val LOCATED_NUMBERS: SimpleModule = SimpleModule("wow-located-numbers").setDeserializerModifier(
            object : ValueDeserializerModifier() {
                override fun modifyDeserializer(
                    config: DeserializationConfig,
                    beanDesc: BeanDescription.Supplier,
                    deserializer: ValueDeserializer<*>,
                ): ValueDeserializer<*> = if (KINDS[beanDesc.beanClass] == INTEGER) {
                    Located(deserializer, beanDesc.beanClass)
                } else {
                    deserializer
                }
            },
        )

        fun kindOf(node: JsonNode): Kind = when {
            node.isMissingNode -> Kind.NONE
            node.isNull -> Kind.NULL
            node.isObject -> Kind.OBJECT
            node.isArray -> Kind.ARRAY
            node.isString -> Kind.STRING
            node.isIntegralNumber -> Kind.INTEGER
            node.isNumber -> Kind.NUMBER
            // What parsed JSON holds besides: a tree read from text holds no binary or POJO node.
            else -> Kind.BOOLEAN
        }

        fun pathOf(failure: DatabindException): String = buildString {
            failure.path.forEach { reference ->
                val name = reference.propertyName
                if (name != null) append(if (isEmpty()) name else ".$name") else append("[${reference.index}]")
            }
        }

        fun nodeAt(json: JsonNode, failure: DatabindException): JsonNode =
            failure.path.fold(json) { node, reference ->
                val name = reference.propertyName
                (if (name != null) node.get(name) else node.get(reference.index)) ?: return MissingNode.getInstance()
            }

        /** The range of an integer [type], boxed or not; an `int`'s for a `Year`, which Jackson reads as one. */
        fun rangeOf(type: Class<*>): String = when (type.kotlin.javaPrimitiveType ?: type) {
            Byte::class.javaPrimitiveType -> "Out of range: -128 to 127."
            Short::class.javaPrimitiveType -> "Out of range: -32768 to 32767."
            Long::class.javaPrimitiveType -> "Out of range: -9223372036854775808 to 9223372036854775807."
            else -> "Out of range: -2147483648 to 2147483647."
        }
    }

    /** An integer's reader that reports an out-of-range number as [OutOfRange], which a bean prefixes with its path. */
    private class Located(delegate: ValueDeserializer<*>, private val type: Class<*>) :
        DelegatingDeserializer(delegate) {
        override fun newDelegatingInstance(newDelegatee: ValueDeserializer<*>): ValueDeserializer<*> =
            Located(newDelegatee, type)

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any? = try {
            super.deserialize(p, ctxt)
        } catch (outOfRange: InputCoercionException) {
            throw OutOfRange(p, outOfRange.originalMessage, null, type)
        }
    }
}
