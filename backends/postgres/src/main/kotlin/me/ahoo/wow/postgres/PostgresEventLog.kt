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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.access.AggregatePage
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.VersionPages
import me.ahoo.wow.spi.log.requireChains
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The [EventLog] of one bounded context on PostgreSQL (documentation/designs/03-log-and-snapshot.md §2).
 *
 * The log takes one table per aggregate type, `wow_log__<context>__<aggregate>`; a context-level
 * table holds one counter row per partition, so partitions, their sequences, leases and checkpoints stay per context.
 * A counter row also holds its partition's lineage, `parent` (null) and `epoch` (0): format 1 reserves
 * them for the online splits of 10.x, and [lineage] reads them back.
 * An append is one autocommitted statement (see [AggregateLogTable.append]): it bumps its partition's counter, which
 * locks the row until the statement commits, and inserts the stream into its aggregate's table at that sequence only
 * if the aggregate is exactly one version behind. Because the lock is held until commit, the sequence order is the
 * order commits become visible, whichever table they land in; a failed append rolls its sequence back too, so a
 * partition's sequences have no gaps. A group commit ([appendAll]) is the same statement for many streams: one
 * round trip per group. The transport is stored in its own `bytea` column as the bytes the runtime encoded, never read,
 * and given back only by [tail] and [readEntries].
 *
 * **Aggregate tables.** As the snapshot tables ([me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore]), an aggregate
 * type's table is created the first time this log touches the type, or up front by [createTable]; a new aggregate
 * type needs no restart. Every aggregate table
 * is recorded in a registry table ([PostgresEventLogTables.REGISTRY]) before its first entry is written, and [tail]
 * reads the context's tables from it: a node tailing a partition finds the tables other nodes created, whether or not
 * it knows their aggregate types.
 *
 * **Tail.** [tail] merges the partition's entries of every aggregate table by sequence, in one statement and so one
 * snapshot, which also reads the partition's counter. It emits only a contiguous run from the last sequence: entries
 * the snapshot should hold but does not (a table registered after this log last read the registry) make it read the
 * registry again and retry, and it never steps over a missing sequence.
 *
 * **Request ids.** The request key is `(tenant_id, request_id)` within each aggregate table: a request id reused by
 * two aggregate types commits twice (the reuse is the caller's error). The kernel's pre-check, the
 * [findByRequestId] taking the aggregate type, reads that type's table alone.
 *
 * **Polling**. A tail polls every `pollInterval` (50 ms by default) while it finds entries; each poll
 * that finds none doubles the wait, up to `maxPollInterval` (1 s), so an idle cluster's partitions cost a statement a
 * second each rather than twenty. A commit this log makes wakes the partition's tails at once, and a poll it woke
 * that finds the partition caught up keeps the backoff, so a commit costs one read of the tail; an entry another node
 * appends is read within `maxPollInterval`. `LISTEN/NOTIFY` would wake the tails of every node; it is
 * not implemented: the bounded backoff and the wake on this log's commits keep an idle cluster cheap without a
 * listening connection per node.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21).
 */
@InternalWowApi
@Suppress("LargeClass") // The log's port and its group commits, of one partition and of several, on one table layout.
class PostgresEventLog(
    private val dataSource: DataSource,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val tailBatch: Int = DEFAULT_TAIL_BATCH,
    maxPollInterval: Duration = DEFAULT_MAX_POLL_INTERVAL,
    /** How the log's tables are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
) : EventLog,
    AccessWalks {
    private val tables = PostgresEventLogTables(context)

    /** A stream's columns, bound and read back. */
    private val logRows = PostgresLogRows(context)

    /** Where a stream is found by a key. */
    private val lookups = PostgresLogLookups(logRows)

    /** Validates the partition count first: a log of another count is refused when it is built. */
    private val pacer = TailPacer(Partitioner.requirePartitions(partitions), pollInterval, maxPollInterval)

    /** The aggregate tables of the context: created, registered and found. */
    private val catalog = PostgresLogCatalog(dataSource, context, partitions, ddl, tables)

    /** The appends: one stream, and a group commit. */
    private val appender = PostgresLogAppender(dataSource, tables, logRows, lookups, catalog)

    /**
     * Creates the partition counters and the registry when missing; refuses a different partition count, and a log
     * recorded in another format version ([PostgresFormat]). The aggregate tables are created on first use, or by
     * [createTable].
     */
    fun createSchema() = catalog.createSchema()

    /**
     * Creates and registers the log table of [aggregateName] when missing, once per log; refuses a table recorded in
     * another format version ([PostgresFormat]), and an aggregate whose table name another aggregate has.
     */
    fun createTable(aggregateName: String) = catalog.createTable(aggregateName)

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
        dispatcher.jdbc {
            require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
            val partition = Partitioner.partitionOf(stream.ref, partitions)
            appender.append(partition, stream, transport)
                .also { if (it is AppendOutcome.Committed) pacer.committed(partition) }
        }

    /** Group commit: [PostgresLogAppender.appendAll]. */
    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
        if (streams.size <= 1) {
            return streams.map { (stream, transport) -> append(stream, transport) }
        }
        require(streams.all { (stream) -> stream.ref.type.context == context }) {
            "A group commit appends streams of $context."
        }
        requireChains(streams)
        val parts = streams.map { (stream) -> Partitioner.partitionOf(stream.ref, partitions) }
        return dispatcher.jdbc { appender.appendAll(parts, streams) }.also { outcomes ->
            parts.withIndex().filter { outcomes[it.index] is AppendOutcome.Committed }.map { it.value }.distinct()
                .forEach(pacer::committed)
        }
    }

    /** A group may hold streams of several partitions. */
    override val appendsAcrossPartitions: Boolean get() = true

    /** A page of [VersionPages.SIZE] streams a statement, each emitted before the next is read. */
    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        VersionPages.byVersion(fromVersion, toVersion, EventStreamRecord::version) { from ->
            dispatcher.jdbc {
                dataSource.autoCommitted { connection ->
                    catalog.withTable(connection, ref.type) { table ->
                        connection.prepareStatement(table.read).use { query ->
                            query.setString(1, ref.id)
                            query.setString(2, ref.tenantId)
                            query.setInt(3, from)
                            query.setInt(4, toVersion)
                            query.setInt(5, VersionPages.SIZE)
                            query.executeQuery().use { rows -> rows.map { logRows.streamOf(table.aggregate, it) } }
                        }
                    }
                }
            }
        }

    /** One statement by the version key, autocommitted: a stamp of a range writes it whole or not at all. */
    @InternalWowApi
    override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
        if (from >= until) return 0
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                catalog.withTable(connection, ref.type) { table ->
                    connection.prepareStatement(table.stamp).use { update ->
                        update.setString(1, AccessValues.text(access))
                        update.setInt(2, asOf)
                        update.setString(3, ref.id)
                        update.setString(4, ref.tenantId)
                        update.setInt(5, from)
                        update.setInt(6, until)
                        update.setInt(7, asOf)
                        update.executeUpdate()
                    }
                }
            }
        }
    }

    /**
     * A statement a page: each aggregate's first stream, by the version key, its cursor [AggregatePage.cursorOf]; none
     * of a type whose table does not exist, which it does not create.
     */
    override suspend fun aggregates(type: AggregateType, after: String?, limit: Int): AggregatePage {
        require(limit > 0) { "A page holds at least one aggregate." }
        val key = after?.let(AggregatePage::keyOf)
        val refs = dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                val table = catalog.existing(connection, type) ?: return@autoCommitted emptyList()
                connection.prepareStatement(if (key == null) table.firstAggregates else table.aggregatesAfter).use {
                    var index = 1
                    key?.let { (id, tenant) ->
                        it.setString(index++, tenant)
                        it.setString(index++, id)
                    }
                    it.setInt(index, limit)
                    it.executeQuery().use { rows -> rows.map { type.ref(it.getString(2), it.getString(1)) } }
                }
            }
        }
        return AggregatePage.of(refs, limit)
    }

    /**
     * One statement of the stamps of distinct streams ([PostgresEventLogTables] `stampRows`), autocommitted, per
     * aggregate type; stamps that overlap are written one at a time, so the later version of two wins.
     */
    @InternalWowApi
    override suspend fun stampAll(stamps: List<AccessStamp>): Int {
        val ranges = stamps.filter { it.from < it.until }
        if (!AccessStamps.disjoint(ranges)) {
            return super.stampAll(ranges)
        }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                ranges.groupBy { it.ref.type }.entries.sumOf { (type, group) ->
                    catalog.withTable(connection, type) { table ->
                        connection.prepareStatement(table.stampRows).use { update ->
                            fun array(kind: String, values: List<Any>) =
                                connection.createArrayOf(kind, values.toTypedArray())
                            update.setArray(1, array("text", group.map { AccessValues.text(it.access) }))
                            update.setArray(2, array("int4", group.map { it.asOf }))
                            update.setArray(3, array("text", group.map { it.ref.id }))
                            update.setArray(4, array("text", group.map { it.ref.tenantId }))
                            update.setArray(5, array("int4", group.map { it.from }))
                            update.setArray(6, array("int4", group.map { it.until }))
                            update.executeUpdate()
                        }
                    }
                }
            }
        }
    }

    /** Whether [type]'s table exists: this log made sure of it, or the registry lists it. */
    override suspend fun holds(type: AggregateType): Boolean = dispatcher.jdbc {
        dataSource.autoCommitted { catalog.existing(it, type) != null }
    }

    /**
     * The registry's `access_walk` of [type] ([PostgresAccessWalks]); none before the type's table is created, which
     * the read does not do: a type has a registry row from its table's creation on.
     */
    override suspend fun walk(type: AggregateType): AccessWalk? = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables.walk).use { query ->
                query.setString(1, context)
                query.setString(2, type.name)
                query.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1)?.let(PostgresAccessWalks::walkOf) else null
                }
            }
        }
    }

    /**
     * One conditional update of the registry row: concurrent claims serialize on it. A type whose table does not exist
     * has no row, and the update matches none.
     */
    override suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables.claimWalk).use { update ->
                update.setString(1, PostgresAccessWalks.textOf(walk))
                update.setString(2, context)
                update.setString(3, type.name)
                update.setNullableString(4, expected)
                update.executeUpdate() == 1
            }
        }
    }

    override suspend fun advance(
        type: AggregateType,
        schema: String,
        cursor: String?,
        walked: Long,
        completed: Boolean,
    ): Boolean = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables.advanceWalk).use { update ->
                update.setString(1, PostgresAccessWalks.progressOf(cursor, walked, completed))
                update.setString(2, context)
                update.setString(3, type.name)
                update.setString(4, schema)
                update.executeUpdate() == 1
            }
        }
    }

    /** One statement: the tail and the earlier request keys, both by the version key. */
    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                catalog.withTable(connection, ref.type) { table ->
                    connection.prepareStatement(table.tail).use { query ->
                        query.setString(1, ref.id)
                        query.setString(2, ref.tenantId)
                        query.setInt(3, fromVersion)
                        query.setString(4, ref.id)
                        query.setString(5, ref.tenantId)
                        query.setInt(6, fromVersion)
                        query.setInt(7, earlier)
                        query.executeQuery().use { rows ->
                            val streams = ArrayList<EventStreamRecord>()
                            val keys = ArrayList<CommittedRequest>()
                            while (rows.next()) {
                                if (rows.getInt("part") == 0) {
                                    streams += logRows.streamOf(table.aggregate, rows)
                                } else {
                                    keys += CommittedRequest(
                                        rows.getInt("version"),
                                        rows.getString("request_id"),
                                        rows.getString("command_type"),
                                    )
                                }
                            }
                            AggregateTail(streams, keys.asReversed())
                        }
                    }
                }
            }
        }
    }

    /**
     * The read of [store]'s snapshots with this log's tails in one statement, [store] keeping this context's snapshots
     * in this log's database: the snapshot, the tail from its version and the request keys before it, as one snapshot
     * of the database sees them; a failure of the statement is thrown, for the kernel to read apart, warn and count it.
     */
    internal fun snapshotTailsWith(store: PostgresSnapshotStore): SnapshotTailReader {
        val shared = { type: String ->
            checkNotNull(store.sharedTable(dataSource, context, type)) {
                "The snapshots of $context are not in the database of its log."
            }
        }
        return SnapshotTailReader { ref, earlier ->
            require(earlier >= 0) { "At most a non-negative number of earlier requests." }
            dispatcher.jdbc {
                dataSource.autoCommitted { connection ->
                    val snapshot = shared(ref.type.name)
                    catalog.withTable(connection, ref.type) { table ->
                        connection.readSnapshotTail(table.tailWithSnapshot(snapshot), store, ref, earlier) {
                            logRows.streamOf(table.aggregate, it)
                        }
                    }
                }
            }
        }
    }

    /** A page of [VersionPages.SIZE] entries a statement, as [read]. */
    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        VersionPages.byVersion(fromVersion, toVersion, { it.stream.version }) { from ->
            dispatcher.jdbc {
                dataSource.autoCommitted { connection ->
                    catalog.withTable(connection, ref.type) { table ->
                        connection.prepareStatement(table.entries).use { query ->
                            query.setString(1, ref.id)
                            query.setString(2, ref.tenantId)
                            query.setInt(3, from)
                            query.setInt(4, toVersion)
                            query.setInt(5, VersionPages.SIZE)
                            query.executeQuery().use { rows ->
                                rows.map { logRows.entryOf(it.getInt("partition"), table.aggregate, it) }
                            }
                        }
                    }
                }
            }
        }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            catalog.withTable(connection, ref.type) { table ->
                connection.prepareStatement(table.position).use { query ->
                    query.setString(1, ref.id)
                    query.setString(2, ref.tenantId)
                    query.setInt(3, version)
                    query.executeQuery().use { rows ->
                        if (rows.next()) LogPosition(rows.getInt("partition"), rows.getLong("sequence")) else null
                    }
                }
            }
        }
    }

    /** The stream committed for [requestId] within [tenantId] in [type]'s table: one indexed read of one table. */
    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                catalog.withTable(connection, type) { lookups.findByRequestIdIn(connection, it, tenantId, requestId) }
            }
        }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var last = after ?: 0L
        var idle = pacer.interval
        var woken = false
        while (true) {
            val mark = pacer.mark(partition)
            val batch = dispatcher.jdbc { dataSource.autoCommitted { readTail(it, partition, last) } }
            batch.entries.forEach {
                emit(it)
                last = it.position.sequence
            }
            if (!batch.again) {
                val pace = pacer.await(partition, mark, batch.entries.isNotEmpty(), idle, woken && !batch.behind)
                idle = pace.idle
                woken = pace.woken
            }
        }
    }

    /**
     * One read of the tail after [last]: the contiguous run of entries from `last + 1`, and whether to read again at
     * once. A run shorter than the partition's counter calls for means an aggregate table this log does not know yet:
     * it reads the registry and, when that finds new tables, reads again at once.
     */
    private fun readTail(connection: Connection, partition: Int, last: Long): TailBatch {
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
    private class TailBatch(val entries: List<LogEntry>, val again: Boolean, val behind: Boolean)

    /** The lineage of [partition] from its counter row: its `parent`, null for one the log began with, and `epoch`. */
    override suspend fun lineage(partition: Int): PartitionLineage {
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

    override suspend fun head(partition: Int): Long? {
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
        val DEFAULT_POLL_INTERVAL = 50.milliseconds
        val DEFAULT_MAX_POLL_INTERVAL = 1.seconds
        const val DEFAULT_TAIL_BATCH = 500

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
