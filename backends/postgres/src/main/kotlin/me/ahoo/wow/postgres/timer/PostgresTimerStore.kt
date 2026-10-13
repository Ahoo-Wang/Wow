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

package me.ahoo.wow.postgres.timer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.VirtualThreads
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * The [TimerStore] of one bounded context on PostgreSQL (decision D166): the table `wow_timer__<context>` in the
 * database of the log ([PostgresTimerTables]).
 *
 * Every write is one autocommitted statement: a put is an upsert whose conflict condition admits only another timer at
 * the same or a later version; a cancel and an update are conditional updates. The due read uses the partial index of
 * the PENDING timers.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
class PostgresTimerStore(
    private val dataSource: DataSource,
    context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : TimerStore {
    private val tables = PostgresTimerTables(context)

    /** Creates the context's timer table and its indexes when missing; refuses one recorded in another format. */
    fun createSchema() {
        dataSource.connection.use { connection ->
            PostgresFormat.creating(connection) {
                PostgresFormat.claim(connection, tables.base, PostgresTimerTables.FORMAT)
                connection.createStatement().use { statement -> tables.ddl().forEach { statement.execute(it) } }
            }
        }
    }

    override suspend fun put(timer: Timer): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.put).use { put ->
            put.setString(1, timer.owner)
            put.setString(2, timer.key)
            put.setString(3, timer.id)
            put.setLong(4, timer.version)
            put.setInt(5, timer.partition)
            put.setLong(6, timer.dueAt)
            put.setString(7, timer.target.type.context)
            put.setString(8, timer.target.type.name)
            put.setString(9, timer.target.tenantId)
            put.setString(10, timer.target.id)
            put.setString(11, timer.commandType)
            put.setString(12, timer.body)
            put.setNullableString(13, timer.causation?.correlationId)
            put.setNullableString(14, timer.causation?.causationId)
            put.setString(15, json.writeValueAsString(timer.headers))
            put.executeUpdate() == 1
        }
    }

    override suspend fun cancel(owner: String, key: String, version: Long): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.cancel).use { cancel ->
            cancel.setLong(1, version)
            cancel.setString(2, owner)
            cancel.setString(3, key)
            cancel.setLong(4, version)
            cancel.executeUpdate() == 1
        }
    }

    override suspend fun cancelAll(owner: String, version: Long): Int = jdbc { connection ->
        connection.prepareStatement(tables.cancelAll).use { cancel ->
            cancel.setLong(1, version)
            cancel.setString(2, owner)
            cancel.setLong(3, version)
            cancel.executeUpdate()
        }
    }

    override suspend fun find(owner: String, key: String): Timer? = jdbc { connection ->
        connection.prepareStatement(tables.find).use { query ->
            query.setString(1, owner)
            query.setString(2, key)
            query.executeQuery().use { rows -> if (rows.next()) timerOf(rows) else null }
        }
    }

    override suspend fun due(partition: Int, until: Long, limit: Int): List<Timer> {
        require(limit >= 1) { "A page holds at least one timer." }
        return jdbc { connection ->
            connection.prepareStatement(tables.due).use { query ->
                query.setInt(1, partition)
                query.setLong(2, until)
                query.setInt(3, limit)
                query.executeQuery().use(::timersOf)
            }
        }
    }

    override suspend fun update(timer: Timer, expected: TimerStatus): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.update).use { update ->
            update.setString(1, timer.status.name)
            update.setInt(2, timer.attempts)
            update.setNullableString(3, timer.outcome)
            update.setLong(4, timer.dueAt)
            update.setString(5, timer.owner)
            update.setString(6, timer.key)
            update.setString(7, timer.id)
            update.setString(8, expected.name)
            update.executeUpdate() == 1
        }
    }

    override suspend fun parked(target: AggregateRef): List<Timer> = jdbc { connection ->
        connection.prepareStatement(tables.parked).use { query ->
            query.setString(1, target.type.context)
            query.setString(2, target.type.name)
            query.setString(3, target.tenantId)
            query.setString(4, target.id)
            query.executeQuery().use(::timersOf)
        }
    }

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        withContext(dispatcher) { dataSource.connection.use(block) }

    private companion object {
        val json = WowJson.mapper

        fun PreparedStatement.setNullableString(index: Int, value: String?) {
            if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
        }

        fun timersOf(rows: ResultSet): List<Timer> = buildList {
            while (rows.next()) {
                add(timerOf(rows))
            }
        }

        fun timerOf(row: ResultSet) = Timer(
            id = row.getString("timer_id"),
            owner = row.getString("owner"),
            key = row.getString("key"),
            version = row.getLong("version"),
            partition = row.getInt("partition"),
            dueAt = row.getLong("due_at"),
            target = AggregateType(row.getString("target_context"), row.getString("target_aggregate"))
                .ref(row.getString("target_tenant_id"), row.getString("target_id")),
            commandType = row.getString("command_type"),
            body = row.getString("body"),
            causation = row.getString("correlation_id")?.let { Causation(it, row.getString("causation_id")) },
            headers = buildMap {
                for ((name, value) in json.readTree(row.getString("headers")).properties()) {
                    put(name, value.asString())
                }
            },
            status = TimerStatus.valueOf(row.getString("status")),
            attempts = row.getInt("attempts"),
            outcome = row.getString("outcome"),
        )
    }
}
