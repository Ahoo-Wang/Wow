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

package me.ahoo.wow.postgres.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.CountedDataSource
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.tck.subscription.CheckpointStoreSpec
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.time.Duration.Companion.seconds

class PostgresCheckpointStoreTest : CheckpointStoreSpec() {
    override fun createStore(): CheckpointStore = store

    @Test
    fun `creating the schema twice is harmless`() {
        PostgresCheckpointStore(PostgresFixture.dataSource).createSchema()
        store.assert().isNotNull()
    }

    @Test
    fun `the checkpoint and lease tables record their format`() {
        // Finding A2-13: a layout change went unnoticed until a statement failed on a missing column.
        store.assert().isNotNull()
        PostgresLeaseStore(PostgresFixture.dataSource).createSchema()
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT structure, version FROM wow_format WHERE structure IN " +
                    "('wow_checkpoint', 'wow_lease', 'wow_lease_member') ORDER BY structure",
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getInt(2)) }
                }
            }
        }.assert().containsExactly("wow_checkpoint" to 1, "wow_lease" to 1, "wow_lease_member" to 1)
    }

    @Test
    fun `a batch of checkpoints is one transaction and one commit`(): Unit = runBlocking {
        // B.O-5: in autocommit, each upsert of the JDBC batch was a transaction, and a WAL flush, of its own.
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val batched = PostgresCheckpointStore(counted.dataSource)
        val group = "batch-${System.nanoTime()}"
        val mark = counted.begin()
        val commits = counted.commits
        // Every row is new: the insert adds them all, the update moves none.
        batched.saveAll(group, (0 until 8).map { Checkpoint.of("snapshot", it, 10L + it) })
        counted.since(mark).map { it.substringBefore(' ') }.assert().containsExactly("INSERT", "UPDATE")
        (counted.commits - commits).assert().isEqualTo(1)
        (0 until 8).map { batched.load(group, "snapshot", it) }.assert().isEqualTo((0 until 8).map { 10L + it })
        // A batch moves positions forward only, in one commit again.
        batched.saveAll(group, listOf(Checkpoint.of("snapshot", 0, 99), Checkpoint.of("snapshot", 1, 5)))
        (counted.commits - commits).assert().isEqualTo(2)
        batched.load(group, "snapshot", 0).assert().isEqualTo(99L)
        batched.load(group, "snapshot", 1).assert().isEqualTo(11L)
    }

    @Test
    fun `a batch of rows that exist and move is an insert that adds none and one plain update`(): Unit = runBlocking {
        // The common save: every row there and behind. No ON CONFLICT DO UPDATE, whose row lock is never taken.
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val batched = PostgresCheckpointStore(counted.dataSource)
        val group = "move-${System.nanoTime()}"
        batched.saveAll(group, (0 until 64).map { Checkpoint.of("saga", it, 1) })
        val mark = counted.begin()
        batched.saveAll(group, (0 until 64).map { Checkpoint.of("saga", it, 2) })
        counted.since(mark).map { it.substringBefore(' ') }.assert().containsExactly("INSERT", "UPDATE")
        (0 until 64).map { batched.load(group, "saga", it) }.assert().allMatch { it == 2L }
    }

    @Test
    fun `a row the guard refuses stays, one that is absent is inserted, one behind moves, in one save`(): Unit =
        runBlocking {
            // The insert adds the absent and leaves the others; the update moves the row behind and leaves the refused.
            val counted = CountedDataSource(PostgresFixture.dataSource)
            val batched = PostgresCheckpointStore(counted.dataSource)
            val group = "mixed-${System.nanoTime()}"
            batched.saveAll(group, listOf(Checkpoint.of("s", 0, 5), Checkpoint.of("s", 1, 5)))
            val mark = counted.begin()
            batched.saveAll(
                group,
                listOf(Checkpoint.of("s", 0, 9), Checkpoint.of("s", 1, 3), Checkpoint.of("s", 2, 4)),
            )
            counted.since(mark).map { it.substringBefore(' ') }.assert().containsExactly("INSERT", "UPDATE")
            batched.load(group, "s", 0).assert().isEqualTo(9L)
            batched.load(group, "s", 1).assert().isEqualTo(5L)
            batched.load(group, "s", 2).assert().isEqualTo(4L)
        }

    @Test
    fun `a batch that names a key twice keeps its furthest position`(): Unit = runBlocking {
        // One statement may touch a row once: the batch keeps one row per key, the furthest.
        val group = "twice-${System.nanoTime()}"
        store.saveAll(group, listOf(Checkpoint.of("s", 0, 7), Checkpoint.of("s", 0, 9), Checkpoint.of("s", 0, 8)))
        store.load(group, "s", 0).assert().isEqualTo(9L)
    }

    @Test
    fun `a batch of any size is the same two statements and one commit`(): Unit = runBlocking {
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val batched = PostgresCheckpointStore(counted.dataSource)
        val group = "large-${System.nanoTime()}"
        val rows = 2_005
        val mark = counted.begin()
        val commits = counted.commits
        batched.saveAll(group, (0 until rows).map { Checkpoint.of("large", it, it.toLong()) })
        // Arrays, not a row's parameters each: one insert and one update, whatever the rows.
        counted.since(mark).size.assert().isEqualTo(2)
        (counted.commits - commits).assert().isEqualTo(1)
        listOf(0, 1_000, rows - 1).forEach {
            batched.load(group, "large", it).assert().isEqualTo(it.toLong())
        }
    }

    @Test
    fun `concurrent batches over the same keys in opposite orders never deadlock`(): Unit = runBlocking {
        // The rows go in key order whatever order the caller gives them, so two batches lock them in one order.
        val group = "order-${System.nanoTime()}"
        val keys = (0 until KEYS)
        repeat(ROUNDS) { round ->
            val forward = keys.map { Checkpoint.of("order", it, round * 2L + 1) }
            val backward = keys.reversed().map { Checkpoint.of("order", it, round * 2L + 2) }
            listOf(forward, backward).map { batch ->
                async(Dispatchers.IO) { store.saveAll(group, batch) }
            }.awaitAll()
        }
        keys.map { store.load(group, "order", it) }.assert().allMatch { it == ROUNDS * 2L }
    }

    @Test
    fun `two saves each ahead on the row the other is behind on never wait on each other`(): Unit = runBlocking {
        // The cycle an update followed by an upsert met: stored k1 = 5, k2 = 5; A saves {k1: 9, k2: 3},
        // B {k1: 3, k2: 9}.
        // What makes this outcome certain: the statements run here, on two connections, in the order that crossed
        // before, each in turn. Each completes at once: the insert takes no lock on a row that exists, and the update
        // locks only the row it moves, never the one the guard refuses, so neither waits for the other (a wait would
        // fail on the lock timeout instead).
        val group = "crossed-${System.nanoTime()}"
        store.saveAll(group, listOf(Checkpoint.of("s", 1, 5), Checkpoint.of("s", 2, 5)))
        val a = listOf(Checkpoint.of("s", 1, 9), Checkpoint.of("s", 2, 3))
        val b = listOf(Checkpoint.of("s", 1, 3), Checkpoint.of("s", 2, 9))
        PostgresFixture.dataSource.connection.use { first ->
            PostgresFixture.dataSource.connection.use { second ->
                listOf(first, second).forEach { connection ->
                    connection.autoCommit = false
                    connection.createStatement().use { it.execute("SET LOCAL lock_timeout = '2s'") }
                }
                run(first, PostgresCheckpointStore.ADD, group, a)
                run(second, PostgresCheckpointStore.ADD, group, b)
                run(first, PostgresCheckpointStore.MOVE, group, a)
                run(second, PostgresCheckpointStore.MOVE, group, b)
                first.commit()
                second.commit()
            }
        }
        store.load(group, "s", 1).assert().isEqualTo(9L)
        store.load(group, "s", 2).assert().isEqualTo(9L)
    }

    @Test
    fun `a row another save inserts while this one runs is moved if this one is ahead`(): Unit = runBlocking {
        // B inserts k at 3 and holds its transaction open; A saves k at 9. What makes the outcome certain: A is seen
        // waiting on B's insert (a transaction lock, in pg_stat_activity) before B commits, so A's insert meets B's row
        // as a conflict and leaves it, and A's update, after it, finds it and moves it to 9. An update before the
        // insert would have found no row, and the insert left B's 3.
        val group = "raced-${System.nanoTime()}"
        PostgresFixture.dataSource.connection.use { other ->
            other.autoCommit = false
            run(other, PostgresCheckpointStore.ADD, group, listOf(Checkpoint.of("s", 1, 3)))
            val saving = async(Dispatchers.IO) { store.saveAll(group, listOf(Checkpoint.of("s", 1, 9))) }
            withTimeout(10.seconds) { while (!insertWaiting()) delay(5) }
            other.commit()
            saving.await()
        }
        store.load(group, "s", 1).assert().isEqualTo(9L)
    }

    /** Whether a checkpoint insert waits on another transaction's row now. */
    private fun insertWaiting(): Boolean = PostgresFixture.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND wait_event = 'transactionid' " +
                "AND query LIKE 'INSERT INTO ${PostgresCheckpointStore.TABLE}%'",
        ).use { query -> query.executeQuery().use { rows -> rows.next() && rows.getInt(1) > 0 } }
    }

    @Test
    fun `concurrent saves crossed on every other key all commit and leave the highest`(): Unit = runBlocking {
        // A smoke over the crossed shape at the store's own pace: an update followed by an upsert deadlocked here on
        // some rounds (40P01). What is certain is the outcome, the highest per key; that no round deadlocks is the
        // ordered lock pass's, which the test above shows step by step.
        val group = "crossed-pace-${System.nanoTime()}"
        repeat(ROUNDS) { round ->
            val high = round * 2L + 2
            val even = (0 until KEYS).map { Checkpoint.of("x", it, if (it % 2 == 0) high else high - 1) }
            val odd = (0 until KEYS).map { Checkpoint.of("x", it, if (it % 2 == 1) high else high - 1) }
            listOf(even, odd).map { batch -> async(Dispatchers.IO) { store.saveAll(group, batch) } }.awaitAll()
        }
        (0 until KEYS).map { store.load(group, "x", it) }.assert().allMatch { it == ROUNDS * 2L }
    }

    @Test
    fun `the update probes the group's rows with the batch, so it locks them in the batch's key order`() {
        // Its lock order is the order of the side it probes with: the arrays, in key order. A plan that scanned the
        // table first would lock in the table's order instead; this pins the one the order argument rests on.
        val group = "plan-${System.nanoTime()}"
        fun rows(position: Long) = (0 until 10).flatMap { s -> (0 until 64).map { Checkpoint.of("s$s", it, position) } }
        runBlocking { store.saveAll(group, rows(1)) }
        val batch = rows(2)
        val plan = PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement("EXPLAIN (FORMAT JSON) ${PostgresCheckpointStore.MOVE}").use { explain ->
                bind(connection, explain, group, batch)
                explain.executeQuery().use { rows ->
                    rows.next()
                    WowJson.mapper.readTree(rows.getString(1))
                }
            }
        }
        val join = plan.findParents("Hash Cond").single()
        val outer = join.get("Plans").single { it.get("Parent Relationship").asString() == "Outer" }
        outer.get("Node Type").asString().assert().isEqualTo("Function Scan")
    }

    private fun run(connection: Connection, sql: String, group: String, rows: List<Checkpoint>) =
        connection.prepareStatement(sql).use { statement ->
            bind(connection, statement, group, rows)
            statement.executeUpdate()
        }

    private fun bind(connection: Connection, statement: PreparedStatement, group: String, rows: List<Checkpoint>) {
        statement.setArray(1, connection.createArrayOf("text", rows.map { it.subscription }.toTypedArray()))
        statement.setArray(2, connection.createArrayOf("integer", rows.map { it.partition }.toTypedArray()))
        statement.setArray(3, connection.createArrayOf("bigint", rows.map { it.position }.toTypedArray()))
        statement.setString(4, group)
    }

    @Test
    fun `a subscription's checkpoints are one read of the key's prefix, not of the group's other subscriptions`():
        Unit = runBlocking {
        // A count's validator on a group of 20 subscriptions and 64 partitions: one subscription's 64 rows, of 1,280.
        val group = "prefix-${System.nanoTime()}"
        store.saveAll(group, (0 until 20).flatMap { s -> (0 until 64).map { Checkpoint.of("s$s", it, 1) } })
        store.list(group, "s7").size.assert().isEqualTo(64)

        // The index's node: the key's two leading columns are its condition, and it finds the subscription's rows alone.
        val ofOne = plan(PostgresCheckpointStore.LIST_OF, group, "s7")
        val scan = ofOne.findParents("Index Name").single()
        scan.get("Index Name").asString().assert().isEqualTo("wow_checkpoint_pkey")
        scan.get("Index Cond").asString().assert().contains("subscription_group").contains("subscription =")
        scan.get("Actual Rows").asInt().assert().isEqualTo(64)
        ofOne.findValues("Rows Removed by Filter").plus(ofOne.findValues("Rows Removed by Index Recheck"))
            .map { it.asInt() }.assert().allMatch { it == 0 }
        // What a count read before: the whole group's rows.
        plan(PostgresCheckpointStore.LIST, group).findParents("Index Name").single().get("Actual Rows").asInt()
            .assert().isEqualTo(1_280)
    }

    /** The executed plan of [sql] with [parameters]; the tables are small, so a sequential scan is set aside. */
    private fun plan(sql: String, vararg parameters: String) = PostgresFixture.dataSource.connection.use { connection ->
        connection.createStatement().use { it.execute("SET enable_seqscan = off") }
        try {
            connection.prepareStatement("EXPLAIN (ANALYZE, FORMAT JSON) $sql").use { query ->
                parameters.forEachIndexed { index, value -> query.setString(index + 1, value) }
                query.executeQuery().use { rows ->
                    rows.next()
                    WowJson.mapper.readTree(rows.getString(1))
                }
            }
        } finally {
            connection.createStatement().use { it.execute("RESET enable_seqscan") }
        }
    }

    private companion object {
        val store by lazy { PostgresCheckpointStore(PostgresFixture.dataSource).also { it.createSchema() } }
        const val KEYS = 64
        const val ROUNDS = 40
    }
}
