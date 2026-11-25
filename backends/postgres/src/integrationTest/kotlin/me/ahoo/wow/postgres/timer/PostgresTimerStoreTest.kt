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

    @Test
    fun `timers whose rule a later version wrote should be skipped by the due read, never starving the readable`(): Unit =
        runBlocking {
            val store = store(context)
            val type = AggregateType(context, "order")
            // More unreadable timers than the page holds, all due before more readable ones than it holds, all of one
            // partition: the read goes on past the unreadable ones, and stops at a page of the readable.
            val partition = Partitioner.partitionOf(type.ref("t", "o-0"), logPartitions)
            val same = generateSequence(0) { it + 1 }.map { type.ref("t", "o-$it") }
                .filter { Partitioner.partitionOf(it, logPartitions) == partition }.take(2 * (PAGE + 1)).toList()
            val ahead = same.take(PAGE + 1)
            val behind = same.drop(PAGE + 1)
            ahead.forEachIndexed { at, ref -> store.put(timer(ref, dueAt = at + 1L)).assert().isTrue() }
            behind.forEachIndexed { at, ref -> store.put(timer(ref, dueAt = 100L + at)).assert().isTrue() }
            val table = PostgresTimerTables(context).aggregate("order").table
            PostgresFixture.dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE $table SET rule = ?::jsonb WHERE due_at < 100").use {
                    it.setString(1, LATER_RULE)
                    it.executeUpdate().assert().isEqualTo(ahead.size)
                }
            }

            val partitions = (0 until logPartitions).toSet()
            store.due(partitions, until = 1_000, perPartition = PAGE).map { it.owner }.assert()
                .containsExactlyElementsOf(behind.take(PAGE))
            PostgresTimerStore.unknown.seen.assert().contains("rule.misfire=later")
            // One timer read by its key is refused, naming the value.
            assertThrownBy<IllegalStateException> { runBlocking { store.find(ahead.first(), "k") } }
                .message().contains("later")
        }

    @Test
    fun `a read past skipped timers should stop where the due rows end`(): Unit = runBlocking {
        val store = store(context)
        val type = AggregateType(context, "order")
        // A page filled by unreadable timers, then fewer readable ones behind them than the page holds.
        val partition = Partitioner.partitionOf(type.ref("t", "o-0"), logPartitions)
        val same = generateSequence(0) { it + 1 }.map { type.ref("t", "o-$it") }
            .filter { Partitioner.partitionOf(it, logPartitions) == partition }.take(PAGE + 2).toList()
        same.forEachIndexed { at, ref -> store.put(timer(ref, dueAt = at + 1L)).assert().isTrue() }
        val table = PostgresTimerTables(context).aggregate("order").table
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE $table SET rule = ?::jsonb WHERE due_at <= ?").use {
                it.setString(1, LATER_RULE)
                it.setLong(2, PAGE + 1L)
                it.executeUpdate().assert().isEqualTo(PAGE + 1)
            }
        }

        store.due((0 until logPartitions).toSet(), until = 1_000, perPartition = PAGE).map { it.owner }.assert()
            .containsExactly(same.last())
    }

    @Test
    fun `a parked timer whose rule a later version wrote should be skipped by the parked read`(): Unit = runBlocking {
        val store = store(context)
        val type = AggregateType(context, "invoice")
        val target = type.ref("t", "target")
        val readable = type.ref("t", "readable")
        val later = type.ref("t", "later")
        listOf(readable, later).forEach { store.put(timer(it, target = target)).assert().isTrue() }
        val table = PostgresTimerTables(context).aggregate("invoice").table
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE $table SET status = 'parked'").use { it.executeUpdate() }
            connection.prepareStatement("UPDATE $table SET rule = ?::jsonb WHERE aggregate_id = ?").use {
                it.setString(1, LATER_TYPE)
                it.setString(2, later.id)
                it.executeUpdate().assert().isEqualTo(1)
            }
        }

        store.parked(target).map { it.owner }.assert().containsExactly(readable)
        PostgresTimerStore.unknown.seen.assert().contains("rule.type=later")
    }

    private fun timer(ref: AggregateRef, dueAt: Long = 5, target: AggregateRef = ref) = Timer(
        id = "$ref#k@1",
        owner = ref,
        key = "k",
        version = 1,
        partition = Partitioner.partitionOf(ref, logPartitions),
        rule = ScheduleRule.At(dueAt),
        dueAt = dueAt,
        target = target,
        commandType = "${ref.type.context}.order.Cancel",
        body = "{}",
    )

    private companion object {
        /** The due read's page of a partition, which the unreadable timers outnumber. */
        const val PAGE = 2

        /** A cron rule of a later version: a misfire this one does not know. */
        const val LATER_RULE = """{"type":"cron","expression":"0 0 * * * *","timeZone":"UTC","misfire":"later"}"""

        /** A rule of a type a later version adds. */
        const val LATER_TYPE = """{"type":"later","at":5}"""

        fun store(context: String) =
            PostgresTimerStore(PostgresFixture.dataSource, context).also { it.createSchema() }
    }
}
