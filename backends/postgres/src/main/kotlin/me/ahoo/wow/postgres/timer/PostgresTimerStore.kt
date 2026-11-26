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
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.postgres.PostgresFailures
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.PostgresSchema
import me.ahoo.wow.postgres.UnknownStoredValues
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.schema.EnsuredOnce
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import tools.jackson.core.JacksonException
import tools.jackson.databind.exc.InvalidFormatException
import tools.jackson.databind.exc.InvalidTypeIdException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource

/**
 * The [TimerStore] of one bounded context on PostgreSQL: a table per aggregate type,
 * `wow_timer__<context>__<aggregate>`, in the database of the type's log ([PostgresTimerTables]), created on the type's
 * first use and recorded in the registry `wow_timer_aggregate`.
 *
 * Every write is autocommitted and goes to its owner's table: a put is an upsert whose conflict condition admits only
 * another timer at the same or a later version; a cancel is a conditional update; a settle is one batch of conditional
 * updates per table, each on the row as it was loaded. The due read takes every partition and every table in one
 * statement, a `UNION ALL` of each table's read over its partial index of the PENDING timers, merged by due time; the
 * same statement counts the registry's tables, so a table another node created since this store last read the
 * registry is read again at once. A timer whose rule this version cannot read (a later version's) is skipped and
 * warned of once per value; only a partition whose page came back full and held such a timer is read on past it, from
 * the last row read ([PostgresTimerTables.dueAfter]), so those rows never starve the timers behind them.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21).
 */
@InternalWowApi
class PostgresTimerStore(
    private val dataSource: DataSource,
    context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's tables are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
) : TimerStore {
    private val tables = PostgresTimerTables(context)
    private val ownerContext = context

    /** The timer tables whose creation and registration this store has made sure of. */
    private val ensured = EnsuredOnce<String, AggregateTimerTable>()

    /** The timer tables of the context, as of the last read of the registry. */
    @Volatile
    private var registered: List<AggregateTimerTable>? = null

    /**
     * Creates the registry of timer tables when missing; refuses one recorded in another format. Each aggregate type's
     * table is created on its first use.
     */
    fun createSchema() {
        dataSource.autoCommitted { connection ->
            PostgresFormat.schema(
                connection,
                ddl,
                listOf(PostgresTimerTables.REGISTRY to PostgresTimerTables.FORMAT),
                tables.ddl(),
            )
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
     * A [ddl] that creates nothing still records an existing table ([ensureRecord]): the due read finds a table only
     * there.
     */
    private fun ensure(connection: Connection, table: AggregateTimerTable): AggregateTimerTable = table.also {
        PostgresFormat.schema(connection, ddl, listOf(table.base to PostgresTimerTables.FORMAT), table.ddl()) {
            record(connection, table)
        }
        if (!ddl.creates) {
            ensureRecord(connection, table)
        }
    }

    /**
     * Records [table] in the registry, keeping a record already there, and refuses a table name another aggregate
     * holds. The registry's keys, `(context, aggregate)` and the table name, make two nodes recording at once leave one
     * record, which the check then reads.
     */
    private fun record(connection: Connection, table: AggregateTimerTable) {
        connection.prepareStatement(tables.register).use { register ->
            register.setString(1, ownerContext)
            register.setString(2, table.aggregate)
            register.setString(3, table.base)
            register.executeUpdate()
        }
        checkHeld(table, holderOf(connection, table))
    }

    /** Refuses [table] unless [holder], who the registry says holds its name, is its own aggregate. */
    private fun checkHeld(table: AggregateTimerTable, holder: Pair<String, String>?) {
        checkNotNull(holder) {
            "The aggregate ${table.aggregate} of $ownerContext is recorded in ${PostgresTimerTables.REGISTRY} with " +
                "another timer table than ${table.base}."
        }
        check(holder == ownerContext to table.aggregate) {
            "The aggregate ${table.aggregate} of $ownerContext takes the timer table ${table.base}, " +
                "which the aggregate ${holder.second} of ${holder.first} has."
        }
    }

    /**
     * Under a [ddl] that creates nothing, the registry record of [table], which the due read reads to find it: written
     * when missing, since a record is data an account without DDL rights may still insert, as `auto` writes it when it
     * creates the table. A table that does not exist (`none` checks nothing) is not recorded: its first use fails on
     * it, where a record would fail every due read of the context. A record that cannot be written is refused, naming
     * it, rather than leaving the table's timers to no due read; a transient failure is thrown as it is.
     */
    private fun ensureRecord(connection: Connection, table: AggregateTimerTable) {
        if (!PostgresSchema.exists(connection, table.table)) return
        val holder = holderOf(connection, table)
        if (holder != null) {
            checkHeld(table, holder)
            return
        }
        try {
            record(connection, table)
        } catch (failure: SQLException) {
            if (PostgresFailures.isTransient(failure)) throw failure
            throw IllegalStateException(
                "The timer table ${table.base} of ${table.aggregate} in $ownerContext has no record in " +
                    "${PostgresTimerTables.REGISTRY}, where the due read finds it, and ddl ${ddl.name.lowercase()} " +
                    "could not write it (${failure.message}). Grant INSERT on ${PostgresTimerTables.REGISTRY}, " +
                    "or insert its record: ('$ownerContext', '${table.aggregate}', '${table.base}').",
                failure,
            )
        }
    }

    /** Who holds [table]'s name in the registry, context and aggregate; null when no record has it. */
    private fun holderOf(connection: Connection, table: AggregateTimerTable): Pair<String, String>? =
        connection.prepareStatement(tables.holder).use { query ->
            query.setString(1, table.base)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
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
            put.setInt(5, timer.version)
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
            put.setNullableString(OPERATOR_ID, timer.operator?.id)
            put.setNullableString(OPERATOR_ID + 1, timer.operator?.tenantId)
            timer.operator?.let { put.setBoolean(OPERATOR_ID + 2, it.actedElsewhere) }
                ?: put.setNull(OPERATOR_ID + 2, Types.BOOLEAN)
            put.setNullableString(OPERATOR_ID + 3, timer.scope.ownerId)
            put.setNullableString(OPERATOR_ID + 4, timer.scope.spaceId)
            put.setLong(OPERATOR_ID + 5, timer.slotDueAt)
            put.setLong(OPERATOR_ID + 6, timer.skipped)
            put.executeUpdate() == 1
        }
    }

    override suspend fun cancel(owner: AggregateRef, key: String, version: Int): Boolean = jdbc { connection ->
        connection.prepareStatement(tableOf(connection, owner.type).cancel).use { cancel ->
            cancel.setInt(1, version)
            cancel.setOwner(2, owner)
            cancel.setString(4, key)
            cancel.setInt(5, version)
            cancel.executeUpdate() == 1
        }
    }

    override suspend fun cancelAll(owner: AggregateRef, version: Int): Int = jdbc { connection ->
        connection.prepareStatement(tableOf(connection, owner.type).cancelAll).use { cancel ->
            cancel.setInt(1, version)
            cancel.setOwner(2, owner)
            cancel.setInt(4, version)
            cancel.executeUpdate()
        }
    }

    override suspend fun find(owner: AggregateRef, key: String): Timer? = jdbc { connection ->
        connection.prepareStatement(tableOf(connection, owner.type).find).use { query ->
            query.setOwner(1, owner)
            query.setString(3, key)
            query.executeQuery().use { rows -> if (rows.next()) refusedTimerOf(rows, owner.type.name) else null }
        }
    }

    override suspend fun due(partitions: Set<Int>, until: Long, perPartition: Int): List<Timer> {
        require(perPartition >= 1) { "A page holds at least one timer of each partition." }
        if (partitions.isEmpty()) {
            return emptyList()
        }
        return jdbc { connection ->
            val rows = unioned(connection, tables::due, perPartition) { query, index ->
                query.setArray(index, connection.createArrayOf("integer", partitions.toTypedArray()))
                query.setLong(index + 1, until)
                query.setInt(index + 2, perPartition)
                index + DUE_PARAMETERS
            }
            val timers = rows.mapNotNull { it.timer }
            // A partition whose page came back full and held rows whose rule this version cannot read: those rows stay
            // at the head of its due order, so the store reads on past them for the readable timers behind them.
            val skipping = rows.groupBy { it.partition }
                .filterValues { page -> page.size == perPartition && page.any { it.timer == null } }
            if (skipping.isEmpty()) {
                timers
            } else {
                val behind = skipping.flatMap { (partition, page) ->
                    val wanted = perPartition - page.count { it.timer != null }
                    readOn(connection, partition, until, perPartition, wanted, page.last())
                }
                (timers + behind).sortedWith(DUE)
            }
        }
    }

    /**
     * Up to [wanted] readable due timers of [partition] after [last], read [page] rows at a time in due order until
     * there are [wanted] or the due rows end: the due read's continuation past rows it skipped
     * ([PostgresTimerTables.dueAfter]).
     */
    private fun readOn(
        connection: Connection,
        partition: Int,
        until: Long,
        page: Int,
        wanted: Int,
        last: DueRow,
    ): List<Timer> = buildList {
        var after = last
        while (size < wanted) {
            val rows = unioned(connection, tables::dueAfter, page) { query, index ->
                query.setInt(index, partition)
                query.setLong(index + 1, until)
                query.setLong(index + 2, after.dueAt)
                query.setString(index + 3, after.timerId)
                query.setInt(index + 4, page)
                index + DUE_AFTER_PARAMETERS
            }
            rows.forEach { row -> if (row.timer != null && size < wanted) add(row.timer) }
            if (rows.size < page) {
                break
            }
            after = rows.last()
        }
    }

    /**
     * One batch of conditional updates per table: one round trip for every change of a type, each statement's update
     * count saying whether its change was written.
     */
    override suspend fun settle(changes: List<TimerChange>): List<Boolean> {
        if (changes.isEmpty()) {
            return emptyList()
        }
        val written = BooleanArray(changes.size)
        jdbc { connection ->
            changes.withIndex().groupBy { it.value.loaded.owner.type }.forEach { (type, ofType) ->
                connection.prepareStatement(tableOf(connection, type).settle).use { settle ->
                    ofType.forEach { (_, change) ->
                        val loaded = change.loaded
                        val next = change.next
                        settle.setString(1, WireNames.of(next.status))
                        settle.setLong(2, next.slot)
                        settle.setLong(3, next.slotDueAt)
                        settle.setLong(4, next.skipped)
                        settle.setLong(5, next.dueAt)
                        settle.setInt(6, next.attempts)
                        settle.setNullableString(7, next.outcome)
                        settle.setOwner(8, loaded.owner)
                        settle.setString(10, loaded.key)
                        settle.setString(11, loaded.id)
                        settle.setString(12, WireNames.of(loaded.status))
                        settle.setLong(13, loaded.slot)
                        settle.setLong(14, loaded.slotDueAt)
                        settle.setInt(15, loaded.attempts)
                        settle.setLong(16, loaded.dueAt)
                        settle.addBatch()
                    }
                    settle.executeBatch().forEachIndexed { at, count -> written[ofType[at].index] = count > 0 }
                }
            }
        }
        return written.toList()
    }

    override suspend fun parked(target: AggregateRef): List<Timer> = jdbc { connection ->
        unioned(connection, tables::parked, Int.MAX_VALUE) { query, index ->
            query.setString(index, target.type.context)
            query.setString(index + 1, target.type.name)
            query.setString(index + 2, target.tenantId)
            query.setString(index + 3, target.id)
            index + PARKED_PARAMETERS
        }.mapNotNull { it.timer }
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
    ): List<DueRow> {
        var known = known(connection)
        while (true) {
            val (count, rows) = read(connection, statement(known), known, limit, bind)
            if (count == known.size) {
                return rows
            }
            val read = register(connection)
            if (read.map { it.base }.toSet() == known.map { it.base }.toSet()) {
                return rows
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
    ): Pair<Int, List<DueRow>> = connection.prepareStatement(sql).use { query ->
        query.setString(1, ownerContext)
        var index = 2
        known.forEach { _ -> index = bind(query, index) }
        if (known.isNotEmpty()) {
            query.setInt(index, limit)
        }
        query.executeQuery().use { rows ->
            var count = 0
            val read = buildList {
                while (rows.next()) {
                    count = rows.getInt("registered")
                    val timerId = rows.getString("timer_id") ?: continue
                    val timer = readableTimerOf(rows, known[rows.getInt("branch")].aggregate)
                    add(DueRow(rows.getInt("partition"), rows.getLong("due_at"), timerId, timer))
                }
            }
            count to read
        }
    }

    /**
     * One row of a read of many timers: its place in its partition's due order, and its timer, null when this version
     * cannot read its rule.
     */
    private class DueRow(val partition: Int, val dueAt: Long, val timerId: String, val timer: Timer?)

    /**
     * The timer of [row], or null for one whose rule this version cannot read (a later version's), which a read of many
     * timers skips, leaving it for a node that can, and warns of once per value. The rule is decoded once.
     */
    private fun readableTimerOf(row: ResultSet, aggregate: String): Timer? {
        val text = row.getString("rule")
        val rule = try {
            json.readValue(text, ScheduleRule::class.java)
        } catch (failure: JacksonException) {
            val (field, value) = unreadable(failure, text)
            unknown.warn(field, value)
            return null
        }
        return timerOf(row, aggregate, rule)
    }

    /** The timer of [row], read alone: one whose rule this version cannot read is refused, naming the value. */
    private fun refusedTimerOf(row: ResultSet, aggregate: String): Timer {
        val text = row.getString("rule")
        val rule = try {
            json.readValue(text, ScheduleRule::class.java)
        } catch (failure: JacksonException) {
            val (field, value) = unreadable(failure, text)
            val message = "A timer's $field is $value, which this version of Wow does not read: $text."
            throw IllegalStateException(message, failure)
        }
        return timerOf(row, aggregate, rule)
    }

    private fun timerOf(row: ResultSet, aggregate: String, rule: ScheduleRule) = Timer(
        id = row.getString("timer_id"),
        owner = AggregateType(ownerContext, aggregate)
            .ref(row.getString("aggregate_tenant_id"), row.getString("aggregate_id")),
        key = row.getString("key"),
        version = row.getInt("version"),
        partition = row.getInt("partition"),
        rule = rule,
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
        // By its wire name; a status this node does not know cannot be stored, as every node reads a new one before one
        // writes it (TimerStatus).
        status = checkNotNull(WireNames.entryOf<TimerStatus>(row.getString("status"))) {
            "A timer's status ${row.getString("status")} is not one this version of Wow reads."
        },
        attempts = row.getInt("attempts"),
        outcome = row.getString("outcome"),
        operator = row.getString("operator_id")?.let {
            Operator(it, row.getString("operator_tenant_id"), row.getBoolean("operator_acted_elsewhere"))
        },
        scope = Scope(row.getString("owner_id"), row.getString("space_id")),
        slotDueAt = row.getLong("slot_due_at"),
        skipped = row.getLong("skipped"),
    )

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        dispatcher.jdbc { dataSource.autoCommitted(block) }

    internal companion object {
        /** The put's parameter of the operator's id, which its tenant, the acted-elsewhere flag and the scope follow. */
        const val OPERATOR_ID = 19

        /** The rule values of a later version this JVM's timer stores read, each warned of once. */
        internal val unknown = UnknownStoredValues("the timers")

        /**
         * The field and the value of [text], a stored rule, that [failure] says this version cannot read: a later
         * version's rule type (`rule.type`), or a value of an enumeration (`rule.misfire`); the rule itself otherwise.
         */
        fun unreadable(failure: JacksonException, text: String): Pair<String, String> = when (failure) {
            is InvalidTypeIdException -> "rule.type" to failure.typeId.toString()
            is InvalidFormatException ->
                failure.path.joinToString(".", prefix = "rule.") { it.propertyName ?: "${it.index}" } to
                    "${failure.value}"
            else -> "rule" to text
        }

        /** The due read's order: the due time, then the timer's id by code point, as `COLLATE "C"` compares it. */
        val DUE: Comparator<Timer> = Comparator { left, right ->
            left.dueAt.compareTo(right.dueAt).takeIf { it != 0 } ?: codePoints(left.id, right.id)
        }

        /** [left] against [right] by code point. */
        fun codePoints(left: String, right: String): Int {
            var i = 0
            var j = 0
            while (i < left.length && j < right.length) {
                val a = left.codePointAt(i)
                val b = right.codePointAt(j)
                if (a != b) {
                    return a.compareTo(b)
                }
                i += Character.charCount(a)
                j += Character.charCount(b)
            }
            return (left.length - i).compareTo(right.length - j)
        }

        val json = WowJson.mapper

        /** The parameters of each table's branch of the due read: the partitions, until, limit. */
        const val DUE_PARAMETERS = 3

        /** Each table's parameters of the due read's continuation: the partition, until, the last read, limit. */
        const val DUE_AFTER_PARAMETERS = 5

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
