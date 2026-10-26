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

package me.ahoo.wow.postgres.timer

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.tck.timer.TimerStoreSpec
import org.junit.jupiter.api.Test

class PostgresTimerStoreTest : TimerStoreSpec() {
    /** A context of each test's own: the due read takes every aggregate type of the context. */
    override val context: String = PostgresFixture.newContext("timer")

    override fun createStore(): TimerStore = store(context)

    @Test
    fun `creating the schema twice is harmless`() {
        store("timer-twice")
        store("timer-twice")
    }

    @Test
    fun `an aggregate type's table is created on its first timer, named after its context and type`(): Unit =
        runBlocking {
            val ref = AggregateType(context, "Order.Line").ref("t", "o-1")
            store(context).put(timer(ref)).assert().isTrue()
            PostgresTimerTables(context).aggregate("Order.Line").base.assert()
                .isEqualTo("wow_timer__${context.replace('-', '_')}__order_line")
            store(context).find(ref, "k").assert().isEqualTo(timer(ref))
        }

    @Test
    fun `a table recorded in another format is refused`(): Unit = runBlocking {
        val ref = AggregateType(context, "order").ref("t", "o-1")
        store(context).put(timer(ref))
        PostgresFixture.bumpFormat(PostgresTimerTables(context).aggregate("order").base)
        assertThrownBy<IllegalStateException> { runBlocking { store(context).find(ref, "k") } }
    }

    @Test
    fun `a table of format 4, one per context, is refused`(): Unit = runBlocking {
        val ref = AggregateType(context, "order").ref("t", "o-1")
        val base = PostgresTimerTables(context).aggregate("order").base
        store(context).put(timer(ref))
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE ${PostgresFormat.TABLE} SET version = 4 WHERE structure = ?").use {
                it.setString(1, base)
                it.executeUpdate().assert().isEqualTo(1)
            }
        }
        assertThrownBy<IllegalStateException> { runBlocking { store(context).find(ref, "k") } }
    }

    @Test
    fun `a timer of an aggregate of another context is refused`() {
        val ref = AggregateType("elsewhere", "order").ref("t", "o-1")
        assertThrownBy<IllegalArgumentException> { runBlocking { store(context).put(timer(ref)) } }
    }

    @Test
    fun `the due read takes each type's table over its partial index`(): Unit = runBlocking {
        // Decision D308: no ABAB for the read across types; its plan shows each branch over its table's due index.
        val types = listOf("order", "invoice", "shipment")
        types.forEach { store(context).put(timer(AggregateType(context, it).ref("t", "$it-1"))) }
        val tables = PostgresTimerTables(context)
        val known = types.map(tables::aggregate)
        val plan = PostgresFixture.dataSource.connection.use { connection ->
            // The tables are tiny: the planner would scan them whole, which says nothing of the index's fitness.
            connection.createStatement().use { it.execute("SET enable_seqscan = off") }
            connection.prepareStatement("EXPLAIN (FORMAT JSON) ${tables.due(known)}").use { query ->
                query.setString(1, context)
                var index = 2
                known.forEach { _ ->
                    query.setArray(index, connection.createArrayOf("integer", arrayOf(0, 1, 2, 3)))
                    query.setLong(index + 1, Long.MAX_VALUE)
                    query.setInt(index + 2, 100)
                    index += 3
                }
                query.setInt(index, 100)
                query.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        }
        val scans = WowJson.mapper.readTree(plan).findValues("Relation Name").map { it.asString() }
        val indexes = WowJson.mapper.readTree(plan).findValues("Index Name").map { it.asString() }
        known.forEach { table ->
            scans.assert().contains(table.base)
            indexes.assert().contains(table.due)
        }
    }

    private fun timer(ref: AggregateRef) = Timer(
        id = "$ref#k@1",
        owner = ref,
        key = "k",
        version = 1,
        partition = Partitioner.partitionOf(ref, logPartitions),
        rule = ScheduleRule.At(5),
        dueAt = 5,
        target = ref,
        commandType = "${ref.type.context}.order.Cancel",
        body = "{}",
    )

    private companion object {
        fun store(context: String) =
            PostgresTimerStore(PostgresFixture.dataSource, context).also { it.createSchema() }
    }
}
