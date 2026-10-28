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
import me.ahoo.wow.query.resolve.FilterNormalizer
import me.ahoo.wow.query.resolve.QueryResolver
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.boundSchemaFixture
import org.junit.jupiter.api.Test
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
    fun `raw normalization must not inject a deletion predicate`() {
        normalizer.normalize(MatchAllFilter).assert().isEqualTo(MatchAllFilter)
        val predicate = EqualFilter(QueryField("field"), "value")
        normalizer.normalize(predicate).assert().isEqualTo(predicate)
    }

    @Test
    fun `shared instant normalizes root and scoped temporal filters without nested deletion defaults`() {
        val epoch = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.INTEGER),
            semanticType = Temporal.Epoch(TimeUnit.SECONDS),
        )
        val item = QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf("createdAt" to epoch),
        )
        val definition = LogicalQuerySchema(
            QueryValueSchema(
                QueryValueKind.OBJECT,
                properties = mapOf(
                    "createdAt" to epoch,
                    "orders" to QueryValueSchema(
                        QueryValueKind.ARRAY, items = item,
                    )
                ),
            )
        )
        val schema = boundSchemaFixture(definition.root)
        val instant = Instant.parse("2026-01-01T12:00:00Z")
        val filter = TodayFilter(QueryField("createdAt"), "UTC")
        // Admission's resolution pass normalizes against one moment, root and element filters alike.
        val root = QueryResolver(schema, instant).filter(filter) as AndFilter
        val scoped = (
            QueryResolver(schema, instant).filter(ElementMatchFilter(QueryField("orders"), filter))
                as ElementMatchFilter
            ).predicate as AndFilter
        root.operands.assert().isEqualTo(scoped.operands)
        (scoped.operands.first() as GreaterThanOrEqualFilter).value.assert()
            .isEqualTo(Instant.parse("2026-01-01T00:00:00Z").epochSecond)
        (scoped.operands.last() as LessThanFilter).value.assert()
            .isEqualTo(Instant.parse("2026-01-02T00:00:00Z").epochSecond)
    }

    @Test
    fun `should expand today without injecting deletion scope`() {
        val normalized = normalizer.normalize(TodayFilter(QueryField("createdAt"), "UTC")) as AndFilter

        normalized.operands.assert().hasSize(2)
        (normalized.operands[0] as GreaterThanOrEqualFilter).value.assert()
            .isEqualTo(Instant.parse("2026-08-22T00:00:00Z").toEpochMilli())
        (normalized.operands[1] as LessThanFilter).value.assert()
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
            QueryField("field"),
            "value",
        )
        val deleted = DeletionFilter(DeletionState.DELETED)
        val tenant = TenantIdFilter("tenant-1")

        val normalized = normalizer.normalize(
            AndFilter(listOf(AndFilter(listOf(deleted, predicate)), tenant)),
        ) as AndFilter

        normalized.operands.assert().containsExactly(deleted, predicate, tenant)
    }

    @Test
    fun `should preserve runtime date formatter`() {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val normalized = normalizer.normalize(
            TodayFilter(QueryField("createdAt"), dateFormatter = formatter),
        ) as AndFilter

        (normalized.operands[0] as GreaterThanOrEqualFilter).value.assert()
            .isEqualTo("2026-08-22 00:00:00")
    }

    @Test
    fun `a relative-time filter without a zone reads its days in UTC, whatever the JVM's zone (Q-10)`() {
        val jvmZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val defaults = FilterNormalizer(Clock.fixed(Instant.parse("2026-08-22T20:00:00Z"), ZoneOffset.UTC))
            defaults.normalize(TodayFilter(QueryField("createdAt"))).assert()
                .isEqualTo(defaults.normalize(TodayFilter(QueryField("createdAt"), "UTC")))
        } finally {
            java.util.TimeZone.setDefault(jvmZone)
        }
    }

    @Test
    fun `should emit numeric boundaries in configured time unit`() {
        val normalized = FilterNormalizer(
            clock = Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
            defaultZoneId = ZoneOffset.UTC,
        ).normalize(
            BeforeTodayFilter(
                field = QueryField("createdAt"),
                time = "12:00:00.123456789",
                zoneId = "UTC",
                timeUnit = TimeUnit.NANOSECONDS,
            ),
        ) as LessThanFilter

        normalized.value.assert().isEqualTo(1_787_400_000_123_456_789L)
    }

    @Test
    fun `should expand extended calendar filters in their local zone across leap year`() {
        val field = QueryField("createdAt")
        val zoneId = "Asia/Shanghai"
        val localNormalizer = FilterNormalizer(
            clock = Clock.fixed(Instant.parse("2024-02-29T12:00:00Z"), ZoneOffset.UTC),
            defaultZoneId = ZoneOffset.UTC,
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
            start.value.assert().isEqualTo(expected.first.toEpochMilli())
            end.value.assert().isEqualTo(expected.second.toEpochMilli())
        }
    }

    @Test
    fun `should expand every relative time filter`() {
        val field = QueryField("createdAt")
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
            RecentDaysFilter(field, 2, "UTC"),
        ).forEach { relative ->
            normalizer.normalize(relative).assert().isInstanceOf(AndFilter::class.java)
        }
        listOf(BeforeTodayFilter(field, "12:00", "UTC"), EarlierDaysFilter(field, 2, "UTC")).forEach { relative ->
            normalizer.normalize(relative).assert().isInstanceOf(LessThanFilter::class.java)
        }
    }

    @Test
    fun `relative-now filters become strict comparisons against one server moment in the field's encoding`() {
        val field = QueryField("timeoutAt")
        val now = Instant.parse("2026-08-22T12:00:00Z")
        normalizer.normalize(BeforeNowFilter(field)).assert()
            .isEqualTo(LessThanFilter(field, now.toEpochMilli()))
        normalizer.normalize(AfterNowFilter(field, "-PT30M")).assert()
            .isEqualTo(
                GreaterThanFilter(field, now.minusSeconds(1800).toEpochMilli())
            )
        normalizer.normalize(BeforeNowFilter(field, "PT1H", timeUnit = TimeUnit.SECONDS)).assert()
            .isEqualTo(LessThanFilter(field, now.plusSeconds(3600).epochSecond))
        normalizer.normalize(BeforeNowFilter(field, zoneId = "Asia/Shanghai", datePattern = "yyyy-MM-dd HH:mm"))
            .assert()
            .isEqualTo(LessThanFilter(field, "2026-08-22 20:00"))
    }

    @Test
    fun `should normalize nulls and simplify logical filters`() {
        val field = QueryField("field")
        val value = "value"
        val nullValue = null
        val normalized = normalizer.normalize(
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
        normalizer.normalize(AndFilter(listOf(MatchNoneFilter))).assert().isEqualTo(MatchNoneFilter)
        normalizer.normalize(AndFilter(listOf(MatchAllFilter))).assert().isEqualTo(MatchAllFilter)
        normalizer.normalize(AndFilter(listOf(EqualFilter(field, value)))).assert().isEqualTo(EqualFilter(field, value))
        normalizer.normalize(OrFilter(listOf(MatchAllFilter))).assert().isEqualTo(MatchAllFilter)
        normalizer.normalize(OrFilter(listOf(MatchNoneFilter))).assert().isEqualTo(MatchNoneFilter)
        normalizer.normalize(OrFilter(listOf(EqualFilter(field, value)))).assert().isEqualTo(EqualFilter(field, value))
        normalizer.normalize(NorFilter(listOf(MatchAllFilter))).assert().isEqualTo(MatchNoneFilter)
        normalizer.normalize(NorFilter(listOf(MatchNoneFilter))).assert().isEqualTo(MatchAllFilter)
    }

    @Test
    fun `should normalize operand free empty string filters`() {
        val field = QueryField("field")
        val emptyValue = ""
        val empty = IsEmptyStringFilter(field)
        val notEmpty = IsNotEmptyStringFilter(field)

        normalizer.normalize(empty).assert().isEqualTo(EqualFilter(field, emptyValue))
        normalizer.normalize(notEmpty).assert().isEqualTo(
            AndFilter(
                listOf(
                    IsNotNullFilter(field),
                    NotEqualFilter(field, emptyValue),
                ),
            ),
        )
    }

    @Test
    fun `should preserve nested deletion predicates without adding a root scope`() {
        val predicate = EqualFilter(
            QueryField("field"),
            "value",
        )
        listOf(
            OrFilter(listOf(DeletionFilter(DeletionState.DELETED), predicate)),
            NorFilter(listOf(DeletionFilter(DeletionState.DELETED), predicate)),
        ).forEach { expression ->
            normalizer.normalize(expression).assert().isEqualTo(expression)
        }
    }
}
