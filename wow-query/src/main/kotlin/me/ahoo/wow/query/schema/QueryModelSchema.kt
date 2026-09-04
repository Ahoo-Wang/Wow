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
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryFieldSchemaMetadata
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryModelSchemaMetadata
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.withUniqueSort
import me.ahoo.wow.serialization.MessageRecords
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.POJONode
import java.math.BigDecimal
import java.math.BigInteger

private val QUERY_STORAGE_TYPE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")
private val SNAPSHOT_CURSOR_UNIQUE_FIELD = QueryField(MessageRecords.AGGREGATE_ID)
private val EVENT_STREAM_CURSOR_UNIQUE_FIELD = QueryField(MessageRecords.ID)

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

    @get:JsonIgnore
    internal val elementScopePaths: Set<String> = buildSet {
        fields.forEach { (field, fieldSchema) ->
            if (QueryCapability.ELEMENT_SCOPE in fieldSchema.bindings) {
                add(field.path)
            }
        }
    }

    val rewriteMode: QueryRewriteMode = when {
        capabilities.any {
            it == QueryCapability.FULL_TEXT_TERMS || it == QueryCapability.FULL_TEXT_PHRASE
        } -> QueryRewriteMode.INFER
        fields.values.any { it.rewriteMode != QueryRewriteMode.NONE } -> QueryRewriteMode.INFER
        else -> QueryRewriteMode.NONE
    }

    private val dynamicFields = fields.filterValues(QueryFieldSchema::dynamicChildren)

    @get:JsonIgnore
    internal val elementDescendantDynamicFields: Set<QueryField> = buildSet {
        dynamicFields.keys.forEach { dynamicField ->
            if (
                elementScopePaths.any { elementPath ->
                    dynamicField.path.length > elementPath.length &&
                        dynamicField.path.startsWith(elementPath) &&
                        dynamicField.path[elementPath.length] == '.'
                }
            ) {
                add(dynamicField)
            }
        }
    }

    @get:JsonIgnore
    internal val fieldResolver = QueryFieldSchemaResolver(this)
    private val resolver = QuerySchemaResolver(this)

    fun supports(capability: QueryCapability): Boolean = capability in capabilities

    fun field(field: QueryField): QueryFieldSchema? {
        fields[field]?.let { return it }
        if (dynamicFields.isEmpty()) return null
        var separator = field.path.lastIndexOf('.')
        while (separator > 0) {
            val ancestorField = QueryField(field.path.substring(0, separator))
            val ancestor = dynamicFields[ancestorField]
            if (ancestor != null) {
                return ancestor.resolveDynamic(
                    source = ancestorField,
                    relative = checkNotNull(field.relativeTo(ancestorField)),
                    elementAncestor = ancestorField in elementDescendantDynamicFields,
                )
            }
            separator = ancestorField.path.lastIndexOf('.')
        }
        return null
    }

    fun resolvePhysicalField(
        field: QueryField,
        capability: QueryCapability,
        logicalParent: QueryField? = null,
        resolvedParent: QueryField? = null,
        physicalParent: QueryField? = null,
    ): QueryField = fieldResolver.resolve(
        field,
        capability,
        logicalParent,
        resolvedParent,
        physicalParent,
        enforceElementScope = false,
    ).let { resolved ->
        resolved.physicalField?.let { physicalField ->
            if (physicalParent == null) {
                physicalField
            } else {
                physicalField.relativeTo(physicalParent)
                    ?: throw QuerySchemaValidationException(
                        "Physical field [$physicalField] is not relative to parent [$physicalParent].",
                    )
            }
        } ?: if (physicalParent == null) {
            resolved.logical
        } else {
            logicalParent?.let(field::relativeTo) ?: resolvedParent?.let(field::relativeTo) ?: field
        }
    }

    internal fun matchesValueTypes(field: QueryField, values: Iterable<JsonNode>): Boolean =
        fields[field]?.matchesValueTypes(values) ?: true

    fun resolve(query: ISingleQuery): QuerySchemaResolution<ISingleQuery> = resolver.resolve(query)

    fun resolve(query: IListQuery): QuerySchemaResolution<IListQuery> = resolver.resolve(query)

    fun resolve(query: IPagedQuery): QuerySchemaResolution<IPagedQuery> = resolver.resolve(query)

    fun resolve(query: ICursorQuery): QuerySchemaResolution<ICursorQuery> = resolver.resolve(
        query.withUniqueSort(
            when (model) {
                QueryModel.SNAPSHOT -> SNAPSHOT_CURSOR_UNIQUE_FIELD
                QueryModel.EVENT_STREAM -> EVENT_STREAM_CURSOR_UNIQUE_FIELD
                else -> throw QuerySchemaValidationException("Cursor query model [$model] is unsupported.")
            },
        ),
    )

    fun resolve(filter: FilterExpression): QuerySchemaResolution<FilterExpression> = resolver.resolve(filter)

    fun resolve(query: AggregationQuery): QuerySchemaResolution<AggregationQuery> = resolver.resolve(query)

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
    val projectionField: QueryField? = bindings[QueryCapability.PRESENCE]?.physicalField,
    val rewriteMode: QueryRewriteMode,
    @get:JsonIgnore internal val maskRule: MaskRule? = null,
) {
    val capabilities: Set<QueryCapability>
        get() = bindings.keys

    val masked: Boolean
        get() = maskRule != null

    fun binding(capability: QueryCapability): QueryFieldBinding? = bindings[capability]

    internal fun resolveDynamic(
        source: QueryField,
        relative: QueryField,
        elementAncestor: Boolean,
    ): QueryFieldSchema {
        val resolvedSource = source.append(relative)
        val resolvedBindings = LinkedHashMap<QueryCapability, QueryFieldBinding>(bindings.size)
        var hasIdentity = false
        var hasRewrite = false
        bindings.forEach { (capability, binding) ->
            if (capability == QueryCapability.ELEMENT_SCOPE) {
                return@forEach
            }
            val resolvedBinding = binding.copy(
                resolvedField = binding.resolvedField.append(relative),
                physicalField = binding.physicalField.append(relative),
            )
            resolvedBindings[capability] = resolvedBinding
            if (resolvedBinding.resolvedField == resolvedSource) {
                hasIdentity = true
            } else {
                hasRewrite = true
            }
        }
        val resolvedRewriteMode = when {
            (elementAncestor && resolvedBindings.isNotEmpty()) ||
                semanticType is Temporal || hasIdentity && hasRewrite -> QueryRewriteMode.INFER
            hasRewrite -> QueryRewriteMode.REQUIRED
            else -> QueryRewriteMode.NONE
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
