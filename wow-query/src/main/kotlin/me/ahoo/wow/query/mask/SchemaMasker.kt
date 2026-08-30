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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

internal class SchemaMasker private constructor(
    private val paths: List<MaskedPath>,
    private val eventBodyTypes: Set<String>?,
) {
    fun mask(node: ObjectNode): ObjectNode {
        eventBodyTypes?.let { validateEventBodyTypes(node, it) }
        paths.forEach { path -> maskAt(node, path.segments, 0, path.mask) }
        return node
    }

    private fun validateEventBodyTypes(node: ObjectNode, knownBodyTypes: Set<String>) {
        val events = node.get("body")?.takeUnless(JsonNode::isNull) ?: return
        if (!events.isArray) {
            throw QuerySchemaValidationException("Event stream [body] must be a JSON array.")
        }
        events.forEach { event ->
            val bodyType = event.takeIf(JsonNode::isObject)?.get("bodyType")
                ?.takeIf(JsonNode::isString)?.stringValue()
            if (bodyType == null || bodyType !in knownBodyTypes) {
                throw QuerySchemaValidationException("Unknown event bodyType: [$bodyType].")
            }
        }
    }

    private fun maskAt(node: JsonNode, segments: List<String>, index: Int, mask: CompiledMask) {
        if (node.isArray) {
            node.forEach { child -> maskAt(child, segments, index, mask) }
            return
        }
        if (!node.isObject) {
            if (node.isNull) return
            throw QuerySchemaValidationException("Masked query field has an invalid JSON wire shape.")
        }
        val child = node.get(segments[index]) ?: return
        if (index < segments.lastIndex) {
            maskAt(child, segments, index + 1, mask)
            return
        }
        maskValue(node as ObjectNode, segments[index], child, mask)
    }

    private fun maskValue(parent: ObjectNode, field: String, value: JsonNode, mask: CompiledMask) {
        when {
            value.isNull -> Unit
            value.isString -> parent.put(field, mask.requireMasked(value.stringValue()))
            value.isArray -> value.forEachIndexed { index, child -> maskArrayValue(value, index, child, mask) }
            else -> throw QuerySchemaValidationException("Masked query field [$field] must be a string JSON value.")
        }
    }

    private fun maskArrayValue(parent: JsonNode, index: Int, value: JsonNode, mask: CompiledMask) {
        when {
            value.isNull -> Unit
            value.isString -> (parent as ArrayNode)
                .set(
                    index,
                    JsonNodeFactory.instance.stringNode(
                        mask.requireMasked(value.stringValue()),
                    ),
                )
            value.isArray -> value.forEachIndexed { nestedIndex, child ->
                maskArrayValue(
                    value,
                    nestedIndex,
                    child,
                    mask,
                )
            }

            else -> throw QuerySchemaValidationException("Masked query array value must contain strings.")
        }
    }

    private fun CompiledMask.requireMasked(value: String): String {
        val masked: String? = mask(value)
        return masked ?: throw QuerySchemaValidationException("Mask strategy returned null.")
    }

    companion object {
        fun create(schema: QueryModelSchema): SchemaMasker? {
            if (!schema.hasMaskedFields) return null
            val prefix = when (schema.model) {
                QueryModel.SNAPSHOT -> "state."
                QueryModel.EVENT_STREAM -> "body.body."
                else -> throw QuerySchemaConflictException("Unsupported masked query model: [${schema.model}].")
            }
            val paths = schema.maskedFields.map { (field, fieldSchema) ->
                val responsePath = fieldSchema.projectionPath ?: field.value
                val invalidPath = when {
                    !field.value.startsWith(prefix) -> "field" to field.value
                    !responsePath.startsWith(prefix) -> "projection path" to responsePath
                    else -> null
                }
                if (invalidPath != null) {
                    throw QuerySchemaConflictException(
                        "${schema.model} masked ${invalidPath.first} must be under " +
                            "[${prefix.removeSuffix(".")}]: [${invalidPath.second}].",
                    )
                }
                MaskedPath(responsePath.split('.'), checkNotNull(fieldSchema.maskRule).compiled)
            }
            val eventBodyTypes = if (schema.model == QueryModel.EVENT_STREAM) {
                schema.requiredEventBodyTypes()
            } else {
                null
            }
            return SchemaMasker(paths, eventBodyTypes)
        }

        private fun QueryModelSchema.requiredEventBodyTypes(): Set<String> {
            val values = fields[LogicalField("body.bodyType")]?.enumValues
                ?: throw QuerySchemaConflictException("Masked event schema requires body.bodyType enum values.")
            val bodyTypes = values.mapNotNull { it.takeIf(JsonNode::isString)?.stringValue() }.toSet()
            if (bodyTypes.size != values.size || bodyTypes.isEmpty()) {
                throw QuerySchemaConflictException("Masked event schema requires string body.bodyType enum values.")
            }
            return bodyTypes
        }
    }

    private data class MaskedPath(val segments: List<String>, val mask: CompiledMask)
}
