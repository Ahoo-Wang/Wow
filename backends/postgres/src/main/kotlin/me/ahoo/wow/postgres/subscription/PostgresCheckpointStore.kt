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

import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.PostgresSchema
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.inTransaction
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The [CheckpointStore] on PostgreSQL: one row per (group, subscription, partition) in `wow_checkpoint`.
 *
 * A save is one autocommitted upsert whose update applies only when the new position is higher, so concurrent saves
 * leave the highest and a lower one changes nothing. JDBC calls run on [dispatcher], virtual threads by
 * default.
 */
@InternalWowApi
class PostgresCheckpointStore(
    private val dataSource: DataSource,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the table is managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
) : CheckpointStore {
    /** Claims the table's format ([PostgresFormat]) and creates it when missing. */
    fun createSchema() {
        dataSource.autoCommitted { connection ->
            PostgresFormat.schema(connection, ddl, listOf(TABLE to FORMAT)) {
                PostgresSchema.execute(connection, listOf(DDL))
            }
        }
    }

    override suspend fun load(group: String, subscription: String, partition: Int): Long? = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(LOAD).use { query ->
                query.setString(1, group)
                query.setString(2, subscription)
                query.setInt(3, partition)
                query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
        }
    }

    override suspend fun list(group: String): List<Checkpoint> = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(LIST).use { query ->
                query.setString(1, group)
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(Checkpoint.of(rows.getString(1), rows.getInt(2), rows.getLong(3)))
                    }
                }
            }
        }
    }

    /** One read of the key's prefix (group, subscription), the primary key's leading columns. */
    override suspend fun list(group: String, subscription: String): List<Checkpoint> = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(LIST_OF).use { query ->
                query.setString(1, group)
                query.setString(2, subscription)
                query.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(Checkpoint.of(subscription, rows.getInt(1), rows.getLong(2))) }
                }
            }
        }
    }

    override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
        dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                connection.prepareStatement(SAVE).use { save ->
                    save.setString(1, group)
                    save.setString(2, subscription)
                    save.setInt(3, partition)
                    save.setLong(4, position)
                    save.executeUpdate()
                }
            }
        }
    }

    /**
     * The upserts [save] makes one by one, in one transaction and one commit, one WAL flush (in autocommit each upsert
     * of a batch was a transaction of its own). A key saved twice keeps its furthest position, as one statement may
     * touch a row once.
     *
     * Two statements, each over the batch as three arrays in key order, no `ON CONFLICT DO UPDATE`:
     * 1. [ADD] inserts the rows not there yet (a subscription's first save in a partition), `ON CONFLICT DO
     *    NOTHING`: a row that exists is left as it is, unlocked, and nothing is written for it.
     * 2. [MOVE] moves every row that is behind with one plain update, its guard the upsert's (`position < new`): it
     *    locks and writes the rows it moves and no other, a row the guard refuses neither.
     *
     * So the common save, every row there and behind, writes each row once, a HOT update, with no lock record.
     * `ON CONFLICT DO UPDATE` locks each conflicting row and writes that lock to the WAL (41% of a checkpoint row's WAL
     * on a busy node), a row its guard refuses too.
     *
     * Each statement locks only the rows it writes: [ADD] the rows it inserts, in key order (its select is ordered);
     * [MOVE] the rows it moves, never one the guard refuses, in the order of its plan's outer side: the arrays, in
     * key order, for a custom plan of a large batch; the group's rows, in the table's order, for a small batch or a
     * generic plan, which a pooled connection reaches within a few saves. So two saves of one group that each move
     * rows the other moves too (an owner's last save beside its successor's in a hand-over, a zombie owner's) may
     * lock them in opposite orders and deadlock; a save that is not moving rows another moves never can. The one
     * PostgreSQL picks as the victim (`40P01`) has had its transaction rolled back, and saves the batch again after a
     * backoff ([DEADLOCK_BACKOFF_MILLIS], doubling), at most [DEADLOCK_ATTEMPTS] times in all: the other save has gone
     * on and, its statements taking milliseconds, ended meanwhile, so the next attempt does not meet it. A save still
     * failing then throws, as any failure: the runtime saves the batch again on its next tick, and a stop saves what
     * it can and reports the failure. A row another save inserted between the two statements is moved by [MOVE] if
     * this save is ahead, its guard checked on the latest version, and left if not.
     *
     * A failed batch commits none of it, and the caller saves it again, as it saves any batch that failed: each
     * statement only moves a position forward.
     */
    override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
        if (checkpoints.isEmpty()) {
            return
        }
        dispatcher.jdbc {
            var attempt = 1
            while (true) {
                try {
                    dataSource.autoCommitted { connection ->
                        inTransaction(connection) { bound -> saveBatch(bound, group, checkpoints) }
                    }
                    break
                } catch (failure: SQLException) {
                    // The deadlock's victim: rolled back whole, saved again; any other failure is the caller's.
                    if (failure.sqlState != PostgresSchema.DEADLOCK_DETECTED || attempt >= DEADLOCK_ATTEMPTS) {
                        throw failure
                    }
                    Thread.sleep(DEADLOCK_BACKOFF_MILLIS shl (attempt - 1))
                    attempt++
                }
            }
        }
    }

    private fun saveBatch(connection: Connection, group: String, checkpoints: List<Checkpoint>) {
        val rows = checkpoints.groupBy { it.subscription to it.partition }.values
            .map { same -> same.maxBy { it.position } }
            .sortedWith(compareBy({ it.subscription }, { it.partition }))
        execute(connection, ADD, group, rows)
        execute(connection, MOVE, group, rows)
    }

    /** Runs [sql], [ADD] or [MOVE], over [rows] given as three arrays in key order, and [group]. */
    private fun execute(connection: Connection, sql: String, group: String, rows: List<Checkpoint>) {
        connection.prepareStatement(sql).use { statement ->
            statement.setArray(1, connection.createArrayOf("text", rows.map { it.subscription }.toTypedArray()))
            statement.setArray(2, connection.createArrayOf("integer", rows.map { it.partition }.toTypedArray()))
            statement.setArray(3, connection.createArrayOf("bigint", rows.map { it.position }.toTypedArray()))
            statement.setString(4, group)
            statement.executeUpdate()
        }
    }

    internal companion object {
        const val TABLE = "wow_checkpoint"

        /** The layout version of the table: its key compares by code point (`COLLATE "C"`). */
        const val FORMAT = 1

        val DDL = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                subscription_group text COLLATE "C" NOT NULL,
                subscription text COLLATE "C" NOT NULL,
                partition integer NOT NULL,
                position bigint NOT NULL,
                PRIMARY KEY (subscription_group, subscription, partition)
            )
        """.trimIndent()

        const val LOAD = "SELECT position FROM $TABLE WHERE subscription_group = ? AND subscription = ? AND partition = ?"

        const val LIST = "SELECT subscription, partition, position FROM $TABLE WHERE subscription_group = ?"

        const val LIST_OF = "SELECT partition, position FROM $TABLE WHERE subscription_group = ? AND subscription = ?"

        /** Upserts one checkpoint ([save]), moving its position only forward. */
        const val SAVE = "INSERT INTO $TABLE AS c (subscription_group, subscription, partition, position) " +
            "VALUES (?, ?, ?, ?) ON CONFLICT (subscription_group, subscription, partition) " +
            "DO UPDATE SET position = excluded.position WHERE c.position < excluded.position"

        /** The attempts of a batch that a deadlock makes the victim of: the first and two more. */
        const val DEADLOCK_ATTEMPTS = 3

        /** The wait before a deadlock's victim saves its batch again, doubling each time: 100 ms, then 200 ms. */
        const val DEADLOCK_BACKOFF_MILLIS = 100L

        /**
         * Inserts the rows of a batch not there yet, the batch given as three arrays in key order and its group. The
         * rows its snapshot holds are filtered out first, by one read of the group's rows, so a save whose rows all
         * exist inserts nothing and checks no conflict; a row another save inserted meanwhile conflicts, and is left as
         * it is.
         */
        val ADD = """
            INSERT INTO $TABLE (subscription_group, subscription, partition, position)
            SELECT g.subscription_group, v.subscription, v.partition, v.position
            FROM unnest(?::text[], ?::integer[], ?::bigint[]) AS v(subscription, partition, position),
                (SELECT ?::text AS subscription_group) AS g
            WHERE NOT EXISTS (
                SELECT 1 FROM $TABLE AS c
                WHERE c.subscription_group = g.subscription_group AND c.subscription = v.subscription
                    AND c.partition = v.partition
            )
            ORDER BY v.subscription, v.partition
            ON CONFLICT (subscription_group, subscription, partition) DO NOTHING
        """.trimIndent()

        /**
         * Moves the rows of a batch that are behind, the batch given as three arrays in key order and its group: a
         * plain update, which locks the rows it moves and no other.
         */
        val MOVE = """
            UPDATE $TABLE AS c SET position = v.position
            FROM unnest(?::text[], ?::integer[], ?::bigint[]) AS v(subscription, partition, position)
            WHERE c.subscription_group = ? AND c.subscription = v.subscription AND c.partition = v.partition
                AND c.position < v.position
        """.trimIndent()
    }
}
