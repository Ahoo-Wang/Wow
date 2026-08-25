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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.POJONode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class RelativeTimeFilterNormalizerTest {
    private val now = Instant.parse("2026-08-22T12:00:00Z")
    private val normalizer = RelativeTimeFilterNormalizer(ZoneOffset.UTC)

    @Test
    fun `DATE should create millisecond Instant boundaries`() {
        val normalized = normalizer.normalize(
            TodayFilter(
                LogicalField("createdAt", FieldType.Temporal.Date),
                zoneId = "UTC",
            ),
            now,
        ) as AndFilter

        ((normalized.operands[0] as GreaterThanOrEqualFilter).value as POJONode).pojo.assert()
            .isEqualTo(Instant.parse("2026-08-22T00:00:00Z"))
        ((normalized.operands[1] as LessThanFilter).value as POJONode).pojo.assert()
            .isEqualTo(Instant.parse("2026-08-23T00:00:00Z"))
    }

    @Test
    fun `NUMBER seconds should create epoch second boundaries`() {
        val normalized = normalizer.normalize(
            TodayFilter(
                LogicalField(
                    "createdAt",
                    FieldType.Temporal.NumericEpoch(TimeUnit.SECONDS),
                ),
                zoneId = "UTC",
            ),
            now,
        ) as AndFilter

        (normalized.operands[0] as GreaterThanOrEqualFilter).value.asLong().assert()
            .isEqualTo(1_787_356_800L)
        (normalized.operands[1] as LessThanFilter).value.asLong().assert()
            .isEqualTo(1_787_443_200L)
    }

    @Test
    fun `STRING should create formatted boundaries`() {
        val normalized = normalizer.normalize(
            TodayFilter(
                LogicalField(
                    "createdAt",
                    FieldType.Temporal.FormattedString(datePattern = "yyyy-MM-dd HH:mm:ss"),
                ),
                zoneId = "UTC",
            ),
            now,
        ) as AndFilter

        (normalized.operands[0] as GreaterThanOrEqualFilter).value.asString().assert()
            .isEqualTo("2026-08-22 00:00:00")
        (normalized.operands[1] as LessThanFilter).value.asString().assert()
            .isEqualTo("2026-08-23 00:00:00")
    }

    @Test
    fun `runtime formatter should remain executable`() {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val normalized = normalizer.normalize(
            TodayFilter(
                LogicalField(
                    "createdAt",
                    FieldType.Temporal.FormattedString(dateFormatter = formatter),
                ),
            ),
            now,
        ) as AndFilter

        (normalized.operands[0] as GreaterThanOrEqualFilter).value.asString().assert()
            .isEqualTo("2026-08-22 00:00:00")
    }

    @Test
    fun `NUMBER nanoseconds should preserve subsecond boundary precision`() {
        val normalized = normalizer.normalize(
            BeforeTodayFilter(
                field = LogicalField(
                    "createdAt",
                    FieldType.Temporal.NumericEpoch(TimeUnit.NANOSECONDS),
                ),
                time = "12:00:00.123456789",
                zoneId = "UTC",
            ),
            now,
        ) as LessThanFilter

        normalized.value.asLong().assert().isEqualTo(1_787_400_000_123_456_789L)
    }

    @Test
    fun `calendar filters should preserve local boundaries across leap year`() {
        val field = LogicalField("createdAt", FieldType.Temporal.Date)
        val leapNow = Instant.parse("2024-02-29T12:00:00Z")
        val zoneId = "Asia/Shanghai"
        val cases = listOf(
            YesterdayFilter(field, zoneId) to
                (Instant.parse("2024-02-27T16:00:00Z") to Instant.parse("2024-02-28T16:00:00Z")),
            NextMonthFilter(field, zoneId) to
                (Instant.parse("2024-02-29T16:00:00Z") to Instant.parse("2024-03-31T16:00:00Z")),
            LastYearFilter(field, zoneId) to
                (Instant.parse("2022-12-31T16:00:00Z") to Instant.parse("2023-12-31T16:00:00Z")),
            ThisYearFilter(field, zoneId) to
                (Instant.parse("2023-12-31T16:00:00Z") to Instant.parse("2024-12-31T16:00:00Z")),
            NextYearFilter(field, zoneId) to
                (Instant.parse("2024-12-31T16:00:00Z") to Instant.parse("2025-12-31T16:00:00Z")),
        )

        cases.forEach { (relative, expected) ->
            val normalized = normalizer.normalize(relative, leapNow) as AndFilter
            val start = normalized.operands[0] as GreaterThanOrEqualFilter
            val end = normalized.operands[1] as LessThanFilter
            start.field.assert().isEqualTo(field)
            end.field.assert().isEqualTo(field)
            (start.value as POJONode).pojo.assert().isEqualTo(expected.first)
            (end.value as POJONode).pojo.assert().isEqualTo(expected.second)
        }
    }

    @Test
    fun `day range should preserve DST and half-open boundaries`() {
        val normalized = normalizer.normalize(
            TodayFilter(
                LogicalField("createdAt", FieldType.Temporal.Date),
                zoneId = "America/New_York",
            ),
            Instant.parse("2024-03-10T12:00:00Z"),
        ) as AndFilter

        ((normalized.operands[0] as GreaterThanOrEqualFilter).value as POJONode).pojo.assert()
            .isEqualTo(Instant.parse("2024-03-10T05:00:00Z"))
        ((normalized.operands[1] as LessThanFilter).value as POJONode).pojo.assert()
            .isEqualTo(Instant.parse("2024-03-11T04:00:00Z"))
    }

    @Test
    fun `week should start on Monday`() {
        val normalized = normalizer.normalize(
            ThisWeekFilter(LogicalField("createdAt", FieldType.Temporal.Date), "UTC"),
            now,
        ) as AndFilter

        ((normalized.operands[0] as GreaterThanOrEqualFilter).value as POJONode).pojo.assert()
            .isEqualTo(Instant.parse("2026-08-17T00:00:00Z"))
        ((normalized.operands[1] as LessThanFilter).value as POJONode).pojo.assert()
            .isEqualTo(Instant.parse("2026-08-24T00:00:00Z"))
    }

    @Test
    fun `should expand every relative time filter`() {
        val field = LogicalField("createdAt")
        val ranges = listOf(
            YesterdayFilter(field, "UTC"),
            TodayFilter(field, "UTC"),
            TomorrowFilter(field, "UTC"),
            ThisWeekFilter(field, "UTC"),
            NextWeekFilter(field, "UTC"),
            LastWeekFilter(field, "UTC"),
            ThisMonthFilter(field, "UTC"),
            LastMonthFilter(field, "UTC"),
            NextMonthFilter(field, "UTC"),
            LastYearFilter(field, "UTC"),
            ThisYearFilter(field, "UTC"),
            NextYearFilter(field, "UTC"),
            RecentDaysFilter(field, 2, "UTC"),
        )

        ranges.forEach { normalizer.normalize(it, now).assert().isInstanceOf(AndFilter::class.java) }
        normalizer.normalize(BeforeTodayFilter(field, "12:00", "UTC"), now).assert()
            .isInstanceOf(LessThanFilter::class.java)
        normalizer.normalize(EarlierDaysFilter(field, 2, "UTC"), now).assert()
            .isInstanceOf(LessThanFilter::class.java)
    }

    @Test
    fun `should recursively normalize logical and element match operands`() {
        val field = LogicalField("createdAt")
        val nested = LogicalField("nestedCreatedAt")
        val normalized = normalizer.normalize(
            AndFilter(
                listOf(
                    TodayFilter(field, "UTC"),
                    OrFilter(
                        listOf(
                            TomorrowFilter(field, "UTC"),
                            NorFilter(
                                listOf(
                                    ElementMatchFilter(
                                        LogicalField("items"),
                                        YesterdayFilter(nested, "UTC"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            now,
        ) as AndFilter

        normalized.operands[0].assert().isInstanceOf(AndFilter::class.java)
        val or = normalized.operands[1] as OrFilter
        or.operands[0].assert().isInstanceOf(AndFilter::class.java)
        val element = ((or.operands[1] as NorFilter).operands[0] as ElementMatchFilter)
        element.predicate.assert().isInstanceOf(AndFilter::class.java)
    }
}
