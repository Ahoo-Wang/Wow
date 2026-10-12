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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import org.postgresql.util.PSQLException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.Executors
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
 * a failed append rolls its sequence back too, so there are no gaps.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
class PostgresEventLog(
    private val dataSource: DataSource,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VIRTUAL_THREADS,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val tailBatch: Int = DEFAULT_TAIL_BATCH,
) : EventLog {
    private val tables = PostgresEventLogTables(context)

    init {
        require(partitions >= 1) { "There is at least one partition." }
    }

    /** Creates the tables and the partition counters when missing; refuses a different partition count. */
    fun createSchema() {
        dataSource.connection.use { connection ->
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

    override suspend fun append(stream: EventStreamRecord): AppendOutcome = withContext(dispatcher) {
        require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
        val partition = Partitioner.partitionOf(stream.ref, partitions)
        try {
            dataSource.connection.use { connection -> appendIn(connection, partition, stream) }
        } catch (failure: SQLException) {
            AppendOutcome.Unknown(failure)
        }
    }

    private fun appendIn(connection: Connection, partition: Int, stream: EventStreamRecord): AppendOutcome {
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
                append.setString(13, stream.fingerprint)
                append.setLong(14, stream.commitTime)
                append.setString(15, encodeEvents(stream.events))
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
                            rows.map { LogEntry(LogPosition(partition, it.getLong("sequence")), streamOf(it)) }
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
        fingerprint = row.getString("fingerprint"),
        commitTime = row.getLong("commit_time"),
        events = decodeEvents(row.getString("events")),
    )

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
        val VIRTUAL_THREADS: CoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
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
