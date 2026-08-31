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
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class FilterNormalizerTest {
    private val normalizer = FilterNormalizer(
        Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
        ZoneOffset.UTC,
    )

    @Test
    fun `should inject active deletion and expand today once`() {
        val normalized = normalizer.normalize(TodayFilter(LogicalField("createdAt"), "UTC")) as AndFilter

        normalized.operands.assert().hasSize(3)
        normalized.operands[0].assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        (normalized.operands[1] as GreaterThanOrEqualFilter).value.asLong().assert()
            .isEqualTo(Instant.parse("2026-08-22T00:00:00Z").toEpochMilli())
        (normalized.operands[2] as LessThanFilter).value.asLong().assert()
            .isEqualTo(Instant.parse("2026-08-23T00:00:00Z").toEpochMilli())
    }

    @Test
    fun `should preserve explicit deletion scope`() {
        normalizer.normalize(DeletionFilter(DeletionState.ALL)).assert()
            .isEqualTo(DeletionFilter(DeletionState.ALL))
    }

    @Test
    fun `should preserve deletion scope nested in conjunctions`() {
        val predicate = EqualFilter(
            LogicalField("field"),
            JsonSerializer.valueToTree<JsonNode>("value"),
        )
        val deleted = DeletionFilter(DeletionState.DELETED)
        val tenant = TenantIdFilter("tenant-1")

        val normalized = normalizer.normalize(
            AndFilter(listOf(AndFilter(listOf(deleted, predicate)), tenant)),
        ) as AndFilter

        normalized.operands.assert().containsExactly(deleted, predicate, tenant)
    }

    @Test
    fun `should allow event stream normalization without deletion scope`() {
        FilterNormalizer(
            clock = Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
            defaultZoneId = ZoneOffset.UTC,
            defaultDeletionState = null,
        ).normalize(MatchAllFilter).assert().isEqualTo(MatchAllFilter)
    }

    @Test
    fun `should preserve runtime date formatter`() {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val normalized = normalizer.normalize(
            TodayFilter(LogicalField("createdAt"), dateFormatter = formatter),
        ) as AndFilter

        (normalized.operands[1] as GreaterThanOrEqualFilter).value.asText().assert()
            .isEqualTo("2026-08-22 00:00:00")
    }

    @Test
    fun `should emit numeric boundaries in configured time unit`() {
        val normalized = FilterNormalizer(
            clock = Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
            defaultZoneId = ZoneOffset.UTC,
            defaultDeletionState = null,
        ).normalize(
            BeforeTodayFilter(
                field = LogicalField("createdAt"),
                time = "12:00:00.123456789",
                zoneId = "UTC",
                timeUnit = TimeUnit.NANOSECONDS,
            ),
        ) as LessThanFilter

        normalized.value.asLong().assert().isEqualTo(1_787_400_000_123_456_789L)
    }

    @Test
    fun `should expand extended calendar filters in their local zone across leap year`() {
        val field = LogicalField("createdAt")
        val zoneId = "Asia/Shanghai"
        val localNormalizer = FilterNormalizer(
            clock = Clock.fixed(Instant.parse("2024-02-29T12:00:00Z"), ZoneOffset.UTC),
            defaultZoneId = ZoneOffset.UTC,
            defaultDeletionState = null,
        )
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
            val normalized = localNormalizer.normalize(relative) as AndFilter
            val start = normalized.operands[0] as GreaterThanOrEqualFilter
            val end = normalized.operands[1] as LessThanFilter
            start.field.assert().isEqualTo(field)
            end.field.assert().isEqualTo(field)
            start.value.asLong().assert().isEqualTo(expected.first.toEpochMilli())
            end.value.asLong().assert().isEqualTo(expected.second.toEpochMilli())
        }
    }

    @Test
    fun `should expand every relative time filter`() {
        val field = LogicalField("createdAt")
        listOf(
            YesterdayFilter(field, "UTC"),
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
            BeforeTodayFilter(field, "12:00", "UTC"),
            RecentDaysFilter(field, 2, "UTC"),
            EarlierDaysFilter(field, 2, "UTC"),
        ).forEach { relative ->
            normalizer.normalize(relative).assert().isInstanceOf(AndFilter::class.java)
        }
    }

    @Test
    fun `should normalize nulls and simplify logical filters`() {
        val field = LogicalField("field")
        val value = JsonSerializer.valueToTree<JsonNode>("value")
        val nullValue = JsonSerializer.valueToTree<JsonNode>(null)
        val noScope = FilterNormalizer(defaultDeletionState = null)
        val normalized = noScope.normalize(
            AndFilter(
                listOf(
                    EqualFilter(field, nullValue),
                    NotEqualFilter(field, nullValue),
                    OrFilter(listOf(MatchNoneFilter, EqualFilter(field, value))),
                    NorFilter(listOf(MatchNoneFilter)),
                    ElementMatchFilter(field, EqualFilter(field, nullValue)),
                    MatchAllFilter,
                ),
            ),
        ) as AndFilter

        normalized.operands.assert().hasSize(4)
        noScope.normalize(AndFilter(listOf(MatchNoneFilter))).assert().isEqualTo(MatchNoneFilter)
        noScope.normalize(AndFilter(listOf(MatchAllFilter))).assert().isEqualTo(MatchAllFilter)
        noScope.normalize(AndFilter(listOf(EqualFilter(field, value)))).assert().isEqualTo(EqualFilter(field, value))
        noScope.normalize(OrFilter(listOf(MatchAllFilter))).assert().isEqualTo(MatchAllFilter)
        noScope.normalize(OrFilter(listOf(MatchNoneFilter))).assert().isEqualTo(MatchNoneFilter)
        noScope.normalize(OrFilter(listOf(EqualFilter(field, value)))).assert().isEqualTo(EqualFilter(field, value))
        noScope.normalize(NorFilter(listOf(MatchAllFilter))).assert().isEqualTo(MatchNoneFilter)
        noScope.normalize(NorFilter(listOf(MatchNoneFilter))).assert().isEqualTo(MatchAllFilter)
    }

    @Test
    fun `should normalize operand free empty string filters`() {
        val field = LogicalField("field")
        val emptyValue = JsonSerializer.valueToTree<JsonNode>("")
        val noScope = FilterNormalizer(defaultDeletionState = null)
        val empty = JsonSerializer.readValue(
            """{"op":"IS_EMPTY_STRING","field":"field"}""",
            FilterExpression::class.java,
        )
        val notEmpty = JsonSerializer.readValue(
            """{"op":"IS_NOT_EMPTY_STRING","field":"field"}""",
            FilterExpression::class.java,
        )

        noScope.normalize(empty).assert().isEqualTo(EqualFilter(field, emptyValue))
        noScope.normalize(notEmpty).assert().isEqualTo(
            AndFilter(
                listOf(
                    IsNotNullFilter(field),
                    NotEqualFilter(field, emptyValue),
                ),
            ),
        )
    }

    @Test
    fun `should keep active scope around nested deletion filters`() {
        val predicate = EqualFilter(
            LogicalField("field"),
            JsonSerializer.valueToTree<JsonNode>("value"),
        )
        listOf(
            OrFilter(listOf(DeletionFilter(DeletionState.DELETED), predicate)),
            NorFilter(listOf(DeletionFilter(DeletionState.DELETED), predicate)),
        ).forEach { expression ->
            val normalized = normalizer.normalize(expression) as AndFilter
            normalized.operands.first().assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        }
    }
}
