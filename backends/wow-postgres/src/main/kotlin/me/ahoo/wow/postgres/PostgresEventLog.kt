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
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
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
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [EventLog] of one bounded context on PostgreSQL (documentation/designs/v10/03-log-and-snapshot.md §2).
 *
 * One table holds the log; a second holds one counter row per partition. An append is one autocommitted statement
 * (see [PostgresEventLogTables.append]): it bumps its partition's counter, which locks the row until the statement
 * commits, and inserts the stream at that sequence only if the aggregate is exactly one version behind. Because the
 * lock is held until commit, the sequence order is the order commits become visible, so [tail] never skips an entry;
 * a failed append rolls its sequence back too, so there are no gaps. The transport is stored in its own column and
 * read back only by [tail].
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

    init {
        require(partitions >= 1) { "There is at least one partition." }
    }

    /**
     * Creates the tables and the partition counters when missing; refuses a different partition count, and a log
     * recorded in another format version ([PostgresFormat]).
     */
    fun createSchema() {
        dataSource.connection.use { connection ->
            PostgresFormat.claim(connection, tables.base, PostgresEventLogTables.FORMAT)
            connection.createStatement().use { statement ->
                tables.ddl().forEach { statement.execute(it) }
                statement.execute(tables.initializePartitions(partitions))
            }
            connection.prepareStatement("SELECT count(*) FROM ${tables.partitions}").use { query ->
                query.executeQuery().use { rows ->
                    rows.next()
                    check(rows.getInt(1) == partitions) {
                        "The log of $context has ${rows.getInt(
                            1
                        )} partitions, not $partitions: changing it is a repartition."
                    }
                }
            }
        }
    }

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        withContext(dispatcher) {
            require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
            val partition = Partitioner.partitionOf(stream.ref, partitions)
            try {
                dataSource.connection.use { connection -> appendIn(connection, partition, stream, transport) }
            } catch (failure: SQLException) {
                AppendOutcome.Unknown(failure)
            }
        }

    private fun appendIn(
        connection: Connection,
        partition: Int,
        stream: EventStreamRecord,
        transport: Transport?,
    ): AppendOutcome {
        try {
            val sequence = connection.prepareStatement(tables.append).use { append ->
                append.setString(1, stream.ref.id)
                append.setString(2, stream.ref.tenantId)
                append.setString(3, stream.ref.type.name)
                append.setInt(4, partition)
                append.setInt(5, stream.version - 1)
                append.setInt(6, partition)
                append.setString(7, stream.ref.type.name)
                append.setString(8, stream.ref.tenantId)
                append.setString(9, stream.ref.id)
                append.setInt(10, stream.version)
                append.setString(11, stream.commandId)
                append.setString(12, stream.requestId)
                append.setString(13, stream.commandType)
                append.setLong(14, stream.commitTime)
                append.setString(15, encodeEvents(stream.events))
                append.setNullableString(16, stream.scope.ownerId)
                append.setNullableString(17, stream.scope.spaceId)
                append.setNullableString(18, stream.causation?.correlationId)
                append.setNullableString(19, stream.causation?.causationId)
                append.setString(20, json.writeValueAsString(stream.headers))
                append.setNullableString(21, transport?.let { encodeTransport(it) })
                append.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
            return if (sequence == null) {
                AppendOutcome.Conflict(currentVersion(connection, stream.ref))
            } else {
                AppendOutcome.Committed(LogPosition(partition, sequence))
            }
        } catch (failure: PSQLException) {
            return when (failure.serverErrorMessage?.constraint) {
                tables.versionKey -> AppendOutcome.Conflict(currentVersion(connection, stream.ref))
                tables.requestKey -> findByRequestIdIn(connection, stream.ref.tenantId, stream.requestId)
                    ?.let { AppendOutcome.AlreadyCommitted(it) }
                    ?: AppendOutcome.Unknown(failure)

                else -> AppendOutcome.Unknown(failure)
            }
        }
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val streams = withContext(dispatcher) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(tables.read).use { query ->
                    query.setString(1, ref.id)
                    query.setString(2, ref.tenantId)
                    query.setString(3, ref.type.name)
                    query.setInt(4, fromVersion)
                    query.setInt(5, toVersion)
                    query.executeQuery().use { rows -> rows.map { streamOf(it) } }
                }
            }
        }
        streams.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = withContext(dispatcher) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(tables.position).use { query ->
                query.setString(1, ref.id)
                query.setString(2, ref.tenantId)
                query.setString(3, ref.type.name)
                query.setInt(4, version)
                query.executeQuery().use { rows ->
                    if (rows.next()) LogPosition(rows.getInt("partition"), rows.getLong("sequence")) else null
                }
            }
        }
    }

    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        withContext(dispatcher) {
            dataSource.connection.use { connection -> findByRequestIdIn(connection, tenantId, requestId) }
        }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var last = after ?: 0L
        while (true) {
            val batch = withContext(dispatcher) {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(tables.tail).use { query ->
                        query.setInt(1, partition)
                        query.setLong(2, last)
                        query.setInt(3, tailBatch)
                        query.executeQuery().use { rows ->
                            rows.map {
                                LogEntry(
                                    LogPosition(partition, it.getLong("sequence")),
                                    streamOf(it),
                                    it.getString("transport")?.let(::decodeTransport),
                                )
                            }
                        }
                    }
                }
            }
            batch.forEach {
                emit(it)
                last = it.position.sequence
            }
            if (batch.size < tailBatch) {
                delay(pollInterval)
            }
        }
    }

    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return withContext(dispatcher) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(tables.head).use { query ->
                    query.setInt(1, partition)
                    query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1).takeIf { it > 0 } else null }
                }
            }
        }
    }

    private fun findByRequestIdIn(connection: Connection, tenantId: String, requestId: String): EventStreamRecord? =
        connection.prepareStatement(tables.byRequest).use { query ->
            query.setString(1, tenantId)
            query.setString(2, requestId)
            query.executeQuery().use { rows -> if (rows.next()) streamOf(rows) else null }
        }

    private fun currentVersion(connection: Connection, ref: AggregateRef): Int =
        connection.prepareStatement(tables.currentVersion).use { query ->
            query.setString(1, ref.id)
            query.setString(2, ref.tenantId)
            query.setString(3, ref.type.name)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun streamOf(row: ResultSet) = EventStreamRecord(
        ref = AggregateType(
            context,
            row.getString("aggregate_name")
        ).ref(row.getString("tenant_id"), row.getString("aggregate_id")),
        version = row.getInt("version"),
        commandId = row.getString("command_id"),
        requestId = row.getString("request_id"),
        commandType = row.getString("command_type"),
        commitTime = row.getLong("commit_time"),
        events = decodeEvents(row.getString("events")),
        scope = Scope(row.getString("owner_id"), row.getString("space_id")),
        causation = row.getString("correlation_id")?.let { Causation(it, row.getString("causation_id")) },
        headers = decodeStrings(row.getString("headers")),
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
        val json: JsonMapper = JsonMapper.builder().build()

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
