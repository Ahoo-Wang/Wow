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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
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
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [EventLog] of one bounded context on PostgreSQL (documentation/designs/v10/03-log-and-snapshot.md §2).
 *
 * The log takes one table per aggregate type, `wow_log__<context>__<aggregate>` (decision D162); a context-level
 * table holds one counter row per partition, so partitions, their sequences, leases and checkpoints stay per context.
 * An append is one autocommitted statement (see [AggregateLogTable.append]): it bumps its partition's counter, which
 * locks the row until the statement commits, and inserts the stream into its aggregate's table at that sequence only
 * if the aggregate is exactly one version behind. Because the lock is held until commit, the sequence order is the
 * order commits become visible, whichever table they land in; a failed append rolls its sequence back too, so a
 * partition's sequences have no gaps. The transport is stored in its own column and read back only by [tail] and
 * [readEntries].
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
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
class PostgresEventLog(
    private val dataSource: DataSource,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val tailBatch: Int = DEFAULT_TAIL_BATCH,
) : EventLog {
    private val tables = PostgresEventLogTables(context)

    /** The aggregate tables whose creation and registration this log has made sure of. */
    private val ensured = ConcurrentHashMap<String, AggregateLogTable>()

    /** The aggregate tables of the context, as of the last read of the registry, in registry order. */
    @Volatile
    private var registered: List<AggregateLogTable>? = null

    init {
        require(partitions >= 1) { "There is at least one partition." }
    }

    /**
     * Creates the partition counters and the registry when missing; refuses a different partition count, and a log
     * recorded in another format version ([PostgresFormat]). The aggregate tables are created on first use, or by
     * [createTable].
     */
    fun createSchema() {
        dataSource.connection.use { connection ->
            PostgresFormat.creating(connection) {
                PostgresFormat.claim(connection, tables.partitionsBase, PostgresEventLogTables.FORMAT)
                PostgresFormat.claim(connection, PostgresEventLogTables.REGISTRY, PostgresEventLogTables.FORMAT)
                connection.createStatement().use { statement ->
                    tables.ddl().forEach { statement.execute(it) }
                    statement.execute(tables.initializePartitions(partitions))
                }
                // A table created before format 1's added columns (D163, D166) gets them, so the tail reads every table.
                register(connection).forEach { table ->
                    connection.createStatement().use { it.execute(table.additions) }
                }
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
        dataSource.connection.use { tableOf(it, aggregateName) }
    }

    /** The table of [aggregate], created and registered when this log has not yet made sure it is. */
    private fun tableOf(connection: Connection, aggregate: String): AggregateLogTable =
        ensured[aggregate] ?: ensure(connection, tables.aggregate(aggregate)).also { ensured[aggregate] = it }

    private fun tableOf(connection: Connection, type: AggregateType): AggregateLogTable {
        require(type.context == context) { "$type is not in context $context." }
        return tableOf(connection, type.name)
    }

    /**
     * Claims [table]'s format, creates it and records it in the registry, in one transaction under an advisory lock,
     * so that nodes creating tables at once neither race the format record nor two aggregates take one table name.
     */
    private fun ensure(connection: Connection, table: AggregateLogTable): AggregateLogTable =
        PostgresFormat.creating(connection) {
            PostgresFormat.claim(connection, table.base, PostgresEventLogTables.FORMAT)
            connection.createStatement().use {
                it.execute(table.ddl())
                it.execute(table.additions)
            }
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
            table
        }

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
            try {
                dataSource.connection.use { connection ->
                    appendIn(connection, tableOf(connection, stream.ref.type), partition, stream, transport)
                }
            } catch (failure: SQLException) {
                AppendOutcome.Unknown(failure)
            }
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
                append.setString(6, stream.ref.tenantId)
                append.setString(7, stream.ref.id)
                append.setInt(8, stream.version)
                append.setString(9, stream.commandId)
                append.setString(10, stream.requestId)
                append.setString(11, stream.commandType)
                append.setLong(12, stream.commitTime)
                append.setString(13, encodeEvents(stream.events))
                append.setNullableString(14, stream.scope.ownerId)
                append.setNullableString(15, stream.scope.spaceId)
                append.setNullableString(16, stream.causation?.correlationId)
                append.setNullableString(17, stream.causation?.causationId)
                append.setString(18, json.writeValueAsString(stream.headers))
                append.setNullableString(19, transport?.let { encodeTransport(it) })
                val operator = stream.operator
                append.setNullableString(20, operator?.id)
                append.setNullableString(21, operator?.tenantId)
                if (operator == null) append.setNull(22, Types.BOOLEAN) else append.setBoolean(22, operator.crossScope)
                append.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
            return if (sequence == null) {
                AppendOutcome.Conflict(currentVersion(connection, table, stream.ref))
            } else {
                AppendOutcome.Committed(LogPosition(partition, sequence))
            }
        } catch (failure: PSQLException) {
            return when (failure.serverErrorMessage?.constraint) {
                table.versionKey -> AppendOutcome.Conflict(currentVersion(connection, table, stream.ref))
                table.requestKey -> findByRequestIdIn(connection, table, stream.ref.tenantId, stream.requestId)
                    ?.let { AppendOutcome.AlreadyCommitted(it) }
                    ?: AppendOutcome.Unknown(failure)

                else -> AppendOutcome.Unknown(failure)
            }
        }
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val streams = dispatcher.jdbc {
            dataSource.connection.use { connection ->
                val table = tableOf(connection, ref.type)
                connection.prepareStatement(table.read).use { query ->
                    query.setString(1, ref.id)
                    query.setString(2, ref.tenantId)
                    query.setInt(3, fromVersion)
                    query.setInt(4, toVersion)
                    query.executeQuery().use { rows -> rows.map { streamOf(table.aggregate, it) } }
                }
            }
        }
        streams.forEach { emit(it) }
    }

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = flow {
        val entries = dispatcher.jdbc {
            dataSource.connection.use { connection ->
                val table = tableOf(connection, ref.type)
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
        entries.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = dispatcher.jdbc {
        dataSource.connection.use { connection ->
            connection.prepareStatement(tableOf(connection, ref.type).position).use { query ->
                query.setString(1, ref.id)
                query.setString(2, ref.tenantId)
                query.setInt(3, version)
                query.executeQuery().use { rows ->
                    if (rows.next()) LogPosition(rows.getInt("partition"), rows.getLong("sequence")) else null
                }
            }
        }
    }

    /** The stream committed for [requestId] within [tenantId] in [type]'s table: one indexed read of one table. */
    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.jdbc {
            dataSource.connection.use { connection ->
                findByRequestIdIn(connection, tableOf(connection, type), tenantId, requestId)
            }
        }

    /**
     * The stream committed for [requestId] within [tenantId] in any aggregate table of the context this log knows
     * (the registry as last read, and the tables it touched since), the earliest committed when two aggregate types
     * hold it: one statement over every table.
     */
    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.jdbc {
            dataSource.connection.use { connection ->
                val known = ((registered ?: register(connection)) + ensured.values).distinctBy { it.base }
                if (known.isEmpty()) return@use null
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
        while (true) {
            val batch = dispatcher.jdbc { dataSource.connection.use { readTail(it, partition, last) } }
            batch.entries.forEach {
                emit(it)
                last = it.position.sequence
            }
            if (!batch.again) {
                delay(pollInterval)
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
        return TailBatch(run, again)
    }

    private class TailBatch(val entries: List<LogEntry>, val again: Boolean)

    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.jdbc {
            dataSource.connection.use { connection ->
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
        const val DEFAULT_TAIL_BATCH = 500

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
            val reservations = node.putArray(RESERVATIONS)
            transport.reservations.forEach { reservations.addObject().put(NAMESPACE, it.namespace).put(KEY, it.key) }
            node.set(TRACE, json.valueToTree(transport.trace))
            return json.writeValueAsString(node)
        }

        fun decodeTransport(text: String): Transport {
            val node = json.readTree(text)
            val reservations = ArrayList<ReservationKey>()
            // ArrayNode has a map member of its own, so iterate rather than map.
            for (reservation in node.get(RESERVATIONS)) {
                reservations += ReservationKey(reservation.get(NAMESPACE).asString(), reservation.get(KEY).asString())
            }
            return Transport(
                wait = node.get(WAIT)?.let(::decodeWait),
                reservations = reservations,
                trace = decodeStrings(json.writeValueAsString(node.get(TRACE))),
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
