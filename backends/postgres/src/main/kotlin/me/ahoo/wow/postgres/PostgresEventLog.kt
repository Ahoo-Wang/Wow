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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.access.AggregatePage
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.requireChains
import me.ahoo.wow.spi.store.StoreCallMarks
import javax.sql.DataSource

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
 * **Polling**. A tail polls every [PostgresEventLogOptions.pollInterval] (50 ms by default) while it finds entries;
 * each poll that finds none doubles the wait, up to [PostgresEventLogOptions.maxPollInterval] (1 s), so an idle
 * cluster's partitions cost a statement a second each rather than twenty. A commit this log makes wakes the
 * partition's tails at once, and a poll it woke that finds the partition caught up keeps the backoff, so a commit
 * costs one read of the tail; an entry another node appends is read within the longest interval. `LISTEN/NOTIFY`
 * would wake the tails of every node; it is not implemented: the bounded backoff and the wake on this log's commits
 * keep an idle cluster cheap without a listening connection per node.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21).
 */
@InternalWowApi
class PostgresEventLog(
    private val dataSource: DataSource,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** Its settings: how its tails poll, and how it manages its tables. */
    options: PostgresEventLogOptions = PostgresEventLogOptions(),
) : EventLog,
    AccessWalks {
    private val ddl = options.ddl

    private val tables = PostgresEventLogTables(context)

    /** A stream's columns, bound and read back. */
    private val logRows = PostgresLogRows(context)

    /** Where a stream is found by a key. */
    private val lookups = PostgresLogLookups(logRows)

    /** Validates the partition count first: a log of another count is refused when it is built. */
    private val pacer =
        TailPacer(Partitioner.requirePartitions(partitions), options.pollInterval, options.maxPollInterval)

    /** The aggregate tables of the context: created, registered and found. */
    private val catalog = PostgresLogCatalog(dataSource, context, partitions, ddl, tables)

    /** The appends: one stream, and a group commit. */
    private val appender = PostgresLogAppender(dataSource, tables, logRows, lookups, catalog, options.arraysAbove)

    /** The array appends whose parameters did not bind: a defect, each logged and its group failed. */
    internal val bindFailures: Long get() = appender.bindFailures

    /** The reads of one aggregate's streams. */
    private val aggregateReads = PostgresAggregateReads(dataSource, dispatcher, context, catalog, logRows, lookups)

    /** The reads of a partition: its entries in sequence order, its head and its lineage. */
    private val tailReads =
        PostgresTailReads(dataSource, dispatcher, context, partitions, options.tailBatch, catalog, tables, logRows)

    /** The access attributes: the stamps of the streams, the pages of aggregates, and the access walks. */
    private val logAccess = PostgresLogAccess(dataSource, dispatcher, context, catalog, tables)

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
        appendOne(stream, transport, null)

    private suspend fun appendOne(
        stream: EventStreamRecord,
        transport: ByteArray?,
        marks: StoreCallMarks?,
    ): AppendOutcome = dispatcher.jdbc {
        marks?.onThread()
        require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
        val partition = Partitioner.partitionOf(stream.ref, partitions)
        appender.append(partition, stream, transport, marks)
            .also { if (it is AppendOutcome.Committed) pacer.committed(partition) }
            .also { marks?.released() }
    }

    /**
     * Group commit: [PostgresLogAppender.appendAll]. A call clocked by the append queue ([StoreCallMarks] in its
     * context) is marked from its entry to its connection given back.
     */
    override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
        val marks = currentCoroutineContext()[StoreCallMarks]?.apply { entered() }
        if (streams.size <= 1) {
            return streams.map { appendOne(it.stream, it.transport, marks) }
        }
        require(streams.all { it.stream.ref.type.context == context }) {
            "A group commit appends streams of $context."
        }
        requireChains(streams)
        val parts = streams.map { Partitioner.partitionOf(it.stream.ref, partitions) }
        return dispatcher.jdbc {
            marks?.onThread()
            appender.appendAll(parts, streams, marks).also { marks?.released() }
        }.also { outcomes ->
            parts.withIndex().filter { outcomes[it.index] is AppendOutcome.Committed }.map { it.value }.distinct()
                .forEach(pacer::committed)
        }
    }

    /** A group may hold streams of several partitions. */
    override val appendsAcrossPartitions: Boolean get() = true

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        aggregateReads.read(ref, fromVersion, toVersion)

    @InternalWowApi
    override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int =
        logAccess.stamp(ref, access, asOf, from, until)

    override suspend fun aggregates(type: AggregateType, after: String?, limit: Int): AggregatePage =
        logAccess.aggregates(type, after, limit)

    /**
     * One statement of the stamps of distinct streams ([PostgresEventLogTables] `stampRows`), autocommitted, per
     * aggregate type; stamps that overlap are written one at a time, so the later version of two wins. MongoDB's log
     * writes overlapping stamps in its one bulk write instead, each update guarded by the version it stamps: both leave
     * the later version's attributes.
     */
    @InternalWowApi
    override suspend fun stampAll(stamps: List<AccessStamp>): Int {
        val ranges = stamps.filter { it.from < it.until }
        if (!AccessStamps.disjoint(ranges)) {
            return super.stampAll(ranges)
        }
        return logAccess.stampAll(ranges)
    }

    override suspend fun holds(type: AggregateType): Boolean = logAccess.holds(type)

    override suspend fun walk(type: AggregateType): AccessWalk? = logAccess.walk(type)

    override suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean =
        logAccess.claim(type, walk, expected)

    override suspend fun advance(
        type: AggregateType,
        schema: String,
        cursor: String?,
        walked: Long,
        completed: Boolean,
    ): Boolean = logAccess.advance(type, schema, cursor, walked, completed)

    override fun serve(schemas: Map<AggregateType, String>) {
        catalog.served = schemas.filterKeys { it.context == context }.mapKeys { (type, _) -> type.name }
    }

    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail =
        aggregateReads.readTail(ref, fromVersion, earlier)

    /** The read of [store]'s snapshots with this log's tails: [PostgresAggregateReads.snapshotTailsWith]. */
    internal fun snapshotTailsWith(store: PostgresSnapshotStore): SnapshotTailReader =
        aggregateReads.snapshotTailsWith(store)

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        aggregateReads.readEntries(ref, fromVersion, toVersion)

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? =
        aggregateReads.positionOf(ref, version)

    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        aggregateReads.findByRequestId(type, tenantId, requestId)

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var last = after ?: 0L
        var idle = pacer.interval
        var woken = false
        while (true) {
            val mark = pacer.mark(partition)
            val batch = dispatcher.jdbc { dataSource.autoCommitted { tailReads.readTail(it, partition, last) } }
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

    override suspend fun lineage(partition: Int): PartitionLineage = tailReads.lineage(partition)

    override suspend fun head(partition: Int): Long? = tailReads.head(partition)
}
