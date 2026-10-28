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

/**
 * How a schema names the JVM type of a value, in both directions: [SchemaIntrospector] writes a schema's `format` and
 * `x-wow-class` from a class, and [StateViewSources] reads the Kotlin type of a view's field back from them.
 *
 * - A `format` names a value a plain JSON type does not tell apart ([FORMATS]): a 32-bit or a 64-bit integer, a
 *   float, a big integer, an exact decimal, an instant, a date, a time with its offset, a duration, a UUID, a URI.
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

    /** The format of each class whose values a plain JSON type does not tell apart. */
    val FORMATS: Map<Class<*>, String> = mapOf(
        Int::class.javaPrimitiveType!! to INT32,
        java.lang.Integer::class.java to INT32,
        Short::class.javaPrimitiveType!! to INT32,
        java.lang.Short::class.java to INT32,
        Byte::class.javaPrimitiveType!! to INT32,
        java.lang.Byte::class.java to INT32,
        Long::class.javaPrimitiveType!! to INT64,
        java.lang.Long::class.java to INT64,
        Float::class.javaPrimitiveType!! to FLOAT,
        java.lang.Float::class.java to FLOAT,
        java.math.BigInteger::class.java to BIGINT,
        java.math.BigDecimal::class.java to DECIMAL,
        java.time.Instant::class.java to DATE_TIME,
        java.time.OffsetDateTime::class.java to DATE_TIME,
        java.time.ZonedDateTime::class.java to DATE_TIME,
        java.time.LocalDate::class.java to DATE,
        java.time.OffsetTime::class.java to TIME,
        java.time.Duration::class.java to DURATION,
        java.time.Period::class.java to DURATION,
        java.util.UUID::class.java to UUID,
        java.net.URI::class.java to URI,
    )

    /** The Kotlin type each format reads back as. */
    val TYPES: Map<String, String> = mapOf(
        INT32 to "Int",
        INT64 to "Long",
        FLOAT to "Float",
        BIGINT to "java.math.BigInteger",
        DECIMAL to "java.math.BigDecimal",
        DATE_TIME to "java.time.Instant",
        DATE to "java.time.LocalDate",
        TIME to "java.time.OffsetTime",
        DURATION to "java.time.Duration",
        UUID to "java.util.UUID",
        URI to "java.net.URI",
    )

    /** The Kotlin types of the JVM's boxed classes, as `x-wow-class` names them. */
    val KOTLIN_TYPES: Map<String, String> = mapOf(
        "java.lang.String" to "String",
        "java.lang.Boolean" to "Boolean",
        "java.lang.Integer" to "Int",
        "java.lang.Short" to "Short",
        "java.lang.Byte" to "Byte",
        "java.lang.Long" to "Long",
        "java.lang.Float" to "Float",
        "java.lang.Double" to "Double",
        "java.lang.Character" to "Char",
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
