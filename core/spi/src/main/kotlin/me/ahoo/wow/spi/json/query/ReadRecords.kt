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

@file:JvmName("ReadRecords")

package me.ahoo.wow.spi.json.query

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.read.CommittedEvent
import me.ahoo.wow.api.read.EventStream
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** [node] as [type]: the node itself when it is one (an `ObjectNode`, a `JsonNode`), else decoded by `WowJson`. */
@InternalWowApi
fun <T : Any> decode(node: JsonNode, type: Class<T>): T =
    if (type.isInstance(node)) type.cast(node) else WowJson.mapper.treeToValue(node, type)

/** This snapshot query record (decision D128, `SnapshotQueryRecords.kt`) of [type], its state decoded as [stateType]. */
@InternalWowApi
fun <S : Any> ObjectNode.toSnapshot(type: AggregateType, stateType: Class<S>): Snapshot<S> = Snapshot.of(
    ref = type.ref(text(SnapshotFields.TENANT_ID), text(SnapshotFields.AGGREGATE_ID)),
    version = present(SnapshotFields.VERSION).asInt(),
    scope = scope(SnapshotFields.OWNER_ID, SnapshotFields.SPACE_ID),
    tags = tags(),
    deleted = present(SnapshotFields.DELETED).asBoolean(),
    operator = operator(SnapshotFields.OPERATOR),
    firstEventTime = present(SnapshotFields.FIRST_EVENT_TIME).asLong(),
    lastEventTime = present(SnapshotFields.LAST_EVENT_TIME).asLong(),
    state = decode(present(SnapshotFields.STATE), stateType),
)

/**
 * This events query record (decision D235 ②, `EventQueryGateways.kt`) of [type], each event's body decoded as the
 * class [eventType] gives its name, a `Map` when it gives none.
 */
@InternalWowApi
fun ObjectNode.toEventStream(type: AggregateType, eventType: (String) -> Class<*>?): EventStream =
    EventStream.of(
        ref = type.ref(text(EventStreamFields.TENANT_ID), text(EventStreamFields.AGGREGATE_ID)),
        version = present(EventStreamFields.VERSION).asInt(),
        commitTime = present(EventStreamFields.COMMIT_TIME).asLong(),
        commandId = text(EventStreamFields.COMMAND_ID),
        requestId = text(EventStreamFields.REQUEST_ID),
        commandType = text(EventStreamFields.COMMAND_TYPE),
        scope = scope(EventStreamFields.OWNER_ID, EventStreamFields.SPACE_ID),
        operator = operator(EventStreamFields.OPERATOR),
        events = present(EventStreamFields.EVENTS).elements().map { event ->
            val name = event.present(EventFields.NAME).asString()
            CommittedEvent.of(
                name = name,
                revision = event.present(EventFields.REVISION).asInt(),
                body = decode(event.present(EventFields.BODY), eventType(name) ?: Map::class.java),
            )
        },
    )

/** The elements of this array node. */
private fun JsonNode.elements(): List<JsonNode> = iterator().asSequence().toList()

private fun JsonNode.present(field: String): JsonNode = get(field)?.takeUnless { it.isNull }
    ?: throw IllegalStateException("A typed row needs the field $field: query it through dynamic().")

private fun JsonNode.text(field: String): String = present(field).asString()

private fun JsonNode.optionalText(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

private fun JsonNode.scope(owner: String, space: String): Scope = Scope(optionalText(owner), optionalText(space))

private fun JsonNode.tags(): Map<String, List<String>> {
    val tags = get(SnapshotFields.TAGS)?.takeUnless { it.isNull } ?: return emptyMap()
    return tags.properties().associate { (name, values) -> name to values.elements().map { it.asString() } }
}

private fun JsonNode.operator(field: String): Operator? {
    val operator = get(field)?.takeUnless { it.isNull } ?: return null
    return Operator.of(
        operator.text(OperatorFields.ID),
        operator.text(OperatorFields.TENANT_ID),
        operator.get(OperatorFields.CROSS_SCOPE)?.asBoolean() ?: false,
    )
}
