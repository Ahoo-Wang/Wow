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

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.json.JsonReadFailure.Reason
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.URL
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.Year
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What Wow's JSON reads beyond what it writes, and what it refuses with the reason a client is given ([StrictReads],
 * [JsonReadFailures]): one row per input, beside the JSON values reference that states each.
 */
class StrictReadsTest {
    private sealed interface Expected

    /** Reads, and is written back as [written]; [since] names the releases that wrote the input, when Wow did. */
    private data class Reads(val written: String, val since: String? = null) : Expected

    private data class Refuses(val reason: Reason, val message: String) : Expected

    private data class Row(val type: Class<*>, val json: String, val expected: Expected)

    private val rows = buildList {
        fun reads(type: Class<*>, json: String, written: String = json, since: String? = null) =
            add(Row(type, json, Reads(written, since)))

        fun refuses(type: Class<*>, json: String, reason: Reason, message: String) =
            add(Row(type, json, Refuses(reason, message)))

        fun wrongKind(type: Class<*>, json: String, expected: String, given: String) =
            refuses(type, json, Reason.WRONG_KIND, "Expected $expected, given $given.")

        fun invalid(type: Class<*>, json: String) =
            refuses(type, json, Reason.INVALID_FORMAT, "Not a valid value of its format.")

        val int = Int::class.javaPrimitiveType!!
        val short = Short::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        val byte = Byte::class.javaPrimitiveType!!
        val double = Double::class.javaPrimitiveType!!
        val float = Float::class.javaPrimitiveType!!
        val boolean = Boolean::class.javaPrimitiveType!!
        val char = Char::class.javaPrimitiveType!!

        // Integers: a fraction is never truncated, a string never taken for a number, a byte never wrapped.
        listOf("1.5", "1e2", "1.0").forEach { wrongKind(int, it, "an integer", "a number") }
        wrongKind(Integer::class.java, "\"7\"", "an integer", "a string")
        wrongKind(long, "\"7\"", "an integer", "a string")
        wrongKind(BigInteger::class.java, "1.5", "an integer", "a number")
        refuses(int, "2147483648", Reason.OUT_OF_RANGE, "Out of range: -2147483648 to 2147483647.")
        refuses(short, "40000", Reason.OUT_OF_RANGE, "Out of range: -32768 to 32767.")
        val longs = "Out of range: -9223372036854775808 to 9223372036854775807."
        refuses(long, "9223372036854775808", Reason.OUT_OF_RANGE, longs)
        refuses(byte, "99999999999999999999", Reason.OUT_OF_RANGE, "Out of range: -128 to 127.")
        wrongKind(byte, "\"1\"", "an integer", "a string")
        refuses(Year::class.java, "3000000000", Reason.OUT_OF_RANGE, "Out of range: -2147483648 to 2147483647.")
        refuses(byte, "200", Reason.OUT_OF_RANGE, "Out of range: -128 to 127.")
        refuses(java.lang.Byte::class.java, "-129", Reason.OUT_OF_RANGE, "Out of range: -128 to 127.")
        reads(byte, "127")
        reads(byte, "-128")
        reads(Year::class.java, "2026")
        wrongKind(Year::class.java, "\"2026\"", "an integer", "a string")
        // Booleans and text: no value of another kind.
        wrongKind(boolean, "1", "a boolean", "an integer")
        wrongKind(java.lang.Boolean::class.java, "\"true\"", "a boolean", "a string")
        wrongKind(String::class.java, "1", "a string", "an integer")
        wrongKind(String::class.java, "1.5", "a string", "a number")
        wrongKind(String::class.java, "true", "a string", "a boolean")
        wrongKind(char, "65", "a string", "an integer")
        invalid(Character::class.java, "\"\"")
        // Floats: no string but the three Wow writes; a number beyond the range reads as IEEE 754 rounds it.
        wrongKind(double, "\"1.5\"", "a number", "a string")
        wrongKind(BigDecimal::class.java, "\"2.50\"", "a number", "a string")
        reads(double, "\"NaN\"")
        reads(float, "\"-Infinity\"")
        reads(double, "1e400", "\"Infinity\"")
        reads(float, "1e39", "\"Infinity\"")
        // Instants and durations: text, and the decimal of epoch seconds Wow 1.x to 6.x wrote.
        val old = "1.x to 6.x"
        reads(Instant::class.java, "1767323045.123456789", "\"2026-01-02T03:04:05.123456789Z\"", old)
        reads(Instant::class.java, "1767323045.000000000", "\"2026-01-02T03:04:05Z\"", old)
        // An integer is epoch seconds too, which no release wrote with its default configuration.
        reads(Instant::class.java, "1767323045", "\"2026-01-02T03:04:05Z\"")
        wrongKind(Instant::class.java, "[2026,1,2]", "a string", "an array")
        invalid(Instant::class.java, "\"2026-01-02\"")
        reads(OffsetDateTime::class.java, "1767294245.500000000", "\"2026-01-01T19:04:05.5Z\"", old)
        wrongKind(OffsetDateTime::class.java, "[2026]", "a string", "an array")
        reads(ZonedDateTime::class.java, "1767294245.000000000", "\"2026-01-01T19:04:05Z\"", old)
        wrongKind(ZonedDateTime::class.java, "[2026]", "a string", "an array")
        reads(Duration::class.java, "5400.000000000", "\"PT1H30M\"", old)
        reads(Duration::class.java, "1.500000000", "\"PT1.5S\"", old)
        reads(Duration::class.java, "5400", "\"PT1H30M\"")
        wrongKind(Duration::class.java, "[1]", "a string", "an array")
        // Dates of java.util: text, and the epoch milliseconds Wow 1.x to 6.x wrote; never a date alone.
        reads(Date::class.java, "1767323045123", "\"2026-01-02T03:04:05.123Z\"", old)
        wrongKind(Date::class.java, "1767323045.5", "a string", "a number")
        wrongKind(Date::class.java, "[1]", "a string", "an array")
        invalid(Date::class.java, "\"2026-01-02\"")
        reads(Calendar::class.java, "1767323045123", "\"2026-01-02T03:04:05.123Z\"", old)
        wrongKind(Calendar::class.java, "1767323045.5", "a string", "a number")
        wrongKind(Calendar::class.java, "[1]", "a string", "an array")
        invalid(Calendar::class.java, "\"2026-01-02\"")
        // Local dates and times: text, and the arrays Wow 1.x to 6.x wrote; no number, nothing dropped.
        reads(LocalDate::class.java, "[2026,1,2]", "\"2026-01-02\"", old)
        wrongKind(LocalDate::class.java, "20455", "a string", "an integer")
        invalid(LocalDate::class.java, "\"2026-01-02T03:04:05\"")
        invalid(LocalDate::class.java, "\"\"")
        reads(LocalDateTime::class.java, "[2026,1,2,3,4,5,123000000]", "\"2026-01-02T03:04:05.123\"", old)
        reads(LocalDateTime::class.java, "[2026,1,2,3,4]", "\"2026-01-02T03:04:00\"", old)
        wrongKind(LocalDateTime::class.java, "1767323045", "a string", "an integer")
        invalid(LocalDateTime::class.java, "\"2026-01-02T03:04:05Z\"")
        reads(YearMonth::class.java, "[2026,1]", "\"2026-01\"", old)
        wrongKind(YearMonth::class.java, "202601", "a string", "an integer")
        reads(LocalTime::class.java, "[3,4,5,123000000]", "\"03:04:05.123\"", old)
        wrongKind(LocalTime::class.java, "3600", "a string", "an integer")
        reads(OffsetTime::class.java, "[3,4,5,\"+08:00\"]", "\"03:04:05+08:00\"", old)
        wrongKind(OffsetTime::class.java, "3600", "a string", "an integer")
        wrongKind(Period::class.java, "[1]", "a string", "an array")
        // Empty text is no value; a locale's is its root.
        invalid(URL::class.java, "\"\"")
        invalid(Currency::class.java, "\"\"")
        reads(Locale::class.java, "\"\"")
        // A time zone is a ZoneId's.
        invalid(TimeZone::class.java, "\"Nowhere/City\"")
        reads(TimeZone::class.java, "\"+08:00\"", "\"GMT+08:00\"")
        reads(TimeZone::class.java, "\"Asia/Shanghai\"")
        reads(TimeZone::class.java, "\"PST\"")
        wrongKind(TimeZone::class.java, "1", "a string", "an integer")
        // Bytes are Base64 text.
        wrongKind(ByteArray::class.java, "[1,2,3]", "a string", "an array")
        reads(ByteArray::class.java, "\"AQID\"")
        reads(IntArray::class.java, "[1,2]")
        val enumeration = "Not one of the values of its enumeration."
        refuses(DayOfWeek::class.java, "\"FUNDAY\"", Reason.NOT_IN_ENUMERATION, enumeration)
    }

    private val failures = JsonReadFailures(WowJson.mapper)

    @Test
    fun `each input should read as its row says, or be refused with its reason`() {
        rows.forEach { row ->
            val described = "${row.type.simpleName} ${row.json}"
            when (val expected = row.expected) {
                is Reads -> {
                    val value = WowJson.mapper.readValue(row.json, row.type)
                    WowJson.mapper.writeValueAsString(value).assert().describedAs(described)
                        .isEqualTo(expected.written)
                }

                is Refuses -> {
                    val failure = runCatching { WowJson.mapper.readValue(row.json, row.type) }.exceptionOrNull()
                    failure.assert().describedAs(described).isInstanceOf(JacksonException::class.java)
                    val tree: JsonNode = WowJson.mapper.readTree(row.json)
                    val read = failures.of(tree, row.type, failure as JacksonException)!!
                    read.path.assert().describedAs(described).isEmpty()
                    read.reason.assert().describedAs(described).isEqualTo(expected.reason)
                    read.message.assert().describedAs(described).isEqualTo(expected.message)
                }
            }
        }
    }

    @Test
    fun `what Wow writes of each class with a refusal should read back`() {
        val values: List<Pair<Class<*>, Any>> = listOf(
            Int::class.java to 1,
            Long::class.java to 1L,
            Byte::class.java to 127.toByte(),
            Year::class.java to Year.of(2026),
            Boolean::class.java to true,
            String::class.java to "text",
            Char::class.java to 'c',
            Double::class.java to 1.5,
            BigDecimal::class.java to BigDecimal("2.50"),
            BigInteger::class.java to BigInteger.TEN,
            Instant::class.java to Instant.parse("2026-01-02T03:04:05Z"),
            Date::class.java to Date(0),
            LocalDate::class.java to LocalDate.of(2026, 1, 2),
            LocalDateTime::class.java to LocalDateTime.of(2026, 1, 2, 3, 4),
            URL::class.java to URI.create("https://example.com/a").toURL(),
            Currency::class.java to Currency.getInstance("CNY"),
            TimeZone::class.java to TimeZone.getTimeZone("Asia/Shanghai"),
            Double::class.java to Double.NaN,
            Double::class.java to Double.POSITIVE_INFINITY,
        )
        values.forEach { (type, value) ->
            val text = WowJson.mapper.writeValueAsString(value)
            val read = WowJson.mapper.readValue(text, type)
            WowJson.mapper.writeValueAsString(read).assert().describedAs(text).isEqualTo(text)
        }
        val bytes = WowJson.mapper.writeValueAsString(byteArrayOf(1, 2, 3))
        WowJson.mapper.readValue(bytes, ByteArray::class.java).assert().containsExactly(1, 2, 3)
    }

    data class Item(val sku: String, val quantity: Int)

    /** Written as its amount, so read from a value of its own. */
    class Money(@get:JsonValue val amount: String)

    class Sku
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    constructor(val value: Map<String, String>)

    data class Shelf(
        val anything: Any,
        val pair: Pair<String, Int>? = null,
        val money: Money? = null,
        val sku: Sku? = null,
        val ints: IntArray? = null,
        val counts: Map<String, Int> = emptyMap(),
        val item: Item? = null,
    )

    private fun shelfFailureOf(json: String): JsonReadFailure {
        val failure = runCatching { WowJson.mapper.readValue(json, Shelf::class.java) }.exceptionOrNull()
        return failures.of(WowJson.mapper.readTree(json), Shelf::class.java, failure as JacksonException)!!
    }

    @Test
    fun `a property of a class whose kinds are not known should be refused as not valid, or as missing`() {
        shelfFailureOf("{}").run {
            path.assert().isEqualTo("anything")
            message.assert().isEqualTo("Expected a value, given none.")
        }
        listOf("pair", "money").forEach { property ->
            shelfFailureOf("""{"anything":1,"$property":[1]}""").run {
                path.assert().describedAs(property).isEqualTo(property)
                reason.assert().describedAs(property).isEqualTo(Reason.INVALID_FORMAT)
            }
        }
        // A delegating creator's failure is its delegate's.
        shelfFailureOf("""{"anything":1,"sku":[1]}""").message.assert().isEqualTo("Expected an object, given an array.")
        shelfFailureOf("""{"anything":1,"ints":{}}""").message.assert()
            .isEqualTo("Expected an array, given an object.")
        shelfFailureOf("""{"anything":1,"counts":[]}""").message.assert()
            .isEqualTo("Expected an object, given an array.")
        shelfFailureOf("""{"anything":1,"item":"x"}""").message.assert()
            .isEqualTo("Expected an object, given a string.")
    }

    data class Order(val items: List<Item>, val tags: Map<String, Int> = emptyMap(), val at: Instant? = null)

    private fun failureOf(json: String): JsonReadFailure {
        val failure = runCatching { WowJson.mapper.readValue(json, Order::class.java) }.exceptionOrNull()
        return failures.of(WowJson.mapper.readTree(json), Order::class.java, failure as JacksonException)!!
    }

    @Test
    fun `a refusal should name the property by its path in the JSON`() {
        failureOf("""{"items":[{"sku":"a","quantity":1.5}]}""").path.assert().isEqualTo("items[0].quantity")
        failureOf("""{"items":[{"sku":"a","quantity":1},{"sku":"b","quantity":3000000000}]}""").run {
            path.assert().isEqualTo("items[1].quantity")
            reason.assert().isEqualTo(Reason.OUT_OF_RANGE)
        }
        failureOf("""{"items":[],"tags":{"a":"x"}}""").path.assert().isEqualTo("tags.a")
        failureOf("""{"items":[],"at":[2026]}""").run {
            path.assert().isEqualTo("at")
            message.assert().isEqualTo("Expected a string, given an array.")
        }
        failureOf("""{"items":[{"quantity":1}]}""").run {
            path.assert().isEqualTo("items[0].sku")
            message.assert().isEqualTo("Expected a string, given none.")
        }
        failureOf("""{"items":[{"sku":null,"quantity":1}]}""").message.assert()
            .isEqualTo("Expected a string, given null.")
        failureOf("""{"items":{}}""").run {
            path.assert().isEqualTo("items")
            message.assert().isEqualTo("Expected an array, given an object.")
        }
        failureOf("[1]").run {
            path.assert().isEmpty()
            message.assert().isEqualTo("Expected an object, given an array.")
        }
    }

    @Test
    fun `a failure that is not about a value should be described as none`() {
        val syntax = runCatching { WowJson.mapper.readTree("{not json") }.exceptionOrNull() as JacksonException
        failures.of(WowJson.mapper.readTree("{}"), Order::class.java, syntax).assert().isNull()
    }
}
