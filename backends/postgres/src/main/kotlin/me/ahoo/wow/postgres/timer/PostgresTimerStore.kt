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
import me.ahoo.wow.postgres.EnsuredTables
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.VirtualThreads
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
 * The [TimerStore] of one bounded context on PostgreSQL (decisions D166, D198, D307): a table per aggregate type,
 * `wow_timer__<context>__<aggregate>`, in the database of the type's log ([PostgresTimerTables]), created on the type's
 * first use and recorded in the registry `wow_timer_aggregate`.
 *
 * Every write is autocommitted and goes to its owner's table: a put is an upsert whose conflict condition admits only
 * another timer at the same or a later version; a cancel is a conditional update; a settle is one batch of conditional
 * updates per table, each on the row as it was loaded. The due read takes every partition and every table in one
 * statement, a `UNION ALL` of each table's read over its partial index of the PENDING timers, merged by due time; the
 * same statement counts the registry's tables, so a table another node created since this store last read the
 * registry is read again at once (decision D307).
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
@InternalWowApi
class PostgresTimerStore(
    private val dataSource: DataSource,
    context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's tables are managed: created when missing by default (review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
) : TimerStore {
    private val tables = PostgresTimerTables(context)
    private val ownerContext = context

    /** The timer tables whose creation and registration this store has made sure of. */
    private val ensured = EnsuredTables<AggregateTimerTable>()

    /** The timer tables of the context, as of the last read of the registry. */
    @Volatile
    private var registered: List<AggregateTimerTable>? = null

    /**
     * Creates the registry of timer tables when missing; refuses one recorded in another format. Each aggregate type's
     * table is created on its first use.
     */
    fun createSchema() {
        dataSource.autoCommitted { connection ->
            PostgresFormat.schema(connection, ddl, listOf(PostgresTimerTables.REGISTRY to PostgresTimerTables.FORMAT)) {
                connection.createStatement().use { statement -> tables.ddl().forEach { statement.execute(it) } }
            }
        }
        registered = null
    }

    /** The table of [type]'s timers, created and registered when this store has not yet made sure it is. */
    private fun tableOf(connection: Connection, type: AggregateType): AggregateTimerTable {
        require(type.context == ownerContext) { "$type keeps its timers in its own context, not $ownerContext." }
        return ensured.get(type.name) { ensure(connection, tables.aggregate(type.name)) }
    }

    /**
     * Claims [table]'s format, creates it and records it in the registry under the format's advisory lock, so that
     * nodes creating tables at once neither race the format record nor two aggregates take one table name.
     */
    private fun ensure(connection: Connection, table: AggregateTimerTable): AggregateTimerTable = table.also {
        PostgresFormat.schema(connection, ddl, listOf(table.base to PostgresTimerTables.FORMAT)) {
            connection.createStatement().use { statement -> table.ddl().forEach { statement.execute(it) } }
            connection.prepareStatement(tables.register).use { register ->
                register.setString(1, ownerContext)
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
            check(holder == ownerContext to table.aggregate) {
                "The aggregate ${table.aggregate} of $ownerContext takes the timer table ${table.base}, " +
                    "which the aggregate ${holder.second} of ${holder.first} has."
            }
        }
    }

    /** Reads the registry again: the timer tables of the context. */
    private fun register(connection: Connection): List<AggregateTimerTable> {
        val names = connection.prepareStatement(tables.registered).use { query ->
            query.setString(1, ownerContext)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(rows.getString(1))
                    }
                }
            }
        }
        return names.map(tables::aggregate).also { registered = it }
    }

    override suspend fun put(timer: Timer): Boolean = jdbc { connection ->
        connection.prepareStatement(tableOf(connection, timer.owner.type).put).use { put ->
            put.setOwner(1, timer.owner)
            put.setString(3, timer.key)
            put.setString(4, timer.id)
            put.setLong(5, timer.version)
            put.setInt(6, timer.partition)
            put.setString(7, json.writeValueAsString(timer.rule))
            put.setLong(8, timer.slot)
            put.setLong(9, timer.dueAt)
            put.setString(10, timer.target.type.context)
            put.setString(11, timer.target.type.name)
            put.setString(12, timer.target.tenantId)
            put.setString(13, timer.target.id)
            put.setString(14, timer.commandType)
            put.setString(15, timer.body)
            put.setNullableString(16, timer.causation?.correlationId)
            put.setNullableString(17, timer.causation?.causationId)
            put.setString(18, json.writeValueAsString(timer.headers))
            put.setNullableString(19, timer.operator?.let(json::writeValueAsString))
            put.setNullableString(20, timer.scope.takeUnless { it == Scope.NONE }?.let(json::writeValueAsString))
            put.setLong(21, timer.slotDueAt)
            put.setLong(22, timer.skipped)
            put.executeUpdate() == 1
        }
    }

    override suspend fun cancel(owner: AggregateRef, key: String, version: Long): Boolean = jdbc { connection ->
        connection.prepareStatement(tableOf(connection, owner.type).cancel).use { cancel ->
            cancel.setLong(1, version)
            cancel.setOwner(2, owner)
            cancel.setString(4, key)
            cancel.setLong(5, version)
            cancel.executeUpdate() == 1
        }
    }

    override suspend fun cancelAll(owner: AggregateRef, version: Long): Int = jdbc { connection ->
        connection.prepareStatement(tableOf(connection, owner.type).cancelAll).use { cancel ->
            cancel.setLong(1, version)
            cancel.setOwner(2, owner)
            cancel.setLong(4, version)
            cancel.executeUpdate()
        }
    }

    override suspend fun find(owner: AggregateRef, key: String): Timer? = jdbc { connection ->
        connection.prepareStatement(tableOf(connection, owner.type).find).use { query ->
            query.setOwner(1, owner)
            query.setString(3, key)
            query.executeQuery().use { rows -> if (rows.next()) timerOf(rows, owner.type.name) else null }
        }
    }

    override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
        require(limit >= 1) { "A page holds at least one timer." }
        if (partitions.isEmpty()) {
            return emptyList()
        }
        return jdbc { connection ->
            unioned(connection, tables::due, limit) { query, index ->
                query.setArray(index, connection.createArrayOf("integer", partitions.toTypedArray()))
                query.setLong(index + 1, until)
                query.setInt(index + 2, limit)
                index + DUE_PARAMETERS
            }
        }
    }

    /** One batch of conditional updates per table: one round trip for every change of a type. */
    override suspend fun settle(changes: List<TimerChange>): Int {
        if (changes.isEmpty()) {
            return 0
        }
        return jdbc { connection ->
            changes.groupBy { it.loaded.owner.type }.entries.sumOf { (type, ofType) ->
                connection.prepareStatement(tableOf(connection, type).settle).use { settle ->
                    ofType.forEach { change ->
                        val loaded = change.loaded
                        val next = change.next
                        settle.setString(1, next.status.name)
                        settle.setLong(2, next.slot)
                        settle.setLong(3, next.slotDueAt)
                        settle.setLong(4, next.skipped)
                        settle.setLong(5, next.dueAt)
                        settle.setInt(6, next.attempts)
                        settle.setNullableString(7, next.outcome)
                        settle.setOwner(8, loaded.owner)
                        settle.setString(10, loaded.key)
                        settle.setString(11, loaded.id)
                        settle.setString(12, loaded.status.name)
                        settle.setLong(13, loaded.slot)
                        settle.setLong(14, loaded.slotDueAt)
                        settle.setInt(15, loaded.attempts)
                        settle.setLong(16, loaded.dueAt)
                        settle.addBatch()
                    }
                    settle.executeBatch().sum()
                }
            }
        }
    }

    override suspend fun parked(target: AggregateRef): List<Timer> = jdbc { connection ->
        unioned(connection, tables::parked, Int.MAX_VALUE) { query, index ->
            query.setString(index, target.type.context)
            query.setString(index + 1, target.type.name)
            query.setString(index + 2, target.tenantId)
            query.setString(index + 3, target.id)
            index + PARKED_PARAMETERS
        }
    }

    /**
     * Runs [statement] over the context's timer tables, [bind] setting each table's parameters from an index and
     * giving the next: the tables as last read from the registry and those this store made sure of since. When the
     * registry holds other tables than those, it reads the registry and runs the statement once more.
     */
    private fun unioned(
        connection: Connection,
        statement: (List<AggregateTimerTable>) -> String,
        limit: Int,
        bind: (PreparedStatement, Int) -> Int,
    ): List<Timer> {
        var known = known(connection)
        while (true) {
            val (count, timers) = read(connection, statement(known), known, limit, bind)
            if (count == known.size) {
                return timers
            }
            val read = register(connection)
            if (read.map { it.base }.toSet() == known.map { it.base }.toSet()) {
                return timers
            }
            known = read
        }
    }

    /** The tables this store knows: the registry as last read, and the ones it made sure of since. */
    private fun known(connection: Connection): List<AggregateTimerTable> =
        ((registered ?: register(connection)) + ensured.values).distinctBy { it.base }

    private fun read(
        connection: Connection,
        sql: String,
        known: List<AggregateTimerTable>,
        limit: Int,
        bind: (PreparedStatement, Int) -> Int,
    ): Pair<Int, List<Timer>> = connection.prepareStatement(sql).use { query ->
        query.setString(1, ownerContext)
        var index = 2
        known.forEach { _ -> index = bind(query, index) }
        if (known.isNotEmpty()) {
            query.setInt(index, limit)
        }
        query.executeQuery().use { rows ->
            var count = 0
            val timers = buildList {
                while (rows.next()) {
                    count = rows.getInt("registered")
                    if (rows.getString("timer_id") != null) {
                        add(timerOf(rows, known[rows.getInt("branch")].aggregate))
                    }
                }
            }
            count to timers
        }
    }

    private fun timerOf(row: ResultSet, aggregate: String) = Timer(
        id = row.getString("timer_id"),
        owner = AggregateType(ownerContext, aggregate)
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
        dispatcher.jdbc { dataSource.autoCommitted(block) }

    private companion object {
        val json = WowJson.mapper

        /** The parameters of each table's branch of the due read: the partitions, until, limit. */
        const val DUE_PARAMETERS = 3

        /** The parameters of each table's branch of the parked read: the target's context, aggregate, tenant and id. */
        const val PARKED_PARAMETERS = 4

        /** Sets the owner's two columns from [index]: its context and aggregate are the table's. */
        fun PreparedStatement.setOwner(index: Int, owner: AggregateRef) {
            setString(index, owner.tenantId)
            setString(index + 1, owner.id)
        }

        fun PreparedStatement.setNullableString(index: Int, value: String?) {
            if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
        }
    }
}
