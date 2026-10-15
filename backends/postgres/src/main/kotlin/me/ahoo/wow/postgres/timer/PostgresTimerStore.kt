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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.VirtualThreads
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * The [TimerStore] of one bounded context on PostgreSQL (decisions D166, D198): the table `wow_timer__<context>` in the
 * database of the log ([PostgresTimerTables]).
 *
 * Every write is autocommitted: a put is an upsert whose conflict condition admits only another timer at the same or a
 * later version; a cancel is a conditional update; a settle is one batch of conditional updates, each on the row as
 * it was loaded. The due read takes every partition asked in one query, over the partial index of the PENDING timers.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
@InternalWowApi
class PostgresTimerStore(
    private val dataSource: DataSource,
    context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : TimerStore {
    private val tables = PostgresTimerTables(context)
    private val ownerContext = context

    /** Creates the context's timer table and its indexes when missing; refuses one recorded in another format. */
    fun createSchema() {
        dataSource.connection.use { connection ->
            PostgresFormat.creating(connection) {
                PostgresFormat.claim(connection, tables.base, PostgresTimerTables.FORMAT)
                connection.createStatement().use { statement -> tables.ddl().forEach { statement.execute(it) } }
            }
        }
    }

    override suspend fun put(timer: Timer): Boolean {
        require(timer.owner.type.context == ownerContext) { "${timer.owner} keeps its timers in its own context." }
        return jdbc { connection -> put(connection, timer) }
    }

    private fun put(connection: Connection, timer: Timer): Boolean =
        connection.prepareStatement(tables.put).use { put ->
            put.setOwner(1, timer.owner)
            put.setString(4, timer.key)
            put.setString(5, timer.id)
            put.setLong(6, timer.version)
            put.setInt(7, timer.partition)
            put.setString(8, json.writeValueAsString(timer.rule))
            put.setLong(9, timer.slot)
            put.setLong(10, timer.dueAt)
            put.setString(11, timer.target.type.context)
            put.setString(12, timer.target.type.name)
            put.setString(13, timer.target.tenantId)
            put.setString(14, timer.target.id)
            put.setString(15, timer.commandType)
            put.setString(16, timer.body)
            put.setNullableString(17, timer.causation?.correlationId)
            put.setNullableString(18, timer.causation?.causationId)
            put.setString(19, json.writeValueAsString(timer.headers))
            put.setNullableString(20, timer.operator?.let(json::writeValueAsString))
            put.setNullableString(21, timer.scope.takeUnless { it == Scope.NONE }?.let(json::writeValueAsString))
            put.setLong(22, timer.slotDueAt)
            put.setLong(23, timer.skipped)
            put.executeUpdate() == 1
        }

    override suspend fun cancel(owner: AggregateRef, key: String, version: Long): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.cancel).use { cancel ->
            cancel.setLong(1, version)
            cancel.setOwner(2, owner)
            cancel.setString(5, key)
            cancel.setLong(6, version)
            cancel.executeUpdate() == 1
        }
    }

    override suspend fun cancelAll(owner: AggregateRef, version: Long): Int = jdbc { connection ->
        connection.prepareStatement(tables.cancelAll).use { cancel ->
            cancel.setLong(1, version)
            cancel.setOwner(2, owner)
            cancel.setLong(5, version)
            cancel.executeUpdate()
        }
    }

    override suspend fun find(owner: AggregateRef, key: String): Timer? = jdbc { connection ->
        connection.prepareStatement(tables.find).use { query ->
            query.setOwner(1, owner)
            query.setString(4, key)
            query.executeQuery().use { rows -> if (rows.next()) timerOf(rows) else null }
        }
    }

    override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
        require(limit >= 1) { "A page holds at least one timer." }
        if (partitions.isEmpty()) {
            return emptyList()
        }
        return jdbc { connection ->
            connection.prepareStatement(tables.due).use { query ->
                query.setArray(1, connection.createArrayOf("integer", partitions.toTypedArray()))
                query.setLong(2, until)
                query.setInt(3, limit)
                query.executeQuery().use(::timersOf)
            }
        }
    }

    /** One batch of conditional updates: one round trip for every change. */
    override suspend fun settle(changes: List<TimerChange>): Int {
        if (changes.isEmpty()) {
            return 0
        }
        return jdbc { connection ->
            connection.prepareStatement(tables.settle).use { settle ->
                changes.forEach { (loaded, next) ->
                    settle.setString(1, next.status.name)
                    settle.setLong(2, next.slot)
                    settle.setLong(3, next.slotDueAt)
                    settle.setLong(4, next.skipped)
                    settle.setLong(5, next.dueAt)
                    settle.setInt(6, next.attempts)
                    settle.setNullableString(7, next.outcome)
                    settle.setOwner(8, loaded.owner)
                    settle.setString(11, loaded.key)
                    settle.setString(12, loaded.id)
                    settle.setString(13, loaded.status.name)
                    settle.setLong(14, loaded.slot)
                    settle.setLong(15, loaded.slotDueAt)
                    settle.setInt(16, loaded.attempts)
                    settle.setLong(17, loaded.dueAt)
                    settle.addBatch()
                }
                settle.executeBatch().sum()
            }
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

    private fun timersOf(rows: ResultSet): List<Timer> = buildList {
        while (rows.next()) {
            add(timerOf(rows))
        }
    }

    private fun timerOf(row: ResultSet) = Timer(
        id = row.getString("timer_id"),
        owner = AggregateType(ownerContext, row.getString("owner_aggregate"))
            .ref(row.getString("owner_tenant_id"), row.getString("owner_id")),
        key = row.getString("key"),
        version = row.getLong("version"),
        partition = row.getInt("partition"),
        rule = json.readValue(row.getString("rule"), ScheduleRule::class.java),
        slot = row.getLong("slot"),
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
        operator = row.getString("operator")?.let { json.readValue(it, Operator::class.java) },
        scope = row.getString("scope")?.let { json.readValue(it, Scope::class.java) } ?: Scope.NONE,
        slotDueAt = row.getLong("slot_due_at"),
        skipped = row.getLong("skipped"),
    )

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        dispatcher.jdbc { dataSource.connection.use(block) }

    private companion object {
        val json = WowJson.mapper

        /** Sets the owner's three columns from [index]: its context is the table's. */
        fun PreparedStatement.setOwner(index: Int, owner: AggregateRef) {
            setString(index, owner.type.name)
            setString(index + 1, owner.tenantId)
            setString(index + 2, owner.id)
        }

        fun PreparedStatement.setNullableString(index: Int, value: String?) {
            if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
        }
    }
}
