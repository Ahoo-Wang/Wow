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

import kotlinx.coroutines.flow.toList
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.CREATED_AT
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.ID
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * The cases of the dataset contract ([DatasetQueryBackendSpec], which extends this class and is the one a backend's
 * test extends) on date buckets across a zone's offset changes. They hold the bucket rule (`DateBuckets`) at daylight
 * saving's transitions, in a half-hour zone, at a half-hour change (Australia/Lord_Howe), at a change by seconds
 * (Africa/Monrovia) and on the day of a change before 1970 (Asia/Shanghai's switch from local mean time). They read
 * their table, [ZONED], through [aggregateZoned], over [DatasetQueryFixtures]' fixtures and hooks; see there for the
 * chain of families this class is part of.
 */
abstract class DatasetDateBucketCases internal constructor() : DatasetAggregationCases() {
    /** [query] over the dataset of [ZONED], as the spec's backend answers it. */
    private suspend fun aggregateZoned(query: AggregationQuery): List<ObjectNode> =
        target(DatasetDateBucketCases.ZONED).aggregate(query)

    @Test
    fun `an hourly bucket starts where its local hour does, a repeated hour twice and a half-hour zone at its own`() =
        blocking {
            fun hours(zone: String, ids: List<String>) = blocking {
                aggregateZoned(
                    AggregationQuery(
                        filter = InFilter(QueryField(ID), ids),
                        groupBy = listOf(
                            AggregationGroup.DateHistogram(CREATED_AT, "hour", AggregationDateUnit.HOUR, zone)
                        ),
                        metrics = listOf(AggregationMetric.Count("count")),
                        sort = listOf(Sort(QueryField("hour"), Sort.Direction.ASC)),
                    ),
                ).map { Instant.ofEpochMilli(it.path("hour").longValue()).toString() to it.path("count").longValue() }
            }
            // New York: 01:30 EST then 03:30 EDT (02:00 never happens); 01:30 EDT then 01:30 EST, two hours.
            hours("America/New_York", listOf("s1", "s2", "f1", "f2")).assert().containsExactly(
                "2025-03-09T06:00:00Z" to 1L,
                "2025-03-09T07:00:00Z" to 1L,
                "2025-11-02T05:00:00Z" to 1L,
                "2025-11-02T06:00:00Z" to 1L,
            )
            // Asia/Kolkata (+05:30): its hours start at :30 in UTC.
            hours("Asia/Kolkata", listOf("k1", "k2")).assert()
                .containsExactly("2025-12-31T23:30:00Z" to 1L, "2026-01-01T00:30:00Z" to 1L)
            Unit
        }

    @Test
    fun `a dense hourly histogram keeps whole the hour a half-hour fall-back makes 90 minutes long`() = blocking {
        // Lord Howe, 2026-04-05: 02:00 +11:00 becomes 01:30 +10:30; l1 at 00:30 +11:00, l3 at 01:45 +10:30, the second
        // half of the 01:00 hour that began at +11:00, and l2 at 02:30 +10:30.
        val rows = aggregateZoned(
            AggregationQuery(
                filter = InFilter(QueryField(ID), listOf("l1", "l2", "l3")),
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        CREATED_AT,
                        "hour",
                        AggregationDateUnit.HOUR,
                        "Australia/Lord_Howe",
                        dense = true,
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("hour"), Sort.Direction.ASC)),
            ),
        )
        rows.map { Instant.ofEpochMilli(it.path("hour").longValue()).toString() to it.path("count").longValue() }
            .assert().containsExactly(
                "2026-04-04T13:00:00Z" to 1L,
                "2026-04-04T14:00:00Z" to 1L,
                "2026-04-04T15:30:00Z" to 1L,
            )
        Unit
    }

    /** The buckets of [unit] in [zone] of the zoned rows [ids], each with its count, in key order. */
    private suspend fun zonedBuckets(unit: AggregationDateUnit, zone: String, vararg ids: String) =
        aggregateZoned(
            AggregationQuery(
                filter = InFilter(QueryField(ID), ids.toList()),
                groupBy = listOf(AggregationGroup.DateHistogram(CREATED_AT, "bucket", unit, zone)),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("bucket"), Sort.Direction.ASC)),
            ),
        ).map { Instant.ofEpochMilli(it.path("bucket").longValue()).toString() to it.path("count").longValue() }

    @Test
    fun `a minute bucket starts at its local minute in a zone whose offset moved by seconds`() = blocking {
        // Africa/Monrovia, 1972-01-07T00:44:30Z: -00:44:30 became UTC, so its minutes started at :30 seconds before it
        // and at :00 after it.
        zonedBuckets(AggregationDateUnit.MINUTE, "Africa/Monrovia", "m1", "m2", "m4").assert().containsExactly(
            "1972-01-06T22:59:30Z" to 1L,
            "1972-01-07T00:43:30Z" to 1L,
            "1972-01-08T00:00:00Z" to 1L,
        )
        Unit
    }

    @Test
    fun `a minute bucket right after Monrovia's 1972 change starts at its local minute`() = blocking {
        Assumptions.assumeFalse(MONROVIA_CHANGE_DAY in semanticDivergences) {
            "Known divergence from the bucket rule: $MONROVIA_CHANGE_DAY."
        }
        // 00:45:10 UTC, the offset UTC since 00:44:30: its own minute.
        zonedBuckets(AggregationDateUnit.MINUTE, "Africa/Monrovia", "m3").assert()
            .containsExactly("1972-01-07T00:45:00Z" to 1L)
        Unit
    }

    @Test
    fun `an hour whose local start a change skipped begins at the transition`() = blocking {
        Assumptions.assumeFalse(SnapshotDateBucketCases.GAP_START in semanticDivergences) {
            "Known divergence from the bucket rule: ${SnapshotDateBucketCases.GAP_START}."
        }
        // Africa/Monrovia skipped local 00:00 to 00:44:30 on 1972-01-07: the 00:00 hour begins at the transition.
        zonedBuckets(AggregationDateUnit.HOUR, "Africa/Monrovia", "g1", "g2").assert()
            .containsExactly("1972-01-06T23:44:30Z" to 1L, "1972-01-07T00:44:30Z" to 1L)
        Unit
    }

    @Test
    fun `a minute whose local start a change skipped begins at the transition, not after its records`() = blocking {
        Assumptions.assumeFalse(SnapshotDateBucketCases.GAP_MID_UNIT in semanticDivergences) {
            "Known divergence from the bucket rule: ${SnapshotDateBucketCases.GAP_MID_UNIT}."
        }
        // 00:44:45 UTC is in the local minute 00:44, of which only 00:44:30 on happened.
        zonedBuckets(AggregationDateUnit.MINUTE, "Africa/Monrovia", "g3", "g4").assert()
            .containsExactly("1972-01-07T00:43:30Z" to 1L, "1972-01-07T00:44:30Z" to 1L)
        Unit
    }

    @Test
    fun `an hourly bucket on the day of an offset change before 1970 starts at its local hour`() = blocking {
        Assumptions.assumeFalse(BEFORE_1970_PARTIAL_CHANGE in semanticDivergences) {
            "Known divergence from the bucket rule: $BEFORE_1970_PARTIAL_CHANGE."
        }
        // Asia/Shanghai, 1901-01-01: local mean time +08:05:43 became +08:00, repeating 23:54:17 to midnight; p1 and
        // p2 are in the 23:00 hour that began at +08:05:43, p3 in the 00:00 hour at +08:00.
        zonedBuckets(AggregationDateUnit.HOUR, "Asia/Shanghai", "p1", "p2", "p3").assert().containsExactly(
            "1900-12-31T14:54:17Z" to 2L,
            "1900-12-31T16:00:00Z" to 1L,
        )
        Unit
    }

    companion object {
        private val CREATED_AT = QueryField("created_at")
        private const val TENANT = "tenant_id"

        /**
         * The case id of an hourly bucket on the day of an offset change before 1970 by part of an hour (Asia/Shanghai's
         * switch from local mean time), for [semanticDivergences]: a store may document it as a limit.
         */
        const val BEFORE_1970_PARTIAL_CHANGE = "date-histogram.before-1970-partial-change"

        /**
         * The case id of a minute bucket on the day of Africa/Monrovia's 1972 change, after it, for [semanticDivergences]:
         * a store whose own time zone data reads that day's offset otherwise.
         */
        const val MONROVIA_CHANGE_DAY = "date-histogram.monrovia-1972-change-day"

        /** Instants around New York's 2025 transitions, in a half-hour zone's hours, and around Lord Howe's, Monrovia's and Shanghai's offset changes. */
        val ZONED = DatasetTable(
            name = "zoned",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT, DatasetColumn.Type.STRING),
                DatasetColumn("created_at", DatasetColumn.Type.DATE),
            ),
            rows = listOf(
                "s1" to "2025-03-09T06:30:00Z",
                "s2" to "2025-03-09T07:30:00Z",
                "f1" to "2025-11-02T05:30:00Z",
                "f2" to "2025-11-02T06:30:00Z",
                "k1" to "2026-01-01T00:20:00Z",
                "k2" to "2026-01-01T00:40:00Z",
                "l1" to "2026-04-04T13:30:00Z",
                "l2" to "2026-04-04T16:00:00Z",
                "l3" to "2026-04-04T15:15:00Z",
                "m1" to "1972-01-06T23:00:10Z",
                "m2" to "1972-01-07T00:44:20Z",
                "m3" to "1972-01-07T00:45:10Z",
                "m4" to "1972-01-08T00:00:10Z",
                "g1" to "1972-01-07T00:14:30Z",
                "g2" to "1972-01-07T00:50:00Z",
                "g3" to "1972-01-07T00:43:50Z",
                "g4" to "1972-01-07T00:44:45Z",
                "p1" to "1900-12-31T15:30:00Z",
                "p2" to "1900-12-31T15:58:00Z",
                "p3" to "1900-12-31T16:30:00Z",
            ).map { (id, at) -> mapOf(ID to id, TENANT to "t1", "created_at" to Instant.parse(at)) },
        )
    }
}
