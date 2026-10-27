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

package me.ahoo.wow.elasticsearch.snapshot

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.json.query.SnapshotFields
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The snapshot document of the Elasticsearch snapshot index: each field of the snapshot record
 * under its own name, at the top level ([RECORD_FIELDS]): the state as the object `state`, the tags as an object of
 * string arrays, the operators as `{id, tenantId, crossScope}` or null, the schedules as the object
 * of each rule by its key, `{type, ...}`; beside them the store's own fields, which no record shows:
 * the state's schema fingerprint, its state revision and, when it is marked stale, the version it was marked at.
 *
 * The state is written and read as Wow's JSON: its numbers keep their exact value and form, since
 * Elasticsearch keeps the `_source` it was given.
 */
internal object ElasticsearchSnapshotDocuments {
    const val TENANT_ID = SnapshotFields.TENANT_ID
    const val AGGREGATE_ID = SnapshotFields.AGGREGATE_ID
    const val VERSION = SnapshotFields.VERSION
    const val OWNER_ID = SnapshotFields.OWNER_ID
    const val SPACE_ID = SnapshotFields.SPACE_ID
    const val DELETED = SnapshotFields.DELETED
    const val TAGS = SnapshotFields.TAGS
    const val FIRST_EVENT_TIME = SnapshotFields.FIRST_EVENT_TIME
    const val LAST_EVENT_TIME = SnapshotFields.LAST_EVENT_TIME
    const val OPERATOR = SnapshotFields.OPERATOR
    const val FIRST_OPERATOR = SnapshotFields.FIRST_OPERATOR
    const val SCHEDULES = SnapshotFields.SCHEDULES
    const val STATE = SnapshotFields.STATE
    const val SCHEMA_FINGERPRINT = "schemaFingerprint"
    const val STATE_REVISION = "stateRevision"
    const val STALE_VERSION = "staleVersion"

    private const val OPERATOR_ID = "id"
    private const val OPERATOR_TENANT_ID = "tenantId"
    private const val OPERATOR_CROSS_SCOPE = "crossScope"

    /** The fields of a snapshot record, in the record's order (`SnapshotRecord.toQueryRecord()`). */
    val RECORD_FIELDS: List<String> = listOf(
        TENANT_ID,
        AGGREGATE_ID,
        VERSION,
        OWNER_ID,
        SPACE_ID,
        DELETED,
        TAGS,
        FIRST_EVENT_TIME,
        LAST_EVENT_TIME,
        OPERATOR,
        FIRST_OPERATOR,
        SCHEDULES,
        STATE,
    )

    /** The store's own fields, which a query never returns. */
    val STORE_FIELDS: List<String> = listOf(SCHEMA_FINGERPRINT, STATE_REVISION, STALE_VERSION)

    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    /** The document of [snapshot], marked stale at [staleVersion] when it is given. */
    fun document(snapshot: SnapshotRecord, staleVersion: Int? = null): ObjectNode = nodes.objectNode().apply {
        put(TENANT_ID, snapshot.ref.tenantId)
        put(AGGREGATE_ID, snapshot.ref.id)
        put(VERSION, snapshot.version)
        put(OWNER_ID, snapshot.scope.ownerId)
        put(SPACE_ID, snapshot.scope.spaceId)
        put(DELETED, snapshot.deleted)
        set(
            TAGS,
            nodes.objectNode().also { tags ->
                snapshot.tags.forEach { (name, values) ->
                    tags.set(name, nodes.arrayNode().also { array -> values.forEach(array::add) })
                }
            },
        )
        put(FIRST_EVENT_TIME, snapshot.firstEventTime)
        put(LAST_EVENT_TIME, snapshot.lastEventTime)
        set(OPERATOR, operator(snapshot.operator))
        set(FIRST_OPERATOR, operator(snapshot.firstOperator))
        set(
            SCHEDULES,
            nodes.objectNode().also { rules ->
                snapshot.schedules.forEach { (key, rule) -> rules.set(key, WowJson.mapper.valueToTree<JsonNode>(rule)) }
            },
        )
        set(STATE, WowJson.mapper.readTree(snapshot.state))
        put(SCHEMA_FINGERPRINT, snapshot.schemaFingerprint)
        put(STATE_REVISION, snapshot.stateRevision)
        staleVersion?.let { put(STALE_VERSION, it) }
    }

    /** The snapshot of [type] that [document] holds. */
    fun snapshot(type: AggregateType, document: ObjectNode): SnapshotRecord {
        val tags = document.path(TAGS)
        return SnapshotRecord(
            ref = type.ref(document.path(TENANT_ID).stringValue(), document.path(AGGREGATE_ID).stringValue()),
            version = document.path(VERSION).intValue(),
            schemaFingerprint = document.path(SCHEMA_FINGERPRINT).stringValue(),
            state = WowJson.mapper.writeValueAsString(document.path(STATE)),
            scope = Scope(document.textOrNull(OWNER_ID), document.textOrNull(SPACE_ID)),
            deleted = document.path(DELETED).booleanValue(),
            firstEventTime = document.path(FIRST_EVENT_TIME).longValue(),
            lastEventTime = document.path(LAST_EVENT_TIME).longValue(),
            tags = tags.propertyNames().associateWith { name -> tags.path(name).toList().map { it.stringValue() } },
            operator = operatorOf(document.path(OPERATOR)),
            firstOperator = operatorOf(document.path(FIRST_OPERATOR)),
            schedules = document.path(SCHEDULES).let { rules ->
                rules.propertyNames().associateWith { key ->
                    WowJson.mapper.treeToValue(rules.path(key), ScheduleRule::class.java)
                }
            },
            stateRevision = document.path(STATE_REVISION).takeIf { it.isNumber }?.intValue() ?: 1,
        )
    }

    /** The version of the snapshot [document] holds. */
    fun versionOf(document: ObjectNode): Int = document.path(VERSION).intValue()

    /** The state revision of the snapshot [document] holds: 1 when it has none. */
    fun stateRevisionOf(document: ObjectNode): Int =
        document.path(STATE_REVISION).takeIf { it.isNumber }?.intValue() ?: 1

    /** The version [document] is marked stale at, or null when it is not marked. */
    fun staleVersionOf(document: ObjectNode): Int? = document.path(STALE_VERSION).takeIf { it.isNumber }?.intValue()

    /**
     * The snapshot record [document] holds, the shape of `SnapshotRecord.toQueryRecord()`: each record field the
     * document holds, in the record's order, without the store's own fields. A field a source filter left out is
     * absent.
     */
    fun record(document: ObjectNode): ObjectNode = nodes.objectNode().apply {
        RECORD_FIELDS.forEach { field -> document.get(field)?.let { set(field, it) } }
    }

    private fun operator(operator: Operator?): JsonNode = if (operator == null) {
        nodes.nullNode()
    } else {
        nodes.objectNode()
            .put(OPERATOR_ID, operator.id)
            .put(OPERATOR_TENANT_ID, operator.tenantId)
            .put(OPERATOR_CROSS_SCOPE, operator.crossScope)
    }

    private fun operatorOf(node: JsonNode): Operator? = if (node.isObject) {
        Operator(
            node.path(OPERATOR_ID).stringValue(),
            node.path(OPERATOR_TENANT_ID).stringValue(),
            node.path(OPERATOR_CROSS_SCOPE).booleanValue(),
        )
    } else {
        null
    }

    private fun ObjectNode.textOrNull(field: String): String? = get(field)?.takeIf { it.isString }?.stringValue()
}
