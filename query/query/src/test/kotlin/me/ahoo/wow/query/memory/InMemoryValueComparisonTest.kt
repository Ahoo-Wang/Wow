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
package me.ahoo.wow.query.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * How the core compares two values wherever it does so itself, the in-memory backend and the residual windows and
 * top-N: a number by its exact value, whatever its width, and a string its field reads as a date by its instant, in
 * equality, grouping, partitioning and ordering alike. A plain string keeps its characters. Each case runs a real query
 * through admission over records of its own.
 */
internal class InMemoryValueComparisonTest : InMemoryReferenceFixtures() {
    private fun backendOf(rows: List<ObjectNode>) =
        InMemoryQueryBackend(backend.aggregateType, { rows.map(ObjectNode::deepCopy) }, Duration.ZERO)

    private fun rows(vararg json: String): List<ObjectNode> = json.map { WowJson.mapper.readTree(it) as ObjectNode }

    private fun aggregate(rows: List<ObjectNode>, query: AggregationQuery): List<ObjectNode> = runBlocking {
        backendOf(rows).aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
    }

    // A date's equality and grouping (#6280): one instant written three ways, and one a millisecond later.

    private val dates = linkedMapOf(
        "utc" to INSTANT,
        "offset" to "2026-01-01T01:00:00+01:00",
        "fraction" to "2026-01-01T00:00:00.000Z",
        "later" to LATER,
    ).map { (id, date) ->
        WowJson.mapper.createObjectNode().put("id", id).put("tenant_id", "t1").put("day", date).put("name", date)
    }

    private fun idsOf(filter: FilterExpression): List<String> = runBlocking {
        val query = ListQuery(filter = filter, sort = listOf(Sort(QueryField("id"), Sort.Direction.ASC)))
        backendOf(dates).stream(QueryAdmission.Trusted.list(query, schema)).toList().map { it.path("id").stringValue() }
    }

    @Test
    fun `a date's equality and membership match every text of its instant`() {
        idsOf(EqualFilter(DAY, INSTANT)).assert().containsExactly("fraction", "offset", "utc")
        idsOf(InFilter(DAY, listOf(INSTANT))).assert().containsExactly("fraction", "offset", "utc")
        idsOf(NotEqualFilter(DAY, INSTANT)).assert().containsExactly("later")
        idsOf(NotInFilter(DAY, listOf(INSTANT))).assert().containsExactly("later")
        // As its range always did; a millisecond apart stays apart.
        idsOf(BetweenFilter(DAY, INSTANT, INSTANT)).assert().containsExactly("fraction", "offset", "utc")
        idsOf(EqualFilter(DAY, LATER)).assert().containsExactly("later")
    }

    @Test
    fun `a plain string's equality keeps its characters`() {
        idsOf(EqualFilter(NAME, INSTANT)).assert().containsExactly("utc")
    }

    @Test
    fun `a date's terms group and a metric filter on it count every text of its instant`() {
        fun counts(field: QueryField<*>) = aggregate(
            dates,
            AggregationQuery(groupBy = listOf(AggregationGroup.Terms(field, "value")), metrics = listOf(COUNT)),
        ).map { it.path("n").longValue() }.sorted()
        counts(DAY).assert().containsExactly(1L, 3L)
        counts(NAME).assert().containsExactly(1L, 1L, 1L, 1L)
        aggregate(
            dates,
            AggregationQuery(metrics = listOf(AggregationMetric.Count("n", filter = EqualFilter(DAY, INSTANT)))),
        ).single().path("n").longValue().assert().isEqualTo(3L)
    }

    // A date's order in aggregation output (#6276): two instants a millisecond apart, whose texts order the other way.

    private val instants = rows(
        """{"name":"earlier","day":"$INSTANT","at":1}""",
        """{"name":"later","day":"$LATER","at":1}""",
    )

    private fun topOneByEdge(rows: List<ObjectNode>, field: QueryField<*>, last: Boolean): String = aggregate(
        rows,
        AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(NAME, "name")),
            metrics = listOf(
                if (last) AggregationMetric.Last(field, "edge", AT) else AggregationMetric.First(field, "edge", AT),
            ),
            sort = listOf(Sort(QueryField("edge"), Sort.Direction.ASC)),
            limit = 1,
        ),
    ).single().path("name").stringValue()

    @Test
    fun `a top-N by a FIRST or LAST date picks the earlier instant`() {
        topOneByEdge(instants, DAY, last = false).assert().isEqualTo("earlier")
        topOneByEdge(instants, DAY, last = true).assert().isEqualTo("earlier")
        // A plain string orders by its characters: "earlier" before "later".
        topOneByEdge(instants, NAME, last = false).assert().isEqualTo("earlier")
    }

    @Test
    fun `a date's terms groups sort by instant`() {
        aggregate(
            instants,
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(DAY, "day")),
                metrics = listOf(COUNT),
                sort = listOf(Sort(QueryField("day"), Sort.Direction.ASC)),
            ),
        ).map { it.path("day").stringValue() }.assert().containsExactly(INSTANT, LATER)
    }

    @Test
    fun `a RUNNING total over date groups accumulates by instant, its ties broken by instant`() {
        fun running(order: String, direction: Sort.Direction): Map<String, Double> = aggregate(
            instants,
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(DAY, "day")),
                metrics = listOf(
                    COUNT,
                    AggregationMetric.Derived(
                        "running",
                        DerivedExpression.Running.of("n", Sort(QueryField(order), direction)),
                    ),
                ),
            ),
        ).associate { it.path("day").stringValue() to it.path("running").doubleValue() }
        running("day", Sort.Direction.ASC).assert().containsEntry(INSTANT, 1.0).containsEntry(LATER, 2.0)
        running("day", Sort.Direction.DESC).assert().containsEntry(INSTANT, 2.0).containsEntry(LATER, 1.0)
        // Both counts are 1: the day breaks the tie, ascending by instant.
        running("n", Sort.Direction.ASC).assert().containsEntry(INSTANT, 1.0).containsEntry(LATER, 2.0)
    }

    // A window's partition by a number (#6273): 1 and 1.0 are one key.

    private val widths = rows(
        """{"price":1,"name":"a"}""",
        """{"price":1,"name":"a"}""",
        """{"price":1.0,"name":"b"}""",
    )

    private fun windowed(rows: List<ObjectNode>, window: DerivedExpression): List<Double> = aggregate(
        rows,
        AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(PRICE, "price"), AggregationGroup.Terms(NAME, "name")),
            metrics = listOf(COUNT, AggregationMetric.Derived("w", window)),
            sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC)),
        ),
    ).map { it.path("w").doubleValue() }

    @Test
    fun `TOTAL, RUNNING and RANK within a number partition 1 and 1_0 together`() {
        windowed(widths, DerivedExpression.Total.of("n", listOf("price"))).assert().containsExactly(3.0, 3.0)
        val byName = Sort(QueryField("name"), Sort.Direction.ASC)
        windowed(widths, DerivedExpression.Running.of("n", byName, listOf("price"))).assert().containsExactly(2.0, 3.0)
        windowed(widths, DerivedExpression.Rank.of("n", listOf("price"))).assert().containsExactly(1.0, 2.0)
        // As with one width throughout.
        val one = rows("""{"price":1,"name":"a"}""", """{"price":1,"name":"a"}""", """{"price":1,"name":"b"}""")
        windowed(one, DerivedExpression.Total.of("n", listOf("price"))).assert().containsExactly(3.0, 3.0)
    }

    @Test
    fun `PREVIOUS pairs a group with the earlier one whose other key is the same number in another width`() {
        val day = SECONDS_A_DAY
        val jan5 = JAN_5 / MILLIS_A_SECOND
        val result = aggregate(
            rows("""{"price":1,"at":${jan5 + 3600}}""", """{"price":1.0,"at":${jan5 + day + 3600}}"""),
            AggregationQuery(
                filter = AndFilter(
                    listOf(GreaterThanOrEqualFilter(AT, jan5 + day), LessThanFilter(AT, jan5 + 2 * day)),
                ),
                groupBy = listOf(
                    AggregationGroup.Terms(PRICE, "price"),
                    AggregationGroup.DateHistogram(AT, "day", AggregationDateUnit.DAY),
                ),
                metrics = listOf(
                    COUNT,
                    AggregationMetric.Derived("previous", DerivedExpression.Previous.of("n", "day")),
                ),
            ),
        )
        result.single().path("previous").doubleValue().assert().isEqualTo(1.0)
    }

    // A residual top-N by an exact number (#6274): decimals that a double cannot tell apart, integers beyond a long.

    private fun topOneByPrice(low: String, high: String, last: Boolean): String = topOneByEdge(
        rows("""{"name":"z-low","price":$low,"at":1}""", """{"name":"a-high","price":$high,"at":1}"""),
        PRICE,
        last,
    )

    @Test
    fun `a top-N by a FIRST or LAST number compares it exactly`() {
        listOf(false, true).forEach { last ->
            topOneByPrice("1.00000000000000000001", "1.00000000000000000002", last).assert().isEqualTo("z-low")
            topOneByPrice("9223372036854775807", "9223372036854775808", last).assert().isEqualTo("z-low")
            topOneByPrice("1", "2", last).assert().isEqualTo("z-low")
        }
    }

    private companion object {
        const val INSTANT = "2026-01-01T00:00:00Z"
        const val LATER = "2026-01-01T00:00:00.001Z"
        const val SECONDS_A_DAY = 86_400L
        const val MILLIS_A_SECOND = 1_000L
        val COUNT = AggregationMetric.Count("n")
    }
}
