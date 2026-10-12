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
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.VirtualThreads
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import tools.jackson.databind.json.JsonMapper
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * The [SnapshotStore] of one bounded context on PostgreSQL (documentation/designs/v10/03-log-and-snapshot.md §3).
 *
 * One table holds one row per aggregate. The state and the tags are `jsonb`, so the snapshot can be queried in place
 * as the default read model. A save is one autocommitted statement that carries its own version guard (see
 * [PostgresSnapshotTables.save]).
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29). A failure of the database is a
 * defect and is thrown: a snapshot is a cache, so the caller may drop it and rebuild later.
 */
class PostgresSnapshotStore(
    private val dataSource: DataSource,
    private val context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : SnapshotStore {
    private val tables = PostgresSnapshotTables(context)

    /** Creates the table when missing; refuses snapshots recorded in another format version ([PostgresFormat]). */
    fun createSchema() {
        dataSource.connection.use { connection ->
            PostgresFormat.claim(connection, tables.base, PostgresSnapshotTables.FORMAT)
            connection.createStatement().use { statement -> tables.ddl().forEach { statement.execute(it) } }
        }
    }

    override suspend fun save(snapshot: SnapshotRecord): Boolean = withContext(dispatcher) {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        dataSource.connection.use { connection ->
            connection.prepareStatement(tables.save).use { save ->
                save.setString(1, snapshot.ref.tenantId)
                save.setString(2, snapshot.ref.type.name)
                save.setString(3, snapshot.ref.id)
                save.setInt(4, snapshot.version)
                save.setString(5, snapshot.schemaFingerprint)
                save.setString(6, snapshot.state)
                save.setNullableString(7, snapshot.scope.ownerId)
                save.setNullableString(8, snapshot.scope.spaceId)
                save.setBoolean(9, snapshot.deleted)
                save.setLong(10, snapshot.firstEventTime)
                save.setLong(11, snapshot.lastEventTime)
                save.setString(12, json.writeValueAsString(snapshot.tags))
                save.executeQuery().use { rows -> rows.next() }
            }
        }
    }

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = withContext(dispatcher) {
        require(ref.type.context == context) { "$ref is not in context $context." }
        dataSource.connection.use { connection ->
            connection.prepareStatement(tables.load).use { query ->
                query.setString(1, ref.tenantId)
                query.setString(2, ref.type.name)
                query.setString(3, ref.id)
                query.executeQuery().use { rows -> if (rows.next()) snapshotOf(rows) else null }
            }
        }
    }

    private fun snapshotOf(row: ResultSet) = SnapshotRecord(
        ref = AggregateType(context, row.getString("aggregate_name"))
            .ref(row.getString("tenant_id"), row.getString("aggregate_id")),
        version = row.getInt("version"),
        schemaFingerprint = row.getString("schema_fingerprint"),
        state = row.getString("state"),
        scope = Scope(row.getString("owner_id"), row.getString("space_id")),
        deleted = row.getBoolean("deleted"),
        firstEventTime = row.getLong("first_event_time"),
        lastEventTime = row.getLong("last_event_time"),
        tags = decodeTags(row.getString("tags")),
    )

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private companion object {
        val json: JsonMapper = WowJson.mapper

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
