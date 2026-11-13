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
            // What makes the deadlock certain:
            // - the rows' heap order is the reverse of their key order: inserted one at a time, the last key first;
            // - one save's connection plans generically, its update scanning the group's rows in heap order; the
            //   other's plans each call, its update probing them with the batch in key order (640 rows, analysed);
            // - one row in 16 is slowed (1 ms, pg_sleep's least), about 40 ms an update, and the second save starts
            //   once the first is seen updating, so the first holds the highest keys and wants the lowest while the
            //   second holds the lowest and wants the highest.
            // What makes the outcome certain: the victim waits 100 ms, more than the other's whole update takes, so its
            // second attempt meets no one; both saves return, every row at the higher save's position.
            val database = "checkpoint_cross_${System.nanoTime()}"
            admin("CREATE DATABASE $database")
            val generic = pool(database, "force_generic_plan")
            val custom = pool(database, "force_custom_plan")
            try {
                val genericStore = PostgresCheckpointStore(generic).also { it.createSchema() }
                val customStore = PostgresCheckpointStore(custom)
                val keys = (0 until SUBSCRIPTIONS).flatMap { s -> (0 until PARTITIONS).map { "s$s" to it } }
                keys.reversed().forEach { (s, p) -> genericStore.save(GROUP, s, p, 1) }
                execute(
                    generic,
                    "ANALYZE ${PostgresCheckpointStore.TABLE}",
                    "CREATE FUNCTION slow_update() RETURNS trigger AS ${'$'}${'$'} BEGIN " +
                        "IF NEW.partition % 16 = 0 THEN PERFORM pg_sleep(0.001); END IF; " +
                        "RETURN NEW; END ${'$'}${'$'} LANGUAGE plpgsql",
                    "CREATE TRIGGER slow_update BEFORE UPDATE ON ${PostgresCheckpointStore.TABLE} FOR EACH ROW " +
                        "EXECUTE FUNCTION slow_update()",
                )
                val before = deadlocks(generic)
                val first = async(Dispatchers.IO) {
                    genericStore.saveAll(GROUP, keys.map { (s, p) -> Checkpoint.of(s, p, 2) })
                }
                withTimeout(10.seconds) { while (!updating(custom)) delay(1) }
                val second = async(Dispatchers.IO) {
                    customStore.saveAll(GROUP, keys.map { (s, p) -> Checkpoint.of(s, p, 3) })
                }
                first.await()
                second.await()
                keys.forEach { (s, p) -> customStore.load(GROUP, s, p).assert().isEqualTo(3L) }
                // The victim: PostgreSQL counts the deadlock it broke; its save returned all the same.
                withTimeout(10.seconds) { while (deadlocks(custom) <= before) delay(100) }
            } finally {
                generic.close()
                custom.close()
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

    /** Whether an update of the checkpoints runs now. */
    private fun updating(source: DataSource): Boolean = source.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND datname = current_database() " +
                    "AND query LIKE 'UPDATE ${PostgresCheckpointStore.TABLE}%'",
            ).use { rows -> rows.next() && rows.getInt(1) > 0 }
        }
    }

    /** The deadlocks PostgreSQL broke in this database so far. */
    private fun deadlocks(source: DataSource): Long = source.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("SELECT pg_stat_clear_snapshot()")
            statement.executeQuery("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")
                .use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    private companion object {
        const val GROUP = "cross"
        const val SUBSCRIPTIONS = 10
        const val PARTITIONS = 64
    }
}
