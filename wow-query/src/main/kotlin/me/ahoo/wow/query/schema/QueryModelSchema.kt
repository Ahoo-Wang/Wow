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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryFieldSchemaMetadata
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryModelSchemaMetadata
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.POJONode
import java.math.BigDecimal
import java.math.BigInteger

private val QUERY_STORAGE_TYPE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")

data class QueryStorageType(val value: String) {
    init {
        require(QUERY_STORAGE_TYPE_PATTERN.matches(value))
    }
}

enum class QueryRewriteMode {
    NONE,
    INFER,
    REQUIRED,
}

data class QueryFieldBinding(
    val resolvedField: QueryField,
    val physicalField: QueryField,
    val storageType: QueryStorageType?,
)

data class QueryModelSchema(
    val model: QueryModel,
    val capabilities: Set<QueryCapability>,
    val fields: Map<QueryField, QueryFieldSchema>,
) {
    @get:JsonIgnore
    internal val maskedFields: Map<QueryField, QueryFieldSchema> = fields.filterValues(QueryFieldSchema::masked)

    @get:JsonIgnore
    internal val hasMaskedFields: Boolean = maskedFields.isNotEmpty()

    val rewriteMode: QueryRewriteMode = when {
        fields.values.any { it.rewriteMode != QueryRewriteMode.NONE } -> QueryRewriteMode.INFER
        else -> QueryRewriteMode.NONE
    }

    private val dynamicFields = fields.filterValues(QueryFieldSchema::dynamicChildren)

    @get:JsonIgnore
    internal val resolver = QuerySchemaResolver(this)

    fun supports(capability: QueryCapability): Boolean = capability in capabilities

    fun field(field: QueryField): QueryFieldSchema? {
        fields[field]?.let { return it }
        if (dynamicFields.isEmpty()) return null
        var separator = field.path.lastIndexOf('.')
        while (separator > 0) {
            val ancestorField = QueryField(field.path.substring(0, separator))
            val ancestor = dynamicFields[ancestorField]
            if (ancestor != null) {
                return ancestor.resolveDynamic(ancestorField, checkNotNull(field.relativeTo(ancestorField)))
            }
            separator = ancestorField.path.lastIndexOf('.')
        }
        return null
    }

    internal fun matchesValueTypes(field: QueryField, values: Iterable<JsonNode>): Boolean =
        fields[field]?.matchesValueTypes(values) ?: true

    fun toMetadata(): QueryModelSchemaMetadata = QueryModelSchemaMetadata(
        model = model,
        capabilities = capabilities,
        fields = fields.entries.sortedBy { it.key.path }.map { (field, schema) ->
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
                capabilities = schema.capabilities,
                masked = schema.masked,
            )
        },
    )
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
    val projectionField: QueryField? = bindings[QueryCapability.PRESENCE]?.resolvedField,
    val rewriteMode: QueryRewriteMode,
    @get:JsonIgnore internal val maskRule: MaskRule? = null,
) {
    val capabilities: Set<QueryCapability>
        get() = bindings.keys

    val masked: Boolean
        get() = maskRule != null

    fun binding(capability: QueryCapability): QueryFieldBinding? = bindings[capability]

    internal fun resolveDynamic(source: QueryField, relative: QueryField): QueryFieldSchema {
        val resolvedSource = source.append(relative)
        val resolvedBindings = bindings
            .filterKeys { it != QueryCapability.ELEMENT_SCOPE }
            .mapValues { (_, binding) ->
                binding.copy(
                    resolvedField = binding.resolvedField.append(relative),
                    physicalField = binding.physicalField.append(relative),
                )
            }
        val rewrites = resolvedBindings.values.map { it.resolvedField != resolvedSource }.distinct()
        val resolvedRewriteMode = when {
            semanticType is Temporal || QueryCapability.ELEMENT_SCOPE in resolvedBindings -> QueryRewriteMode.INFER
            resolvedBindings.isEmpty() || rewrites == listOf(false) -> QueryRewriteMode.NONE
            rewrites == listOf(true) -> QueryRewriteMode.REQUIRED
            else -> QueryRewriteMode.INFER
        }
        return QueryFieldSchema(
            title = title,
            description = description,
            enumValues = enumValues,
            valueTypes = valueTypes,
            nullable = nullable,
            required = required,
            cardinality = cardinality,
            semanticType = semanticType,
            dynamicChildren = dynamicChildren,
            bindings = resolvedBindings,
            projectionField = projectionField?.append(relative),
            rewriteMode = resolvedRewriteMode,
            maskRule = maskRule,
        )
    }

    internal fun matchesValueTypes(values: Iterable<JsonNode>): Boolean =
        valueTypes.isEmpty() ||
            valueTypes.any { it !in BUILT_IN_QUERY_VALUE_TYPES } ||
            values.all { value -> value.isNull || valueTypes.any(value::matches) }
}

data class LogicalQuerySchema(
    val fields: Map<QueryField, LogicalQueryFieldSchema>,
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

private val BUILT_IN_QUERY_VALUE_TYPES = setOf(
    QueryValueType.STRING,
    QueryValueType.INTEGER,
    QueryValueType.DECIMAL,
    QueryValueType.BOOLEAN,
    QueryValueType.OBJECT,
)

private fun JsonNode.matches(type: QueryValueType): Boolean {
    if (isPojo) return pojoValue.matches(type)
    return when (type) {
        QueryValueType.STRING -> isString
        QueryValueType.INTEGER -> isNumber && canConvertToExactIntegral()
        QueryValueType.DECIMAL -> isNumber
        QueryValueType.BOOLEAN -> isBoolean
        QueryValueType.OBJECT -> isObject
        else -> true
    }
}

private val JsonNode.pojoValue: Any?
    get() = (this as? POJONode)?.pojo

private fun Any?.matches(type: QueryValueType): Boolean = when (this) {
    is CharSequence,
    is Char,
    is Enum<*>,
    -> type == QueryValueType.STRING
    is Boolean -> type == QueryValueType.BOOLEAN
    is Byte,
    is Short,
    is Int,
    is Long,
    is BigInteger,
    -> type == QueryValueType.INTEGER || type == QueryValueType.DECIMAL
    is Float,
    is Double,
    is BigDecimal,
    -> type.matchesNumber(this as Number)
    else -> true
}

private fun QueryValueType.matchesNumber(value: Number): Boolean {
    if (this == QueryValueType.DECIMAL) return true
    if (this != QueryValueType.INTEGER) return false
    val node = when (value) {
        is Float -> JsonNodeFactory.instance.numberNode(value)
        is Double -> JsonNodeFactory.instance.numberNode(value)
        is BigDecimal -> JsonNodeFactory.instance.numberNode(value)
        else -> return true
    }
    return node.canConvertToExactIntegral()
}
