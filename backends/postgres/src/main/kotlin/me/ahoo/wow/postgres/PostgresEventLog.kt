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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.TagsBackfill
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.notAppendedAfter
import me.ahoo.wow.spi.log.requireChains
import org.postgresql.util.PSQLException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
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
 * [findByRequestId] taking the aggregate type, reads that type's table alone; the context-wide one searches every
 * aggregate table this log knows.
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
    TagsBackfill {
    private val tables = PostgresEventLogTables(context)

    /** Validates the partition count first: a log of another count is refused when it is built. */
    private val pacer = TailPacer(Partitioner.requirePartitions(partitions), pollInterval, maxPollInterval)

    /** The aggregate tables whose creation and registration this log has made sure of. */
    private val ensured = EnsuredTables<AggregateLogTable>()

    /** The aggregate tables of the context, as of the last read of the registry, in registry order. */
    @Volatile
    private var registered: List<AggregateLogTable>? = null

    /**
     * Creates the partition counters and the registry when missing; refuses a different partition count, and a log
     * recorded in another format version ([PostgresFormat]). The aggregate tables are created on first use, or by
     * [createTable].
     */
    fun createSchema() {
        dataSource.autoCommitted { connection ->
            val structures = listOf(tables.partitionsBase, PostgresEventLogTables.REGISTRY)
                .map { it to PostgresEventLogTables.FORMAT }
            PostgresFormat.schema(connection, ddl, structures) {
                PostgresSchema.execute(connection, tables.ddl())
            }
            if (ddl == Ddl.AUTO) {
                // A counter table created before the lineage columns gets them before its counters are written.
                PostgresFormat.addColumns(
                    connection,
                    tables.partitionsBase,
                    PostgresEventLogTables.PARTITION_ADDED,
                    tables.partitionAdditions,
                )
                connection.createStatement().use { it.execute(tables.initializePartitions(partitions)) }
                // A table created before format 1's added columns gets them, so the tail reads every table.
                register(connection).forEach { addColumns(connection, it) }
                // Read again on first use, with the tables created from now on.
                registered = null
            }
            connection.prepareStatement("SELECT count(*) FROM ${tables.partitions}").use { query ->
                query.executeQuery().use { rows ->
                    rows.next()
                    val existing = rows.getInt(1)
                    check(existing == partitions) {
                        "The log of $context has $existing partitions, not $partitions: changing it is a repartition."
                    }
                }
            }
        }
    }

    /**
     * Creates and registers the log table of [aggregateName] when missing, once per log; refuses a table recorded in
     * another format version ([PostgresFormat]), and an aggregate whose table name another aggregate has.
     */
    fun createTable(aggregateName: String) {
        dataSource.autoCommitted { tableOf(it, aggregateName) }
    }

    /** The table of [aggregate], created and registered when this log has not yet made sure it is. */
    private fun tableOf(connection: Connection, aggregate: String): AggregateLogTable =
        ensured.get(aggregate) { ensure(connection, tables.aggregate(aggregate)) }

    private fun tableOf(connection: Connection, type: AggregateType): AggregateLogTable {
        require(type.context == context) { "$type is not in context $context." }
        return tableOf(connection, type.name)
    }

    /**
     * Runs [block] on [type]'s table. When the table is gone (`42P01`: dropped since this log made sure of it, by a
     * test or a migration), the statement ran nothing: it makes sure of the table again and runs [block] once more.
     */
    private inline fun <T> withTable(
        connection: Connection,
        type: AggregateType,
        block: (AggregateLogTable) -> T,
    ): T {
        val table = tableOf(connection, type)
        return try {
            block(table)
        } catch (failure: PSQLException) {
            if (failure.sqlState != UNDEFINED_TABLE) throw failure
            ensured.forget(type.name, table)
            block(tableOf(connection, type))
        }
    }

    /**
     * Claims [table]'s format, creates it and records it in the registry, in one transaction under an advisory lock,
     * so that nodes creating tables at once neither race the format record nor two aggregates take one table name.
     */
    private fun ensure(connection: Connection, table: AggregateLogTable): AggregateLogTable = table.also {
        PostgresFormat.schema(connection, ddl, listOf(table.base to PostgresEventLogTables.FORMAT)) {
            PostgresSchema.execute(connection, listOf(table.ddl()))
            connection.prepareStatement(tables.register).use { register ->
                register.setString(1, context)
                register.setString(2, table.aggregate)
                register.setString(3, table.base)
                register.executeUpdate()
            }
            val holder = connection.prepareStatement(tables.holder).use { query ->
                query.setString(1, table.base)
                query.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1) to rows.getString(2)
                }
            }
            check(holder == context to table.aggregate) {
                "The aggregate ${table.aggregate} of $context takes the log table ${table.base}, " +
                    "which the aggregate ${holder.second} of ${holder.first} has."
            }
        }
        if (ddl == Ddl.AUTO) {
            addColumns(connection, table)
        }
    }

    /** Adds format 1's added columns to [table] when it was created before them ([PostgresFormat.addColumns]). */
    private fun addColumns(connection: Connection, table: AggregateLogTable) =
        PostgresFormat.addColumns(connection, table.base, AggregateLogTable.ADDED, table.additions)

    /** Reads the registry again: the aggregate tables of the context, in registry order. */
    private fun register(connection: Connection): List<AggregateLogTable> {
        val names = connection.prepareStatement(tables.registered).use { query ->
            query.setString(1, context)
            query.executeQuery().use { rows -> rows.map { it.getString(1) } }
        }
        return names.map { tables.aggregate(it) }.also { registered = it }
    }

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
        dispatcher.jdbc {
            require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
            val partition = Partitioner.partitionOf(stream.ref, partitions)
            var sent = false
            try {
                dataSource.autoCommitted { connection ->
                    tableOf(connection, stream.ref.type)
                    sent = true
                    withTable(connection, stream.ref.type) { appendIn(connection, it, partition, stream, transport) }
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
                bindStream(append, 6, stream, transport)
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
                table.requestKey -> findByRequestIdIn(connection, table, stream.ref.tenantId, stream.requestId)
                    ?.let { AppendOutcome.AlreadyCommitted(it) }
                    ?: AppendOutcome.Unknown(failure)

                else -> outcomeOf(failure)
            }
        }
    }

    /** Binds [stream] and [transport] to [statement] from parameter [first] on: one per [AggregateLogTable.COLUMNS]. */
    private fun bindStream(statement: PreparedStatement, first: Int, stream: EventStreamRecord, transport: ByteArray?) {
        var index = first
        statement.setString(index++, stream.ref.tenantId)
        statement.setString(index++, stream.ref.id)
        statement.setInt(index++, stream.version)
        statement.setString(index++, stream.commandId)
        statement.setString(index++, stream.requestId)
        statement.setString(index++, stream.commandType)
        statement.setLong(index++, stream.commitTime)
        statement.setString(index++, encodeEvents(stream.events))
        statement.setNullableString(index++, stream.scope.ownerId)
        statement.setNullableString(index++, stream.scope.spaceId)
        statement.setNullableString(index++, stream.causation?.correlationId)
        statement.setNullableString(index++, stream.causation?.causationId)
        statement.setString(index++, json.writeValueAsString(stream.headers))
        statement.setBytes(index++, transport)
        val operator = stream.operator
        statement.setNullableString(index++, operator?.id)
        statement.setNullableString(index++, operator?.tenantId)
        if (operator == null) {
            statement.setNull(index++, Types.BOOLEAN)
        } else {
            statement.setBoolean(index++, operator.crossScope)
        }
        statement.setNullableString(index, stream.tags?.let(json::writeValueAsString))
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
                types.forEach { tableOf(connection, it) }
                sent = true
                try {
                    appendGroupsIn(connection, parts, group)
                } catch (failure: PSQLException) {
                    // A table is gone: the statement ran nothing ([withTable]); make sure of them again, once.
                    if (failure.sqlState != UNDEFINED_TABLE) throw failure
                    types.forEach { ensured.forget(it.name, tableOf(connection, it)) }
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
        val tablesOf = group.map { (stream) -> tableOf(connection, stream.ref.type) }
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
                        bindStream(statement, first + 1, stream, transport)
                    } else {
                        bindStream(statement, index * AggregateLogTable.STREAM_PARAMETERS + 1, stream, transport)
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

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val streams = dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                withTable(connection, ref.type) { table ->
                    connection.prepareStatement(table.read).use { query ->
                        query.setString(1, ref.id)
                        query.setString(2, ref.tenantId)
                        query.setInt(3, fromVersion)
                        query.setInt(4, toVersion)
                        query.executeQuery().use { rows -> rows.map { streamOf(table.aggregate, it) } }
                    }
                }
            }
        }
        streams.forEach { emit(it) }
    }

    @InternalWowApi
    override suspend fun untagged(type: AggregateType, after: AggregateRef?, limit: Int): List<AggregateRef> {
        require(limit >= 1) { "A page holds at least one aggregate." }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                withTable(connection, type) { table ->
                    connection.prepareStatement(table.untagged).use { query ->
                        query.setString(1, after?.id.orEmpty())
                        query.setString(2, after?.tenantId.orEmpty())
                        query.setInt(3, limit)
                        query.executeQuery().use { rows ->
                            rows.map { type.ref(it.getString("tenant_id"), it.getString("aggregate_id")) }
                        }
                    }
                }
            }
        }
    }

    /** One batch of updates, each version's only where its stream records no tags. */
    @InternalWowApi
    override suspend fun recordTags(ref: AggregateRef, tagsByVersion: Map<Int, Map<String, List<String>>>): Int {
        if (tagsByVersion.isEmpty()) return 0
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                withTable(connection, ref.type) { table ->
                    connection.prepareStatement(table.recordTags).use { update ->
                        tagsByVersion.forEach { (version, tags) ->
                            update.setString(1, json.writeValueAsString(tags))
                            update.setString(2, ref.id)
                            update.setString(3, ref.tenantId)
                            update.setInt(4, version)
                            update.addBatch()
                        }
                        update.executeBatch().sum()
                    }
                }
            }
        }
    }

    /** One statement: the tail and the earlier request keys, both by the version key. */
    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                withTable(connection, ref.type) { table ->
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
                                    streams += streamOf(table.aggregate, rows)
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
     * One statement when the snapshots keep [AggregateRef]'s snapshots in this log's database: the
     * snapshot, the tail from its version and the request keys before it, as one snapshot of the database sees them.
     * Null when they are kept elsewhere; a failure of the statement is thrown, for the kernel to read apart, warn and
     * count it.
     */
    override val snapshotTails: SnapshotTailReader = SnapshotTailReader { ref, snapshots, earlier ->
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        val store = snapshots.storeOf(ref.type) as? PostgresSnapshotStore ?: return@SnapshotTailReader null
        dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                store.sharedTable(dataSource, context, ref.type.name)?.let { snapshot ->
                    withTable(connection, ref.type) { table ->
                        connection.readSnapshotTail(table.tailWithSnapshot(snapshot), store, ref, earlier) {
                            streamOf(table.aggregate, it)
                        }
                    }
                }
            }
        }
    }

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = flow {
        val entries = dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                withTable(connection, ref.type) { table ->
                    connection.prepareStatement(table.entries).use { query ->
                        query.setString(1, ref.id)
                        query.setString(2, ref.tenantId)
                        query.setInt(3, fromVersion)
                        query.setInt(4, toVersion)
                        query.executeQuery().use { rows ->
                            rows.map { entryOf(it.getInt("partition"), table.aggregate, it) }
                        }
                    }
                }
            }
        }
        entries.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            withTable(connection, ref.type) { table ->
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
                withTable(connection, type) { findByRequestIdIn(connection, it, tenantId, requestId) }
            }
        }

    /**
     * The stream committed for [requestId] within [tenantId] in any aggregate table of the context this log knows
     * (the registry as last read, and the tables it touched since), the earliest committed when two aggregate types
     * hold it: one statement over every table.
     */
    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                val known = ((registered ?: register(connection)) + ensured.values).distinctBy { it.base }
                if (known.isEmpty()) return@autoCommitted null
                connection.prepareStatement(tables.byRequest(known)).use { query ->
                    known.indices.forEach { index ->
                        query.setString(index * 2 + 1, tenantId)
                        query.setString(index * 2 + 2, requestId)
                    }
                    query.executeQuery().use { rows ->
                        if (rows.next()) streamOf(known[rows.getInt("branch")].aggregate, rows) else null
                    }
                }
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
        val known = registered ?: register(connection)
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
                        entries += entryOf(partition, known[branch].aggregate, rows)
                    }
                }
                head to entries
            }
        }
        val run = contiguousRun(entries, last)
        val expected = minOf(head - last, tailBatch.toLong()).coerceAtLeast(0)
        val again = when {
            run.size < expected -> register(connection).map { it.base } != known.map { it.base }
            else -> run.size == tailBatch
        }
        return TailBatch(run, again, behind = run.size < expected)
    }

    /** One read of a tail: its [entries], whether to read again at once, whether the counter is ahead of them. */
    private class TailBatch(val entries: List<LogEntry>, val again: Boolean, val behind: Boolean)

    /**
     * The lineage of [partition] from its counter row: its `parent` and `epoch`, read as
     * [PartitionLineage.ROOT] when the columns are null or missing (a schema created before them).
     */
    override suspend fun lineage(partition: Int): PartitionLineage {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                connection.prepareStatement(tables.partitionRow).use { query ->
                    query.setInt(1, partition)
                    query.executeQuery().use { rows ->
                        check(rows.next()) { "The log of $context has no counter of partition $partition." }
                        val columns = (1..rows.metaData.columnCount).map { rows.metaData.getColumnName(it) }.toSet()
                        val parent = if (PARENT in columns) rows.getInt(PARENT).takeUnless { rows.wasNull() } else null
                        val epoch = if (EPOCH in columns) rows.getInt(EPOCH) else 0
                        PartitionLineage.of(parent, epoch)
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

    private fun findByRequestIdIn(
        connection: Connection,
        table: AggregateLogTable,
        tenantId: String,
        requestId: String,
    ): EventStreamRecord? = connection.prepareStatement(table.byRequest).use { query ->
        query.setString(1, tenantId)
        query.setString(2, requestId)
        query.executeQuery().use { rows -> if (rows.next()) streamOf(table.aggregate, rows) else null }
    }

    /**
     * The outcome of [stream] refused for its version: [AppendOutcome.AlreadyCommitted] when its request is committed
     * (a retry of one request at one version collides on both keys, and the version key is checked
     * first), else the conflict, with the aggregate's current version.
     */
    private fun conflictOf(connection: Connection, table: AggregateLogTable, stream: EventStreamRecord): AppendOutcome =
        duplicateOr(connection, table, stream) { AppendOutcome.Conflict(currentVersion(connection, table, stream.ref)) }

    /** [AppendOutcome.AlreadyCommitted] when [stream]'s request is committed, else [conflict]. */
    private inline fun duplicateOr(
        connection: Connection,
        table: AggregateLogTable,
        stream: EventStreamRecord,
        conflict: () -> AppendOutcome.Conflict,
    ): AppendOutcome =
        findByRequestIdIn(connection, table, stream.ref.tenantId, stream.requestId)
            ?.let { AppendOutcome.AlreadyCommitted(it) }
            ?: conflict()

    private fun currentVersion(connection: Connection, table: AggregateLogTable, ref: AggregateRef): Int =
        connection.prepareStatement(table.currentVersion).use { query ->
            query.setString(1, ref.id)
            query.setString(2, ref.tenantId)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun entryOf(partition: Int, aggregate: String, row: ResultSet) = LogEntry(
        LogPosition(partition, row.getLong("sequence")),
        streamOf(aggregate, row),
        row.getBytes("transport"),
    )

    private fun streamOf(aggregate: String, row: ResultSet) = EventStreamRecord(
        ref = AggregateType(context, aggregate).ref(row.getString("tenant_id"), row.getString("aggregate_id")),
        version = row.getInt("version"),
        commandId = row.getString("command_id"),
        requestId = row.getString("request_id"),
        commandType = row.getString("command_type"),
        commitTime = row.getLong("commit_time"),
        events = decodeEvents(row.getString("events")),
        scope = Scope(row.getString("owner_id"), row.getString("space_id")),
        causation = row.getString("correlation_id")?.let { Causation(it, row.getString("causation_id")) },
        headers = decodeStrings(row.getString("headers")),
        operator = row.getString("operator_id")?.let {
            Operator(it, row.getString("operator_tenant_id"), row.getBoolean("operator_cross_scope"))
        },
        tags = row.getString("tags")?.let(PostgresSnapshotStore::decodeTags),
    )

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun <T> ResultSet.map(row: (ResultSet) -> T): List<T> {
        val values = ArrayList<T>()
        while (next()) {
            values += row(this)
        }
        return values
    }

    private companion object {
        val DEFAULT_POLL_INTERVAL = 50.milliseconds
        val DEFAULT_MAX_POLL_INTERVAL = 1.seconds
        const val DEFAULT_TAIL_BATCH = 500

        /** The most streams one group commit statement takes: 17 parameters each, well within the protocol's 65535. */
        const val MAX_GROUP = 1000

        /** SQLSTATE `40P01`: the statement was rolled back to end a deadlock. */
        const val DEADLOCK_DETECTED = "40P01"

        /** SQLSTATE `42P01`: a table the statement names does not exist; the statement ran nothing. */
        const val UNDEFINED_TABLE = "42P01"

        /** The lineage columns of a partition's counter row. */
        const val PARENT = "parent"
        const val EPOCH = "epoch"

        /** The entries of [entries], in sequence order, that continue `last` without a gap. */
        fun contiguousRun(entries: List<LogEntry>, last: Long): List<LogEntry> {
            var next = last + 1
            return entries.takeWhile { it.position.sequence == next++ }
        }

        /** Wow's mapper: its trees keep every number exact, as `jsonb` stores them (`numeric`), so `2.50` stays. */
        val json: JsonMapper = WowJson.mapper

        /** Events as one JSON array; each payload is stored as JSON, so the log can be queried in place. */
        fun encodeEvents(events: List<EventRecord>): String {
            val array = json.createArrayNode()
            events.forEach { event ->
                array.addObject()
                    .put("name", event.name)
                    .put("revision", event.revision)
                    .set("payload", json.readTree(event.payload))
            }
            return json.writeValueAsString(array)
        }

        /** A JSON object of strings, such as the headers. */
        fun decodeStrings(text: String): Map<String, String> = buildMap {
            for ((name, value) in json.readTree(text).properties()) {
                put(name, value.asString())
            }
        }

        fun decodeEvents(text: String): List<EventRecord> = buildList {
            // ArrayNode has a map member of its own, so iterate rather than map.
            for (event in json.readTree(text) as ArrayNode) {
                add(
                    EventRecord(
                        name = event.get("name").asString(),
                        revision = event.get("revision").asInt(),
                        payload = json.writeValueAsString(event.get("payload")),
                    ),
                )
            }
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
