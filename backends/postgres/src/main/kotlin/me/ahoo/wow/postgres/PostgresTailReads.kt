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
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
        return TailBatch(run, again, behind = run.size < expected, head = head)
    }

    /**
     * One read of a tail: its [entries], whether to read again at once, whether the counter is ahead of them, and the
     * counter, [head], as the read saw it.
     */
    class TailBatch(val entries: List<LogEntry>, val again: Boolean, val behind: Boolean, val head: Long)

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

    /**
     * The last sequence the counter of [partition] gave, null for a partition with none yet; a partition without its
     * counter row is refused, as [lineage] refuses it: the store is damaged, and an empty head would be a wrong answer.
     */
    suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                connection.prepareStatement(tables.head).use { query ->
                    query.setInt(1, partition)
                    query.executeQuery().use { rows ->
                        check(rows.next()) { "The log of $context has no counter of partition $partition." }
                        rows.getLong(1).takeIf { it > 0 }
                    }
                }
            }
        }
    }

    /**
     * The last sequence the counter of each of [partitions] gave, null for one with none yet, in one statement; a
     * partition without its counter row is refused, as [head] refuses one.
     */
    suspend fun heads(partitions: Collection<Int>): Map<Int, Long?> {
        partitions.forEach { require(it in 0 until this.partitions) { "No partition $it among ${this.partitions}." } }
        if (partitions.isEmpty()) {
            return emptyMap()
        }
        val wanted = partitions.distinct()
        val heads = dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                connection.prepareStatement(tables.heads).use { query ->
                    query.setArray(1, connection.createArrayOf("integer", wanted.toTypedArray()))
                    query.executeQuery().use { rows ->
                        buildMap { while (rows.next()) put(rows.getInt(1), rows.getLong(2).takeIf { it > 0 }) }
                    }
                }
            }
        }
        val missing = wanted.filterNot(heads::containsKey)
        check(missing.isEmpty()) { "The log of $context has no counter of partition ${missing.min()}." }
        return wanted.associateWith { heads[it] }
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

/**
 * Whether one tail of [partition] in the log of [context] is stalled: reading nothing while its partition's counter is
 * past what it read ([PostgresTailReads.TailBatch.behind]). A tail meets that only when no table it reads holds the next
 * entry: a commit's counter and entries are seen together, and a table another node registered since is read again at
 * once. So an entry's table that is not in the registry, or an entry deleted by hand; the tail never steps over it.
 * After [warnAfter] of it, it warns, naming the partition, the sequence it waits for and the counter, and again each
 * [warnAfter] the stall lasts. The log's own logger warns, so an operator finds it under the log's name.
 */
internal class TailStall(
    private val context: String,
    private val partition: Int,
    private val warnAfter: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** Since when the tail has read nothing behind its counter, and when it last warned; null while it is not stalled. */
    private var stalledAt: TimeMark? = null
    private var warnedAt: TimeMark? = null

    /** Takes [batch], read after [last]: a stall that lasts [warnAfter] since it began, or since its last warning, warns. */
    fun observe(batch: PostgresTailReads.TailBatch, last: Long) {
        if (!batch.behind || batch.entries.isNotEmpty()) {
            stalledAt = null
            warnedAt = null
            return
        }
        val began = stalledAt ?: timeSource.markNow().also { stalledAt = it }
        val due = (warnedAt ?: began).elapsedNow() >= warnAfter
        if (due) {
            logger.warn(
                "The tail of partition {} of the log of {} has waited {} for sequence {}: the partition's counter is " +
                    "at {}, but no log table that {} lists holds that entry, so its subscriptions read no further. " +
                    "Its table has no record there, or the entry was deleted.",
                partition,
                context,
                began.elapsedNow().inWholeSeconds.seconds,
                last + 1,
                batch.head,
                PostgresEventLogTables.REGISTRY,
            )
            warnedAt = timeSource.markNow()
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PostgresEventLog::class.java)
    }
}
