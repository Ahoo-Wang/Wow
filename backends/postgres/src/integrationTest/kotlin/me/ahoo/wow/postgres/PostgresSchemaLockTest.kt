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

package me.ahoo.wow.postgres

import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.query.PostgresQueryIndex
import me.ahoo.wow.postgres.query.PostgresQueryIndexes
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Finding F10: a crashed node's transaction, idle while it holds a table's `ROW EXCLUSIVE` lock, must not stall the
 * schema step of the node that replaces it, nor queue the table's readers behind that step. Finding F16: nodes that
 * start together run one table's schema steps one at a time.
 */
class PostgresSchemaLockTest {
    private val dataSource = PostgresFixture.dataSource

    /** Drops the owner index of [tables]' snapshot table, so that its schema step has an index to make. */
    private fun dropOwnerIndex(tables: PostgresSnapshotTables) {
        dataSource.connection.use { it.createStatement().execute("DROP INDEX \"${tables.ownerIndex}\"") }
    }

    @Test
    fun `a current schema should take no table lock, however long another transaction holds the table`() {
        val context = PostgresFixture.newContext("lock")
        PostgresSnapshotStore(dataSource, context).createTable("order")
        val tables = PostgresSnapshotTables(context, "order")
        holding(tables.snapshot) {
            // A new store, as a replacing node has: its schema step reads the catalog and runs no DDL.
            assertTimeoutPreemptively(10.seconds.toJavaDuration()) {
                PostgresSnapshotStore(dataSource, context).createTable("order")
            }
        }
    }

    @Test
    fun `a missing index behind a held table should fail fast, naming the table and its holder`() {
        val context = PostgresFixture.newContext("lock")
        PostgresSnapshotStore(dataSource, context).createTable("order")
        val tables = PostgresSnapshotTables(context, "order")
        dropOwnerIndex(tables)
        holding(tables.snapshot) { holder ->
            val wait = PostgresSchema.LockWait(timeout = 200.milliseconds, attempts = 2, backoff = 50.milliseconds)
            val started = TimeSource.Monotonic.markNow()
            val failure = assertThrows<IllegalStateException> {
                dataSource.connection.use { connection ->
                    PostgresFormat.schema(
                        connection,
                        Ddl.AUTO,
                        listOf(tables.base to PostgresSnapshotTables.FORMAT),
                        wait
                    ) {
                        PostgresSchema.execute(connection, tables.ddl())
                    }
                }
            }
            started.elapsedNow().assert().isLessThan(5.seconds)
            failure.message.assert().contains(tables.base, "pid $holder", "idle in transaction")
        }
        // Once the holder is gone, the step makes the index.
        dataSource.connection.use { connection ->
            PostgresFormat.schema(connection, Ddl.AUTO, listOf(tables.base to PostgresSnapshotTables.FORMAT)) {
                PostgresSchema.execute(connection, tables.ddl())
            }
            PostgresSchema.exists(connection, "\"${tables.ownerIndex}\"").assert().isTrue()
        }
    }

    @Test
    fun `nodes starting together on a new database should both make the schema and its query indexes`() {
        // Finding F16: one node's schema step and another's CREATE INDEX CONCURRENTLY on the same table deadlocked.
        val context = PostgresFixture.newContext("together")
        val aggregates = (1..6).map { "a$it" }
        val nodes = 2
        val ready = CyclicBarrier(nodes)
        val pool = Executors.newFixedThreadPool(nodes)
        try {
            val starts = (1..nodes).map {
                pool.submit {
                    ready.await()
                    // Each node opens its own store, as a new process does.
                    val store = PostgresSnapshotStore(dataSource, context)
                    aggregates.forEach { aggregate ->
                        store.createTable(aggregate)
                        PostgresQueryIndexes.manage(dataSource, Ddl.AUTO, listOf(indexOf(context, aggregate)))
                    }
                }
            }
            starts.forEach { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }
        aggregates.forEach { aggregate ->
            PostgresQueryIndexes.manage(dataSource, Ddl.VALIDATE, listOf(indexOf(context, aggregate))).apply {
                missing.assert().isEmpty()
                invalid.assert().isEmpty()
            }
        }
    }

    @Test
    fun `a schema step should wait for the table's step on another node, then run`() {
        val context = PostgresFixture.newContext("lock")
        PostgresSnapshotStore(dataSource, context).createTable("order")
        val tables = PostgresSnapshotTables(context, "order")
        dropOwnerIndex(tables)
        val held = CountDownLatch(1)
        val other = Thread {
            dataSource.connection.use { connection ->
                PostgresSchema.locked(connection, tables.snapshot, PostgresSchema.LockWait.DEFAULT) {
                    held.countDown()
                    Thread.sleep(HELD_MILLIS)
                }
            }
        }.apply { start() }
        held.await()
        val started = TimeSource.Monotonic.markNow()
        dataSource.connection.use { connection ->
            PostgresFormat.schema(connection, Ddl.AUTO, listOf(tables.base to PostgresSnapshotTables.FORMAT)) {
                PostgresSchema.execute(connection, tables.ddl())
            }
            started.elapsedNow().inWholeMilliseconds.assert().isGreaterThanOrEqualTo(HELD_MILLIS / 2)
            PostgresSchema.exists(connection, "\"${tables.ownerIndex}\"").assert().isTrue()
        }
        other.join()
    }

    @Test
    fun `a step whose table another node keeps locked should fail naming that node`() {
        val context = PostgresFixture.newContext("lock")
        val tables = PostgresSnapshotTables(context, "order")
        dataSource.connection.use { holder ->
            PostgresSchema.locked(holder, tables.snapshot, PostgresSchema.LockWait.DEFAULT) {
                val wait = PostgresSchema.LockWait(timeout = 200.milliseconds, attempts = 2, backoff = 50.milliseconds)
                val failure = assertThrows<IllegalStateException> {
                    dataSource.connection.use { connection ->
                        PostgresFormat.schema(
                            connection,
                            Ddl.AUTO,
                            listOf(tables.base to PostgresSnapshotTables.FORMAT),
                            wait
                        ) {
                            PostgresSchema.execute(connection, tables.ddl())
                        }
                    }
                }
                failure.message.assert().contains(tables.base, "pid ${holder.pid()}")
            }
        }
    }

    @Test
    fun `a step PostgreSQL ends as a deadlock should be tried again`() {
        dataSource.connection.use { connection ->
            var attempts = 0
            PostgresSchema.retrying(connection, "t", PostgresSchema.LockWait.DEFAULT) {
                attempts++
                if (attempts == 1) throw SQLException("deadlock detected", PostgresSchema.DEADLOCK_DETECTED)
            }
            attempts.assert().isEqualTo(2)
            assertThrows<SQLException> {
                PostgresSchema.retrying(connection, "t", PostgresSchema.LockWait.DEFAULT) {
                    throw SQLException("syntax error", "42601")
                }
            }
        }
    }

    private fun indexOf(context: String, aggregate: String): PostgresQueryIndex {
        val tables = PostgresSnapshotTables(context, aggregate)
        val name = PostgresIdentifiers.limited(tables.base + "_ix_version")
        return PostgresQueryIndex(
            name,
            tables.snapshot,
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"$name\" ON ${tables.snapshot} (tenant_id, version) WHERE NOT deleted",
        )
    }

    /** Runs [block] while another session holds [table] in an open transaction, as a crashed node's would; its pid. */
    private fun holding(table: String, block: (Int) -> Unit) {
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().execute("LOCK TABLE $table IN ROW EXCLUSIVE MODE")
            try {
                block(holder.pid())
            } finally {
                holder.rollback()
                holder.autoCommit = true
            }
        }
    }

    private companion object {
        const val HELD_MILLIS = 1_000L
    }

    private fun Connection.pid(): Int =
        createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
}
