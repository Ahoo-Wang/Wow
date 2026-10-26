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
import javax.sql.DataSource

/**
 * The [CheckpointStore] on PostgreSQL: one row per (group, subscription, partition) in `wow_checkpoint`.
 *
 * A save is one autocommitted upsert whose update applies only when the new position is higher, so concurrent saves
 * leave the highest and a lower one changes nothing (decision D45). JDBC calls run on [dispatcher], virtual threads by
 * default (decision D29).
 */
@InternalWowApi
class PostgresCheckpointStore(
    private val dataSource: DataSource,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the table is managed: created when missing by default (review A5-10). */
    private val ddl: Ddl = Ddl.AUTO,
) : CheckpointStore {
    /** Claims the table's format ([PostgresFormat], decision D59) and creates it when missing. */
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
     * The upserts [save] makes one by one, as one JDBC batch in one transaction: one round trip and one commit, one WAL
     * flush (work package B.O-5; in autocommit each upsert of a batch was a transaction of its own). They go in key
     * order, so two batches touching the same rows lock them in the same order. A failed batch commits none of them,
     * and the caller saves them again, as it saves any batch that failed: each upsert only moves a position forward.
     */
    override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
        if (checkpoints.isEmpty()) {
            return
        }
        dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                inTransaction(connection) { saveBatch(connection, group, checkpoints) }
            }
        }
    }

    private fun saveBatch(connection: Connection, group: String, checkpoints: List<Checkpoint>) {
        connection.prepareStatement(SAVE).use { save ->
            checkpoints.sortedWith(compareBy({ it.subscription }, { it.partition })).forEach {
                save.setString(1, group)
                save.setString(2, it.subscription)
                save.setInt(3, it.partition)
                save.setLong(4, it.position)
                save.addBatch()
            }
            save.executeBatch()
        }
    }

    internal companion object {
        const val TABLE = "wow_checkpoint"

        /** The layout version of the table (finding A2-13). */
        const val FORMAT = 1

        val DDL = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                subscription_group text NOT NULL,
                subscription text NOT NULL,
                partition integer NOT NULL,
                position bigint NOT NULL,
                PRIMARY KEY (subscription_group, subscription, partition)
            )
        """.trimIndent()

        const val LOAD = "SELECT position FROM $TABLE WHERE subscription_group = ? AND subscription = ? AND partition = ?"

        val SAVE = """
            INSERT INTO $TABLE AS c (subscription_group, subscription, partition, position) VALUES (?, ?, ?, ?)
            ON CONFLICT (subscription_group, subscription, partition) DO UPDATE SET position = excluded.position
            WHERE c.position < excluded.position
        """.trimIndent()
    }
}
