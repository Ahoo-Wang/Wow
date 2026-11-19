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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JavaType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor
import tools.jackson.module.kotlin.KotlinModule
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.DayOfWeek
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The schema of each platform value class is what Wow's JSON writes for it ([WireTypes.SCALARS]): a value of each, in
 * a state, is written with `WowJson` and its JSON type compared with its schema's.
 */
class WireScalarsTest {
    /** A state holding a value of every class of [WireTypes.SCALARS], and of the platform's other value classes. */
    @Suppress("LongParameterList")
    data class Values(
        val boolean: Boolean = true,
        val boxedBoolean: Boolean? = true,
        val int: Int = 1,
        val boxedInt: Int? = 1,
        val short: Short = 1,
        val boxedShort: Short? = 1,
        val byte: Byte = 1,
        val boxedByte: Byte? = 1,
        val year: Year = Year.of(2026),
        val long: Long = 1,
        val boxedLong: Long? = 1,
        val bigInteger: BigInteger = BigInteger.TEN,
        val float: Float = 1.5f,
        val boxedFloat: Float? = 1.5f,
        val double: Double = 1.5,
        val boxedDouble: Double? = 1.5,
        val bigDecimal: BigDecimal = BigDecimal("2.50"),
        val instant: Instant = Instant.parse("2026-01-02T03:04:05Z"),
        val offsetDateTime: OffsetDateTime = OffsetDateTime.parse("2026-01-02T03:04:05+08:00"),
        val zonedDateTime: ZonedDateTime = ZonedDateTime.parse("2026-01-02T03:04:05+08:00[Asia/Shanghai]"),
        val date: Date = Date(0),
        val calendar: Calendar = Calendar.getInstance().apply { timeInMillis = 0 },
        val localDate: LocalDate = LocalDate.parse("2026-01-02"),
        val localTime: LocalTime = LocalTime.parse("03:04:05"),
        val offsetTime: OffsetTime = OffsetTime.parse("03:04:05+08:00"),
        val duration: Duration = Duration.ofMinutes(90),
        val period: Period = Period.of(1, 2, 3),
        // A Kotlin duration the JVM holds as a long, and one it holds as the class.
        val kotlinDuration: kotlin.time.Duration = 90.minutes,
        val boxedKotlinDuration: kotlin.time.Duration? = 90.minutes,
        val uuid: UUID = UUID(1, 2),
        val uri: URI = URI("https://example.com/a"),
        val url: URL = URI("https://example.com/a").toURL(),
        val bytes: ByteArray = byteArrayOf(1, 2, 3),
        val string: String = "a",
        val char: Char = 'c',
        val boxedChar: Char? = 'c',
        val localDateTime: LocalDateTime = LocalDateTime.parse("2026-01-02T03:04:05"),
        val yearMonth: YearMonth = YearMonth.of(2026, 1),
        val monthDay: MonthDay = MonthDay.of(1, 2),
        val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
        val zoneOffset: ZoneOffset = ZoneOffset.ofHours(8),
        val timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
        val locale: Locale = Locale.CHINA,
        val currency: Currency = Currency.getInstance("CNY"),
        // Not scalars of the table: enumerations, which Wow's JSON writes by their names, as their schemas say.
        val dayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        val month: Month = Month.JANUARY,
    )

    // Read back from the text, as a reader sees it: a tree of the value holds a byte array as binary, not text.
    private val written: JsonNode = WowJson.mapper.readTree(WowJson.mapper.writeValueAsString(Values()))

    private val introspector = SchemaIntrospector(javaClass.classLoader)

    private val properties: Map<*, *> = introspector.complete(QueryKeywordsTest.fragment(Values::class.java))
        .defs.getValue(Values::class.java.name).let { (it as Map<*, *>)["properties"] as Map<*, *> }

    @Suppress("UNCHECKED_CAST")
    private fun schema(name: String): Map<String, Any?> = properties[name] as Map<String, Any?>

    /** The JSON types of [schema], `null` aside: a nullable property's schema also admits it. */
    private fun types(schema: Map<String, Any?>): List<Any?> = when (val type = schema["type"]) {
        is List<*> -> type - "null"
        else -> listOf(type)
    }

    private fun typeOf(node: JsonNode): String = when {
        node.isString -> "string"
        node.isIntegralNumber -> "integer"
        node.isNumber -> "number"
        node.isBoolean -> "boolean"
        else -> node.nodeType.name
    }

    @Test
    fun `a YearMonth should be the string Wow's JSON writes, not an object of its fields`() {
        written["yearMonth"].asString().assert().isEqualTo("2026-01")
        schema("yearMonth").assert()
            .containsEntry("type", "string")
            .containsEntry(WireTypes.CLASS_KEYWORD, YearMonth::class.java.name)
            .doesNotContainKey("properties")
    }

    @Test
    fun `every scalar class should have a value here, so a class added to the table is checked`() {
        val classes = Values::class.declaredMemberProperties.mapNotNull { it.javaField?.type }.toSet()
        WireTypes.SCALARS.keys.filterNot(classes::contains).map { it.name }.assert().isEmpty()
    }

    @Test
    fun `each value's schema should be of the JSON type and format Wow's JSON writes it as`() {
        // A Kotlin duration the JVM holds as a long is described as the class Kotlin declares.
        val fields = Values::class.declaredMemberProperties.associate { property ->
            val declared = (property.returnType.classifier as KClass<*>).java
            property.name to if (declared == kotlin.time.Duration::class.java) declared else property.javaField!!.type
        }
        val disagreements = fields.mapNotNull { (name, type) ->
            val schema = schema(name)
            val wire = typeOf(written.getValue(name))
            val expected = WireTypes.SCALARS[type]
            when {
                types(schema) != listOf(wire) -> "$name (${type.name}): written as $wire, described as ${schema["type"]}"
                "properties" in schema -> "$name (${type.name}): a scalar described with properties"
                expected != null && expected.type.keyword != wire ->
                    "$name (${type.name}): written as $wire, listed as ${expected.type.keyword}"
                expected != null && schema[WireTypes.FORMAT] != expected.format ->
                    "$name (${type.name}): of the format ${schema[WireTypes.FORMAT]}, listed as ${expected.format}"
                else -> null
            }
        }
        disagreements.assert().isEmpty()
    }

    data class HoldsCharset(val value: Charset = Charsets.UTF_8)
    data class HoldsPattern(val value: Pattern = Pattern.compile("a+"))
    data class HoldsPath(val value: Path = Path.of("/tmp/a"))
    data class HoldsFile(val value: File = File("/tmp/a"))
    data class HoldsInetAddress(val value: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    data class HoldsClass(val value: Class<*> = String::class.java)
    data class HoldsStringBuilder(val value: StringBuilder = StringBuilder("a"))
    data class HoldsChars(val value: CharArray = charArrayOf('a'))
    data class HoldsAtomicBoolean(val value: AtomicBoolean = AtomicBoolean(true))
    data class HoldsAtomicInteger(val value: AtomicInteger = AtomicInteger(1))
    data class HoldsAtomicLong(val value: AtomicLong = AtomicLong(1))
    data class HoldsDurations(
        val list: List<kotlin.time.Duration> = listOf(1.hours),
        val map: Map<String, kotlin.time.Duration> = mapOf("a" to 3.seconds),
    )

    @JvmInline
    value class Timeout(val value: kotlin.time.Duration)

    data class HoldsTimeout(val value: Timeout = Timeout(5.seconds))
    data class HoldsCharsets(val value: Map<String, Charset> = emptyMap())

    @Test
    fun `a platform class written as no object and no scalar of the table should be refused, naming the property`() {
        mapOf(
            HoldsCharset::class.java to Charset::class.java,
            HoldsPattern::class.java to Pattern::class.java,
            HoldsPath::class.java to Path::class.java,
            HoldsFile::class.java to File::class.java,
            HoldsInetAddress::class.java to InetAddress::class.java,
            HoldsClass::class.java to Class::class.java,
            HoldsStringBuilder::class.java to StringBuilder::class.java,
            HoldsChars::class.java to CharArray::class.java,
            HoldsAtomicBoolean::class.java to AtomicBoolean::class.java,
            HoldsAtomicInteger::class.java to AtomicInteger::class.java,
            HoldsAtomicLong::class.java to AtomicLong::class.java,
            HoldsCharsets::class.java to Charset::class.java,
        ).forEach { (state, held) ->
            val key = "${state.name}#value"
            assertThrows<IllegalStateException>(state.simpleName) {
                introspector.complete(QueryKeywordsTest.fragment(state))
            }.message.assert().describedAs(state.simpleName).isEqualTo(
                "Wow's JSON does not write ${held.name}, which $key holds, as an object, and it is no scalar " +
                    "Wow describes, so its schema would not be what is written: declare it in wow " +
                    "{ schemaOverrides.put(\"$key\", \"<JSON Schema>\") }, or hold it as a String.",
            )
        }
    }

    @Test
    fun `a month should be written by its name, as its schema's enumeration says`() {
        written["month"].asString().assert().isEqualTo("JANUARY")
        schema("month")["enum"].assert().isEqualTo(Month.entries.map { it.name })
    }

    @Test
    fun `a Kotlin duration should be ISO-8601 text, held as a long, a class, an element or a map's value`() {
        written["kotlinDuration"].asString().assert().isEqualTo("PT1H30M")
        written["boxedKotlinDuration"].asString().assert().isEqualTo("PT1H30M")
        val durations = WowJson.mapper.readTree(WowJson.mapper.writeValueAsString(HoldsDurations()))
        durations["list"][0].asString().assert().isEqualTo("PT1H")
        durations["map"]["a"].asString().assert().isEqualTo("PT3S")
        val held = introspector.complete(QueryKeywordsTest.fragment(HoldsDurations::class.java))
            .defs.getValue(HoldsDurations::class.java.name).let { (it as Map<*, *>)["properties"] as Map<*, *> }
        val list = (held["list"] as Map<*, *>)["items"] as Map<*, *>
        val map = (held["map"] as Map<*, *>)["additionalProperties"] as Map<*, *>
        listOf(schema("kotlinDuration"), schema("boxedKotlinDuration"), list, map).forEach { duration ->
            val type = duration["type"]
            (if (type is List<*>) type - "null" else listOf(type)).assert().isEqualTo(listOf("string"))
            duration[WireTypes.FORMAT].assert().isEqualTo("duration")
            duration[WireTypes.CLASS_KEYWORD].assert().isEqualTo(kotlin.time.Duration::class.java.name)
        }
    }

    @Test
    fun `a value class over a Kotlin duration should be refused, naming the property`() {
        val key = "${HoldsTimeout::class.java.name}#value"
        val fragment = QueryKeywordsTest.fragment(HoldsTimeout::class.java)
        assertThrows<IllegalStateException> { introspector.complete(fragment) }
            .message.assert().isEqualTo(
                "Wow's JSON writes $key, a ${Timeout::class.java.name} over a kotlin.time.Duration, as Kotlin's " +
                    "internal encoding of the duration, which no unit reads: declare it in wow " +
                    "{ schemaOverrides.put(\"$key\", \"<JSON Schema>\") }, or hold a kotlin.time.Duration.",
            )
    }

    @Test
    fun `a declared schema should stand for such a property, or for its class`() {
        val key = "${HoldsCharset::class.java.name}#value"
        val declared = """{"type":"string"}"""
        listOf(key, Charset::class.java.name).forEach { name ->
            val schema = SchemaIntrospector(javaClass.classLoader, mapOf(name to declared))
                .complete(QueryKeywordsTest.fragment(HoldsCharset::class.java))
                .defs.getValue(HoldsCharset::class.java.name).let { (it as Map<*, *>)["properties"] as Map<*, *> }
            (schema["value"] as Map<*, *>)["type"].assert().describedAs(name).isEqualTo("string")
        }
    }

    data class HoldsRef(val value: AggregateRef = AggregateType("ordering", "order").ref("tenant", "order-1"))

    @Test
    fun `an application class Wow writes with a serializer of its own should be described by its class`() {
        // Wow's JSON writes an AggregateRef by a serializer of its own, which reports no object to a format visitor;
        // the generator's mapper sees its class. Asked of Wow's JSON, the guard would refuse every state holding one.
        fun isObject(mapper: JsonMapper): Boolean {
            var isObject = false
            mapper.acceptJsonFormatVisitor(
                AggregateRef::class.java,
                object : JsonFormatVisitorWrapper.Base() {
                    override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor? {
                        isObject = true
                        return null
                    }
                },
            )
            return isObject
        }
        isObject(WowJson.mapper).assert().isFalse()
        isObject(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()).assert().isTrue()
        // The schema of its class is the shape the serializer writes.
        val defs = introspector.complete(QueryKeywordsTest.fragment(HoldsRef::class.java)).defs
        val value = ((defs.getValue(HoldsRef::class.java.name) as Map<*, *>)["properties"] as Map<*, *>)["value"]
        val ref = (value as Map<*, *>)["\$ref"]?.let { defs.getValue(Manifest.schemaKey(it as String)) } ?: value
        val wire = WowJson.mapper.readTree(WowJson.mapper.writeValueAsString(HoldsRef()))["value"]
        ((ref as Map<*, *>)["properties"] as Map<*, *>).keys.assert()
            .containsExactlyInAnyOrderElementsOf(wire.propertyNames())
    }

    private fun JsonNode.getValue(name: String): JsonNode = get(name) ?: error("Wow's JSON writes no $name")
}
