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

package me.ahoo.wow.query.tck

import kotlinx.coroutines.flow.map
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.schema.QueryCapability
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The cases of the snapshot contract ([QueryBackendSpec], which extends this class and is the one a backend's test
 * extends) on date buckets across a zone's offset changes. They hold the bucket rule (`DateBuckets`) at daylight
 * saving's transitions, in a half-hour zone, at a half-hour change (Australia/Lord_Howe) and where a change skipped a
 * unit's local start (Africa/Monrovia's 1972 change). They read through [createdAtBuckets], over
 * [SnapshotQueryFixtures]' fixtures and hooks; see there for the chain of families this class is part of.
 */
abstract class SnapshotDateBucketCases internal constructor() : SnapshotAggregationCases() {
    /**
     * The buckets of [unit] in [zone], filled when [dense], of snapshots whose `state.createdAt` is each instant of
     * [createdAt]: each bucket's start, ISO-8601, with its count, in key order. A storage that cannot group by time
     * skips the case.
     */
    private suspend fun createdAtBuckets(
        createdAt: List<String>,
        unit: AggregationDateUnit,
        zone: String,
        dense: Boolean = false,
    ): List<Pair<String, Long>> {
        val fixture = given(
            createdAt.mapIndexed { index, at ->
                snapshot("z$index", """{"createdAt":${java.time.Instant.parse(at).toEpochMilli()}}""")
            },
        )
        assumeBound(
            fixture.schema.field(QueryField("state.createdAt"))?.binding(QueryCapability.AGGREGATE_TEMPORAL),
            "state.createdAt",
            QueryCapability.AGGREGATE_TEMPORAL,
            "temporal grouping",
        )
        val bucket = AggregationGroup.DateHistogram(QueryField("state.createdAt"), "bucket", unit, zone, dense = dense)
        return fixture.aggregate(
            AggregationQuery(
                groupBy = listOf(bucket),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("bucket"), Sort.Direction.ASC)),
            ),
        ).map { java.time.Instant.ofEpochMilli(it.path("bucket").longValue()).toString() to it.path("count").longValue() }
    }

    @Test
    fun `an hourly bucket in a region starts where its local hour does, a repeated hour twice`() = blocking {
        // New York, 2025-11-02: 01:30 EDT, then 01:30 EST an hour later; 2025-03-09: 01:30 EST, then 03:30 EDT.
        val createdAt =
            listOf("2025-11-02T05:30:00Z", "2025-11-02T06:30:00Z", "2025-03-09T06:30:00Z", "2025-03-09T07:30:00Z")
        createdAtBuckets(createdAt, AggregationDateUnit.HOUR, "America/New_York").assert().containsExactly(
            "2025-03-09T06:00:00Z" to 1L,
            "2025-03-09T07:00:00Z" to 1L,
            "2025-11-02T05:00:00Z" to 1L,
            "2025-11-02T06:00:00Z" to 1L,
        )
        Unit
    }

    @Test
    fun `an hourly bucket in a half-hour zone starts at its local hour`() = blocking {
        // Asia/Kolkata is +05:30: 05:50 and 06:10 local are in its 05:00 and 06:00, at :30 in UTC.
        createdAtBuckets(
            listOf("2026-01-01T00:20:00Z", "2026-01-01T00:40:00Z"),
            AggregationDateUnit.HOUR,
            "Asia/Kolkata"
        )
            .assert().containsExactly("2025-12-31T23:30:00Z" to 1L, "2026-01-01T00:30:00Z" to 1L)
        Unit
    }

    @Test
    fun `a dense hourly histogram fills the buckets between, a repeated hour twice`() = blocking {
        // New York, 2025-11-02: 00:30 EDT and 01:30 EST; 01:00 EDT between them holds nothing.
        createdAtBuckets(
            listOf("2025-11-02T04:30:00Z", "2025-11-02T06:30:00Z"),
            AggregationDateUnit.HOUR,
            "America/New_York",
            dense = true,
        ).assert().containsExactly(
            "2025-11-02T04:00:00Z" to 1L,
            "2025-11-02T05:00:00Z" to 0L,
            "2025-11-02T06:00:00Z" to 1L,
        )
        Unit
    }

    @Test
    fun `a dense hourly histogram fills an hour a half-hour fall-back makes 90 minutes long`() = blocking {
        // Lord Howe, 2026-04-05: 02:00 +11:00 becomes 01:30 +10:30. 00:30 +11:00 and 02:30 +10:30, with the 01:00 hour
        // between them, from 01:00 +11:00 to 02:00 +10:30, empty.
        createdAtBuckets(
            listOf("2026-04-04T13:30:00Z", "2026-04-04T16:00:00Z"),
            AggregationDateUnit.HOUR,
            "Australia/Lord_Howe",
            dense = true,
        ).assert().containsExactly(
            "2026-04-04T13:00:00Z" to 1L,
            "2026-04-04T14:00:00Z" to 0L,
            "2026-04-04T15:30:00Z" to 1L,
        )
        Unit
    }

    @Test
    fun `an hour whose local start a change skipped begins at the transition`() {
        standard(GAP_START, semanticDivergences, BUCKET_RULE) {
            // Africa/Monrovia, 1972-01-07T00:44:30Z: -00:44:30 became UTC, skipping local 00:00 to 00:44:30, so the 00:00
            // hour begins at the transition; 23:30 -00:44:30 is in the hour before.
            createdAtBuckets(
                listOf("1972-01-07T00:14:30Z", "1972-01-07T00:50:00Z"),
                AggregationDateUnit.HOUR,
                "Africa/Monrovia"
            )
                .assert().containsExactly("1972-01-06T23:44:30Z" to 1L, "1972-01-07T00:44:30Z" to 1L)
        }
    }

    @Test
    fun `a minute whose local start a change skipped begins at the transition, not after its records`() {
        standard(GAP_MID_UNIT, semanticDivergences, BUCKET_RULE) {
            // Monrovia again: 00:44:45 UTC is in the local minute 00:44, of which only 00:44:30 on happened.
            createdAtBuckets(
                listOf("1972-01-07T00:43:50Z", "1972-01-07T00:44:45Z"),
                AggregationDateUnit.MINUTE,
                "Africa/Monrovia"
            )
                .assert().containsExactly("1972-01-07T00:43:30Z" to 1L, "1972-01-07T00:44:30Z" to 1L)
        }
    }

    companion object {
        /**
         * The case id of a unit whose local start a change skipped, the gap beginning at that start (Africa/Monrovia's
         * 1972 hour 00:00), for [semanticDivergences]: a store that resolves the skipped start otherwise than to the
         * transition.
         */
        const val GAP_START = "date-histogram.gap-start"

        /**
         * The case id of a unit whose local start lies inside a gap that began before it (Africa/Monrovia's 1972 minute
         * 00:44), for [semanticDivergences]: a store that resolves the skipped start forward by the gap's length rather
         * than to the transition starts the bucket after its records.
         */
        const val GAP_MID_UNIT = "date-histogram.gap-mid-unit"
    }
}
