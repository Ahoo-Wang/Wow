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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.VirtualThreads
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * The [SnapshotStore] of one bounded context on PostgreSQL (documentation/designs/v10/03-log-and-snapshot.md §3).
 *
 * Each aggregate type has a table of its own (decision D161, [PostgresSnapshotTables]), created on its first save or
 * load, which claims its format version first ([PostgresFormat]). One table holds one row per aggregate. The state and the tags are `jsonb`, so the snapshot can be queried in place
 * as the default read model. A save is one autocommitted statement that carries its own version and state revision
 * guard (see [PostgresSnapshotTables.save]); a save refused for a higher state revision then marks the row stale. The
 * claimed state revision of each table (decision D172) is a row of [PostgresSnapshotTables.TABLES], claimed with one
 * statement.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29). A failure of the database is a
 * defect and is thrown: a snapshot is a cache, so the caller may drop it and rebuild later.
 */
@InternalWowApi
class PostgresSnapshotStore(
    private val dataSource: DataSource,
    private val context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's tables are managed: created when missing by default (review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
) : SnapshotStore {
    private val created = ConcurrentHashMap<String, PostgresSnapshotTables>()

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
     * The tables of [aggregateName], created when this store has not yet made sure they are. Not `computeIfAbsent`:
     * it would hold a monitor across the JDBC calls, and virtual threads blocked on a monitor pin their carriers (JDK
     * 21), so concurrent first uses could take every carrier and deadlock. Concurrent creations are serialized by
     * [PostgresFormat.creating] instead, and creating twice is harmless.
     */
    private fun tables(aggregateName: String): PostgresSnapshotTables = created[aggregateName]
        ?: PostgresSnapshotTables(context, aggregateName).also { tables ->
            dataSource.autoCommitted { connection ->
                PostgresFormat.schema(connection, ddl, listOf(tables.base to PostgresSnapshotTables.FORMAT)) {
                    connection.createStatement().use { statement -> tables.ddl().forEach { statement.execute(it) } }
                }
            }
            created[aggregateName] = tables
        }

    override suspend fun save(snapshot: SnapshotRecord): Boolean = dispatcher.jdbc {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        val tables = tables(snapshot.ref.type.name)
        dataSource.autoCommitted { connection ->
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
        }
    }

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean = dispatcher.jdbc {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        dataSource.autoCommitted { upsert(it, tables(snapshot.ref.type.name).rewrite, snapshot) }
    }

    /** Runs the upsert [statement] of [snapshot]; whether it wrote the row. */
    private fun upsert(connection: Connection, statement: String, snapshot: SnapshotRecord): Boolean =
        connection.prepareStatement(statement).use { save ->
            save.setString(1, snapshot.ref.tenantId)
            save.setString(2, snapshot.ref.id)
            save.setInt(3, snapshot.version)
            save.setString(4, snapshot.schemaFingerprint)
            save.setString(5, snapshot.state)
            save.setNullableString(6, snapshot.scope.ownerId)
            save.setNullableString(7, snapshot.scope.spaceId)
            save.setBoolean(8, snapshot.deleted)
            save.setLong(9, snapshot.firstEventTime)
            save.setLong(10, snapshot.lastEventTime)
            save.setString(11, json.writeValueAsString(snapshot.tags))
            save.setNullableString(12, snapshot.operator?.let(json::writeValueAsString))
            save.setNullableString(13, snapshot.firstOperator?.let(json::writeValueAsString))
            save.setNullableString(14, snapshot.schedules.takeIf { it.isNotEmpty() }?.let(::encodeSchedules))
            save.setInt(15, snapshot.stateRevision)
            save.executeQuery().use { rows -> rows.next() }
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

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = dispatcher.jdbc {
        require(ref.type.context == context) { "$ref is not in context $context." }
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables(ref.type.name).load).use { query ->
                query.setString(1, ref.tenantId)
                query.setString(2, ref.id)
                query.executeQuery().use { rows -> if (rows.next()) snapshotOf(ref.type, rows) else null }
            }
        }
    }

    private fun snapshotOf(type: AggregateType, row: ResultSet) = SnapshotRecord(
        ref = type.ref(row.getString("tenant_id"), row.getString("aggregate_id")),
        version = row.getInt("version"),
        schemaFingerprint = row.getString("schema_fingerprint"),
        state = row.getString("state"),
        scope = Scope(row.getString("owner_id"), row.getString("space_id")),
        deleted = row.getBoolean("deleted"),
        firstEventTime = row.getLong("first_event_time"),
        lastEventTime = row.getLong("last_event_time"),
        tags = decodeTags(row.getString("tags")),
        operator = row.getString("operator")?.let(::decodeOperator),
        firstOperator = row.getString("first_operator")?.let(::decodeOperator),
        schedules = row.getString("schedules")?.let(::decodeSchedules).orEmpty(),
        stateRevision = row.getInt("state_revision"),
    )

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun PreparedStatement.setNullableBoolean(index: Int, value: Boolean?) {
        if (value == null) setNull(index, Types.BOOLEAN) else setBoolean(index, value)
    }

    private companion object {
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

        fun decodeTags(text: String): Map<String, List<String>> = buildMap {
            for ((name, values) in json.readTree(text).properties()) {
                // An array node has a map member of its own, so iterate rather than map.
                val list = ArrayList<String>(values.size())
                for (value in values) {
                    list += value.asString()
                }
                put(name, list)
            }
        }
    }
}
