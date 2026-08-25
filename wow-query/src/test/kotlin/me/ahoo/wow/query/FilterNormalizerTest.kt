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
import java.time.ZoneId
import java.time.ZoneOffset

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
    fun `should capture clock instant once per root normalization`() {
        val countingClock = object : Clock() {
            var reads = 0

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant {
                reads++
                return Instant.parse("2026-08-22T12:00:00Z")
            }
        }
        val noScope = FilterNormalizer(
            clock = countingClock,
            defaultZoneId = ZoneOffset.UTC,
            defaultDeletionState = null,
        )

        noScope.normalize(
            AndFilter(
                listOf(
                    TodayFilter(LogicalField("createdAt"), "UTC"),
                    TomorrowFilter(LogicalField("updatedAt"), "UTC"),
                ),
            ),
        )

        countingClock.reads.assert().isEqualTo(1)
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
