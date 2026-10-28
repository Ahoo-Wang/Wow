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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import tools.jackson.databind.JsonNode

/**
 * One immutable logical value shape, shared by backend schema snapshots: its [kind], the scalar [valueTypes], an
 * object's [properties] and [additionalProperties], an array's [items], a union's [alternatives], and what the
 * sources declare about it ([semanticType], [maskRule], [aliases], [deprecated]). The core builds it from the model's
 * sources; a storage adapter reads it, through [LogicalQuerySchema], to report what its storage serves.
 * Collection inputs are snapshotted; mutable enum JSON is detached on read.
 */
class QueryValueSchema @InternalWowApi constructor(
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
    /** The sensitivity of this string value: its level and how responses mask it; `null` when it is not sensitive. */
    val maskRule: MaskRule? = null,
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

    /**
     * Whether [other] declares the same value, recursively: every fact a consumer of the logical definition reads
     * (the masker reads nullability, kinds, value types, items and alternatives), including the ones the capability
     * descriptor leaves out. Property order counts, as it does for the descriptor.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun sameDefinitionAs(other: QueryValueSchema): Boolean {
        if (this === other) return true
        return kind == other.kind && nullable == other.nullable && required == other.required &&
            title == other.title && description == other.description && valueTypes == other.valueTypes &&
            semanticType == other.semanticType && maskRule == other.maskRule && variant == other.variant &&
            aliases == other.aliases && deprecated == other.deprecated && enumSnapshot == other.enumSnapshot &&
            enumDescriptions == other.enumDescriptions &&
            sameDefinition(items, other.items) &&
            sameDefinition(additionalProperties, other.additionalProperties) &&
            alternatives.size == other.alternatives.size &&
            alternatives.indices.all { alternatives[it].sameDefinitionAs(other.alternatives[it]) } &&
            properties.keys.toList() == other.properties.keys.toList() &&
            properties.all { (name, value) -> value.sameDefinitionAs(other.properties.getValue(name)) }
    }

    private fun sameDefinition(value: QueryValueSchema?, other: QueryValueSchema?): Boolean =
        if (value == null || other == null) value === other else value.sameDefinitionAs(other)
}

/**
 * Where a storage keeps a logical value for one capability, as its adapter reports it ([of]): the [physicalPath] in
 * the stored record, whose `*` key slots the core fills from the logical path; the native [storageTypes] there, or
 * `null` when the storage does not know them; the [physicalScope], the container whose elements the storage indexes
 * as separate documents (an Elasticsearch `nested` mapping), when there is one; and whether an index backs it
 * ([indexed]), `null` when the storage does not say. [indexed] never gates a capability: the descriptor publishes it.
 */
class QueryFieldBindingTemplate @InternalWowApi constructor(
    val physicalPath: QueryPathTemplate,
    storageTypes: Set<QueryStorageType>?,
    val physicalScope: QueryField? = null,
    val indexed: Boolean? = null,
) {
    val storageTypes: Set<QueryStorageType>? = storageTypes?.let {
        java.util.Collections.unmodifiableSet(LinkedHashSet(it))
    }

    init {
        require(this.storageTypes == null || this.storageTypes.isNotEmpty()) { "Known storage types cannot be empty." }
    }

    override fun equals(other: Any?): Boolean = other is QueryFieldBindingTemplate &&
        physicalPath == other.physicalPath && storageTypes == other.storageTypes &&
        physicalScope == other.physicalScope && indexed == other.indexed

    override fun hashCode(): Int =
        31 * (31 * (31 * physicalPath.hashCode() + (storageTypes?.hashCode() ?: 0)) + (physicalScope?.hashCode() ?: 0)) +
            (indexed?.hashCode() ?: 0)

    companion object {
        /** The binding at [physicalPath], of the native [storageTypes] (null: unknown), in [physicalScope]. */
        @JvmStatic
        @JvmOverloads
        fun of(
            physicalPath: QueryPathTemplate,
            storageTypes: Set<QueryStorageType>?,
            physicalScope: QueryField? = null,
            indexed: Boolean? = null,
        ): QueryFieldBindingTemplate = QueryFieldBindingTemplate(physicalPath, storageTypes, physicalScope, indexed)
    }
}

/**
 * What a storage serves at one logical path, as its adapter reports it ([of]): the [bindings], the location each
 * capability it can execute reads, and where a projection takes the value from ([projectionPath]) and a response
 * returns it ([responsePath]), `null` where the storage cannot project or return it.
 */
class QueryValueBindings @InternalWowApi constructor(
    bindings: Map<QueryCapability, QueryFieldBindingTemplate> = emptyMap(),
    val projectionPath: QueryPathTemplate? = null,
    val responsePath: QueryPathTemplate? = null,
) {
    val bindings: Map<QueryCapability, QueryFieldBindingTemplate> = java.util.Collections.unmodifiableMap(
        LinkedHashMap(bindings)
    )

    companion object {
        /** The [bindings] of each capability, with the [projectionPath] and [responsePath], if any. */
        @JvmStatic
        @JvmOverloads
        fun of(
            bindings: Map<QueryCapability, QueryFieldBindingTemplate>,
            projectionPath: QueryPathTemplate? = null,
            responsePath: QueryPathTemplate? = null,
        ): QueryValueBindings = QueryValueBindings(bindings, projectionPath, responsePath)
    }
}
