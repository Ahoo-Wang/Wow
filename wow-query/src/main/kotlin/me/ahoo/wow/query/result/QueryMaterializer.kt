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

package me.ahoo.wow.query.result

import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

internal class QueryMaterializer(private val objectMapper: ObjectMapper) {
    fun <S : Any> snapshot(record: ObjectNode, metadata: AggregateMetadata<*, S>): MaterializedSnapshot<S> =
        try {
            MaterializedSnapshot(
                contextName = text(record, "contextName"),
                aggregateName = text(record, "aggregateName"),
                tenantId = text(record, "tenantId"),
                ownerId = text(record, "ownerId"),
                spaceId = text(record, "spaceId"),
                aggregateId = text(record, "aggregateId"),
                version = record["version"].intValue(),
                eventId = text(record, "eventId"),
                firstOperator = text(record, "firstOperator"),
                operator = text(record, "operator"),
                firstEventTime = time(record, "firstEventTime"),
                eventTime = time(record, "eventTime"),
                state = objectMapper.treeToValue(record["state"], metadata.state.aggregateType),
                snapshotTime = time(record, "snapshotTime"),
                tags = objectMapper.convertValue(record["tags"], object : TypeReference<AbacTags>() {}),
                deleted = record["deleted"].booleanValue()
            )
        } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
            throw QueryException(QueryErrorCode.MATERIALIZATION_FAILED, QueryStage.MATERIALIZATION)
        }

    fun record(record: ObjectNode, projection: QueryProjection): ObjectNode = when (projection) {
        QueryProjection.All -> record
        is QueryProjection.Include -> include(record, projection.fields)
        is QueryProjection.Exclude -> exclude(record, projection.fields)
    }

    private fun text(record: ObjectNode, field: String): String = record[field].stringValue()

    private fun time(record: ObjectNode, field: String): Long = Instant.parse(text(record, field)).toEpochMilli()

    private fun include(source: ObjectNode, fields: Set<LogicalField>): ObjectNode {
        val selection = selection(fields)
        return project(source, selection) as? ObjectNode ?: JsonNodeFactory.instance.objectNode()
    }

    private fun exclude(source: ObjectNode, fields: Set<LogicalField>): ObjectNode {
        val result = source.deepCopy()
        val selection = selection(fields)
        remove(result, selection)
        return result
    }

    private fun selection(fields: Set<LogicalField>): Selection {
        val root = Selection()
        fields.forEach { field ->
            var current = root
            field.value.split('.').forEach { segment -> current = current.children.getOrPut(segment, ::Selection) }
            current.selected = true
        }
        return root
    }

    private fun project(source: JsonNode, selection: Selection): JsonNode? = when (source) {
        is ObjectNode -> JsonNodeFactory.instance.objectNode().also { target ->
            selection.children.forEach { (name, childSelection) ->
                val child = source[name] ?: return@forEach
                val projected = if (childSelection.selected) child.deepCopy() else project(child, childSelection)
                if (projected != null) target.set(name, projected)
            }
        }

        is ArrayNode -> JsonNodeFactory.instance.arrayNode().also { target ->
            source.forEach { child -> project(child, selection)?.let(target::add) }
        }

        else -> if (selection.selected) source.deepCopy() else null
    }

    private fun remove(target: JsonNode, selection: Selection) {
        when (target) {
            is ObjectNode -> selection.children.forEach { (name, childSelection) ->
                if (childSelection.selected) {
                    target.remove(name)
                } else {
                    target[name]?.let { remove(it, childSelection) }
                }
            }

            is ArrayNode -> target.forEach { child -> remove(child, selection) }
        }
    }

    private class Selection(
        var selected: Boolean = false,
        val children: MutableMap<String, Selection> = linkedMapOf()
    )
}
