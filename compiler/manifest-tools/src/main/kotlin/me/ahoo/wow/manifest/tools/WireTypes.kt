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

package me.ahoo.wow.manifest.tools

import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinitionProvider
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.impl.module.SimpleTypeModule

/**
 * How a schema names the JVM type of a value, in both directions: [SchemaIntrospector] writes a schema's `format` and
 * `x-wow-class` from a class, and [StateViewSources] reads the Kotlin type of a view's field back from them.
 *
 * - A platform value class written as a scalar has the JSON type and format [SCALARS] gives it.
 * - A `format` names a value a plain JSON type does not tell apart ([FORMATS]): a 32-bit or a 64-bit integer, a
 *   float, a big integer, an exact decimal, an instant, a date, a time with its offset, a duration, a UUID, a URI,
 *   Base64 bytes.
 *   Each format reads back as one type ([TYPES]): `date-time` as an `Instant`, say.
 * - `x-wow-class` ([CLASS_KEYWORD]) names the class, by its JVM binary name, whenever the JSON type and the format do not
 *   ([isNamed]): an application's class (an object, an enumeration, a polymorphic type) and a platform value class
 *   that is not the one its format reads back as (a `Short`, an `OffsetDateTime`, a `LocalDateTime`). A
 *   parameterized class is named with its arguments, as the definitions' keys are: `a.b.Page(a.b.Item)`.
 * - A `Set` is an array of `uniqueItems` ([UNIQUE_ITEMS]), read back as a `Set`.
 */
internal object WireTypes {
    const val CLASS_KEYWORD = me.ahoo.wow.manifest.CLASS_KEYWORD
    const val FORMAT = "format"
    const val UNIQUE_ITEMS = "uniqueItems"

    private const val INT32 = "int32"
    private const val INT64 = "int64"
    private const val FLOAT = "float"
    private const val BIGINT = "bigint"
    private const val DECIMAL = "decimal"
    private const val DATE_TIME = "date-time"
    private const val DATE = "date"
    private const val TIME = "time"
    private const val DURATION = "duration"
    private const val UUID = "uuid"
    private const val URI = "uri"
    private const val BYTE = "byte"

    /** A schema's JSON type of a scalar value. */
    enum class JsonType(val keyword: String) {
        STRING("string"),
        INTEGER("integer"),
        NUMBER("number"),
        BOOLEAN("boolean"),
    }

    /** How Wow's JSON writes a value of a platform class: a scalar of [type], told apart by its [format] if any. */
    data class Scalar(val type: JsonType, val format: String? = null)

    private val INT32_SCALAR = Scalar(JsonType.INTEGER, INT32)
    private val INT64_SCALAR = Scalar(JsonType.INTEGER, INT64)
    private val STRING_SCALAR = Scalar(JsonType.STRING)
    private val BOOLEAN_SCALAR = Scalar(JsonType.BOOLEAN)

    /**
     * The platform classes a state, a command or an event holds as a scalar, as Wow's JSON (`WowJson`) writes them,
     * with their schema's type and format: the one place both come from. [SchemaIntrospector] describes each by it
     * ([module]), ahead of the schema generator's own list, which describes some of them as objects (a `YearMonth` as
     * `{year, month}`, written `"2026-01"`), and a `Byte` as a string. A class matches by itself, not by a subclass.
     * `WireScalarsTest` writes a value of each with `WowJson` and compares, so an entry without a value there fails
     * it, and so does a class written otherwise. Every entry is part of the contract: another platform class written
     * as a scalar (a `Charset`, an `AtomicLong`, a `Path`) has no entry, and a property holding it is refused
     * ([SchemaOverrides]), naming it.
     */
    val SCALARS: Map<Class<*>, Scalar> = mapOf(
        Boolean::class.javaPrimitiveType!! to BOOLEAN_SCALAR,
        java.lang.Boolean::class.java to BOOLEAN_SCALAR,
        Int::class.javaPrimitiveType!! to INT32_SCALAR,
        java.lang.Integer::class.java to INT32_SCALAR,
        Short::class.javaPrimitiveType!! to INT32_SCALAR,
        java.lang.Short::class.java to INT32_SCALAR,
        Byte::class.javaPrimitiveType!! to INT32_SCALAR,
        java.lang.Byte::class.java to INT32_SCALAR,
        java.time.Year::class.java to INT32_SCALAR,
        Long::class.javaPrimitiveType!! to INT64_SCALAR,
        java.lang.Long::class.java to INT64_SCALAR,
        java.math.BigInteger::class.java to Scalar(JsonType.INTEGER, BIGINT),
        Float::class.javaPrimitiveType!! to Scalar(JsonType.NUMBER, FLOAT),
        java.lang.Float::class.java to Scalar(JsonType.NUMBER, FLOAT),
        Double::class.javaPrimitiveType!! to Scalar(JsonType.NUMBER),
        java.lang.Double::class.java to Scalar(JsonType.NUMBER),
        java.math.BigDecimal::class.java to Scalar(JsonType.NUMBER, DECIMAL),
        java.time.Instant::class.java to Scalar(JsonType.STRING, DATE_TIME),
        java.time.OffsetDateTime::class.java to Scalar(JsonType.STRING, DATE_TIME),
        java.time.ZonedDateTime::class.java to Scalar(JsonType.STRING, DATE_TIME),
        java.util.Date::class.java to Scalar(JsonType.STRING, DATE_TIME),
        java.util.Calendar::class.java to Scalar(JsonType.STRING, DATE_TIME),
        java.time.LocalDate::class.java to Scalar(JsonType.STRING, DATE),
        java.time.LocalTime::class.java to Scalar(JsonType.STRING, TIME),
        java.time.OffsetTime::class.java to Scalar(JsonType.STRING, TIME),
        java.time.Duration::class.java to Scalar(JsonType.STRING, DURATION),
        java.time.Period::class.java to Scalar(JsonType.STRING, DURATION),
        // As a `java.time.Duration` is, held as a class: nullable, an element, a map's value ([kotlinDurations]).
        kotlin.time.Duration::class.java to Scalar(JsonType.STRING, DURATION),
        java.util.UUID::class.java to Scalar(JsonType.STRING, UUID),
        java.net.URI::class.java to Scalar(JsonType.STRING, URI),
        java.net.URL::class.java to Scalar(JsonType.STRING, URI),
        // A byte array is written as its Base64 text.
        ByteArray::class.java to Scalar(JsonType.STRING, BYTE),
    ) + listOf(
        String::class.java,
        Char::class.javaPrimitiveType!!,
        java.lang.Character::class.java,
        java.time.LocalDateTime::class.java,
        java.time.YearMonth::class.java,
        java.time.MonthDay::class.java,
        java.time.ZoneId::class.java,
        java.time.ZoneOffset::class.java,
        java.util.TimeZone::class.java,
        java.util.Locale::class.java,
        java.util.Currency::class.java,
    ).associateWith { STRING_SCALAR }

    /** The format of each class whose values a plain JSON type does not tell apart, as [SCALARS] gives it. */
    val FORMATS: Map<Class<*>, String> = SCALARS.mapNotNull { (type, scalar) -> scalar.format?.let { type to it } }.toMap()

    /**
     * The schema of a property Kotlin declares as a `kotlin.time.Duration`, which the JVM holds as a `long`: the
     * ISO-8601 text Wow's JSON writes, as [SCALARS] describes a `Duration` it holds as a class. None for any other.
     */
    fun <M : MemberScope<*, *>> kotlinDurations(): CustomPropertyDefinitionProvider<M> =
        CustomPropertyDefinitionProvider { member, context ->
            val unboxed = member.type.erasedType.isPrimitive
            if (!unboxed || KotlinMembers.typeOf(member)?.classifier != kotlin.time.Duration::class) {
                null
            } else {
                val schema = context.generatorConfig.createObjectNode()
                    .put("type", JsonType.STRING.keyword)
                    .put(FORMAT, DURATION)
                    .put(CLASS_KEYWORD, kotlin.time.Duration::class.java.name)
                CustomPropertyDefinition(schema, CustomDefinition.AttributeInclusion.YES)
            }
        }

    /** The schema generator's description of each of [SCALARS], by its JSON type alone; its format is [FORMATS]'. */
    fun module(): SimpleTypeModule = SimpleTypeModule().also { module ->
        SCALARS.forEach { (type, scalar) ->
            when (scalar.type) {
                JsonType.STRING -> module.withStringType(type)
                JsonType.INTEGER -> module.withIntegerType(type)
                JsonType.NUMBER -> module.withNumberType(type)
                JsonType.BOOLEAN -> module.withBooleanType(type)
            }
        }
    }

    /**
     * The numeric formats as ranges, by the formats each holds every value of, itself included: an `int32` is within
     * an `int64`, both within a `bigint`, and every one of them and a `float` within a `decimal`. A number of no format
     * is any number of its type, so it holds them all.
     */
    private val WITHIN: Map<String, Set<String>> = mapOf(
        INT32 to setOf(INT32),
        INT64 to setOf(INT32, INT64),
        BIGINT to setOf(INT32, INT64, BIGINT),
        FLOAT to setOf(FLOAT),
        DECIMAL to setOf(INT32, INT64, BIGINT, FLOAT, DECIMAL),
    )

    /** Whether [format] is a numeric format: a range of the numbers of its type ([holds]). */
    fun isNumeric(format: Any?): Boolean = format in WITHIN

    /** Whether every value of the numeric format [narrower] is one of [wider]; a null format is any number. */
    fun holds(wider: String?, narrower: String?): Boolean =
        wider == null || narrower != null && narrower in WITHIN.getValue(wider)

    /** The Kotlin type each format reads back as, by its qualified name, as generated code names it. */
    val TYPES: Map<String, String> = mapOf(
        INT32 to "kotlin.Int",
        INT64 to "kotlin.Long",
        FLOAT to "kotlin.Float",
        BIGINT to "java.math.BigInteger",
        DECIMAL to "java.math.BigDecimal",
        DATE_TIME to "java.time.Instant",
        DATE to "java.time.LocalDate",
        TIME to "java.time.OffsetTime",
        DURATION to "java.time.Duration",
        UUID to "java.util.UUID",
        URI to "java.net.URI",
        BYTE to "kotlin.ByteArray",
    )

    /** The Kotlin types of the JVM's boxed classes, as `x-wow-class` names them, by their qualified names. */
    val KOTLIN_TYPES: Map<String, String> = mapOf(
        "java.lang.String" to "kotlin.String",
        "java.lang.Boolean" to "kotlin.Boolean",
        "java.lang.Integer" to "kotlin.Int",
        "java.lang.Short" to "kotlin.Short",
        "java.lang.Byte" to "kotlin.Byte",
        "java.lang.Long" to "kotlin.Long",
        "java.lang.Float" to "kotlin.Float",
        "java.lang.Double" to "kotlin.Double",
        "java.lang.Character" to "kotlin.Char",
    )

    /** The classes a plain JSON type reads back as: a string, a boolean, a number. */
    private val PLAIN: Set<Class<*>> = setOf(
        String::class.java,
        Boolean::class.javaPrimitiveType!!,
        java.lang.Boolean::class.java,
        Double::class.javaPrimitiveType!!,
        java.lang.Double::class.java,
        Any::class.java,
        Number::class.java,
    )

    private val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")

    /** The classes each format reads back as, as the JVM names them. */
    private val READ_BACK: Set<Class<*>> = setOf(
        Int::class.javaPrimitiveType!!,
        java.lang.Integer::class.java,
        Long::class.javaPrimitiveType!!,
        java.lang.Long::class.java,
        Float::class.javaPrimitiveType!!,
        java.lang.Float::class.java,
        java.math.BigInteger::class.java,
        java.math.BigDecimal::class.java,
        java.time.Instant::class.java,
        java.time.LocalDate::class.java,
        java.time.OffsetTime::class.java,
        java.time.Duration::class.java,
        java.util.UUID::class.java,
        java.net.URI::class.java,
    )

    /** Whether [type] is the platform's (Java's or Kotlin's) rather than an application's. */
    fun isPlatform(type: Class<*>): Boolean = type.isPrimitive || PLATFORM_PACKAGES.any { type.name.startsWith(it) }

    /**
     * Whether a schema names [type] by its class: an application's class, and a platform value class that its JSON
     * type and format do not read back as. Arrays, collections and maps are named by their items.
     */
    fun isNamed(type: Class<*>): Boolean = when {
        type.isArray || Collection::class.java.isAssignableFrom(type) || Map::class.java.isAssignableFrom(type) -> false
        !isPlatform(type) -> true
        else -> type !in PLAIN && type !in READ_BACK
    }

    /** The binary name `x-wow-class` gives a value of [type]: a primitive's boxed class. */
    fun binaryName(type: Class<*>): String = (if (type.isPrimitive) BOXED.getValue(type) else type).name

    private val BOXED: Map<Class<*>, Class<*>> = mapOf(
        Short::class.javaPrimitiveType!! to java.lang.Short::class.java,
        Byte::class.javaPrimitiveType!! to java.lang.Byte::class.java,
        Char::class.javaPrimitiveType!! to java.lang.Character::class.java,
    )
}
