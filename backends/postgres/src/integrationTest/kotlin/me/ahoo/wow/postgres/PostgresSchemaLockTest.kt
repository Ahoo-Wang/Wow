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
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.sql.Connection
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Finding F10: a crashed node's transaction, idle while it holds a table's `ROW EXCLUSIVE` lock, must not stall the
 * schema step of the node that replaces it, nor queue the table's readers behind that step.
 */
class PostgresSchemaLockTest {
    private val dataSource = PostgresFixture.dataSource

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
    fun `a missing column behind a held table should fail fast, naming the table and its holder`() {
        val context = PostgresFixture.newContext("lock")
        PostgresSnapshotStore(dataSource, context).createTable("order")
        val tables = PostgresSnapshotTables(context, "order")
        dataSource.connection.use { it.createStatement().execute("ALTER TABLE ${tables.snapshot} DROP COLUMN stale_version") }
        holding(tables.snapshot) { holder ->
            val wait = PostgresSchema.LockWait(timeout = 200.milliseconds, attempts = 2, backoff = 50.milliseconds)
            val started = TimeSource.Monotonic.markNow()
            val failure = assertThrows<IllegalStateException> {
                dataSource.connection.use { connection ->
                    PostgresFormat.schema(connection, Ddl.AUTO, listOf(tables.base to PostgresSnapshotTables.FORMAT), wait) {
                        PostgresSchema.execute(connection, tables.ddl())
                    }
                }
            }
            started.elapsedNow().assert().isLessThan(5.seconds)
            failure.message.assert().contains(tables.base, "pid $holder", "idle in transaction")
        }
        // Once the holder is gone, the step adds the column.
        dataSource.connection.use { connection ->
            PostgresFormat.schema(connection, Ddl.AUTO, listOf(tables.base to PostgresSnapshotTables.FORMAT)) {
                PostgresSchema.execute(connection, tables.ddl())
            }
            PostgresSchema.hasColumns(connection, tables.snapshot, listOf("stale_version")).assert().isTrue()
        }
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

    private fun Connection.pid(): Int =
        createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
}
