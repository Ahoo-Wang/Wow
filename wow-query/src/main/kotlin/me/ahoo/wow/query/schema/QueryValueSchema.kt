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
import me.ahoo.wow.api.query.schema.QueryDeprecation
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import tools.jackson.databind.JsonNode

/**
 * One immutable logical value shape, shared by backend schema snapshots.
 * Collection inputs are snapshotted; mutable enum JSON is detached on read.
 */
class QueryValueSchema(
    val kind: QueryValueKind,
    val title: String? = null,
    val description: String? = null,
    enumValues: List<JsonNode>? = null,
    enumDescriptions: Map<JsonNode, String> = emptyMap(),
    valueTypes: Set<QueryValueType> = if (kind == QueryValueKind.OBJECT) setOf(QueryValueType.OBJECT) else emptySet(),
    properties: Map<String, QueryValueSchema> = emptyMap(),
    val items: QueryValueSchema? = null,
    val additionalProperties: QueryValueSchema? = null,
    alternatives: List<QueryValueSchema> = emptyList(),
    val nullable: Boolean = kind != QueryValueKind.UNION || alternatives.any { it.nullable },
    val required: Boolean = false,
    val semanticType: QuerySemanticType? = null,
    @get:JsonIgnore val maskRule: MaskRule? = null,
    /** The discriminator value when this value is one variant of a variant payload, such as an event's `bodyType`. */
    val variant: String? = null,
    aliases: Set<QueryField> = emptySet(),
    /** Set when the field is kept only for existing callers. */
    val deprecated: QueryDeprecation? = null,
) {
    /** Other logical paths that name this value. */
    val aliases: Set<QueryField> = java.util.Collections.unmodifiableSet(LinkedHashSet(aliases))

    private val enumSnapshot: List<JsonNode>? = enumValues?.map { it.deepCopy() }
    val enumValues: List<JsonNode>?
        get() = enumSnapshot?.map { it.deepCopy() }

    /** What each enum value means, by value; values without a description are absent. */
    val enumDescriptions: Map<JsonNode, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(enumDescriptions))
    val valueTypes: Set<QueryValueType> = java.util.Collections.unmodifiableSet(LinkedHashSet(valueTypes))
    val properties: Map<String, QueryValueSchema> = java.util.Collections.unmodifiableMap(LinkedHashMap(properties))
    val alternatives: List<QueryValueSchema> = java.util.List.copyOf(alternatives)

    val cardinality: QueryCardinality? = when (kind) {
        QueryValueKind.UNKNOWN -> null
        QueryValueKind.ARRAY -> QueryCardinality.MANY
        QueryValueKind.UNION -> this.alternatives.map { it.cardinality }.distinct().singleOrNull()
        else -> QueryCardinality.SINGLE
    }

    init {
        require((kind == QueryValueKind.ARRAY) == (items != null)) { "Only an array value must have items." }
        require(kind == QueryValueKind.OBJECT || this.properties.isEmpty() && additionalProperties == null) {
            "Only an object value can have named or dynamic properties."
        }
        require((kind == QueryValueKind.UNION) == this.alternatives.isNotEmpty()) {
            "Only a union value must have alternatives."
        }
        when (kind) {
            QueryValueKind.SCALAR -> require(
                this.valueTypes.isNotEmpty() && QueryValueType.OBJECT !in this.valueTypes
            ) {
                "A scalar value requires non-object value types."
            }
            QueryValueKind.OBJECT -> require(this.valueTypes == setOf(QueryValueType.OBJECT)) {
                "An object value requires the object value type."
            }
            else -> require(
                this.valueTypes.isEmpty()
            ) { "Value types belong to scalar or object nodes, not their containers." }
        }
        if (kind == QueryValueKind.NULL) require(nullable) { "A null value must allow null." }
        if (kind == QueryValueKind.UNION) {
            require(this.alternatives.size > 1) { "A union value requires at least two alternatives." }
            require(
                nullable == this.alternatives.any { it.nullable }
            ) { "Union nullability must reflect every alternative." }
        }
        this.properties.keys.forEach(::requireQueryPathSegment)
    }
}

class QueryFieldBindingTemplate(
    val physicalPath: QueryPathTemplate,
    storageTypes: Set<QueryStorageType>?,
) {
    val storageTypes: Set<QueryStorageType>? = storageTypes?.let {
        java.util.Collections.unmodifiableSet(LinkedHashSet(it))
    }

    init {
        require(this.storageTypes == null || this.storageTypes.isNotEmpty()) { "Known storage types cannot be empty." }
    }

    override fun equals(other: Any?): Boolean = other is QueryFieldBindingTemplate &&
        physicalPath == other.physicalPath && storageTypes == other.storageTypes

    override fun hashCode(): Int = 31 * physicalPath.hashCode() + (storageTypes?.hashCode() ?: 0)
}

class QueryValueBindings(
    bindings: Map<QueryCapability, QueryFieldBindingTemplate> = emptyMap(),
    val projectionPath: QueryPathTemplate? = null,
    val responsePath: QueryPathTemplate? = null,
) {
    val bindings: Map<QueryCapability, QueryFieldBindingTemplate> = java.util.Collections.unmodifiableMap(
        LinkedHashMap(bindings)
    )
}
