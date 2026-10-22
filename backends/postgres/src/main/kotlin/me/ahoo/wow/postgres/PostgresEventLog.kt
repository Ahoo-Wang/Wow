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
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.StoreDdl
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
import me.ahoo.wow.spi.log.TagsBackfill
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.postgresql.util.PSQLException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
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
 * The log takes one table per aggregate type, `wow_log__<context>__<aggregate>` (decision D162); a context-level
 * table holds one counter row per partition, so partitions, their sequences, leases and checkpoints stay per context.
 * A counter row also holds its partition's lineage, `parent` (null) and `epoch` (0) (decision D240): format 1 reserves
 * them for the online splits of 10.x, and [lineage] reads them back.
 * An append is one autocommitted statement (see [AggregateLogTable.append]): it bumps its partition's counter, which
 * locks the row until the statement commits, and inserts the stream into its aggregate's table at that sequence only
 * if the aggregate is exactly one version behind. Because the lock is held until commit, the sequence order is the
 * order commits become visible, whichever table they land in; a failed append rolls its sequence back too, so a
 * partition's sequences have no gaps. A group commit ([appendAll]) is the same statement for many streams: one
 * round trip per group. The transport is stored in its own column and read back only by [tail] and [readEntries].
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
 * two aggregate types commits twice (decision D55 makes the reuse the caller's error). The kernel's pre-check, the
 * [findByRequestId] taking the aggregate type, reads that type's table alone; the context-wide one searches every
 * aggregate table this log knows.
 *
 * **Polling** (finding A2-14). A tail polls every `pollInterval` (50 ms by default) while it finds entries; each poll
 * that finds none doubles the wait, up to `maxPollInterval` (1 s), so an idle cluster's partitions cost a statement a
 * second each rather than twenty. A commit this log makes wakes the partition's tails at once, and a poll it woke
 * that finds the partition caught up keeps the backoff, so a commit costs one read of the tail; an entry another node
 * appends is read within `maxPollInterval`. `LISTEN/NOTIFY` (design 03 §2) would wake the tails of every node; it is
 * not implemented: the bounded backoff and the wake on this log's commits keep an idle cluster cheap without a
 * listening connection per node.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
@InternalWowApi
class PostgresEventLog(
    private val dataSource: DataSource,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val tailBatch: Int = DEFAULT_TAIL_BATCH,
    maxPollInterval: Duration = DEFAULT_MAX_POLL_INTERVAL,
    /** How the log's tables are managed: created when missing by default (review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
) : EventLog,
    TagsBackfill {
    private val tables = PostgresEventLogTables(context)

    /** Validates the partition count first (decision D240): a log of another count is refused when it is built. */
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
                connection.createStatement().use { statement -> tables.ddl().forEach { statement.execute(it) } }
            }
            if (ddl == StoreDdl.AUTO) {
                // A counter table created before the lineage columns (D240) gets them before its counters are written.
                PostgresFormat.addColumns(
                    connection,
                    tables.partitionsBase,
                    PostgresEventLogTables.PARTITION_ADDED,
                    tables.partitionAdditions,
                )
                connection.createStatement().use { it.execute(tables.initializePartitions(partitions)) }
                // A table created before format 1's added columns (D163, D166) gets them, so the tail reads every table.
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
            connection.createStatement().use { it.execute(table.ddl()) }
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
        if (ddl == StoreDdl.AUTO) {
            addColumns(connection, table)
        }
    }

    /** Adds format 1's added columns (D163) to [table] when it was created before them ([PostgresFormat.addColumns]). */
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

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
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
        transport: Transport?,
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
    private fun bindStream(statement: PreparedStatement, first: Int, stream: EventStreamRecord, transport: Transport?) {
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
        statement.setNullableString(index++, transport?.let { encodeTransport(it) })
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
     * Group commit (decision D248 ①): the streams of one partition in one autocommitted statement
     * ([PostgresEventLogTables.appendGroup]), so a group costs one round trip, as one [append] does.
     *
     * **Version checks.** Each stream keeps its own check: the statement reads every aggregate's version and takes a
     * sequence only for the streams exactly one version ahead of theirs (decision D249 ④), bumping the partition's
     * counter once by their count `k` and writing them at the `k` sequences after it, in the group's order. A stream
     * that does not fit gets its conflict, or its duplicate when its request is committed (finding A2-1), as [append]
     * answers it; the others commit.
     *
     * **No gaps** (decisions D10, D250). The bump and the inserts are one statement: they commit together or not at
     * all, and the counter row stays locked until then, so the order in which commits become visible is the sequence
     * order, whichever node appends to the partition. Another node may append to the partition at once (Local mode
     * during a handover, decision D71): its statement queues on the counter row. When it commits a version a stream of
     * the group needs after this statement read the versions, the group's insert hits the unique version key; a stream
     * repeating a committed request hits the request key; a value PostgreSQL refuses fails the statement too, and so
     * does PostgreSQL ending a deadlock with it (`40P01`; the group takes its locks in one order, so none is expected).
     * Each rolls the whole statement back, the bump included, and is a definite abort: the streams are then appended one by
     * one, each getting the outcome [append] gives it, and the rest commit (as MongoDB's group commit does).
     *
     * **Unknown.** Any other failure, a connection lost while the statement runs included, leaves the group's commit
     * unknown: every stream of it is [AppendOutcome.Unknown], never appended again one by one (finding A2-6).
     *
     * A group of more than [MAX_GROUP] streams is committed [MAX_GROUP] at a time, keeping a statement's parameters
     * within the protocol's limit.
     */
    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> {
        if (streams.size <= 1) {
            return streams.map { (stream, transport) -> append(stream, transport) }
        }
        val partition = Partitioner.partitionOf(streams.first().first.ref, partitions)
        require(
            streams.all { (stream) ->
                stream.ref.type.context == context && Partitioner.partitionOf(stream.ref, partitions) == partition
            },
        ) {
            "A group commit appends streams of one partition of $context."
        }
        require(streams.map { it.first.ref }.toSet().size == streams.size) {
            "A group commit appends distinct aggregates."
        }
        return dispatcher.jdbc {
            streams.chunked(MAX_GROUP).flatMap { group ->
                var sent = false
                try {
                    dataSource.autoCommitted { connection ->
                        val types = group.map { (stream) -> stream.ref.type }.distinct()
                        types.forEach { tableOf(connection, it) }
                        sent = true
                        try {
                            appendGroupIn(connection, partition, group)
                        } catch (failure: PSQLException) {
                            // A table is gone: the statement ran nothing ([withTable]); make sure of them again, once.
                            if (failure.sqlState != UNDEFINED_TABLE) throw failure
                            types.forEach { ensured.forget(it.name, tableOf(connection, it)) }
                            appendGroupIn(connection, partition, group)
                        }
                    }
                } catch (failure: SQLException) {
                    val outcome = unsentOrOutcome(failure, sent)
                    group.map { outcome }
                }
            }
        }.also { outcomes ->
            if (outcomes.any { it is AppendOutcome.Committed }) pacer.committed(partition)
        }
    }

    private fun appendGroupIn(
        connection: Connection,
        partition: Int,
        group: List<Pair<EventStreamRecord, Transport?>>,
    ): List<AppendOutcome> {
        val tablesOf = group.map { (stream) -> tableOf(connection, stream.ref.type) }
        // Sorted: every group takes its tables' request keys in one order ([PostgresEventLogTables.appendGroup]).
        val tables = tablesOf.distinctBy { it.base }.sortedBy { it.base }
        val sql = this.tables.appendGroup(partition, tables, tablesOf.map { table -> tables.indexOf(table) })
        val rows = try {
            connection.prepareStatement(sql).use { statement ->
                group.forEachIndexed { index, (stream, transport) ->
                    bindStream(statement, index * AggregateLogTable.STREAM_PARAMETERS + 1, stream, transport)
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
            return group.mapIndexed { index, (stream, transport) ->
                try {
                    appendIn(connection, tablesOf[index], partition, stream, transport)
                } catch (alone: SQLException) {
                    outcomeOf(alone)
                }
            }
        }
        return group.mapIndexed { index, (stream) ->
            val (version, sequence) = rows[index]
            if (sequence == null) {
                duplicateOr(connection, tablesOf[index], stream) { AppendOutcome.Conflict(version) }
            } else {
                AppendOutcome.Committed(LogPosition(partition, sequence))
            }
        }
    }

    /**
     * The outcome of an append that failed with [failure]: [AppendOutcome.Rejected] when PostgreSQL refused a value of
     * the stream, for good and committing nothing (findings A2-7, A3-10: a `\u0000`, a key too long for its index);
     * otherwise whether it committed is unknown.
     */
    /**
     * The outcome of an append that failed with [failure]; [sent] says whether its statement may have reached the
     * server. One that surely did not, failing to get a connection or the table, wrote nothing: a transient failure is
     * thrown, so the command is answered with the retryable storage error rather than an unknown outcome (finding
     * A2-6 keeps unknown for what may have reached the store).
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

    /** One statement: the tail and the earlier request keys, both by the version key (decision D261). */
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
     * The lineage of [partition] from its counter row (decision D240): its `parent` and `epoch`, read as
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
     * (finding A2-1: a retry of one request at one version collides on both keys, and the version key is checked
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
        row.getString("transport")?.let(::decodeTransport),
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

        /** The lineage columns of a partition's counter row (decision D240). */
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

        /** A transport as one JSON object; an absent wait is left out. */
        fun encodeTransport(transport: Transport): String {
            val node = json.createObjectNode()
            transport.wait?.let { wait -> encodeWait(node.putObject(WAIT), wait) }
            encodeKeys(node.putArray(RESERVATIONS), transport.reservations)
            node.set(TRACE, json.valueToTree(transport.trace))
            // Added within format 1 (finding A2-4): left out when empty, so an older reader never meets it.
            if (transport.releases.isNotEmpty()) {
                encodeKeys(node.putArray(RELEASES), transport.releases)
            }
            return json.writeValueAsString(node)
        }

        /** Reserved keys as objects; a system key (added within format 1) is marked, a tenant's is not. */
        private fun encodeKeys(array: ArrayNode, keys: List<ReservationKey>) {
            keys.forEach { key ->
                val node = array.addObject().put(NAMESPACE, key.namespace).put(KEY, key.key)
                if (key.system) {
                    node.put(SYSTEM, true)
                }
            }
        }

        private fun decodeKeys(array: JsonNode?): List<ReservationKey> {
            val keys = ArrayList<ReservationKey>()
            // ArrayNode has a map member of its own, so iterate rather than map.
            for (key in array ?: return keys) {
                keys += ReservationKey(
                    key.get(NAMESPACE).asString(),
                    key.get(KEY).asString(),
                    key.get(SYSTEM)?.asBoolean() ?: false,
                )
            }
            return keys
        }

        fun decodeTransport(text: String): Transport {
            val node = json.readTree(text)
            return Transport(
                wait = node.get(WAIT)?.let(::decodeWait),
                reservations = decodeKeys(node.get(RESERVATIONS)),
                trace = decodeStrings(json.writeValueAsString(node.get(TRACE))),
                releases = decodeKeys(node.get(RELEASES)),
            )
        }

        /** A wait context's fields into [node]; a hop leaves out its absent context and subscription. */
        fun encodeWait(node: ObjectNode, wait: WaitContext) {
            node.put(WAIT_ID, wait.waitId).put(DEADLINE, wait.deadline)
            node.putObject(ORIGIN)
                .put(NODE, wait.origin.node)
                .put(ADDRESS, wait.origin.address)
                .put(SERVICE, wait.origin.service)
            val remaining = node.putArray(REMAINING)
            wait.remaining.forEach { hop ->
                val encoded = remaining.addObject().put(STAGE, hop.stage.name)
                hop.context?.let { encoded.put(CONTEXT, it) }
                hop.subscription?.let { encoded.put(SUBSCRIPTION, it) }
            }
        }

        fun decodeWait(node: JsonNode): WaitContext {
            val origin = node.get(ORIGIN)
            val remaining = ArrayList<Hop>()
            // ArrayNode has a map member of its own, so iterate rather than map.
            for (hop in node.get(REMAINING)) {
                remaining += Hop(
                    Stage.valueOf(hop.get(STAGE).asString()),
                    hop.get(CONTEXT)?.asString(),
                    hop.get(SUBSCRIPTION)?.asString(),
                )
            }
            return WaitContext(
                waitId = node.get(WAIT_ID).asString(),
                origin = Origin(
                    origin.get(NODE).asString(),
                    origin.get(ADDRESS).asString(),
                    origin.get(SERVICE).asString(),
                ),
                remaining = remaining,
                deadline = node.get(DEADLINE).asLong(),
            )
        }

        /** A JSON object of strings, such as the headers. */
        fun decodeStrings(text: String): Map<String, String> = buildMap {
            for ((name, value) in json.readTree(text).properties()) {
                put(name, value.asString())
            }
        }

        const val WAIT = "wait"
        const val WAIT_ID = "wait_id"
        const val ORIGIN = "origin"
        const val NODE = "node"
        const val ADDRESS = "address"
        const val SERVICE = "service"
        const val REMAINING = "remaining"
        const val STAGE = "stage"
        const val CONTEXT = "context"
        const val SUBSCRIPTION = "subscription"
        const val DEADLINE = "deadline"
        const val RESERVATIONS = "reservations"
        const val RELEASES = "releases"
        const val SYSTEM = "system"
        const val NAMESPACE = "namespace"
        const val KEY = "key"
        const val TRACE = "trace"

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
