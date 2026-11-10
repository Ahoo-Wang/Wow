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
import me.ahoo.wow.postgres.PostgresLogCatalog.Companion.UNDEFINED_TABLE
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
import me.ahoo.wow.spi.log.notAppendedAfter
import me.ahoo.wow.spi.log.requireChains
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException
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
            var sent = false
            try {
                dataSource.autoCommitted { connection ->
                    catalog.tableOf(connection, stream.ref.type)
                    sent = true
                    catalog.withTable(connection, stream.ref.type) {
                        appendIn(connection, it, partition, stream, transport)
                    }
                }
            } catch (failure: SQLException) {
                unsentOrOutcome(failure, sent)
            }.also { if (it is AppendOutcome.Committed) pacer.committed(partition) }
        }

    private fun appendIn(
        connection: Connection,
        table: AggregateLogTable,
        partition: Int,
        stream: EventStreamRecord,
        transport: ByteArray?,
    ): AppendOutcome {
        try {
            val sequence = connection.prepareStatement(table.append).use { append ->
                append.setString(1, stream.ref.id)
                append.setString(2, stream.ref.tenantId)
                append.setInt(3, partition)
                append.setInt(4, stream.version - 1)
                append.setInt(5, partition)
                logRows.bindStream(append, 6, stream, transport)
                append.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
            return if (sequence == null) {
                conflictOf(connection, table, stream)
            } else {
                AppendOutcome.Committed(LogPosition(partition, sequence))
            }
        } catch (failure: PSQLException) {
            // The table is gone: the statement ran nothing, and the caller makes sure of the table again.
            if (failure.sqlState == UNDEFINED_TABLE) throw failure
            return when (failure.serverErrorMessage?.constraint) {
                table.versionKey -> conflictOf(connection, table, stream)
                table.requestKey -> lookups.findByRequestIdIn(connection, table, stream.ref.tenantId, stream.requestId)
                    ?.let { AppendOutcome.AlreadyCommitted(it) }
                    ?: AppendOutcome.Unknown(failure)

                else -> outcomeOf(failure)
            }
        }
    }

    /**
     * Group commit: the streams of one partition in one autocommitted statement
     * ([PostgresEventLogTables.appendGroup]), so a group costs one round trip, as one [append] does.
     *
     * **Version checks.** Each stream keeps its own check: the statement reads every aggregate's version and takes a
     * sequence only for the streams exactly one version ahead of theirs, bumping the partition's
     * counter once by their count `k` and writing them at the `k` sequences after it, in the group's order. A stream
     * that does not fit gets its conflict, or its duplicate when its request is committed, as [append]
     * answers it; the others commit.
     *
     * **No gaps**. The bump and the inserts are one statement: they commit together or not at
     * all, and the counter row stays locked until then, so the order in which commits become visible is the sequence
     * order, whichever node appends to the partition. Another node may append to the partition at once (Local mode
     * during a handover): its statement queues on the counter row. When it commits a version a stream of
     * the group needs after this statement read the versions, the group's insert hits the unique version key; a stream
     * repeating a committed request hits the request key; a value PostgreSQL refuses fails the statement too, and so
     * does PostgreSQL ending a deadlock with it (`40P01`; the group takes its locks in one order, so none is expected).
     * Each rolls the whole statement back, the bump included, and is a definite abort: the streams are then appended one by
     * one, each getting the outcome [append] gives it, and the rest commit (as MongoDB's group commit does).
     *
     * **Unknown.** Any other failure, a connection lost while the statement runs included, leaves the group's commit
     * unknown: every stream of it is [AppendOutcome.Unknown], never appended again one by one.
     *
     * **Several partitions**. A group may hold the streams of several
     * partitions, which the append queue's mergers gather into one call: one statement then bumps each partition's
     * counter by its streams that fit, in partition order ([PostgresEventLogTables.appendGroupAcross]), so the group
     * costs one round trip and one commit. A definite abort of it is retried a partition at a time, each as a group of
     * one partition: a stream's conflict or duplicate decides its own partition's group only.
     *
     * A group of more than [MAX_GROUP] streams is committed [MAX_GROUP] at a time, keeping a statement's parameters
     * within the protocol's limit.
     */
    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
        if (streams.size <= 1) {
            return streams.map { (stream, transport) -> append(stream, transport) }
        }
        require(streams.all { (stream) -> stream.ref.type.context == context }) {
            "A group commit appends streams of $context."
        }
        requireChains(streams)
        val parts = streams.map { (stream) -> Partitioner.partitionOf(stream.ref, partitions) }
        return dispatcher.jdbc {
            val outcomes = arrayOfNulls<AppendOutcome>(streams.size)
            chunksOf(streams, MAX_GROUP).forEach { chunk ->
                appendChunk(chunk.map { parts[it.index] }, chunk.map { it.value }).forEachIndexed { at, outcome ->
                    outcomes[chunk[at].index] = outcome
                }
            }
            outcomes.map { checkNotNull(it) }
        }.also { outcomes ->
            parts.withIndex().filter { outcomes[it.index] is AppendOutcome.Committed }.map { it.value }.distinct()
                .forEach(pacer::committed)
        }
    }

    /** A group may hold streams of several partitions. */
    override val appendsAcrossPartitions: Boolean get() = true

    /**
     * One statement's [group], each stream of the partition in [parts] beside it: their outcomes, every one the same
     * when the statement's outcome is a failure.
     */
    private fun appendChunk(parts: List<Int>, group: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
        var sent = false
        return try {
            dataSource.autoCommitted { connection ->
                val types = group.map { (stream) -> stream.ref.type }.distinct()
                types.forEach { catalog.tableOf(connection, it) }
                sent = true
                try {
                    appendGroupsIn(connection, parts, group)
                } catch (failure: PSQLException) {
                    // A table is gone: the statement ran nothing ([withTable]); make sure of them again, once.
                    if (failure.sqlState != UNDEFINED_TABLE) throw failure
                    types.forEach { catalog.forget(it.name, catalog.tableOf(connection, it)) }
                    appendGroupsIn(connection, parts, group)
                }
            }
        } catch (failure: SQLException) {
            val outcome = unsentOrOutcome(failure, sent)
            group.map { outcome }
        }
    }

    /**
     * [group] in one statement, each stream of the partition in [parts] beside it: the single-partition statement
     * ([PostgresEventLogTables.appendGroup]) when they are of one, the cross-partition one
     * ([PostgresEventLogTables.appendGroupAcross]) otherwise. A definite abort appends them again: one
     * by one in one partition, a partition at a time across several.
     */
    private fun appendGroupsIn(
        connection: Connection,
        parts: List<Int>,
        group: List<Pair<EventStreamRecord, ByteArray?>>,
    ): List<AppendOutcome> {
        val across = parts.distinct().size > 1
        val tablesOf = group.map { (stream) -> catalog.tableOf(connection, stream.ref.type) }
        // Sorted: every group takes its tables' request keys in one order ([PostgresEventLogTables.appendGroup]).
        val tables = tablesOf.distinctBy { it.base }.sortedBy { it.base }
        val branches = tablesOf.map { table -> tables.indexOf(table) }
        val sql = if (across) {
            this.tables.appendGroupAcross(
                tables,
                branches
            )
        } else {
            this.tables.appendGroup(parts[0], tables, branches)
        }
        val rows = try {
            connection.prepareStatement(sql).use { statement ->
                group.forEachIndexed { index, (stream, transport) ->
                    if (across) {
                        val first = index * PostgresEventLogTables.ACROSS_STREAM_PARAMETERS + 1
                        statement.setInt(first, parts[index])
                        logRows.bindStream(statement, first + 1, stream, transport)
                    } else {
                        logRows.bindStream(
                            statement,
                            index * AggregateLogTable.STREAM_PARAMETERS + 1,
                            stream,
                            transport
                        )
                    }
                }
                statement.executeQuery().use { rows ->
                    rows.map { it.getInt("version") to it.getLong("sequence").takeUnless { _ -> it.wasNull() } }
                }
            }
        } catch (failure: PSQLException) {
            val constraint = failure.serverErrorMessage?.constraint
            // PostgreSQL rolls back the statement it picks to end a deadlock: that is a definite abort too.
            val definite = PostgresFailures.isRefused(failure) || failure.sqlState == DEADLOCK_DETECTED ||
                tables.any { constraint == it.versionKey || constraint == it.requestKey }
            if (!definite) throw failure
            return if (across) {
                appendByPartition(
                    connection,
                    parts,
                    group
                )
            } else {
                appendAlone(connection, parts[0], group, tablesOf)
            }
        }
        val failed = HashMap<AggregateRef, AppendOutcome>()
        return group.mapIndexed { index, (stream) ->
            val (version, sequence) = rows[index]
            failed[stream.ref] ?: if (sequence == null) {
                duplicateOr(connection, tablesOf[index], stream) { AppendOutcome.Conflict(version) }
                    .also { failed[stream.ref] = notAppendedAfter(stream, it) }
            } else {
                AppendOutcome.Committed(LogPosition(parts[index], sequence))
            }
        }
    }

    /** [group] a partition at a time, each its own group, whose conflicts and duplicates are its own. */
    private fun appendByPartition(
        connection: Connection,
        parts: List<Int>,
        group: List<Pair<EventStreamRecord, ByteArray?>>,
    ): List<AppendOutcome> {
        val outcomes = arrayOfNulls<AppendOutcome>(group.size)
        group.indices.groupBy { parts[it] }.values.forEach { indexes ->
            appendGroupsIn(
                connection,
                indexes.map { parts[it] },
                indexes.map { group[it] }
            ).forEachIndexed { at, outcome ->
                outcomes[indexes[at]] = outcome
            }
        }
        return outcomes.map { checkNotNull(it) }
    }

    /**
     * [group] appended one by one, after its statement aborted for one of them: an aggregate's later streams are not
     * appended once one did not commit ([EventLog.appendAll]).
     */
    private fun appendAlone(
        connection: Connection,
        partition: Int,
        group: List<Pair<EventStreamRecord, ByteArray?>>,
        tablesOf: List<AggregateLogTable>,
    ): List<AppendOutcome> {
        val failed = HashMap<AggregateRef, AppendOutcome>()
        return group.mapIndexed { index, (stream, transport) ->
            failed[stream.ref] ?: try {
                appendIn(connection, tablesOf[index], partition, stream, transport)
            } catch (alone: SQLException) {
                outcomeOf(alone)
            }.also { if (it !is AppendOutcome.Committed) failed[stream.ref] = notAppendedAfter(stream, it) }
        }
    }

    /**
     * The outcome of an append that failed with [failure]: [AppendOutcome.Rejected] when PostgreSQL refused a value of
     * the stream, for good and committing nothing (a `\u0000`, a key too long for its index);
     * otherwise whether it committed is unknown.
     */
    /**
     * The outcome of an append that failed with [failure]; [sent] says whether its statement may have reached the
     * server. One that surely did not, failing to get a connection or the table, wrote nothing: a transient failure is
     * thrown, so the command is answered with the retryable storage error rather than an unknown outcome
     * (unknown is kept for what may have reached the store).
     */
    private fun unsentOrOutcome(failure: SQLException, sent: Boolean): AppendOutcome =
        if (!sent && PostgresFailures.isTransient(failure)) throw failure else outcomeOf(failure)

    private fun outcomeOf(failure: SQLException): AppendOutcome =
        if (PostgresFailures.isRefused(failure)) {
            AppendOutcome.Rejected(PostgresFailures.refusal(failure))
        } else {
            AppendOutcome.Unknown(failure)
        }

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

    /**
     * The outcome of [stream] refused for its version: [AppendOutcome.AlreadyCommitted] when its request is committed
     * (a retry of one request at one version collides on both keys, and the version key is checked
     * first), else the conflict, with the aggregate's current version.
     */
    private fun conflictOf(connection: Connection, table: AggregateLogTable, stream: EventStreamRecord): AppendOutcome =
        duplicateOr(connection, table, stream) {
            AppendOutcome.Conflict(lookups.currentVersion(connection, table, stream.ref))
        }

    /** [AppendOutcome.AlreadyCommitted] when [stream]'s request is committed, else [conflict]. */
    private inline fun duplicateOr(
        connection: Connection,
        table: AggregateLogTable,
        stream: EventStreamRecord,
        conflict: () -> AppendOutcome.Conflict,
    ): AppendOutcome =
        lookups.findByRequestIdIn(connection, table, stream.ref.tenantId, stream.requestId)
            ?.let { AppendOutcome.AlreadyCommitted(it) }
            ?: conflict()

    private companion object {
        val DEFAULT_POLL_INTERVAL = 50.milliseconds
        val DEFAULT_MAX_POLL_INTERVAL = 1.seconds
        const val DEFAULT_TAIL_BATCH = 500

        /** The most streams one group commit statement takes: 17 parameters each, well within the protocol's 65535. */
        const val MAX_GROUP = 1000

        /** SQLSTATE `40P01`: the statement was rolled back to end a deadlock. */
        const val DEADLOCK_DETECTED = "40P01"

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

/** A stream with its transport, as [EventLog.appendAll] takes it. */
private typealias Appended = Pair<EventStreamRecord, ByteArray?>

/**
 * [streams] cut into groups of about [size] each, with their places in [streams]: an aggregate's streams go into the
 * group of its first one, whose statement checks each against its predecessor ([PostgresEventLogTables.appendGroup]).
 */
private fun chunksOf(streams: List<Appended>, size: Int): List<List<IndexedValue<Appended>>> {
    val chunks = ArrayList<ArrayList<IndexedValue<Appended>>>()
    val chunkOf = HashMap<AggregateRef, ArrayList<IndexedValue<Appended>>>()
    streams.withIndex().forEach { entry ->
        val ref = entry.value.first.ref
        val chunk = chunkOf[ref]
            ?: chunks.lastOrNull()?.takeIf { it.size < size }
            ?: ArrayList<IndexedValue<Appended>>().also { chunks += it }
        chunk += entry
        chunkOf[ref] = chunk
    }
    return chunks
}
