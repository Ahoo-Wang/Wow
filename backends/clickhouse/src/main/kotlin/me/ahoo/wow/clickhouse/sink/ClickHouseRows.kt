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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventRecord
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The row of a [StateEventRecord] in the base table (or in `_state` without the history), a `JSONEachRow` object
 * written with [WowJson], so numbers keep their exact text:
 *
 * | Column | From |
 * | --- | --- |
 * | `id` | `ref@version` ([StateEventRecord.id]) |
 * | `tenant_id`, `aggregate_id` | the ref's tenant and id (the context and aggregate are the table's) |
 * | `version`, `command_id`, `request_id`, `command_type` | as they are |
 * | `commit_time` | the commit time in UTC, to the millisecond |
 * | `owner_id`, `space_id` | the scope, empty when none |
 * | `deleted`, `tags` | as they are |
 * | `correlation_id`, `causation_id` | the causation, empty when none |
 * | `headers` | as they are |
 * | `operator_id`, `operator_tenant_id`, `operator_cross_scope` | the operator; empty and false when none |
 * | `events` | each event's `(name, revision, payload)`, the payload as JSON text |
 * | `state`, `state_fingerprint` | the state as JSON text and its schema's fingerprint; empty without the state |
 * | `state_numbers` | the exact text of each top-level number of the state, which `_state_flat` reads decimals from |
 *
 * The transport is not a fact and is not kept.
 */
internal object ClickHouseRows {
    private val COMMIT_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /** The row of [record]. */
    fun rowOf(record: StateEventRecord): ObjectNode = WowJson.mapper.createObjectNode().apply {
        put("id", record.id)
        put("tenant_id", record.ref.tenantId)
        put("aggregate_id", record.ref.id)
        put("version", record.version)
        put("command_id", record.commandId)
        put("request_id", record.requestId)
        put("command_type", record.commandType)
        put("commit_time", COMMIT_TIME.format(Instant.ofEpochMilli(record.commitTime)))
        put("owner_id", record.scope.ownerId.orEmpty())
        put("space_id", record.scope.spaceId.orEmpty())
        put("deleted", record.deleted)
        putObject("tags").apply {
            record.tags.forEach { (key, values) -> putArray(key).apply { values.forEach(::add) } }
        }
        put("correlation_id", record.causation?.correlationId.orEmpty())
        put("causation_id", record.causation?.causationId.orEmpty())
        putObject("headers").apply { record.headers.forEach { (key, value) -> put(key, value) } }
        put("operator_id", record.operator?.id.orEmpty())
        put("operator_tenant_id", record.operator?.tenantId.orEmpty())
        put("operator_cross_scope", record.operator?.crossScope ?: false)
        putArray("events").apply {
            record.events.forEach { event ->
                addObject()
                    .put("name", event.name)
                    .put("revision", event.revision)
                    .put("payload", textOf(event.payload))
            }
        }
        val state = record.state
        put("state", state?.let { textOf(it.state) }.orEmpty())
        put("state_fingerprint", state?.schemaFingerprint.orEmpty())
        putObject("state_numbers").apply {
            state?.state?.properties()?.forEach { (name, value) ->
                if (value.isNumber) put(name, value.decimalValue().toPlainString())
            }
        }
    }

    /** The `JSONEachRow` body of [records]: one row per line. */
    fun bodyOf(records: List<StateEventRecord>): ByteArray =
        records.joinToString(separator = "\n", postfix = "\n") { textOf(rowOf(it)) }.toByteArray(Charsets.UTF_8)

    private fun textOf(node: JsonNode): String = WowJson.mapper.writeValueAsString(node)
}
