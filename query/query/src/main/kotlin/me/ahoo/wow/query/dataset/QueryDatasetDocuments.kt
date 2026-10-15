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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.dataset.QueryDatasetProperties.DESCRIPTION
import me.ahoo.wow.query.dataset.QueryDatasetProperties.ENUM
import me.ahoo.wow.query.dataset.QueryDatasetProperties.ENUM_DESCRIPTION
import me.ahoo.wow.query.dataset.QueryDatasetProperties.ENUM_VALUE
import me.ahoo.wow.query.dataset.QueryDatasetProperties.FIELDS
import me.ahoo.wow.query.dataset.QueryDatasetProperties.ITEMS
import me.ahoo.wow.query.dataset.QueryDatasetProperties.KIND
import me.ahoo.wow.query.dataset.QueryDatasetProperties.NULLABLE
import me.ahoo.wow.query.dataset.QueryDatasetProperties.PROPERTIES
import me.ahoo.wow.query.dataset.QueryDatasetProperties.SEMANTIC
import me.ahoo.wow.query.dataset.QueryDatasetProperties.TYPES
import me.ahoo.wow.query.dataset.QueryDatasetProperties.VALUES
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySemanticType
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.requireQueryPathSegment
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The properties of a dataset file, the 9.x format:
 *
 * ```json
 * {
 *   "title": "Orders",
 *   "identity": "id",
 *   "eventTime": "commit_time",
 *   "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id", "tags": { "region": "region" } },
 *   "storage": { "table": "db.shop_order_state_flat" },
 *   "fields": {
 *     "id": { "types": ["STRING"], "nullable": false },
 *     "phone": { "types": ["STRING"], "sensitive": { "level": "CONFIDENTIAL", "keepSuffix": 4 } }
 *   }
 * }
 * ```
 *
 * `scope` names either the `tenant` column or `"platform": true`. A field is a query schema declaration (`kind`,
 * `types`, `nullable`, `enum`, `semantic`, `description`, `properties`, `items`, `values`) and may also declare its
 * sensitivity (`sensitive`: `level`, `keepPrefix`, `keepSuffix`). `storage` is the backend's opaque binding.
 */
@ExperimentalWowApi
internal object QueryDatasetProperties {
    const val TITLE = "title"
    const val IDENTITY = "identity"
    const val EVENT_TIME = "eventTime"
    const val SCOPE = "scope"
    const val TENANT = "tenant"
    const val PLATFORM = "platform"
    const val OWNER = "owner"
    const val SPACE = "space"
    const val TAGS = "tags"
    const val STORAGE = "storage"
    const val FIELDS = "fields"
    const val SENSITIVE = "sensitive"
    const val LEVEL = "level"
    const val KEEP_PREFIX = "keepPrefix"
    const val KEEP_SUFFIX = "keepSuffix"
    const val KIND = "kind"
    const val TYPES = "types"
    const val NULLABLE = "nullable"
    const val ENUM = "enum"
    const val ENUM_VALUE = "value"
    const val ENUM_DESCRIPTION = "description"
    const val SEMANTIC = "semantic"
    const val DESCRIPTION = "description"
    const val PROPERTIES = "properties"
    const val ITEMS = "items"
    const val VALUES = "values"

    internal val ROOT = setOf(TITLE, IDENTITY, EVENT_TIME, SCOPE, STORAGE, FIELDS)
    internal val SCOPE_PROPERTIES = setOf(TENANT, OWNER, SPACE, TAGS, PLATFORM)
    internal val SENSITIVE_PROPERTIES = setOf(LEVEL, KEEP_PREFIX, KEEP_SUFFIX)
    internal val FIELD_PROPERTIES =
        setOf(KIND, TYPES, NULLABLE, ENUM, SEMANTIC, DESCRIPTION, PROPERTIES, ITEMS, VALUES)
    internal val ENUM_PROPERTIES = setOf(ENUM_VALUE, ENUM_DESCRIPTION)
}

/** Unions, `null` and unknown values are inferred, never declared. */
private val DECLARABLE_KINDS = listOf(QueryValueKind.SCALAR, QueryValueKind.OBJECT, QueryValueKind.ARRAY)

private val DECLARABLE_TYPES =
    setOf(QueryValueType.STRING, QueryValueType.INTEGER, QueryValueType.DECIMAL, QueryValueType.BOOLEAN)

/** Parses a dataset file ([QueryDatasetProperties]) of [name]; an [IllegalArgumentException] says what is wrong. */
internal fun parseDatasetDocument(name: AggregateType, json: String): QueryDataset {
    val root = try {
        QueryJson.mapper.readTree(json)
    } catch (error: JacksonException) {
        throw IllegalArgumentException("Dataset document is not JSON: ${error.originalMessage}", error)
    }
    require(root is ObjectNode) { "Dataset root must be an object." }
    root.requireOnly(QueryDatasetProperties.ROOT, "dataset")
    val fields = root.get(FIELDS)
    require(fields is ObjectNode) { "Dataset [$FIELDS] must be an object." }
    val scope = root.get(QueryDatasetProperties.SCOPE)
    require(scope is ObjectNode) { "Dataset [${QueryDatasetProperties.SCOPE}] must be an object." }
    scope.requireOnly(QueryDatasetProperties.SCOPE_PROPERTIES, "dataset scope")
    val storage = root.get(QueryDatasetProperties.STORAGE) ?: QueryJson.mapper.createObjectNode()
    require(storage is ObjectNode) { "Dataset [${QueryDatasetProperties.STORAGE}] must be an object." }
    val platform = scope.get(QueryDatasetProperties.PLATFORM)?.let {
        require(it.isBoolean && it.booleanValue()) {
            "Dataset [${QueryDatasetProperties.SCOPE}.${QueryDatasetProperties.PLATFORM}] must be true when present."
        }
        true
    } ?: false
    val tenant = scope.fieldOf(QueryDatasetProperties.TENANT)
    require((tenant != null) != platform) {
        "Dataset [${QueryDatasetProperties.SCOPE}] declares exactly one of " +
            "[${QueryDatasetProperties.TENANT}] (its tenant column) and [${QueryDatasetProperties.PLATFORM}]."
    }
    return QueryDataset(
        name = name,
        scope = QueryDatasetScope(
            tenant = tenant,
            platform = platform,
            owner = scope.fieldOf(QueryDatasetProperties.OWNER),
            space = scope.fieldOf(QueryDatasetProperties.SPACE),
            tags = scope.tagsOf(),
        ),
        declaration = QuerySchemaDeclaration(
            fields.properties().associate { (field, declaration) ->
                require(declaration is ObjectNode) { "Dataset field [$field] must be an object." }
                QueryField(field) to declaration.toDatasetFieldDeclaration(field)
            },
        ),
        identity = root.fieldOf(QueryDatasetProperties.IDENTITY),
        eventTime = root.fieldOf(QueryDatasetProperties.EVENT_TIME),
        title = root.get(QueryDatasetProperties.TITLE)?.let {
            require(it.isString) { "Dataset [${QueryDatasetProperties.TITLE}] must be a string." }
            it.stringValue()
        },
        storage = storage,
    )
}

private fun ObjectNode.tagsOf(): Map<String, QueryField> {
    val tags = get(QueryDatasetProperties.TAGS) ?: return emptyMap()
    val path = "${QueryDatasetProperties.SCOPE}.${QueryDatasetProperties.TAGS}"
    require(tags is ObjectNode) { "Dataset [$path] must be an object." }
    return tags.properties().associate { (key, field) ->
        require(field.isString) { "Dataset [$path.$key] must name a field." }
        key to QueryField(field.stringValue())
    }
}

private fun ObjectNode.fieldOf(name: String): QueryField? = get(name)?.let {
    require(it.isString) { "Dataset [$name] must name a field." }
    QueryField(it.stringValue())
}

/** A query schema field declaration plus [QueryDatasetProperties.SENSITIVE], which only a dataset may declare. */
private fun ObjectNode.toDatasetFieldDeclaration(field: String): QueryFieldDeclaration {
    val sensitive = get(QueryDatasetProperties.SENSITIVE) ?: return toDeclaration(field)
    val path = "$field.${QueryDatasetProperties.SENSITIVE}"
    require(sensitive is ObjectNode) { "Dataset field [$path] must be an object." }
    sensitive.requireOnly(QueryDatasetProperties.SENSITIVE_PROPERTIES, "dataset sensitivity")
    val level = sensitive.get(QueryDatasetProperties.LEVEL)?.takeIf(JsonNode::isString)?.stringValue()
        ?.let { name -> SensitivityLevel.entries.firstOrNull { it.name == name } }
    require(level != null) {
        "Dataset field [$path.${QueryDatasetProperties.LEVEL}] must be one of ${SensitivityLevel.entries}."
    }
    fun keep(name: String): Int = sensitive.get(name)?.let {
        require(it.isInt && it.intValue() >= 0) { "Dataset field [$path.$name] must be a non-negative integer." }
        it.intValue()
    } ?: 0
    val rest = deepCopy().apply { remove(QueryDatasetProperties.SENSITIVE) }
    return rest.toDeclaration(field).copy(
        maskRule = DeclarationValue.Set(
            MaskRule(
                level,
                QueryMask.Keep(
                    prefix = keep(QueryDatasetProperties.KEEP_PREFIX),
                    suffix = keep(QueryDatasetProperties.KEEP_SUFFIX),
                ),
            ),
        ),
    )
}

private fun ObjectNode.toDeclaration(field: String): QueryFieldDeclaration {
    requireOnly(QueryDatasetProperties.FIELD_PROPERTIES, "query schema")
    val enum = enum(field)
    return QueryFieldDeclaration(
        kind = kind(field),
        valueTypes = types(field),
        nullable = boolean(NULLABLE, field),
        enumValues = enum?.let { values -> DeclarationValue.Set(values.map { it.first }) } ?: DeclarationValue.Unset,
        enumDescriptions = enum?.let { values ->
            DeclarationValue.Set(values.mapNotNull { (value, description) -> description?.let { value to it } }.toMap())
        } ?: DeclarationValue.Unset,
        semanticType = semantic(field),
        description = text(DESCRIPTION, field),
        properties = properties(field),
        items = child(ITEMS, field),
        additionalProperties = child(VALUES, field),
    )
}

private fun ObjectNode.kind(field: String): DeclarationValue<QueryValueKind> {
    val value = get(KIND) ?: return DeclarationValue.Unset
    val kind = value.takeIf(JsonNode::isString)?.stringValue()
        ?.let { name -> DECLARABLE_KINDS.firstOrNull { it.name == name } }
    require(kind != null) { "Query schema [$field.$KIND] must be one of $DECLARABLE_KINDS." }
    return DeclarationValue.Set(kind)
}

private fun ObjectNode.types(field: String): DeclarationValue<Set<QueryValueType>> {
    val value = get(TYPES) ?: return DeclarationValue.Unset
    require(value.isArray && !value.isEmpty) { "Query schema [$field.$TYPES] must be a non-empty array." }
    return DeclarationValue.Set(
        value.asSequence().map { item ->
            val type = item.takeIf(JsonNode::isString)?.stringValue()?.let { name ->
                DECLARABLE_TYPES.firstOrNull { it.value == name }
            }
            require(type != null) { "Query schema [$field.$TYPES] values must be one of $DECLARABLE_TYPES." }
            type
        }.toSet(),
    )
}

private fun ObjectNode.boolean(name: String, field: String): DeclarationValue<Boolean> {
    val value = get(name) ?: return DeclarationValue.Unset
    require(value.isBoolean) { "Query schema [$field.$name] must be a boolean." }
    return DeclarationValue.Set(value.booleanValue())
}

private fun ObjectNode.text(name: String, field: String): DeclarationValue<String?> {
    val value = get(name) ?: return DeclarationValue.Unset
    require(value.isString) { "Query schema [$field.$name] must be a string." }
    return DeclarationValue.Set(value.stringValue())
}

/** The declared values, each with its description; `null` when the field declares no enum. */
private fun ObjectNode.enum(field: String): List<Pair<JsonNode, String?>>? {
    val value = get(ENUM) ?: return null
    require(value.isArray && !value.isEmpty) { "Query schema [$field.$ENUM] must be a non-empty array." }
    val values = value.toList().map { entry ->
        require(entry is ObjectNode && entry.has(ENUM_VALUE)) {
            "Query schema [$field.$ENUM] entries must be objects with a [$ENUM_VALUE]."
        }
        entry.requireOnly(QueryDatasetProperties.ENUM_PROPERTIES, "query schema")
        val description = entry.get(ENUM_DESCRIPTION)
        require(description == null || description.isString) {
            "Query schema [$field.$ENUM.$ENUM_DESCRIPTION] must be a string."
        }
        entry.get(ENUM_VALUE) to description?.stringValue()
    }
    require(values.map { it.first }.distinct().size == values.size) { "Query schema [$field.$ENUM] repeats a value." }
    return values
}

private fun ObjectNode.semantic(field: String): DeclarationValue<QuerySemanticType?> {
    val value = get(SEMANTIC) ?: return DeclarationValue.Unset
    require(value is ObjectNode) { "Query schema [$field.$SEMANTIC] must be an object." }
    val semantic = QueryJson.mapper.treeToValue(value, QuerySemanticType::class.java)
    require(semantic != QuerySemanticType.Unknown) { "Query schema [$field.$SEMANTIC] has an unknown type." }
    return DeclarationValue.Set(semantic)
}

private fun ObjectNode.properties(field: String): DeclarationValue<Map<String, QueryFieldDeclaration>> {
    val value = get(PROPERTIES) ?: return DeclarationValue.Unset
    require(value is ObjectNode) { "Query schema [$field.$PROPERTIES] must be an object." }
    return DeclarationValue.Set(
        value.properties().associate { (name, node) ->
            requireQueryPathSegment(name)
            require(node is ObjectNode) { "Query schema [$field.$PROPERTIES.$name] must be an object." }
            name to node.toDeclaration("$field.$name")
        },
    )
}

private fun ObjectNode.child(name: String, field: String): DeclarationValue<QueryFieldDeclaration?> {
    val value = get(name) ?: return DeclarationValue.Unset
    require(value is ObjectNode) { "Query schema [$field.$name] must be an object." }
    return DeclarationValue.Set(value.toDeclaration("$field.$name"))
}

private fun ObjectNode.requireOnly(allowed: Set<String>, subject: String) {
    val unknown = properties().map { it.key }.filterNot(allowed::contains)
    require(unknown.isEmpty()) { "Unknown $subject properties: $unknown." }
}
