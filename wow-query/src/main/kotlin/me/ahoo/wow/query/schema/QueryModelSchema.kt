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

package me.ahoo.wow.query.schema

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryFieldSchemaMetadata
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryModelSchemaMetadata
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import tools.jackson.databind.JsonNode

private val QUERY_STORAGE_TYPE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")

data class QueryStorageType(val value: String) {
    init {
        require(QUERY_STORAGE_TYPE_PATTERN.matches(value))
    }
}

data class QueryFieldBinding(
    val physicalPath: String,
    val storageType: QueryStorageType?,
)

data class QueryModelSchema(
    val model: QueryModel,
    val capabilities: Set<QueryCapability>,
    val fields: Map<LogicalField, QueryFieldSchema>,
) {
    @get:JsonIgnore
    internal val maskedFields: Map<LogicalField, QueryFieldSchema> = fields.filterValues { it.maskRule != null }

    @get:JsonIgnore
    internal val hasMaskedFields: Boolean = maskedFields.isNotEmpty()

    private val dynamicFields = buildMap {
        fields.forEach { (field, fieldSchema) ->
            if (fieldSchema.dynamicChildren) {
                put(
                    field.value,
                    fieldSchema.copy(bindings = fieldSchema.bindings - QueryCapability.ELEMENT_SCOPE),
                )
            }
        }
    }

    @get:JsonIgnore
    internal val resolver = QuerySchemaResolver(this)

    fun resolve(field: LogicalField): QueryFieldSchema? {
        fields[field]?.let { return it }
        if (dynamicFields.isEmpty()) return null
        var separator = field.value.lastIndexOf('.')
        while (separator > 0) {
            val ancestorPath = field.value.substring(0, separator)
            val ancestor = dynamicFields[ancestorPath]
            if (ancestor != null) {
                val suffix = field.value.substring(separator + 1)
                return ancestor.copy(
                    bindings = ancestor.bindings.mapValues { (_, binding) ->
                        binding.copy(physicalPath = "${binding.physicalPath}.$suffix")
                    },
                    projectionPath = ancestor.projectionPath?.let { "$it.$suffix" },
                )
            }
            separator = ancestorPath.lastIndexOf('.')
        }
        return null
    }
}

data class QueryFieldSchema(
    val title: String?,
    val description: String?,
    val enumValues: List<JsonNode>?,
    val valueTypes: Set<QueryValueType>,
    val nullable: Boolean,
    val required: Boolean,
    val cardinality: QueryCardinality,
    val semanticType: QuerySemanticType?,
    val dynamicChildren: Boolean,
    val bindings: Map<QueryCapability, QueryFieldBinding>,
    val projectionPath: String? = bindings[QueryCapability.PRESENCE]?.physicalPath,
    @get:JsonIgnore val maskRule: MaskRule? = null,
)

data class LogicalQuerySchema(
    val fields: Map<LogicalField, LogicalQueryFieldSchema>,
)

data class LogicalQueryFieldSchema(
    val title: String?,
    val description: String?,
    val enumValues: List<JsonNode>?,
    val valueTypes: Set<QueryValueType>,
    val nullable: Boolean,
    val required: Boolean,
    val cardinality: QueryCardinality,
    val semanticType: QuerySemanticType?,
    val dynamicChildren: Boolean,
    @get:JsonIgnore val maskRule: MaskRule? = null,
)

fun QueryModelSchema.toMetadata(): QueryModelSchemaMetadata =
    QueryModelSchemaMetadata(
        model = model,
        capabilities = capabilities,
        fields = fields.entries.sortedBy { it.key.value }.map { (field, schema) ->
            QueryFieldSchemaMetadata(
                field = field,
                title = schema.title,
                description = schema.description,
                enumValues = schema.enumValues,
                valueTypes = schema.valueTypes,
                nullable = schema.nullable,
                required = schema.required,
                cardinality = schema.cardinality,
                semanticType = schema.semanticType,
                dynamicChildren = schema.dynamicChildren,
                capabilities = schema.bindings.keys,
                masked = schema.maskRule != null,
            )
        },
    )
