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
import java.util.concurrent.atomic.AtomicReferenceArray
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
     * The rows that exist are moved first with one plain update ([MOVE]): one key lookup per row, its guard the
     * upsert's (`position < new`), and no `ON CONFLICT`, whose speculative insert locks each conflicting row and writes
     * that lock to the WAL (41% of a checkpoint row's WAL on a busy node, its update being HOT). That is every row of a
     * save but a subscription's first in a partition. The update says which rows it moved; the others are either
     * absent or refused by the guard (a position not ahead of the stored one), and only they take the upsert
     * ([saveRows]), which inserts the absent and, its guard checked again under the row's lock, leaves the refused as
     * they are, or moves one that a concurrent save inserted meanwhile.
     *
     * The rows go in key order, so two batches touching the same rows lock them in the same order and never wait on
     * each other in a cycle; past [MAX_ROWS] the upserts are split into several statements of the one transaction. A
     * failed batch commits none of them, and the caller saves them again, as it saves any batch that failed: each
     * statement only moves a position forward.
     */
    override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
        if (checkpoints.isEmpty()) {
            return
        }
        dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                inTransaction(connection) { bound -> saveBatch(bound, group, checkpoints) }
            }
        }
    }

    private fun saveBatch(connection: Connection, group: String, checkpoints: List<Checkpoint>) {
        val rows = checkpoints.groupBy { it.subscription to it.partition }.values
            .map { same -> same.maxBy { it.position } }
            .sortedWith(compareBy({ it.subscription }, { it.partition }))
        val moved = move(connection, group, rows)
        rows.filterNot { (it.subscription to it.partition) in moved }.chunked(MAX_ROWS).forEach { chunk ->
            connection.prepareStatement(saveRows(chunk.size)).use { save ->
                chunk.forEachIndexed { at, checkpoint ->
                    val first = at * COLUMNS
                    save.setString(first + 1, group)
                    save.setString(first + 2, checkpoint.subscription)
                    save.setInt(first + 3, checkpoint.partition)
                    save.setLong(first + 4, checkpoint.position)
                }
                save.executeUpdate()
            }
        }
    }

    /** Moves the rows of [rows] that exist and are behind, with one update; the keys it moved. */
    private fun move(connection: Connection, group: String, rows: List<Checkpoint>): Set<Pair<String, Int>> =
        connection.prepareStatement(MOVE).use { update ->
            update.setArray(1, connection.createArrayOf("text", rows.map { it.subscription }.toTypedArray()))
            update.setArray(2, connection.createArrayOf("integer", rows.map { it.partition }.toTypedArray()))
            update.setArray(3, connection.createArrayOf("bigint", rows.map { it.position }.toTypedArray()))
            update.setString(4, group)
            update.executeQuery().use { moved ->
                buildSet { while (moved.next()) add(moved.getString(1) to moved.getInt(2)) }
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

        val SAVE = rowsSql(1)

        /**
         * Moves the rows of a batch that exist and are behind, the batch given as three arrays in key order, and
         * returns the keys it moved: one lookup of the key a row, as a plain update, no lock beyond the update's.
         */
        val MOVE = """
            UPDATE $TABLE AS c SET position = v.position
            FROM unnest(?::text[], ?::integer[], ?::bigint[]) AS v(subscription, partition, position)
            WHERE c.subscription_group = ? AND c.subscription = v.subscription AND c.partition = v.partition
                AND c.position < v.position
            RETURNING c.subscription, c.partition
        """.trimIndent()

        /** The parameters of one row of [saveRows]. */
        private const val COLUMNS = 4

        /** The most rows one statement of [saveBatch] upserts: well under PostgreSQL's 32,767 parameters. */
        const val MAX_ROWS = 1_000

        /** [saveRows] of each row count up to [MAX_ROWS], built once: a batch saves without building its SQL. */
        private val saves = AtomicReferenceArray<String>(MAX_ROWS + 1)

        /** Upserts [rows] checkpoints in the order given, each moving its position only forward. */
        fun saveRows(rows: Int): String =
            saves.get(rows) ?: rowsSql(rows).also { if (rows <= MAX_ROWS) saves.set(rows, it) }

        private fun rowsSql(rows: Int): String = """
            INSERT INTO $TABLE AS c (subscription_group, subscription, partition, position)
            VALUES ${List(rows) { "(?, ?, ?, ?)" }.joinToString(", ")}
            ON CONFLICT (subscription_group, subscription, partition) DO UPDATE SET position = excluded.position
            WHERE c.position < excluded.position
        """.trimIndent()
    }
}
