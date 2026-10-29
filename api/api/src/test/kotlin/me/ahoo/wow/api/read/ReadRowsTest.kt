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

package me.ahoo.wow.api.read

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import org.junit.jupiter.api.Test

/** The rows of the typed read API are plain values (decisions D59, D227), and its reified entries name their class. */
class ReadRowsTest {
    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")
    private val operator = Operator.of("alice", "tenant")

    @Test
    fun `every row should be a value`() {
        valueSemantics(
            { Snapshot.of(ref, 1, Scope("alice"), mapOf("t" to listOf("a")), false, operator, 1, 2, "s") },
            Snapshot.of(ref, 2, Scope.NONE, emptyMap(), true, null, 1, 2, "s"),
        )
        val due = mapOf("remind" to ScheduleRule.At.of(5))
        val firstOperated = { Snapshot.of(ref, 1, Scope("alice"), emptyMap(), false, operator, 1, 2, "s", operator, due) }
        valueSemantics(firstOperated, Snapshot.of(ref, 1, Scope("alice"), emptyMap(), false, operator, 1, 2, "s", null, due))
        valueSemantics(
            firstOperated,
            Snapshot.of(ref, 1, Scope("alice"), emptyMap(), false, operator, 1, 2, "s", operator, emptyMap()),
        )
        val event = CommittedEvent.of("placed", 1, mapOf("a" to 1))
        valueSemantics({ CommittedEvent.of("placed", 1, mapOf("a" to 1)) }, CommittedEvent.of("placed", 2, "b"))
        valueSemantics(
            { EventStream.of(ref, 1, 3, "c", "r", "sales.order.Place", Scope.NONE, operator, listOf(event)) },
            EventStream.of(ref, 2, 3, "c", "r", "sales.order.Place", Scope.NONE, null, emptyList()),
        )
        valueSemantics({ Positioned.of("row", "p-1") }, Positioned.of("row", null))
    }

    @OptIn(ExperimentalWowApi::class)
    @Test
    fun `the reified entries should pass their type argument's class`() {
        val snapshots = mockk<TenantSnapshotQueries<String>>()
        val rows = mockk<RowQueries<Map<*, *>>>()
        val queries = mockk<Queries> {
            every { snapshots(String::class.java) } returns snapshots
            every { datasets(DatasetName.of("sales", "orders"), Map::class.java) } returns rows
        }
        queries.snapshots<String>().assert().isSameAs(snapshots)
        queries.datasets<Map<*, *>>(DatasetName.of("sales", "orders")).assert().isSameAs(rows)
        every { snapshots.dynamic(Map::class.java) } returns rows
        snapshots.dynamic<Map<*, *>>().assert().isSameAs(rows)
        val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
        val groups = emptyFlow<Map<*, *>>()
        coEvery { rows.aggregate(query, Map::class.java) } returns groups
        rows.aggregate<Map<*, *>>(query).assert().isSameAs(groups)
    }

    private fun valueSemantics(make: () -> Any, other: Any) {
        val value = make()
        val same = make()
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
