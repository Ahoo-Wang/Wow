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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.postgres.PostgresLogCatalog.Companion.UNDEFINED_TABLE
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.notAppendedAfter
import me.ahoo.wow.spi.store.StoreCallMarks
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The appends of a [PostgresEventLog]: one stream ([append]) and a group commit ([appendAll]), and the outcome each
 * stream gets.
 */
internal class PostgresLogAppender(
    private val dataSource: DataSource,
    private val tables: PostgresEventLogTables,
    private val logRows: PostgresLogRows,
    private val lookups: PostgresLogLookups,
    private val catalog: PostgresLogCatalog,
) {
    /** [stream], of [partition], in one autocommitted statement; [marks] when the call is clocked. */
    fun append(
        partition: Int,
        stream: EventStreamRecord,
        transport: ByteArray?,
        marks: StoreCallMarks?,
    ): AppendOutcome {
        var sent = false
        return try {
            dataSource.autoCommitted { connection ->
                marks?.acquired()
                catalog.tableOf(connection, stream.ref.type)
                sent = true
                catalog.withTable(connection, stream.ref.type) {
                    appendIn(connection, it, partition, stream, transport, marks)
                }
            }
        } catch (failure: SQLException) {
            unsentOrOutcome(failure, sent)
        }
    }

    private fun appendIn(
        connection: Connection,
        table: AggregateLogTable,
        partition: Int,
        stream: EventStreamRecord,
        transport: ByteArray?,
        marks: StoreCallMarks?,
    ): AppendOutcome {
        try {
            val sequence = connection.prepareStatement(table.append).use { append ->
                append.setString(1, stream.ref.id)
                append.setString(2, stream.ref.tenantId)
                append.setInt(3, partition)
                append.setInt(4, stream.version - 1)
                append.setInt(5, partition)
                logRows.bindStream(append, 6, stream, transport)
                marks?.sent()
                append.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
                    .also { marks?.read() }
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
     * within the protocol's limit. A chunk whose statement surely did not leave throws ([unsentOrOutcome]) only while
     * no chunk of the call was sent: once one was, the call returns, and that chunk's streams and the streams of the
     * chunks after it, which it does not try, are [AppendOutcome.Unknown] ([EventLog.appendAll]).
     *
     * [marks], when the call is clocked: its first connection acquired, its first statement sent and its last
     * statement's result read, a definite abort's appends again among them.
     */
    fun appendAll(parts: List<Int>, streams: List<Append>, marks: StoreCallMarks?): List<AppendOutcome> {
        val outcomes = arrayOfNulls<AppendOutcome>(streams.size)
        var sent = false
        var stopped: SQLException? = null
        chunksOf(streams, MAX_GROUP).forEach { chunk ->
            val group = chunk.map { it.value }
            val chunkOutcomes = stopped?.let { failure -> group.map { AppendOutcome.Unknown(failure) } } ?: try {
                appendChunk(chunk.map { parts[it.index] }, group, marks).also { sent = true }
            } catch (failure: SQLException) {
                if (!sent) throw failure
                stopped = failure
                group.map { AppendOutcome.Unknown(failure) }
            }
            chunkOutcomes.forEachIndexed { at, outcome -> outcomes[chunk[at].index] = outcome }
        }
        return outcomes.map { checkNotNull(it) }
    }

    /**
     * One statement's [group], each stream of the partition in [parts] beside it: their outcomes, every one the same
     * when the statement's outcome is a failure.
     */
    private fun appendChunk(parts: List<Int>, group: List<Append>, marks: StoreCallMarks?): List<AppendOutcome> {
        var sent = false
        return try {
            dataSource.autoCommitted { connection ->
                marks?.acquired()
                val types = group.map { it.stream.ref.type }.distinct()
                types.forEach { catalog.tableOf(connection, it) }
                sent = true
                try {
                    appendGroupsIn(connection, parts, group, marks)
                } catch (failure: PSQLException) {
                    // A table is gone: the statement ran nothing ([withTable]); make sure of them again, once.
                    if (failure.sqlState != UNDEFINED_TABLE) throw failure
                    types.forEach { catalog.forget(it.name, catalog.tableOf(connection, it)) }
                    appendGroupsIn(connection, parts, group, marks)
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
        group: List<Append>,
        marks: StoreCallMarks?,
    ): List<AppendOutcome> {
        val across = parts.distinct().size > 1
        val tablesOf = group.map { catalog.tableOf(connection, it.stream.ref.type) }
        // Sorted: every group takes its tables' request keys in one order ([PostgresEventLogTables.appendGroup]).
        val tables = tablesOf.distinctBy { it.base }.sortedBy { it.base }
        val branches = tablesOf.map { table -> tables.indexOf(table) }
        val sql = if (across) {
            this.tables.appendGroupAcross(tables, branches)
        } else {
            this.tables.appendGroup(parts[0], tables, branches)
        }
        val rows = try {
            runGroup(connection, sql, across, parts, group, marks)
        } catch (failure: PSQLException) {
            val constraint = failure.serverErrorMessage?.constraint
            // PostgreSQL rolls back the statement it picks to end a deadlock: that is a definite abort too.
            val definite = PostgresFailures.isRefused(failure) || failure.sqlState == DEADLOCK_DETECTED ||
                tables.any { constraint == it.versionKey || constraint == it.requestKey }
            if (!definite) throw failure
            return if (across) {
                appendByPartition(connection, parts, group, marks)
            } else {
                appendAlone(connection, parts[0], group, tablesOf, marks)
            }
        }
        val failed = HashMap<AggregateRef, AppendOutcome>()
        return group.mapIndexed { index, entry ->
            val stream = entry.stream
            val (version, sequence) = rows[index]
            failed[stream.ref] ?: if (sequence == null) {
                duplicateOr(connection, tablesOf[index], stream) { AppendOutcome.Conflict(version) }
                    .also { failed[stream.ref] = notAppendedAfter(stream, it) }
            } else {
                AppendOutcome.Committed(LogPosition(parts[index], sequence))
            }
        }
    }

    /** [group]'s statement [sql], its streams bound, run: each stream's version, and its sequence when appended. */
    private fun runGroup(
        connection: Connection,
        sql: String,
        across: Boolean,
        parts: List<Int>,
        group: List<Append>,
        marks: StoreCallMarks?,
    ): List<Pair<Int, Long?>> = connection.prepareStatement(sql).use { statement ->
        group.forEachIndexed { index, entry ->
            if (across) {
                val first = index * PostgresEventLogTables.ACROSS_STREAM_PARAMETERS + 1
                statement.setInt(first, parts[index])
                logRows.bindStream(statement, first + 1, entry.stream, entry.transport)
            } else {
                logRows.bindStream(
                    statement,
                    index * AggregateLogTable.STREAM_PARAMETERS + 1,
                    entry.stream,
                    entry.transport
                )
            }
        }
        marks?.sent()
        statement.executeQuery().use { rows ->
            rows.map { it.getInt("version") to it.getLong("sequence").takeUnless { _ -> it.wasNull() } }
        }.also { marks?.read() }
    }

    /** [group] a partition at a time, each its own group, whose conflicts and duplicates are its own. */
    private fun appendByPartition(
        connection: Connection,
        parts: List<Int>,
        group: List<Append>,
        marks: StoreCallMarks?,
    ): List<AppendOutcome> {
        val outcomes = arrayOfNulls<AppendOutcome>(group.size)
        group.indices.groupBy { parts[it] }.values.forEach { indexes ->
            val partition = indexes.map { group[it] }
            appendGroupsIn(connection, indexes.map { parts[it] }, partition, marks).forEachIndexed { at, outcome ->
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
        group: List<Append>,
        tablesOf: List<AggregateLogTable>,
        marks: StoreCallMarks?,
    ): List<AppendOutcome> {
        val failed = HashMap<AggregateRef, AppendOutcome>()
        return group.mapIndexed { index, entry ->
            val stream = entry.stream
            val transport = entry.transport
            failed[stream.ref] ?: try {
                appendIn(connection, tablesOf[index], partition, stream, transport, marks)
            } catch (alone: SQLException) {
                outcomeOf(alone)
            }.also { if (it !is AppendOutcome.Committed) failed[stream.ref] = notAppendedAfter(stream, it) }
        }
    }

    /**
     * The outcome of an append that failed with [failure]; [sent] says whether its statement may have reached the
     * server. One that surely did not, failing to get a connection or the table, wrote nothing, whatever the failure:
     * it is thrown, translated as every failure of the log is (a transient one is the retryable storage error), never
     * an unknown outcome, which is kept for what may have reached the store. In a group, [appendAll] decides whether a
     * later chunk may still throw.
     */
    private fun unsentOrOutcome(failure: SQLException, sent: Boolean): AppendOutcome =
        if (!sent) throw failure else outcomeOf(failure)

    /**
     * The outcome of an append that failed with [failure]: [AppendOutcome.Rejected] when PostgreSQL refused a value of
     * the stream, for good and committing nothing (a `\u0000`, a key too long for its index);
     * otherwise whether it committed is unknown.
     */
    private fun outcomeOf(failure: SQLException): AppendOutcome =
        if (PostgresFailures.isRefused(failure)) {
            AppendOutcome.Rejected(PostgresFailures.refusal(failure))
        } else {
            AppendOutcome.Unknown(failure)
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
        /** The most streams one group commit statement takes: 17 parameters each, well within the protocol's 65535. */
        const val MAX_GROUP = 1000

        /** SQLSTATE `40P01`: the statement was rolled back to end a deadlock. */
        const val DEADLOCK_DETECTED = "40P01"
    }
}

/**
 * [streams] cut into groups of about [size] each, with their places in [streams]: an aggregate's streams go into the
 * group of its first one, whose statement checks each against its predecessor ([PostgresEventLogTables.appendGroup]).
 */
private fun chunksOf(streams: List<Append>, size: Int): List<List<IndexedValue<Append>>> {
    val chunks = ArrayList<ArrayList<IndexedValue<Append>>>()
    val chunkOf = HashMap<AggregateRef, ArrayList<IndexedValue<Append>>>()
    streams.withIndex().forEach { entry ->
        val ref = entry.value.stream.ref
        val chunk = chunkOf[ref]
            ?: chunks.lastOrNull()?.takeIf { it.size < size }
            ?: ArrayList<IndexedValue<Append>>().also { chunks += it }
        chunk += entry
        chunkOf[ref] = chunk
    }
    return chunks
}
