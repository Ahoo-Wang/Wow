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

import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import tools.jackson.databind.JsonNode

/** Generation-owned data. Runtime masking neither resolves schema paths nor builds plans. */
internal class QueryMaskDefinition private constructor(
    val root: MaskNode,
    val profile: QueryModelProfile,
    val payloadTypes: Set<String>?,
) {
    internal class MaskNode(
        val values: List<QueryMaskValue>,
        val shapes: List<QueryValueSchema>,
        val properties: Map<String, MaskNode>,
        val keys: List<Pair<Set<String>, MaskNode>>,
        val item: MaskNode?,
    ) {
        val plainObject: Boolean = values.isEmpty() && keys.isEmpty() &&
            shapes.isNotEmpty() && shapes.all { it.kind == QueryValueKind.OBJECT }

        val stringMask: MaskRule? = values.singleOrNull()
            ?.takeIf { properties.isEmpty() && keys.isEmpty() && item == null }
            ?.takeIf { it.masked.isStringShape() && it.allowed.isStringShape() && shapes.all { shape -> shape.isStringShape() } }
            ?.masked?.maskRule

        /** This node visited alone, as an array or a map: its merged lists are compiled once, on first use. */
        val group: MaskGroup by lazy { MaskGroup(listOf(this), emptyList()) }
    }

    /**
     * Mask nodes that apply to one response value together ([nodes], plus the [inherited] values of an enclosing
     * masked array), with the merged lists the masker reads compiled once per group instead of once per visited value.
     * Every group is reached from the root through [items] and [property], so a definition has a fixed set of them.
     */
    internal class MaskGroup(val nodes: List<MaskNode>, inherited: List<QueryMaskValue>) {
        /** The node to visit on its own: one node and nothing inherited. */
        val single: MaskNode? = nodes.singleOrNull()?.takeIf { inherited.isEmpty() }
        val values: List<QueryMaskValue> = nodes.flatMap { it.values } + inherited
        val shapes: List<QueryValueSchema> = nodes.flatMap { it.shapes }

        /** The group of this value's array items. */
        val items: MaskGroup by lazy {
            MaskGroup(nodes.mapNotNull { it.item }, values.flatMap { it.arrayMembers })
        }

        /** The one node whose named properties are all an object visit needs: one node and no dynamic keys. */
        val namedOnly: MaskNode? = nodes.singleOrNull()?.takeIf { it.keys.isEmpty() }
        private val dynamic: List<Pair<Set<String>, MaskNode>> = nodes.flatMap { it.keys }
        val names: Set<String> = nodes.flatMapTo(linkedSetOf()) { it.properties.keys }

        /** Whether an object visit walks the object's own properties rather than [names]. */
        fun walksObject(size: Int): Boolean = dynamic.isNotEmpty() || names.size > size

        private val unnamed: MaskGroup? by lazy {
            dynamic.map { it.second }.takeIf { it.isNotEmpty() }?.let { MaskGroup(it, emptyList()) }
        }
        private val named: Map<String, MaskGroup?> by lazy {
            val excluded = dynamic.flatMapTo(linkedSetOf()) { it.first }
            (names + excluded).associateWith { name ->
                val children = nodes.mapNotNull { it.properties[name] } +
                    dynamic.filter { name !in it.first }.map { it.second }
                children.takeIf { it.isNotEmpty() }?.let { MaskGroup(it, emptyList()) }
            }
        }

        /** The group of property [name], or `null` when no mask applies to it. */
        fun property(name: String): MaskGroup? = if (name in named) named[name] else unnamed
    }

    companion object {
        fun create(schema: QueryModelSchema): QueryMaskDefinition? {
            if (!schema.hasMaskedFields) return null
            val paths = schema.protectedSources.responseMasks
            val profile = schema.profile
                ?: throw QuerySchemaConflictException("Unsupported masked model: [${schema.model}].")
            val prefix = profile.payloadField.path.split('.')
            paths.forEach { (path, value) ->
                val names = path.segments.filterIsInstance<QueryPathSegment.Property>().map { it.name }
                if (names.take(prefix.size) != prefix || !value.masked.maskable() || value.allowed.hasUnknownShape()) {
                    throw QuerySchemaConflictException(
                        "Mask requires a declared string domain under the model payload."
                    )
                }
            }
            val payloadTypes = schema.declaredPayloadTypes(profile)
            val shapes = schema.definition.values.mapKeys { it.key.segments }.toMutableMap()
            schema.bindings.forEach { (logical, native) ->
                native.responsePath?.let { response ->
                    schema.definition.value(logical)?.let { shapes.putIfAbsent(response.segments, it) }
                }
            }
            return QueryMaskDefinition(
                build(paths.map { it.first.segments to it.second }, emptyList(), shapes),
                profile,
                payloadTypes,
            )
        }

        private fun QueryModelSchema.declaredPayloadTypes(profile: QueryModelProfile): Set<String>? {
            val typeField = profile.payloadTypeField ?: return null
            val values = field(typeField)?.value?.enumValues
                ?: throw QuerySchemaConflictException("Masked event schema requires $typeField enum values.")
            val types = values.mapNotNull { it.takeIf(JsonNode::isString)?.stringValue() }.toSet()
            if (types.isEmpty() || types.size != values.size) {
                throw QuerySchemaConflictException("Masked event schema requires string $typeField enum values.")
            }
            return types
        }

        private fun build(
            paths: List<Pair<List<QueryPathSegment>, QueryMaskValue>>,
            prefix: List<QueryPathSegment>,
            shapes: Map<List<QueryPathSegment>, QueryValueSchema>,
        ): MaskNode {
            val values = paths.filter { it.first.isEmpty() }.map { it.second }
            if (values.mapNotNull { it.masked.maskRule }.distinct().size > 1) {
                throw QuerySchemaConflictException("Conflicting mask rules at one response value.")
            }
            val children = paths.filter { it.first.isNotEmpty() }.groupBy {
                val segment = it.first.first()
                segment to if (segment is QueryPathSegment.Key) it.second.excludedKeys[segment.slot].orEmpty() else emptySet()
            }.mapValues { (part, entries) ->
                build(
                    entries.map { it.first.drop(1) to it.second },
                    prefix + part.first,
                    shapes
                )
            }
            return MaskNode(
                values,
                listOfNotNull(shapes[prefix]),
                children.filterKeys { it.first is QueryPathSegment.Property }
                    .mapKeys { (part, _) -> (part.first as QueryPathSegment.Property).name },
                children.filterKeys { it.first is QueryPathSegment.Key }.map { it.key.second to it.value },
                children[QueryPathSegment.Item to emptySet()],
            )
        }
    }
}

private fun QueryValueSchema.maskable(): Boolean = when (kind) {
    QueryValueKind.NULL -> true
    QueryValueKind.SCALAR -> valueTypes == setOf(QueryValueType.STRING)
    QueryValueKind.ARRAY -> checkNotNull(items).maskable()
    QueryValueKind.UNION -> alternatives.all { it.maskable() }
    else -> false
}

private fun QueryValueSchema.isStringShape(): Boolean =
    kind == QueryValueKind.SCALAR && valueTypes.size == 1 && QueryValueType.STRING in valueTypes

private fun QueryValueSchema.hasUnknownShape(): Boolean = when (kind) {
    QueryValueKind.UNKNOWN -> true
    QueryValueKind.ARRAY -> checkNotNull(items).hasUnknownShape()
    QueryValueKind.UNION -> alternatives.any { it.hasUnknownShape() }
    else -> false
}
