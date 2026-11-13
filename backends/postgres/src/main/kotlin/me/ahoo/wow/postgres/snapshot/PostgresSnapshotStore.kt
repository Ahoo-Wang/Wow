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

package me.ahoo.wow.postgres.snapshot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.postgres.PostgresFailures
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.PostgresSchema
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.inTransaction
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.postgres.jdbcRead
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.schema.EnsuredOnce
import me.ahoo.wow.spi.snapshot.SnapshotRebuildStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import me.ahoo.wow.spi.store.StoreCallMarks
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource

/**
 * The [SnapshotStore] of one bounded context on PostgreSQL (documentation/designs/03-log-and-snapshot.md §3).
 *
 * Each aggregate type has a table of its own ([PostgresSnapshotTables]), created on its first save or
 * load, which claims its format version first ([PostgresFormat]). One table holds one row per aggregate. The state and
 * the access attributes are `jsonb`, so the snapshot can be queried in place
 * as the default read model. A save is one autocommitted statement that carries its own version and state revision
 * guard (see [PostgresSnapshotTables.save]); a save refused for a higher state revision then marks the row stale. The
 * claimed state revision of each table is a row of [PostgresSnapshotTables.TABLES], claimed with one
 * statement.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21). A failure of the database is a
 * defect and is thrown: a snapshot is a cache, so the caller may drop it and rebuild later.
 */
@InternalWowApi
class PostgresSnapshotStore(
    private val dataSource: DataSource,
    private val context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's tables are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
) : SnapshotStore, SnapshotRebuildStore {
    /**
     * Whether the node covered this context's snapshot writes ([me.ahoo.wow.spi.store.CoveredSnapshots]): its log, the
     * checkpoints of its snapshot subscription and all its snapshots on this store's database, so [saveAll] commits
     * without waiting for its flush. Not by default: a store opened on its own writes every snapshot durably.
     */
    @Volatile
    internal var covered: Boolean = false

    /** This store rewrites its snapshots in place: it is its own rebuild control plane. */
    override val rebuilds: SnapshotRebuildStore get() = this

    private val created = EnsuredOnce<String, PostgresSnapshotTables>()

    /**
     * Nothing to create up front: each aggregate type's table is created on its first use ([createTable]), since the
     * store does not know the context's aggregate types. Kept for the host, which calls it at startup.
     */
    fun createSchema() = Unit

    /**
     * Creates the table of [aggregateName] when missing, once per store; refuses snapshots recorded in another format
     * version ([PostgresFormat]).
     */
    fun createTable(aggregateName: String) {
        tables(aggregateName)
    }

    /**
     * The tables of [aggregateName], created when this store has not yet made sure they are: once, however many first
     * uses ask at once ([EnsuredOnce]).
     */
    private fun tables(aggregateName: String): PostgresSnapshotTables = created.get(aggregateName) {
        PostgresSnapshotTables(context, aggregateName).also { tables ->
            dataSource.autoCommitted { connection ->
                PostgresFormat.schema(connection, ddl, listOf(tables.base to PostgresSnapshotTables.FORMAT)) {
                    PostgresSchema.execute(connection, tables.ddl())
                }
            }
        }
    }

    override suspend fun save(snapshot: SnapshotRecord): Boolean = dispatcher.jdbc {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        val tables = tables(snapshot.ref.type.name)
        dataSource.autoCommitted { connection -> saveIn(connection, tables, snapshot) }
    }

    /** The version-guarded save of [snapshot] on [connection]; a refused one marks a higher state revision stale. */
    private fun saveIn(connection: Connection, tables: PostgresSnapshotTables, snapshot: SnapshotRecord): Boolean =
        upsert(connection, tables.save, snapshot).also { written ->
            if (!written) {
                connection.prepareStatement(tables.markRefused).use { mark ->
                    mark.setInt(1, snapshot.version)
                    mark.setString(2, snapshot.ref.tenantId)
                    mark.setString(3, snapshot.ref.id)
                    mark.setInt(4, snapshot.version)
                    mark.setInt(5, snapshot.stateRevision)
                    mark.executeUpdate()
                }
            }
        }

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean = dispatcher.jdbc {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        dataSource.autoCommitted { upsert(it, tables(snapshot.ref.type.name).rewrite, snapshot) }
    }

    /**
     * Saves [snapshots]: in the rounds [SnapshotSaveOutcome.rounds] gives, each round's snapshots of
     * one aggregate type with one statement ([PostgresSnapshotTables.saveRows], at most
     * [PostgresSnapshotTables.MAX_ROWS] a statement), then, only when some were refused, one statement marking those of
     * a higher state revision stale. So a batch of one aggregate type that all lands is one round trip, as one save is.
     *
     * All of it is one transaction and one commit, the aggregate types in name order and each statement's rows in key
     * order, so concurrent batches lock their common rows in the same order.
     *
     * When the store is [covered], the commit does not wait for its WAL flush (`synchronous_commit = off`). Covered
     * means the node found the context's log, the checkpoints its snapshot subscription saves and every store its
     * snapshots go to all on this one store ([me.ahoo.wow.spi.store.CoveredSnapshots]). Then the snapshots are its log
     * folded, and the subscription saves the checkpoint covering them, which waits for its own flush, only after they
     * are written. The WAL being written in order, that flush carries them, as any append's does, or the WAL writer's
     * after `wal_writer_delay`. A crash of PostgreSQL loses at most the snapshots after the last durable checkpoint,
     * which the subscription then takes again and writes again; meanwhile a load replays more of the log. Waiting for
     * the flush there too doubled a command's WAL flushes, its append's and its snapshot's. PostgreSQL flushes one at a
     * time, so on a disk whose flushes take half a millisecond a node at a thousand commands a second spent more than a
     * second of flushing a second, and its commits queued.
     *
     * A store not covered commits as any write, waiting for its flush. A checkpoint durable elsewhere, in another
     * database or backend, would otherwise cover snapshots this one could lose with nothing to write them again, and
     * an idle aggregate's snapshot would stay at the state before until its next command.
     *
     * A statement PostgreSQL refuses for a value ([PostgresFailures.isRefused]) aborts the transaction, which then wrote
     * nothing: the rounds run again statement by statement in autocommit, where such a statement's snapshots are saved
     * one by one, so the one refused is [SnapshotSaveOutcome.Failed] alone. Any other failure is thrown, and nothing of
     * the batch was written.
     */
    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
        // A write the snapshot subscription clocks: marked from its entry to its connection given back, the commit's
        // round trip and a refusal's mark among the release.
        val marks = currentCoroutineContext()[StoreCallMarks]?.apply { entered() }
        return dispatcher.jdbc {
            marks?.onThread()
            snapshots.forEach { require(it.ref.type.context == context) { "${it.ref} is not in context $context." } }
            val tablesOf = snapshots.map { it.ref.type.name }.distinct().associateWith(::tables)
            if (snapshots.isEmpty()) {
                emptyList()
            } else {
                dataSource.autoCommitted { connection ->
                    marks?.acquired()
                    try {
                        inTransaction(connection, asynchronous = covered) { bound ->
                            saveRounds(bound, tablesOf, snapshots, alone = false, marks)
                        }
                    } catch (failure: SQLException) {
                        PostgresFailures.refusedOrThrow(failure)
                        saveRounds(connection, tablesOf, snapshots, alone = true, marks)
                    }
                }.also { marks?.released() }
            }
        }
    }

    /**
     * Saves [snapshots] round by round on [connection]; their outcomes in order. With [alone], a statement refused for
     * a value has its snapshots saved one by one; without, the refusal is thrown (it aborted the transaction).
     */
    private fun saveRounds(
        connection: Connection,
        tablesOf: Map<String, PostgresSnapshotTables>,
        snapshots: List<SnapshotRecord>,
        alone: Boolean,
        marks: StoreCallMarks?,
    ): List<SnapshotSaveOutcome> {
        val outcomes = arrayOfNulls<SnapshotSaveOutcome>(snapshots.size)
        SnapshotSaveOutcome.rounds(snapshots).forEach { round ->
            round.groupBy { snapshots[it].ref.type.name }.toSortedMap().forEach { (aggregate, indexes) ->
                // In key order, so concurrent statements lock their common rows in the same order.
                val ordered = indexes.sortedWith(compareBy(KEY_ORDER) { snapshots[it] })
                ordered.chunked(PostgresSnapshotTables.MAX_ROWS).forEach { chunk ->
                    val rows = chunk.map(snapshots::get)
                    val saved = saveRows(connection, tablesOf.getValue(aggregate), rows, alone, marks)
                    saved.forEachIndexed { at, outcome -> outcomes[chunk[at]] = outcome }
                }
            }
        }
        return outcomes.map { checkNotNull(it) }
    }

    /**
     * Saves [snapshots], of distinct refs of one aggregate type, in key order; their outcomes in order. Both
     * statements lock their rows in key order, as every concurrent batch does, so batches never deadlock.
     */
    private fun saveRows(
        connection: Connection,
        tables: PostgresSnapshotTables,
        snapshots: List<SnapshotRecord>,
        alone: Boolean,
        marks: StoreCallMarks?,
    ): List<SnapshotSaveOutcome> {
        val written = try {
            connection.prepareStatement(tables.saveRows(snapshots.size)).use { save ->
                val width = PostgresSnapshotTables.ROW_PARAMETERS
                snapshots.forEachIndexed { row, snapshot -> bind(save, row * width, snapshot) }
                marks?.sent()
                save.executeQuery().use { rows ->
                    buildSet<Pair<String, String>> {
                        while (rows.next()) {
                            add(rows.getString(1) to rows.getString(2))
                        }
                    }
                }.also { marks?.read() }
            }
        } catch (failure: SQLException) {
            // Refused for a value, the statement wrote nothing; any other failure is thrown, and so is a refusal in the
            // batch's transaction, which it aborted.
            if (!alone) throw failure
            PostgresFailures.refusedOrThrow(failure)
            return snapshots.map { savedAlone(connection, tables, it) }
        }
        val refused = snapshots.filter { (it.ref.tenantId to it.ref.id) !in written }
        if (refused.isNotEmpty()) {
            connection.prepareStatement(tables.markRefusedRows(refused.size)).use { mark ->
                refused.forEachIndexed { row, snapshot ->
                    val at = row * MARK_PARAMETERS
                    mark.setString(at + 1, snapshot.ref.tenantId)
                    mark.setString(at + 2, snapshot.ref.id)
                    mark.setInt(at + 3, snapshot.version)
                    mark.setInt(at + 4, snapshot.stateRevision)
                }
                mark.executeUpdate()
            }
        }
        return snapshots.map { SnapshotSaveOutcome.of((it.ref.tenantId to it.ref.id) in written) }
    }

    /** [snapshot] saved alone, after a statement of several was refused for a value: refused alone, it failed. */
    private fun savedAlone(
        connection: Connection,
        tables: PostgresSnapshotTables,
        snapshot: SnapshotRecord,
    ): SnapshotSaveOutcome = try {
        SnapshotSaveOutcome.of(saveIn(connection, tables, snapshot))
    } catch (failure: SQLException) {
        SnapshotSaveOutcome.Failed(PostgresFailures.refusedOrThrow(failure))
    }

    /** Runs the upsert [statement] of [snapshot]; whether it wrote the row. */
    private fun upsert(connection: Connection, statement: String, snapshot: SnapshotRecord): Boolean =
        connection.prepareStatement(statement).use { save ->
            bind(save, 0, snapshot)
            save.executeQuery().use { rows -> rows.next() }
        }

    /** Sets the parameters of [snapshot]'s row, the [PostgresSnapshotTables.ROW_PARAMETERS] after [offset]. */
    private fun bind(save: PreparedStatement, offset: Int, snapshot: SnapshotRecord) {
        save.setString(offset + 1, snapshot.ref.tenantId)
        save.setString(offset + 2, snapshot.ref.id)
        save.setInt(offset + 3, snapshot.version)
        save.setString(offset + 4, snapshot.schemaFingerprint)
        save.setString(offset + 5, snapshot.state)
        save.setNullableString(offset + 6, snapshot.scope.ownerId)
        save.setNullableString(offset + 7, snapshot.scope.spaceId)
        save.setBoolean(offset + 8, snapshot.deleted)
        save.setLong(offset + 9, snapshot.firstEventTime)
        save.setLong(offset + 10, snapshot.lastEventTime)
        save.setString(offset + 11, AccessValues.text(snapshot.access))
        save.setNullableString(offset + 12, snapshot.operator?.let(json::writeValueAsString))
        save.setNullableString(offset + 13, snapshot.firstOperator?.let(json::writeValueAsString))
        save.setNullableString(offset + 14, snapshot.schedules.takeIf { it.isNotEmpty() }?.let(::encodeSchedules))
        save.setInt(offset + 15, snapshot.stateRevision)
    }

    override suspend fun markStale(ref: AggregateRef): Boolean = dispatcher.jdbc {
        require(ref.type.context == context) { "$ref is not in context $context." }
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables(ref.type.name).markStale).use { mark ->
                mark.setString(1, ref.tenantId)
                mark.setString(2, ref.id)
                mark.executeUpdate() > 0
            }
        }
    }

    override suspend fun pending(
        type: AggregateType,
        revision: Int,
        after: AggregateRef?,
        limit: Int,
    ): List<AggregateRef> = dispatcher.jdbc {
        require(type.context == context) { "$type is not in context $context." }
        require(limit >= 1) { "A page holds at least one ref." }
        val tables = tables(type.name)
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(if (after == null) tables.pendingFirst else tables.pendingAfter).use { query ->
                query.setInt(1, revision)
                if (after == null) {
                    query.setInt(2, limit)
                } else {
                    query.setString(2, after.tenantId)
                    query.setString(3, after.id)
                    query.setInt(4, limit)
                }
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(type.ref(rows.getString(1), rows.getString(2)))
                        }
                    }
                }
            }
        }
    }

    override suspend fun table(type: AggregateType): SnapshotTable? = dispatcher.jdbc {
        require(type.context == context) { "$type is not in context $context." }
        val tables = tables(type.name)
        dataSource.autoCommitted { connection -> recorded(connection, tables) }
    }

    override suspend fun claim(type: AggregateType, revision: Int, lower: Boolean): SnapshotTable = dispatcher.jdbc {
        require(type.context == context) { "$type is not in context $context." }
        require(revision >= 1) { "A state revision starts at 1." }
        val tables = tables(type.name)
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables.claim).use { claim ->
                claim.setString(1, tables.base)
                claim.setInt(2, revision)
                claim.setBoolean(3, revision != 1)
                claim.setBoolean(4, lower)
                claim.executeQuery().use { rows -> if (rows.next()) tableOf(rows) else null }
            } ?: checkNotNull(recorded(connection, tables))
        }
    }

    override suspend fun mark(
        type: AggregateType,
        revision: Int,
        rebuilding: Boolean?,
        backfilled: Boolean?,
    ): SnapshotTable? = dispatcher.jdbc {
        require(type.context == context) { "$type is not in context $context." }
        val tables = tables(type.name)
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables.mark).use { mark ->
                mark.setNullableBoolean(1, rebuilding)
                mark.setNullableBoolean(2, backfilled)
                mark.setString(3, tables.base)
                mark.setInt(4, revision)
                mark.executeQuery().use { rows -> if (rows.next()) tableOf(rows) else null }
            }
        }
    }

    private fun recorded(connection: Connection, tables: PostgresSnapshotTables): SnapshotTable? =
        connection.prepareStatement(tables.table).use { query ->
            query.setString(1, tables.base)
            query.executeQuery().use { rows -> if (rows.next()) tableOf(rows) else null }
        }

    private fun tableOf(row: ResultSet) = SnapshotTable(
        revision = row.getInt("revision"),
        rebuilding = row.getBoolean("rebuilding"),
        backfilled = row.getBoolean("backfilled"),
    )

    // Interruptible: the kernel gives up on a load past its timeout, and the read ends with it.
    override suspend fun load(ref: AggregateRef): SnapshotRecord? = dispatcher.jdbcRead {
        require(ref.type.context == context) { "$ref is not in context $context." }
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables(ref.type.name).load).use { query ->
                query.setString(1, ref.tenantId)
                query.setString(2, ref.id)
                query.executeQuery().use { rows -> if (rows.next()) snapshotOf(ref.type, rows) else null }
            }
        }
    }

    /**
     * The table of [aggregateName]'s snapshots, made sure of, when this store keeps [context]'s snapshots in
     * [dataSource]: the log of that context in that database reads a snapshot with its tail in one
     * statement. Null when the store is another context's or in another database.
     */
    internal fun sharedTable(dataSource: DataSource, context: String, aggregateName: String): String? =
        if (dataSource === this.dataSource && context == this.context) tables(aggregateName).snapshot else null

    /** The snapshot of [row], its columns named with [prefix] ([PostgresSnapshotTables.READ_COLUMNS]). */
    internal fun snapshotOf(type: AggregateType, row: ResultSet, prefix: String = "") = SnapshotRecord(
        ref = type.ref(row.getString(prefix + "tenant_id"), row.getString(prefix + "aggregate_id")),
        version = row.getInt(prefix + "version"),
        schemaFingerprint = row.getString(prefix + "schema_fingerprint"),
        state = row.getString(prefix + "state"),
        scope = Scope(row.getString(prefix + "owner_id"), row.getString(prefix + "space_id")),
        deleted = row.getBoolean(prefix + "deleted"),
        firstEventTime = row.getLong(prefix + "first_event_time"),
        lastEventTime = row.getLong(prefix + "last_event_time"),
        access = AccessValues.parse(row.getString(prefix + "access")).orEmpty(),
        operator = row.getString(prefix + "operator")?.let(::decodeOperator),
        firstOperator = row.getString(prefix + "first_operator")?.let(::decodeOperator),
        schedules = row.getString(prefix + "schedules")?.let(::decodeSchedules).orEmpty(),
        stateRevision = row.getInt(prefix + "state_revision"),
    )

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun PreparedStatement.setNullableBoolean(index: Int, value: Boolean?) {
        if (value == null) setNull(index, Types.BOOLEAN) else setBoolean(index, value)
    }

    internal companion object {
        /** The parameters of one refused snapshot's row of [PostgresSnapshotTables.markRefusedRows]. */
        const val MARK_PARAMETERS = 4

        /** The order of the snapshot tables' keys, the `C` collation's: tenant, then aggregate id, by code point. */
        val KEY_ORDER: Comparator<SnapshotRecord> = Comparator { left, right ->
            byCodePoint(left.ref.tenantId, right.ref.tenantId).takeIf { it != 0 } ?: byCodePoint(left.ref.id, right.ref.id)
        }

        private fun byCodePoint(left: String, right: String): Int {
            val lefts = left.codePoints().iterator()
            val rights = right.codePoints().iterator()
            while (lefts.hasNext() && rights.hasNext()) {
                val order = lefts.nextInt().compareTo(rights.nextInt())
                if (order != 0) {
                    return order
                }
            }
            return lefts.hasNext().compareTo(rights.hasNext())
        }

        val json: JsonMapper = WowJson.mapper

        fun decodeOperator(text: String): Operator = json.readValue(text, Operator::class.java)

        /** Each rule by its key, each written as its own value so it carries its `type`. */
        fun encodeSchedules(schedules: Map<String, ScheduleRule>): String {
            val node = json.createObjectNode()
            schedules.forEach { (key, rule) -> node.set(key, json.readTree(json.writeValueAsString(rule))) }
            return json.writeValueAsString(node)
        }

        fun decodeSchedules(text: String): Map<String, ScheduleRule> = buildMap {
            for ((key, rule) in json.readTree(text).properties()) {
                put(key, json.treeToValue(rule, ScheduleRule::class.java))
            }
        }
    }
}
