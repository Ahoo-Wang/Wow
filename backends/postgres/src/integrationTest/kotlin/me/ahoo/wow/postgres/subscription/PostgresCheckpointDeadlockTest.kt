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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/**
 * Two saves of one group moving the same rows, whose plans lock them in opposite orders, deadlock; PostgreSQL rolls
 * the victim back, and its save saves the batch again ([PostgresCheckpointStore.DEADLOCK_ATTEMPTS]).
 */
class PostgresCheckpointDeadlockTest {
    @Test
    fun `two saves moving the same rows in opposite orders deadlock, and the victim saves its batch again`(): Unit =
        runBlocking {
            // What makes the deadlock certain, by states seen, no duration:
            // - the rows' heap order is the reverse of their key order: inserted one at a time, the last key first;
            // - the first save's connection plans generically, its update scanning the group's rows in heap order, the
            //   highest key first; the second's plans each call, its update probing them with the batch in key order,
            //   the lowest first (640 rows, analysed);
            // - a trigger on the gate row, in the middle of the keys, takes an advisory lock shared that a third
            //   connection holds: the first save is seen waiting on it, holding the gate row and every row above it;
            // - the second save is then seen waiting on a row (the gate row), holding every row below it;
            // - the advisory lock is let go: the first save goes on down into the rows the second holds, while the
            //   second waits for the gate row the first holds. The cycle is closed.
            // What makes the outcome certain: PostgreSQL breaks it, the victim waits 100 ms and saves its batch again,
            // and meanwhile the other ends at once, nothing holding it any more; both saves return, every row at the
            // higher save's position.
            val database = "checkpoint_cross_${System.nanoTime()}"
            admin("CREATE DATABASE $database")
            val generic = pool(database, "force_generic_plan")
            val custom = pool(database, "force_custom_plan")
            val gate = connect(database)
            try {
                val genericStore = PostgresCheckpointStore(generic).also { it.createSchema() }
                val customStore = PostgresCheckpointStore(custom)
                val keys = (0 until SUBSCRIPTIONS).flatMap { s -> (0 until PARTITIONS).map { "s$s" to it } }
                keys.reversed().forEach { (s, p) -> genericStore.save(GROUP, s, p, 1) }
                val (gateSubscription, gatePartition) = keys[keys.size / 2]
                execute(
                    generic,
                    "ANALYZE ${PostgresCheckpointStore.TABLE}",
                    "CREATE FUNCTION gate() RETURNS trigger AS ${'$'}${'$'} BEGIN " +
                        "IF NEW.subscription = '$gateSubscription' AND NEW.partition = $gatePartition THEN " +
                        "PERFORM pg_advisory_xact_lock_shared($GATE); END IF; " +
                        "RETURN NEW; END ${'$'}${'$'} LANGUAGE plpgsql",
                    "CREATE TRIGGER gate BEFORE UPDATE ON ${PostgresCheckpointStore.TABLE} FOR EACH ROW " +
                        "EXECUTE FUNCTION gate()",
                )
                gate.createStatement().use { it.execute("SELECT pg_advisory_lock($GATE)") }
                val first = async(Dispatchers.IO) {
                    genericStore.saveAll(GROUP, keys.map { (s, p) -> Checkpoint.of(s, p, 2) })
                }
                awaitWaiting(database, "advisory")
                val second = async(Dispatchers.IO) {
                    customStore.saveAll(GROUP, keys.map { (s, p) -> Checkpoint.of(s, p, 3) })
                }
                awaitWaiting(database, "transactionid", "tuple")
                gate.createStatement().use { it.execute("SELECT pg_advisory_unlock($GATE)") }
                first.await()
                second.await()
                keys.forEach { (s, p) -> customStore.load(GROUP, s, p).assert().isEqualTo(3L) }
            } finally {
                gate.close()
                generic.close()
                custom.close()
            }
            // The victim: PostgreSQL counts the deadlock it broke. A backend reports its counts as it ends, so they
            // are read once the pools closed, from another database's connection.
            try {
                withTimeout(30.seconds) { while (deadlocks(database) < 1) delay(100) }
            } finally {
                admin("DROP DATABASE $database WITH (FORCE)")
            }
        }

    private fun admin(sql: String) = PostgresFixture.dataSource.connection.use { it.createStatement().execute(sql) }

    private fun pool(database: String, planCacheMode: String): HikariDataSource {
        val container = WowContainers.postgres
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database"
                username = container.username
                password = container.password
                maximumPoolSize = 2
                connectionInitSql = "SET plan_cache_mode = $planCacheMode"
                // A server-side statement from the first call, so the generic plan is the one used.
                addDataSourceProperty("prepareThreshold", "1")
            },
        )
    }

    private fun execute(source: DataSource, vararg statements: String) = source.connection.use { connection ->
        connection.createStatement().use { statement -> statements.forEach(statement::execute) }
    }

    private fun connect(database: String): Connection {
        val container = WowContainers.postgres
        return DriverManager.getConnection(
            "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database",
            container.username,
            container.password,
        )
    }

    /** Returns once an update of [database]'s checkpoints is seen waiting on a lock of one of [events]. */
    private suspend fun awaitWaiting(database: String, vararg events: String) = withTimeout(30.seconds) {
        val listed = events.joinToString(", ") { "'$it'" }
        val sql = "SELECT count(*) FROM pg_stat_activity WHERE datname = '$database' AND wait_event_type = 'Lock' " +
            "AND wait_event IN ($listed) AND query LIKE 'UPDATE ${PostgresCheckpointStore.TABLE}%'"
        while (PostgresFixture.dataSource.connection.use { c -> c.count(sql) } == 0L) delay(5)
    }

    /** The deadlocks PostgreSQL broke in [database] so far, as its ended backends reported them. */
    private fun deadlocks(database: String): Long = PostgresFixture.dataSource.connection.use { connection ->
        connection.createStatement().use { it.execute("SELECT pg_stat_clear_snapshot()") }
        connection.count("SELECT coalesce(sum(deadlocks), 0) FROM pg_stat_database WHERE datname = '$database'")
    }

    private fun Connection.count(sql: String): Long = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getLong(1) else 0L }
    }

    private companion object {
        const val GROUP = "cross"
        const val SUBSCRIPTIONS = 10
        const val PARTITIONS = 64

        /** The advisory lock the gate row's trigger takes. */
        const val GATE = 6228L
    }
}
