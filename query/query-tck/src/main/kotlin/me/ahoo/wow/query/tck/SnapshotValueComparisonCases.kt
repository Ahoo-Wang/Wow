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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.DAY
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.T0
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The cases of the snapshot contract ([QueryBackendSpec], which extends this class and is the one a backend's test
 * extends) on how a value compares: a number by its value, whatever its JSON width (`1`, `1.0`, `1.00`), and a date
 * string by the instant it names, whatever its text (`…T00:00:00Z`, `…T01:00:00+01:00`, `…T00:00:00.000Z`), so a
 * millisecond apart is apart and a text that orders the other way does not decide. They hold it in filters, TERMS
 * groups, DISTINCT_COUNT, a list's sort, the windows (TOTAL, RUNNING, RANK, PREVIOUS) and a Top-N by FIRST / LAST.
 *
 * A storage that does not take a date string as an operand ([QueryStorageFamilyRules.dateOperands]
 * [me.ahoo.wow.query.schema.QueryStorageFamilyRules]) binds no such capability to [PLACED_AT], and a feature a storage
 * declines skips its case. They read [SnapshotQueryFixtures]' fixtures and hooks; see there for the chain of families
 * this class is part of.
 */
@WowSpi
abstract class SnapshotValueComparisonCases internal constructor() : SnapshotDateBucketCases() {
    // A date string: one instant in three texts, and one a millisecond later whose text orders before them.

    private suspend fun placed(): Fixture = given(
        listOf("utc" to INSTANT, "offset" to "2026-01-01T01:00:00+01:00", "fraction" to "2026-01-01T00:00:00.000Z")
            .map { (id, at) -> snapshot(id, """{"status":"earlier","placedAt":"$at","createdAt":$T0}""") } +
            snapshot("later", """{"status":"later","placedAt":"$LATER","createdAt":$T0}"""),
    )

    private fun Fixture.assumeBound(field: QueryField<*>, capability: QueryCapability, feature: String) =
        assumeBound(schema.field(field)?.binding(capability), field.path, capability, feature)

    @Test
    fun `a date's equality and membership match every text of its instant`(): Unit = blocking {
        val fixture = placed()
        fixture.assumeBound(PLACED_AT, QueryCapability.EXACT_MATCH, "a date operand")
        standard(DATE_EQUALITY, semanticDivergences, VALUE_ORDER) {
            fixture.ids(EqualFilter(PLACED_AT, INSTANT)).assert().containsExactlyInAnyOrder(*SAME_INSTANT)
            fixture.ids(InFilter(PLACED_AT, listOf(INSTANT))).assert().containsExactlyInAnyOrder(*SAME_INSTANT)
            fixture.ids(NotEqualFilter(PLACED_AT, INSTANT)).assert().containsExactly("later")
            fixture.ids(NotInFilter(PLACED_AT, listOf(INSTANT))).assert().containsExactly("later")
            fixture.ids(EqualFilter(PLACED_AT, LATER)).assert().containsExactly("later")
        }
    }

    @Test
    fun `a date's range holds every text of its instant, and a millisecond later apart`(): Unit = blocking {
        val fixture = placed()
        fixture.assumeBound(PLACED_AT, QueryCapability.RANGE, "a date range")
        standard(DATE_RANGE, semanticDivergences, VALUE_ORDER) {
            fixture.ids(BetweenFilter(PLACED_AT, INSTANT, INSTANT)).assert().containsExactlyInAnyOrder(*SAME_INSTANT)
            fixture.ids(GreaterThanOrEqualFilter(PLACED_AT, LATER)).assert().containsExactly("later")
            fixture.ids(LessThanFilter(PLACED_AT, LATER)).assert().containsExactlyInAnyOrder(*SAME_INSTANT)
        }
    }

    // Two instants within one millisecond, and one in the next.
    private suspend fun millis(): Fixture = given(
        listOf("first" to FIRST, "second" to SECOND, "next" to NEXT)
            .map { (id, at) -> snapshot(id, """{"status":"earlier","placedAt":"$at","createdAt":$T0}""") },
    )

    @Test
    fun `a date compares at its millisecond, two instants within one equal and one in the next apart`(): Unit =
        blocking {
            val fixture = millis()
            fixture.assumeBound(PLACED_AT, QueryCapability.RANGE, "a date range")
            standard(DATE_MILLISECOND, semanticDivergences, VALUE_ORDER) {
                val within = arrayOf("first", "second")
                fixture.ids(EqualFilter(PLACED_AT, "2026-01-01T00:00:00.000500Z")).assert()
                    .containsExactlyInAnyOrder(*within)
                fixture.ids(EqualFilter(PLACED_AT, INSTANT)).assert().containsExactlyInAnyOrder(*within)
                fixture.ids(InFilter(PLACED_AT, listOf(LATER))).assert().containsExactly("next")
                fixture.ids(NotEqualFilter(PLACED_AT, "2026-01-01T00:00:00.000900Z")).assert().containsExactly("next")
                fixture.ids(NotInFilter(PLACED_AT, listOf(LATER))).assert().containsExactlyInAnyOrder(*within)
                fixture.ids(GreaterThanFilter(PLACED_AT, FIRST)).assert().containsExactly("next")
                fixture.ids(GreaterThanOrEqualFilter(PLACED_AT, SECOND)).assert()
                    .containsExactlyInAnyOrder("first", "second", "next")
                fixture.ids(LessThanFilter(PLACED_AT, NEXT)).assert().containsExactlyInAnyOrder(*within)
                fixture.ids(LessThanOrEqualFilter(PLACED_AT, FIRST)).assert().containsExactlyInAnyOrder(*within)
                fixture.ids(BetweenFilter(PLACED_AT, SECOND, SECOND)).assert().containsExactlyInAnyOrder(*within)
            }
        }

    @Test
    fun `a list sorted by a date orders by instant`(): Unit = blocking {
        val fixture = placed()
        fixture.assumeBound(PLACED_AT, QueryCapability.SORT, "a date sort")
        fixture.ids(MatchAllFilter, listOf(Sort(PLACED_AT, Sort.Direction.ASC))).let { ids ->
            ids.take(SAME_INSTANT.size).assert().containsExactlyInAnyOrder(*SAME_INSTANT)
            ids.last().assert().isEqualTo("later")
        }
        fixture.ids(MatchAllFilter, listOf(Sort(PLACED_AT, Sort.Direction.DESC))).first().assert().isEqualTo("later")
    }

    @Test
    fun `a date's TERMS groups and DISTINCT_COUNT count every text of its instant once`(): Unit = blocking {
        val fixture = placed()
        fixture.assumeBound(PLACED_AT, QueryCapability.AGGREGATE_TERMS, "a date group")
        fixture.aggregate(
            AggregationQuery(groupBy = listOf(AggregationGroup.Terms(PLACED_AT, "day")), metrics = listOf(COUNT)),
        ).map { it.path("n").longValue() }.sorted().assert().containsExactly(1L, 3L)
        assumeDistinctCount(fixture)
        fixture.aggregate(
            AggregationQuery(
                metrics = listOf(AggregationMetric.DistinctCount(AggregationExpression.Field(PLACED_AT), "distinct")),
            ),
        ).single().path("distinct").longValue().assert().isEqualTo(2L)
    }

    @Test
    fun `a RUNNING total over date groups accumulates by instant`(): Unit = blocking {
        val fixture = placed()
        fixture.assumeBound(PLACED_AT, QueryCapability.AGGREGATE_TERMS, "a date group")
        assumeWindow(fixture, WindowFunction.RUNNING)
        fun running(direction: Sort.Direction): Map<Long, Double> = blocking {
            fixture.aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(PLACED_AT, "day")),
                    metrics = listOf(
                        COUNT,
                        AggregationMetric.Derived(
                            "running",
                            DerivedExpression.Running.of("n", Sort(QueryField("day"), direction)),
                        ),
                    ),
                ),
            ).associate { it.path("n").longValue() to it.path("running").doubleValue() }
        }
        // The earlier instant's group holds three records, the later one's one.
        running(Sort.Direction.ASC).assert().containsEntry(3L, 3.0).containsEntry(1L, 4.0)
        running(Sort.Direction.DESC).assert().containsEntry(1L, 1.0).containsEntry(3L, 4.0)
    }

    @Test
    fun `a top-N by a FIRST or LAST date picks the earlier instant`(): Unit = blocking {
        val fixture = placed()
        fixture.assumeBound(STATUS, QueryCapability.AGGREGATE_TERMS, "a status group")
        fixture.assumeBound(PLACED_AT, QueryCapability.AGGREGATE_TERMS, "a FIRST / LAST date")
        fixture.assumeBound(CREATED_AT, QueryCapability.SORT, "a FIRST / LAST order")
        assumeTopN(fixture)
        listOf(false, true).forEach { last ->
            fixture.topOneByEdge(PLACED_AT, last, Sort.Direction.ASC).assert().isEqualTo("earlier")
            fixture.topOneByEdge(PLACED_AT, last, Sort.Direction.DESC).assert().isEqualTo("later")
        }
    }

    // Text where code point order and UTF-16 code unit order part (#6772): a character above U+FFFF, stored as two
    // surrogates, against ones of U+E000 to U+FFFF. Every store orders text by code point, so must every backend.

    private suspend fun texts(): Fixture = given(
        TEXTS.map { (id, text) -> snapshot(id, """{"status":"$text","createdAt":$T0}""") },
    )

    @Test
    fun `a list sorted by a text orders it by code point`(): Unit = blocking {
        val fixture = texts()
        fixture.assumeBound(STATUS, QueryCapability.SORT, "a text sort")
        val ascending = TEXTS.map { it.first }
        fixture.ids(MatchAllFilter, listOf(Sort(STATUS, Sort.Direction.ASC))).assert().isEqualTo(ascending)
        fixture.ids(MatchAllFilter, listOf(Sort(STATUS, Sort.Direction.DESC))).assert().isEqualTo(ascending.reversed())
    }

    @Test
    fun `a range over a text compares it by code point`(): Unit = blocking {
        val fixture = texts()
        fixture.assumeBound(STATUS, QueryCapability.RANGE, "a text range")
        fixture.ids(GreaterThanFilter(STATUS, FULLWIDTH_A)).assert().containsExactly("emoji")
        fixture.ids(LessThanFilter(STATUS, FULLWIDTH_A)).assert().containsExactlyInAnyOrder("ascii", "private")
    }

    @Test
    fun `a top-N by a FIRST or LAST text picks it by code point`(): Unit = blocking {
        val fixture = texts()
        fixture.assumeBound(STATUS, QueryCapability.AGGREGATE_TERMS, "a status group and a FIRST / LAST text")
        fixture.assumeBound(CREATED_AT, QueryCapability.SORT, "a FIRST / LAST order")
        assumeTopN(fixture)
        listOf(false, true).forEach { last ->
            fixture.topOneByEdge(STATUS, last, Sort.Direction.DESC).assert().isEqualTo(EMOJI)
            fixture.topOneByEdge(STATUS, last, Sort.Direction.ASC).assert().isEqualTo("z")
        }
    }

    // A number in mixed JSON widths.

    private suspend fun widths(): Fixture = given(
        listOf("int" to "1", "decimal" to "1.0", "scaled" to "1.00", "two" to "2")
            .map { (id, amount) -> snapshot("w-$id", """{"status":"$id","amount":$amount,"createdAt":$T0}""") },
    )

    @Test
    fun `a number's equality and membership match it in every width`(): Unit = blocking {
        val fixture = widths()
        fixture.assumeBound(AMOUNT, QueryCapability.EXACT_MATCH, "a number operand")
        val one = arrayOf("w-int", "w-decimal", "w-scaled")
        fixture.ids(EqualFilter(AMOUNT, 1)).assert().containsExactlyInAnyOrder(*one)
        fixture.ids(EqualFilter(AMOUNT, BigDecimal("1.0"))).assert().containsExactlyInAnyOrder(*one)
        fixture.ids(InFilter(AMOUNT, listOf(BigDecimal("1.00")))).assert().containsExactlyInAnyOrder(*one)
        fixture.ids(NotEqualFilter(AMOUNT, 1)).assert().containsExactly("w-two")
        fixture.ids(NotInFilter(AMOUNT, listOf(1))).assert().containsExactly("w-two")
    }

    @Test
    fun `a number's TERMS groups and DISTINCT_COUNT count it once in every width`(): Unit = blocking {
        val fixture = widths()
        fixture.assumeBound(AMOUNT, QueryCapability.AGGREGATE_TERMS, "a number group")
        fixture.aggregate(
            AggregationQuery(groupBy = listOf(AggregationGroup.Terms(AMOUNT, "amount")), metrics = listOf(COUNT)),
        ).map { it.path("n").longValue() }.sorted().assert().containsExactly(1L, 3L)
        assumeDistinctCount(fixture)
        fixture.aggregate(
            AggregationQuery(
                metrics = listOf(AggregationMetric.DistinctCount(AggregationExpression.Field(AMOUNT), "distinct")),
            ),
        ).single().path("distinct").longValue().assert().isEqualTo(2L)
    }

    /** Three records grouped by amount and status: `1` twice with status a, `1.0` once with status b. */
    private suspend fun partitions(): Fixture = given(
        listOf(Triple("p1", "1", "a"), Triple("p2", "1", "a"), Triple("p3", "1.0", "b")).map { (id, amount, status) ->
            snapshot(id, """{"status":"$status","amount":$amount,"createdAt":$T0}""")
        },
    )

    private fun Fixture.windowed(window: DerivedExpression): List<Double> = blocking {
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(AMOUNT, "amount"), AggregationGroup.Terms(STATUS, "status")),
                metrics = listOf(COUNT, AggregationMetric.Derived("w", window)),
                sort = listOf(Sort(QueryField("status"), Sort.Direction.ASC)),
            ),
        ).map { it.path("w").doubleValue() }
    }

    @Test
    fun `TOTAL, RUNNING and RANK within a number partition its widths together`(): Unit = blocking {
        val fixture = partitions()
        fixture.assumeBound(AMOUNT, QueryCapability.AGGREGATE_TERMS, "a number group")
        fixture.assumeBound(STATUS, QueryCapability.AGGREGATE_TERMS, "a status group")
        val byStatus = Sort(QueryField("status"), Sort.Direction.ASC)
        val windows = listOf(
            WindowFunction.TOTAL to (DerivedExpression.Total.of("n", listOf("amount")) to listOf(3.0, 3.0)),
            WindowFunction.RUNNING to
                (DerivedExpression.Running.of("n", byStatus, listOf("amount")) to listOf(2.0, 3.0)),
            WindowFunction.RANK to (DerivedExpression.Rank.of("n", listOf("amount")) to listOf(1.0, 2.0)),
        ).filter { (function, _) -> fixture.schema.storage.aggregation.window(function) != SupportMode.NONE }
        Assumptions.assumeTrue(windows.isNotEmpty()) { "Skipped: the storage declares no TOTAL, RUNNING or RANK." }
        windows.forEach { (function, case) ->
            fixture.windowed(case.first).assert().describedAs("$function").containsExactlyElementsOf(case.second)
        }
    }

    @Test
    fun `PREVIOUS pairs a group with the earlier one whose number is the same in another width`(): Unit = blocking {
        val fixture = given(
            listOf(
                snapshot("q1", """{"amount":1,"createdAt":${T0 + HOUR}}"""),
                snapshot("q2", """{"amount":1.0,"createdAt":${T0 + DAY + HOUR}}"""),
            ),
        )
        fixture.assumeBound(AMOUNT, QueryCapability.AGGREGATE_TERMS, "a number group")
        fixture.assumeBound(CREATED_AT, QueryCapability.AGGREGATE_TEMPORAL, "a date histogram")
        fixture.assumeBound(CREATED_AT, QueryCapability.RANGE, "a period")
        assumeWindow(fixture, WindowFunction.PREVIOUS)
        fixture.aggregate(
            AggregationQuery(
                filter = AndFilter(
                    listOf(GreaterThanOrEqualFilter(CREATED_AT, T0 + DAY), LessThanFilter(CREATED_AT, T0 + 2 * DAY)),
                ),
                groupBy = listOf(
                    AggregationGroup.Terms(AMOUNT, "amount"),
                    AggregationGroup.DateHistogram(CREATED_AT, "day", AggregationDateUnit.DAY),
                ),
                metrics = listOf(
                    COUNT,
                    AggregationMetric.Derived("previous", DerivedExpression.Previous.of("n", "day")),
                ),
            ),
        ).single().path("previous").doubleValue().assert().isEqualTo(1.0)
    }

    @Test
    fun `a top-N by a FIRST or LAST number orders it by value across widths`(): Unit = blocking {
        // Their texts order the other way: "10" before "9.5".
        val fixture = given(
            listOf(
                snapshot("n-low", """{"status":"a-low","amount":9.5,"createdAt":$T0}"""),
                snapshot("n-high", """{"status":"z-high","amount":10,"createdAt":$T0}"""),
            ),
        )
        fixture.assumeBound(STATUS, QueryCapability.AGGREGATE_TERMS, "a status group")
        fixture.assumeBound(AMOUNT, QueryCapability.AGGREGATE_TERMS, "a FIRST / LAST number")
        fixture.assumeBound(CREATED_AT, QueryCapability.SORT, "a FIRST / LAST order")
        assumeTopN(fixture)
        listOf(false, true).forEach { last ->
            fixture.topOneByEdge(AMOUNT, last, Sort.Direction.ASC).assert().isEqualTo("a-low")
            fixture.topOneByEdge(AMOUNT, last, Sort.Direction.DESC).assert().isEqualTo("z-high")
        }
    }

    private fun assumeWindow(fixture: Fixture, function: WindowFunction) =
        assumeSupported(fixture.schema.storage.aggregation.window(function), "storage.aggregation.windows", "$function")

    private fun assumeDistinctCount(fixture: Fixture) = assumeSupported(
        fixture.schema.storage.aggregation.distinctCount,
        "storage.aggregation.distinctCount",
        "DISTINCT_COUNT",
    )

    private fun assumeTopN(fixture: Fixture) {
        assumeSupported(fixture.schema.storage.aggregation.firstLast, "storage.aggregation.firstLast", "FIRST / LAST")
        assumeSupported(fixture.schema.storage.aggregation.topN, "storage.aggregation.topN", "a top-N")
    }

    /** The status of the one group a top-N by the FIRST or LAST [field] (by `createdAt`) in [direction] keeps. */
    private fun Fixture.topOneByEdge(
        field: QueryField<*>,
        last: Boolean,
        direction: Sort.Direction,
    ): String = blocking {
        val edge = if (last) {
            AggregationMetric.Last(field, "edge", CREATED_AT)
        } else {
            AggregationMetric.First(field, "edge", CREATED_AT)
        }
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(STATUS, "status")),
                metrics = listOf(edge),
                sort = listOf(Sort(QueryField("edge"), direction)),
                limit = 1,
            ),
        ).single().path("status").stringValue()
    }

    companion object {
        /**
         * The case id of a date's equality and membership by instant, for [semanticDivergences]: a store that reads an
         * operand without a fraction as its whole second matches a record a millisecond later.
         */
        const val DATE_EQUALITY = "date.equality-by-instant"

        /**
         * The case id of a date's range by instant, for [semanticDivergences]: a store that rounds an inclusive upper
         * bound without a fraction up to the end of its second holds a record a millisecond later.
         */
        const val DATE_RANGE = "date.range-by-instant"

        /**
         * The case id of a date compared at its millisecond, for [semanticDivergences]: on every store, an operand and
         * a stored instant within one millisecond are equal, and a range holds the operand's millisecond whole.
         */
        const val DATE_MILLISECOND = "date.millisecond"

        private const val INSTANT = "2026-01-01T00:00:00Z"
        private const val LATER = "2026-01-01T00:00:00.001Z"
        private const val FIRST = "2026-01-01T00:00:00.000200Z"
        private const val SECOND = "2026-01-01T00:00:00.000700Z"
        private const val NEXT = "2026-01-01T00:00:00.001100Z"
        private const val HOUR = 3_600_000L
        private val SAME_INSTANT = arrayOf("fraction", "offset", "utc")
        private val PLACED_AT = QueryField("state.placedAt")
        private val STATUS = QueryField("state.status")
        private val CREATED_AT = QueryField("state.createdAt")
        private val COUNT = AggregationMetric.Count("n")

        private const val FULLWIDTH_A = "\uFF21"
        private const val EMOJI = "\uD83D\uDE00"

        /** Each record's id and its text, in code point order: `Ａ` (U+FF21) before `😀` (U+1F600). */
        private val TEXTS = listOf("ascii" to "z", "private" to "\uE000", "fullwidth" to FULLWIDTH_A, "emoji" to EMOJI)
    }
}
