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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.json.JsonReadFailure.Reason
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
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
 * What Wow's JSON reads beyond what it writes, and what it refuses with the reason a client is given ([StrictReads],
 * [JsonReadFailures]): one row per input. The JSON values reference states no value that is not a row here
 * ([StrictReadsReferenceTest]).
 */
class StrictReadsTest {
    @Test
    fun `each input should read as its row says, or be refused with its reason`() {
        val wrong = StrictReadsTable.rows.mapNotNull { row -> StrictReadsTable.mismatchOf(row) }
        wrong.assert().describedAs(wrong.joinToString("\n")).isEmpty()
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
            MonthDay::class.java to MonthDay.of(1, 2),
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
}

/** The rows [StrictReadsTest] reads, and the reference page's test holds the page to. */
internal object StrictReadsTable {
    sealed interface Expected

    /** Reads, and is written back as [written]; [since] names the releases that wrote the input, when Wow did. */
    data class Reads(val written: String, val since: String? = null) : Expected

    data class Refuses(val reason: Reason, val message: String) : Expected

    data class Row(val type: Class<*>, val json: String, val expected: Expected)

    private val failures = JsonReadFailures(WowJson.mapper)

    /** What [row]'s input does when it is not what the row says, or null. */
    fun mismatchOf(row: Row): String? {
        val described = "${row.type.simpleName} ${row.json}"
        val read = runCatching { WowJson.mapper.readValue(row.json, row.type) }
        return when (val expected = row.expected) {
            is Reads -> read.fold(
                { value ->
                    val written = WowJson.mapper.writeValueAsString(value)
                    if (written == expected.written) null else "$described: written back as $written"
                },
                { "$described: refused, ${it.message?.lineSequence()?.first()}" },
            )

            is Refuses -> {
                val failure = read.exceptionOrNull() as? JacksonException
                    ?: return "$described: read, as ${read.getOrNull()}"
                val given = failures.of(WowJson.mapper.readTree(row.json), row.type, failure)
                    ?: return "$described: no failure of a value"
                val actual = Refuses(given.reason, given.message)
                if (given.path.isEmpty() && actual == expected) null else "$described: $actual at '${given.path}'"
            }
        }
    }

    val rows: List<Row> = buildList {
        fun reads(type: Class<*>, json: String, written: String = json, since: String? = null) =
            add(Row(type, json, Reads(written, since)))

        fun refuses(type: Class<*>, json: String, reason: Reason, message: String) =
            add(Row(type, json, Refuses(reason, message)))

        fun wrongKind(type: Class<*>, json: String, expected: String, given: String) =
            refuses(type, json, Reason.WRONG_KIND, "Expected $expected, given $given.")

        fun invalid(type: Class<*>, json: String) =
            refuses(type, json, Reason.INVALID_FORMAT, "Not a valid value of its format.")

        fun outOfRange(type: Class<*>, json: String, range: String) =
            refuses(type, json, Reason.OUT_OF_RANGE, "Out of range: $range.")

        val int = Int::class.javaPrimitiveType!!
        val short = Short::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        val byte = Byte::class.javaPrimitiveType!!
        val double = Double::class.javaPrimitiveType!!
        val float = Float::class.javaPrimitiveType!!
        val boolean = Boolean::class.javaPrimitiveType!!
        val char = Char::class.javaPrimitiveType!!
        val old = "1.x to 6.x"

        // Booleans: no number or string; no null for a primitive.
        wrongKind(boolean, "1", "a boolean", "an integer")
        wrongKind(java.lang.Boolean::class.java, "\"true\"", "a boolean", "a string")
        refuses(boolean, "null", Reason.WRONG_KIND, "A value is required, given null.")
        // Integers: a fraction is never truncated, a string never taken for a number, a byte never wrapped.
        listOf("1.5", "1e2", "1.0").forEach { wrongKind(int, it, "an integer", "a number") }
        wrongKind(Integer::class.java, "\"7\"", "an integer", "a string")
        outOfRange(int, "2147483648", "-2147483648 to 2147483647")
        wrongKind(short, "1.5", "an integer", "a number")
        outOfRange(short, "40000", "-32768 to 32767")
        outOfRange(byte, "200", "-128 to 127")
        outOfRange(java.lang.Byte::class.java, "-129", "-128 to 127")
        outOfRange(byte, "99999999999999999999", "-128 to 127")
        wrongKind(byte, "\"1\"", "an integer", "a string")
        reads(byte, "127")
        reads(byte, "-128")
        reads(Year::class.java, "2026")
        wrongKind(Year::class.java, "\"2026\"", "an integer", "a string")
        outOfRange(Year::class.java, "3000000000", "-2147483648 to 2147483647")
        wrongKind(long, "\"7\"", "an integer", "a string")
        outOfRange(long, "9223372036854775808", "-9223372036854775808 to 9223372036854775807")
        wrongKind(BigInteger::class.java, "1.5", "an integer", "a number")
        wrongKind(BigInteger::class.java, "1e3", "an integer", "a number")
        wrongKind(BigInteger::class.java, "\"12\"", "an integer", "a string")
        // Floats: no string but the three Wow writes; a number beyond the range reads as IEEE 754 rounds it.
        listOf(float, double).forEach { type ->
            listOf("\"NaN\"", "\"Infinity\"", "\"-Infinity\"").forEach { reads(type, it) }
            wrongKind(type, "\"1.5\"", "a number", "a string")
        }
        reads(float, "1e39", "\"Infinity\"")
        reads(double, "1e400", "\"Infinity\"")
        reads(BigDecimal::class.java, "1e400", "1E+400")
        wrongKind(BigDecimal::class.java, "\"2.50\"", "a number", "a string")
        // Instants and offset date-times: text, and the decimal of epoch seconds Wow 1.x to 6.x wrote.
        reads(Instant::class.java, "\"2026-01-02T03:04:05+08:00\"", "\"2026-01-01T19:04:05Z\"")
        reads(Instant::class.java, "\"2026-01-02t03:04:05z\"", "\"2026-01-02T03:04:05Z\"")
        reads(Instant::class.java, "1767323045.123456789", "\"2026-01-02T03:04:05.123456789Z\"", old)
        reads(Instant::class.java, "1767323045.000000000", "\"2026-01-02T03:04:05Z\"", old)
        // An integer is epoch seconds too, which no release wrote with its default configuration.
        reads(Instant::class.java, "1767323045", "\"2026-01-02T03:04:05Z\"")
        wrongKind(Instant::class.java, "[2026,1,2]", "a string", "an array")
        invalid(Instant::class.java, "\"2026-01-02T03:04:05\"")
        invalid(Instant::class.java, "\"2026-01-02\"")
        val offset = "\"2026-01-02T03:04:05+08:00\""
        reads(OffsetDateTime::class.java, offset, offset)
        reads(OffsetDateTime::class.java, "1767294245.5", "\"2026-01-01T19:04:05.5Z\"")
        reads(OffsetDateTime::class.java, "1767294245.500000000", "\"2026-01-01T19:04:05.5Z\"", old)
        wrongKind(OffsetDateTime::class.java, "[2026]", "a string", "an array")
        invalid(OffsetDateTime::class.java, "\"2026-01-02T03:04:05\"")
        reads(ZonedDateTime::class.java, "\"2026-01-02T03:04:05+08:00[Asia/Shanghai]\"", offset)
        reads(ZonedDateTime::class.java, "1767294245.000000000", "\"2026-01-01T19:04:05Z\"", old)
        wrongKind(ZonedDateTime::class.java, "[2026]", "a string", "an array")
        // Dates of java.util: text, and the epoch milliseconds Wow 1.x to 6.x wrote; never a date alone.
        val millis = "\"2026-01-02T03:04:05.123Z\""
        reads(Date::class.java, "\"2026-01-02T03:04:05.123+08:00\"", "\"2026-01-01T19:04:05.123Z\"")
        reads(Date::class.java, "1767323045123", millis, old)
        invalid(Date::class.java, "\"2026-01-02\"")
        wrongKind(Date::class.java, "1767323045.5", "a string", "a number")
        wrongKind(Date::class.java, "[1]", "a string", "an array")
        invalid(Date::class.java, "\"yesterday\"")
        reads(Calendar::class.java, "1767323045123", millis, old)
        invalid(Calendar::class.java, "\"2026-01-02\"")
        wrongKind(Calendar::class.java, "1767323045.5", "a string", "a number")
        wrongKind(Calendar::class.java, "[1]", "a string", "an array")
        // Local dates and times: text, and the arrays Wow 1.x to 6.x wrote; no number, nothing dropped.
        reads(LocalDate::class.java, "[2026,1,2]", "\"2026-01-02\"", old)
        invalid(LocalDate::class.java, "\"2026-01-02T03:04:05\"")
        wrongKind(LocalDate::class.java, "20455", "a string", "an integer")
        listOf("\"\"", "\"12026-01-01\"", "\"2026-1-2\"", "\"2026-02-30\"")
            .forEach { invalid(LocalDate::class.java, it) }
        reads(LocalTime::class.java, "[3,4,5,123000000]", "\"03:04:05.123\"", old)
        wrongKind(LocalTime::class.java, "3600", "a string", "an integer")
        invalid(LocalTime::class.java, "\"3:04\"")
        invalid(LocalTime::class.java, "\"03:04:05+08:00\"")
        reads(OffsetTime::class.java, "[3,4,5,\"+08:00\"]", "\"03:04:05+08:00\"", old)
        wrongKind(OffsetTime::class.java, "3600", "a string", "an integer")
        invalid(OffsetTime::class.java, "\"03:04:05\"")
        reads(LocalDateTime::class.java, "[2026,1,2,3,4,5,123000000]", "\"2026-01-02T03:04:05.123\"", old)
        reads(LocalDateTime::class.java, "[2026,1,2,3,4]", "\"2026-01-02T03:04:00\"", old)
        wrongKind(LocalDateTime::class.java, "1767323045", "a string", "an integer")
        listOf("\"2026-01-02T03:04:05Z\"", "\"2026-01-02T03:04:05+08:00\"", "\"2026-01-02 03:04:05\"")
            .forEach { invalid(LocalDateTime::class.java, it) }
        reads(YearMonth::class.java, "\"5-01\"", "\"0005-01\"")
        reads(YearMonth::class.java, "[2026,1]", "\"2026-01\"", old)
        wrongKind(YearMonth::class.java, "202601", "a string", "an integer")
        listOf("\"+12026-01\"", "\"2026-1\"", "\"2026-13\"").forEach { invalid(YearMonth::class.java, it) }
        wrongKind(MonthDay::class.java, "[1,2]", "a string", "an array")
        invalid(MonthDay::class.java, "\"01-02\"")
        invalid(MonthDay::class.java, "\"--02-30\"")
        // Durations: text, and the decimal of seconds Wow 1.x to 6.x wrote.
        reads(Duration::class.java, "\"P1D\"", "\"PT24H\"")
        reads(Duration::class.java, "\"pt1h\"", "\"PT1H\"")
        reads(Duration::class.java, "5400.000000000", "\"PT1H30M\"", old)
        reads(Duration::class.java, "1.500000000", "\"PT1.5S\"", old)
        reads(Duration::class.java, "5400", "\"PT1H30M\"")
        wrongKind(Duration::class.java, "[1]", "a string", "an array")
        invalid(Duration::class.java, "\"P1M\"")
        invalid(Duration::class.java, "\"90m\"")
        reads(Period::class.java, "\"P2W\"", "\"P14D\"")
        invalid(Period::class.java, "\"PT1H\"")
        wrongKind(Period::class.java, "1", "a string", "an integer")
        wrongKind(Period::class.java, "[1]", "a string", "an array")
        val kotlinDuration = kotlin.time.Duration::class.java
        reads(kotlinDuration, "\"P1D\"", "\"PT24H\"")
        reads(kotlinDuration, "\"PT9999999999999999H\"", "\"PT2562047788015215H30M7S\"")
        invalid(kotlinDuration, "\"1h 30m\"")
        invalid(kotlinDuration, "\"P1M\"")
        // Kotlin's duration is a value class over a long: its reader reports no class, so no kind is claimed.
        refuses(kotlinDuration, "5", Reason.INVALID_FORMAT, "Not a valid value of its type, given an integer.")
        // Text: no value of another kind; empty text is no value, but a URI's.
        reads(UUID::class.java, "\"00000000-0000-0001-0000-00000000000A\"", "\"00000000-0000-0001-0000-00000000000a\"")
        invalid(UUID::class.java, "\"x\"")
        reads(URI::class.java, "\"\"")
        invalid(URI::class.java, "\"a b\"")
        invalid(URL::class.java, "\"\"")
        invalid(URL::class.java, "\"example.com\"")
        wrongKind(ByteArray::class.java, "[1,2,3]", "a string", "an array")
        invalid(ByteArray::class.java, "\"AQI\"")
        invalid(ByteArray::class.java, "\"_-8=\"")
        reads(ByteArray::class.java, "\"AQID\"")
        reads(IntArray::class.java, "[1,2]")
        wrongKind(String::class.java, "1", "a string", "an integer")
        wrongKind(String::class.java, "1.5", "a string", "a number")
        wrongKind(String::class.java, "true", "a string", "a boolean")
        wrongKind(String::class.java, "{}", "a string", "an object")
        wrongKind(char, "65", "a string", "an integer")
        invalid(Character::class.java, "\"\"")
        invalid(char, "\"ab\"")
        // Zones, locales and currencies.
        reads(ZoneId::class.java, "\"+08:00\"")
        invalid(ZoneId::class.java, "\"Nowhere/City\"")
        invalid(ZoneId::class.java, "\"asia/shanghai\"")
        reads(ZoneOffset::class.java, "\"+8\"", "\"+08:00\"")
        reads(ZoneOffset::class.java, "\"+00:00\"", "\"Z\"")
        invalid(ZoneOffset::class.java, "\"+18:30\"")
        invalid(ZoneOffset::class.java, "\"UTC\"")
        reads(TimeZone::class.java, "\"+08:00\"", "\"GMT+08:00\"")
        reads(TimeZone::class.java, "\"Asia/Shanghai\"")
        reads(TimeZone::class.java, "\"PST\"")
        invalid(TimeZone::class.java, "\"Nowhere/City\"")
        wrongKind(TimeZone::class.java, "1", "a string", "an integer")
        // Java's form, which Wow 9.x wrote (Jackson 2's `toString`), and the root locale.
        reads(Locale::class.java, "\"zh_CN\"", "\"zh-CN\"")
        reads(Locale::class.java, "\"en_US\"", "\"en-US\"")
        reads(Locale::class.java, "\"\"")
        reads(Locale::class.java, "\"zh-Hans-CN\"")
        wrongKind(Locale::class.java, "1", "a string", "an integer")
        wrongKind(Locale::class.java, "true", "a string", "a boolean")
        wrongKind(Locale::class.java, "{}", "a string", "an object")
        // 9.x's form of a locale with a script: Jackson 3 keeps no script of it, on every minor.
        reads(Locale::class.java, "\"zh_CN_#Hans\"", "\"zh-CN\"")
        // A text of one subtag is a BCP 47 tag on every Jackson minor, as 3.2 reads it: 3.1 read it as a language.
        reads(Locale::class.java, "\"en\"")
        reads(Locale::class.java, "\"und\"", "\"\"")
        invalid(Locale::class.java, "\"not a tag!\"")
        invalid(Currency::class.java, "\"\"")
        invalid(Currency::class.java, "\"XYZ\"")
        invalid(Currency::class.java, "\"cny\"")
        val enumeration = "Not one of the values of its enumeration."
        refuses(DayOfWeek::class.java, "\"FUNDAY\"", Reason.NOT_IN_ENUMERATION, enumeration)
    }
}
