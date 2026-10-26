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

package me.ahoo.wow.elasticsearch.events

import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.spi.json.query.EventFields
import me.ahoo.wow.spi.json.query.EventStreamFields
import me.ahoo.wow.spi.state.StateEventRecord
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The stream document of the events index (decision D235): the record of the `events` model as it is
 * (`EventStreamRecords.stored`), each field under the model's name ([RECORD_FIELDS]), the operator `{id, tenantId,
 * crossScope}` or null (decision D163), each event `{name, revision, body}` as committed: its stored name and revision,
 * its body as Wow's JSON (decision D122), whose numbers Elasticsearch keeps exactly in `_source`; the tags after the
 * commit (decision D259) with [ElasticsearchEventIndex.TAGS_RECORDED] `true`. A document indexed before the index
 * learned the tags has neither, and its record's `tags` is null. The position, causation, headers, state and transport
 * of a commit are not part of it.
 */
internal object ElasticsearchEventDocuments {
    /** The fields of a stream record, in the record's order. */
    val RECORD_FIELDS: List<String> = listOf(
        EventStreamFields.AGGREGATE_ID,
        EventStreamFields.TENANT_ID,
        EventStreamFields.VERSION,
        EventStreamFields.COMMIT_TIME,
        EventStreamFields.COMMAND_ID,
        EventStreamFields.REQUEST_ID,
        EventStreamFields.COMMAND_TYPE,
        EventStreamFields.OWNER_ID,
        EventStreamFields.SPACE_ID,
        EventStreamFields.OPERATOR,
        EventStreamFields.TAGS,
        EventStreamFields.EVENTS,
    )

    private const val OPERATOR_ID = "id"
    private const val OPERATOR_TENANT_ID = "tenantId"
    private const val OPERATOR_CROSS_SCOPE = "crossScope"

    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    /** The document of [record]'s stream. */
    fun document(record: StateEventRecord): ObjectNode = nodes.objectNode().apply {
        put(EventStreamFields.AGGREGATE_ID, record.ref.id)
        put(EventStreamFields.TENANT_ID, record.ref.tenantId)
        put(EventStreamFields.VERSION, record.version)
        put(EventStreamFields.COMMIT_TIME, record.commitTime)
        put(EventStreamFields.COMMAND_ID, record.commandId)
        put(EventStreamFields.REQUEST_ID, record.requestId)
        put(EventStreamFields.COMMAND_TYPE, record.commandType)
        put(EventStreamFields.OWNER_ID, record.scope.ownerId)
        put(EventStreamFields.SPACE_ID, record.scope.spaceId)
        set(EventStreamFields.OPERATOR, operator(record.operator))
        val tags = putObject(EventStreamFields.TAGS)
        record.tags.forEach { (key, values) -> tags.putArray(key).also { array -> values.forEach(array::add) } }
        put(ElasticsearchEventIndex.TAGS_RECORDED, true)
        val events = putArray(EventStreamFields.EVENTS)
        record.events.forEach { event ->
            events.addObject()
                .put(EventFields.NAME, event.name)
                .put(EventFields.REVISION, event.revision)
                .set(EventFields.BODY, event.payload)
        }
    }

    /** The stream record [source] holds: each record field it holds, in the record's order. */
    fun record(source: ObjectNode): ObjectNode = nodes.objectNode().apply {
        RECORD_FIELDS.forEach { field ->
            val value = source.get(field)
            when {
                value != null -> set(field, value)
                // Indexed before the index learned the tags (decision D259): not recorded.
                field == EventStreamFields.TAGS -> putNull(field)
            }
        }
    }

    private fun operator(operator: Operator?) = if (operator == null) {
        nodes.nullNode()
    } else {
        nodes.objectNode()
            .put(OPERATOR_ID, operator.id)
            .put(OPERATOR_TENANT_ID, operator.tenantId)
            .put(OPERATOR_CROSS_SCOPE, operator.crossScope)
    }
}
