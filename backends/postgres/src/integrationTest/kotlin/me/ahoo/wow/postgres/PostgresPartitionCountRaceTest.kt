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
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Deep review r1 (PostgreSQL backend): `createSchema` fills an empty counter table (`initializePartitions`, one
 * autocommitted `INSERT … WHERE NOT EXISTS`) and then counts its rows in a second statement, both outside the creation
 * lock. Two first starts with different partition counts can each pass their own count: the first commits its rows and
 * counts them before the second's insert, whose snapshot saw the table empty, adds the rest. Both logs then open, on
 * one counter table, with different partition counts.
 *
 * The interleaving is held, not timed: a `BEFORE INSERT` trigger on the context's counter table holds the second
 * start's insert (its session carries `wow.review.slow=on`) after its snapshot was taken and before it wrote a row,
 * until the first start has finished.
 */
class PostgresPartitionCountRaceTest {
    private val dataSource = PostgresFixture.dataSource

    @Test
    fun `two first starts with different partition counts never both open the log`() {
        val context = PostgresFixture.newContext("count-race")
        val tables = PostgresEventLogTables(context)
        // The tables and their format records as a first start makes them, then emptied: what two first starts both
        // find once one of them has created the tables under the creation lock.
        PostgresEventLog(dataSource, context, SMALL).createSchema()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM ${tables.partitions}")
                statement.execute(
                    """
                    CREATE OR REPLACE FUNCTION wow_review_hold_insert() RETURNS trigger AS ${'$'}${'$'}
                    BEGIN
                        IF current_setting('wow.review.slow', true) = 'on' AND NEW.partition = 0 THEN
                            PERFORM pg_sleep($HOLD_SECONDS);
                        END IF;
                        RETURN NEW;
                    END
                    ${'$'}${'$'} LANGUAGE plpgsql
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE TRIGGER wow_review_hold BEFORE INSERT ON ${tables.partitions} " +
                        "FOR EACH ROW EXECUTE FUNCTION wow_review_hold_insert()",
                )
            }
        }
        val container = WowContainers.postgres
        val held = PGSimpleDataSource().apply {
            setURL(container.jdbcUrl)
            user = container.username
            password = container.password
            options = "-c wow.review.slow=on"
        }

        // The second start: its insert takes its snapshot (the table empty), then waits in the trigger.
        val large = CompletableFuture.supplyAsync {
            runCatching { PostgresEventLog(held, context, LARGE).createSchema() }
        }
        awaitHeldInsert()
        // The first start, meanwhile: it finds the table empty too, fills 16 counters, and counts 16.
        val small = runCatching { PostgresEventLog(dataSource, context, SMALL).createSchema() }
        val second = large.get(HOLD_SECONDS * 4L, TimeUnit.SECONDS)

        val stored = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM ${tables.partitions}").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
        // At most one of the two counts may open a log over the one counter table, and it is the count stored.
        val opened = listOf(SMALL to small, LARGE to second)
            .filter { (_, outcome) -> outcome.isSuccess }
            .map { it.first }
        opened.assert().describedAs("the partition counts whose log opened, over $stored counter rows").hasSize(1)
        opened.single().assert().isEqualTo(stored)
    }

    /** Waits until a session of this database sleeps in the trigger: the held insert has its snapshot. */
    private fun awaitHeldInsert() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOLD_SECONDS.toLong())
        while (System.nanoTime() < deadline) {
            val sleeping = dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep' AND query LIKE '%generate_series%'",
                    ).use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
            if (sleeping > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("The second start's insert never reached the trigger.")
    }

    private companion object {
        const val SMALL = 16
        const val LARGE = 32
        const val HOLD_SECONDS = 3
        const val POLL_MILLIS = 20L
    }
}
