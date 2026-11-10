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

import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.PartitionLineage
import java.sql.Connection
import javax.sql.DataSource

/** The reads of a partition of a [PostgresEventLog]: its entries in sequence order, its head and its lineage. */
internal class PostgresTailReads(
    private val dataSource: DataSource,
    private val dispatcher: CoroutineDispatcher,
    private val context: String,
    private val partitions: Int,
    private val tailBatch: Int,
    private val catalog: PostgresLogCatalog,
    private val tables: PostgresEventLogTables,
    private val logRows: PostgresLogRows,
) {
    /**
     * One read of the tail after [last]: the contiguous run of entries from `last + 1`, and whether to read again at
     * once. A run shorter than the partition's counter calls for means an aggregate table this log does not know yet:
     * it reads the registry and, when that finds new tables, reads again at once.
     */
    fun readTail(connection: Connection, partition: Int, last: Long): TailBatch {
        val known = catalog.registered ?: catalog.register(connection)
        val (head, entries) = connection.prepareStatement(tables.tail(known)).use { query ->
            var index = 1
            query.setInt(index++, partition)
            known.forEach { _ ->
                query.setInt(index++, partition)
                query.setLong(index++, last)
                query.setInt(index++, tailBatch)
            }
            if (known.isNotEmpty()) {
                query.setInt(index, tailBatch)
            }
            query.executeQuery().use { rows ->
                var head = 0L
                val entries = ArrayList<LogEntry>()
                while (rows.next()) {
                    head = rows.getLong("head")
                    val branch = rows.getInt("branch")
                    if (!rows.wasNull()) {
                        entries += logRows.entryOf(partition, known[branch].aggregate, rows)
                    }
                }
                head to entries
            }
        }
        val run = contiguousRun(entries, last)
        val expected = minOf(head - last, tailBatch.toLong()).coerceAtLeast(0)
        val again = when {
            run.size < expected -> catalog.register(connection).map { it.base } != known.map { it.base }
            else -> run.size == tailBatch
        }
        return TailBatch(run, again, behind = run.size < expected)
    }

    /** One read of a tail: its [entries], whether to read again at once, whether the counter is ahead of them. */
    class TailBatch(val entries: List<LogEntry>, val again: Boolean, val behind: Boolean)

    /** The lineage of [partition] from its counter row: its `parent`, null for one the log began with, and `epoch`. */
    suspend fun lineage(partition: Int): PartitionLineage {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                connection.prepareStatement(tables.partitionRow).use { query ->
                    query.setInt(1, partition)
                    query.executeQuery().use { rows ->
                        check(rows.next()) { "The log of $context has no counter of partition $partition." }
                        val parent = rows.getInt(PARENT).takeUnless { rows.wasNull() }
                        PartitionLineage.of(parent, rows.getInt(EPOCH))
                    }
                }
            }
        }
    }

    suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                connection.prepareStatement(tables.head).use { query ->
                    query.setInt(1, partition)
                    query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1).takeIf { it > 0 } else null }
                }
            }
        }
    }

    private companion object {
        /** The lineage columns of a partition's counter row. */
        const val PARENT = "parent"
        const val EPOCH = "epoch"

        /** The entries of [entries], in sequence order, that continue `last` without a gap. */
        fun contiguousRun(entries: List<LogEntry>, last: Long): List<LogEntry> {
            var next = last + 1
            return entries.takeWhile { it.position.sequence == next++ }
        }
    }
}
