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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * The rows of a context's log on PostgreSQL: a stream's columns as an append binds them ([bindStream]), and the stream
 * or entry a read gives back ([streamOf], [entryOf]). The events are one JSON array and the headers one JSON object,
 * so the log can be queried in place.
 */
internal class PostgresLogRows(private val context: String) {
    /** Binds [stream] and [transport] to [statement] from parameter [first] on: one per [AggregateLogTable.COLUMNS]. */
    fun bindStream(statement: PreparedStatement, first: Int, stream: EventStreamRecord, transport: ByteArray?) {
        var index = first
        statement.setString(index++, stream.ref.tenantId)
        statement.setString(index++, stream.ref.id)
        statement.setInt(index++, stream.version)
        statement.setString(index++, stream.commandId)
        statement.setString(index++, stream.requestId)
        statement.setString(index++, stream.commandType)
        statement.setLong(index++, stream.commitTime)
        statement.setString(index++, encodeEvents(stream.events))
        statement.setNullableString(index++, stream.scope.ownerId)
        statement.setNullableString(index++, stream.scope.spaceId)
        statement.setNullableString(index++, stream.causation?.correlationId)
        statement.setNullableString(index++, stream.causation?.causationId)
        statement.setString(index++, json.writeValueAsString(stream.headers))
        statement.setBytes(index++, transport)
        val operator = stream.operator
        statement.setNullableString(index++, operator?.id)
        statement.setNullableString(index++, operator?.tenantId)
        if (operator == null) {
            statement.setNull(index++, Types.BOOLEAN)
        } else {
            statement.setBoolean(index++, operator.actedElsewhere)
        }
        statement.setNullableString(index++, stream.access?.let(PostgresLogAccessColumn::text))
        stream.accessVersion?.let { statement.setInt(index, it) } ?: statement.setNull(index, Types.INTEGER)
    }

    fun entryOf(partition: Int, aggregate: String, row: ResultSet) = LogEntry(
        LogPosition(partition, row.getLong("sequence")),
        streamOf(aggregate, row),
        row.getBytes("transport"),
    )

    fun streamOf(aggregate: String, row: ResultSet) = EventStreamRecord(
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
            Operator(it, row.getString("operator_tenant_id"), row.getBoolean("operator_acted_elsewhere"))
        },
        access = PostgresLogAccessColumn.of(row.getString("access")),
        accessVersion = row.getInt("access_version").takeUnless { row.wasNull() },
    )

    private companion object {
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

        /** A JSON object of strings, such as the headers. */
        fun decodeStrings(text: String): Map<String, String> = buildMap {
            for ((name, value) in json.readTree(text).properties()) {
                put(name, value.asString())
            }
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

internal fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

internal fun <T> ResultSet.map(row: (ResultSet) -> T): List<T> {
    val values = ArrayList<T>()
    while (next()) {
        values += row(this)
    }
    return values
}
