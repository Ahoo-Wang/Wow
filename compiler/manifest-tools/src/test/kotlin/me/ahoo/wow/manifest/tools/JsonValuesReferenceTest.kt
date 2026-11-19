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
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
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
import kotlin.time.Duration.Companion.minutes

/**
 * The JSON values reference (`documentation/docs/{en,zh}/reference/json-values.md`) is the table [WireTypes.SCALARS]
 * is: a row for each of its classes, the row's JSON type and format the table's, and each value the row shows written
 * as Wow's JSON writes [EXAMPLES]' value of that class.
 */
class JsonValuesReferenceTest {
    /** A row of the page: the classes its first cell names, and the code spans of its JSON and its written cells. */
    private data class Row(
        val page: String,
        val classes: List<String>,
        val json: List<String>,
        val written: List<String>,
    )

    private val root = File(requireNotNull(System.getProperty(ROOT)) { "System property $ROOT is not set." })

    private val classes: Map<String, Class<*>> = WireTypes.SCALARS.keys.associateBy(::referenceName)

    private val rows: List<Row> = listOf("en", "zh").flatMap { language ->
        val page = "documentation/docs/$language/reference/json-values.md"
        File(root, page).readLines().filter { it.startsWith("| `") }.map { line ->
            val cells = line.split("|").map { cell -> SPAN.findAll(cell).map { it.groupValues[1] }.toList() }
            Row(page, cells[1], cells[2], cells[3])
        }
    }

    @Test
    fun `the page should list every class of the table, and no other, in each language`() {
        rows.groupBy { it.page }.forEach { (page, rows) ->
            val listed = rows.flatMap { it.classes }
            (classes.keys - listed.toSet()).assert().describedAs("$page leaves out").isEmpty()
            (listed - classes.keys).assert().describedAs("$page lists beyond the table").isEmpty()
            listed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.assert()
                .describedAs("$page lists twice").isEmpty()
        }
    }

    @Test
    fun `each row's JSON type and format should be those the table gives its classes`() {
        rows.forEach { row ->
            row.classes.mapNotNull(classes::get).forEach { type ->
                val scalar = WireTypes.SCALARS.getValue(type)
                row.json.assert().describedAs("${row.page}: ${type.name}")
                    .isEqualTo(listOfNotNull(scalar.type.keyword, scalar.format))
            }
        }
    }

    @Test
    fun `each row's written values should be what Wow's JSON writes for the test's values, in order`() {
        rows.forEach { row ->
            val values = row.classes.mapNotNull { classes[it]?.let(EXAMPLES::get) }
            values.size.assert().describedAs("${row.page}: values for ${row.classes}").isEqualTo(1)
            val written = values.single().map { WowJson.mapper.writeValueAsString(it) }
            row.written.assert().describedAs("${row.page}: ${row.classes}").isEqualTo(written)
        }
    }

    private companion object {
        const val ROOT = "wow-test.repository.root"
        val SPAN = Regex("`([^`]+)`")

        /** A class as the page names it: by its simple name, Kotlin's duration by its qualified one. */
        fun referenceName(type: Class<*>): String =
            if (type == kotlin.time.Duration::class.java) type.name else type.simpleName

        private const val MILLIS = 1767323045123

        /** The values each row shows, by one class of the row: its others are written alike. */
        val EXAMPLES: Map<Class<*>, List<Any>> = mapOf(
            Boolean::class.javaObjectType to listOf(true, false),
            Int::class.javaObjectType to listOf(1, Int.MAX_VALUE, Int.MIN_VALUE),
            Short::class.javaObjectType to listOf<Short>(1, Short.MIN_VALUE, Short.MAX_VALUE),
            Byte::class.javaObjectType to listOf<Byte>(1, Byte.MIN_VALUE, Byte.MAX_VALUE),
            Year::class.java to listOf(Year.of(2026), Year.of(0), Year.of(-1), Year.of(12026)),
            Long::class.javaObjectType to listOf(1L, Long.MAX_VALUE),
            BigInteger::class.java to listOf(BigInteger("123456789012345678901234567890")),
            Float::class.javaObjectType to listOf(
                1.5f,
                0.1f,
                Float.MAX_VALUE,
                -0.0f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            ),
            Double::class.javaObjectType to listOf(
                0.1,
                1.0,
                1e300,
                -0.0,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
            ),
            BigDecimal::class.java to listOf("2.50", "0.000001", "1E+3", "1E-7").map(::BigDecimal),
            Instant::class.java to listOf(
                "2026-01-02T03:04:05Z",
                "2026-01-02T03:04:05.123456789Z",
                "+12026-01-01T00:00:00Z",
                "-0001-01-01T00:00:00Z",
            ).map(Instant::parse),
            OffsetDateTime::class.java to listOf(OffsetDateTime.parse("2026-01-02T03:04:05+08:00")),
            ZonedDateTime::class.java to listOf(ZonedDateTime.parse("2026-01-02T03:04:05+08:00[Asia/Shanghai]")),
            Date::class.java to listOf(Date(MILLIS)),
            Calendar::class.java to listOf(
                Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply { timeInMillis = MILLIS },
            ),
            LocalDate::class.java to listOf("2026-01-02", "+12026-01-01", "-0001-01-01").map(LocalDate::parse),
            LocalTime::class.java to listOf("03:04:05", "03:04", "03:04:05.12").map(LocalTime::parse),
            OffsetTime::class.java to listOf("03:04:05+08:00", "03:04Z").map(OffsetTime::parse),
            java.time.Duration::class.java to listOf(
                java.time.Duration.ofMinutes(90),
                java.time.Duration.ZERO,
                java.time.Duration.ofDays(2),
                java.time.Duration.ofMillis(-1500),
                java.time.Duration.ofNanos(1),
            ),
            Period::class.java to listOf(Period.of(1, 2, 3), Period.ZERO, Period.ofWeeks(2), Period.ofMonths(-1)),
            kotlin.time.Duration::class.java to listOf(90.minutes, -(90.minutes), kotlin.time.Duration.INFINITE),
            UUID::class.java to listOf(UUID(1, 2)),
            URI::class.java to listOf("https://example.com/a%20b", "urn:isbn:123", "/relative").map(::URI),
            java.net.URL::class.java to listOf(URI("https://example.com/a").toURL()),
            ByteArray::class.java to listOf(byteArrayOf(1, 2, 3), byteArrayOf(-1, -2, -3, -4), byteArrayOf()),
            String::class.java to listOf("\u0000"),
            Char::class.javaObjectType to listOf('c'),
            LocalDateTime::class.java to
                listOf("2026-01-02T03:04:05", "+12026-01-01T00:00:00").map(LocalDateTime::parse),
            YearMonth::class.java to listOf(
                YearMonth.of(2026, 1),
                YearMonth.of(5, 1),
                YearMonth.of(12026, 1),
                YearMonth.of(-1, 1),
            ),
            MonthDay::class.java to listOf(MonthDay.of(1, 2), MonthDay.of(2, 29)),
            ZoneId::class.java to listOf("Asia/Shanghai", "UTC", "Z", "GMT+8").map(ZoneId::of),
            ZoneOffset::class.java to listOf(
                ZoneOffset.ofHours(8),
                ZoneOffset.ofHoursMinutes(5, 30),
                ZoneOffset.ofHours(-18),
                ZoneOffset.UTC,
                ZoneOffset.ofHoursMinutesSeconds(1, 2, 3),
            ),
            TimeZone::class.java to listOf("UTC", "Asia/Shanghai", "GMT+08:00").map(TimeZone::getTimeZone),
            Locale::class.java to listOf(
                Locale.CHINA,
                Locale.forLanguageTag("zh-Hans-CN"),
                Locale.forLanguageTag("en-US-x-lvariant-POSIX"),
                Locale.ROOT,
            ),
            Currency::class.java to listOf(Currency.getInstance("CNY")),
        )
    }
}
