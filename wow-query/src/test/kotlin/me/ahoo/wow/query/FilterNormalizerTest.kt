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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    fun `should allow event stream normalization without deletion scope`() {
        FilterNormalizer(
            clock = Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
            defaultZoneId = ZoneOffset.UTC,
            defaultDeletionState = null,
        ).normalize(MatchAllFilter).assert().isEqualTo(MatchAllFilter)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy date formatter`() {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val normalized = normalizer.normalize(
            Condition.today("createdAt", formatter).toFilterExpression(),
        ) as AndFilter

        (normalized.operands[1] as GreaterThanOrEqualFilter).value.asString().assert()
            .isEqualTo("2026-08-22 00:00:00")
        (normalized.operands[2] as LessThanFilter).value.asString().assert()
            .isEqualTo("2026-08-23 00:00:00")
    }
}
