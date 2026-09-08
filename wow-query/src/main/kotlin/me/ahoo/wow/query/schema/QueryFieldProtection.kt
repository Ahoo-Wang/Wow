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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality

internal fun isCursorFieldAllowed(schema: QueryModelSchema, logical: QueryField, field: QueryFieldSchema): Boolean =
    field.binding(QueryCapability.CURSOR_SORT) != null && field.value.cardinality == QueryCardinality.SINGLE &&
        field.elementAncestors == emptyList<QueryField>() && !isFieldProtected(schema, logical, field)

internal fun isCursorFieldAllowed(
    schema: QueryModelSchema,
    path: QueryPathTemplate,
    value: QueryValueSchema,
    native: QueryValueBindings,
): Boolean = QueryCapability.CURSOR_SORT in native.bindings && value.cardinality == QueryCardinality.SINGLE &&
    QueryPathSegment.Item !in path.segments && !isFieldProtected(schema, path)

internal fun isFieldProtected(schema: QueryModelSchema, logical: QueryField, field: QueryFieldSchema): Boolean {
    if (!schema.hasMaskedFields) return false
    return schema.protectedSources.overlaps(QuerySourceNamespace.LOGICAL, logical.toPathTemplate().segments) ||
        field.bindings.values.any {
            schema.protectedSources.overlaps(QuerySourceNamespace.PHYSICAL, it.physicalField.toPathTemplate().segments)
        } || listOfNotNull(
            field.projectionField?.let { QuerySourceNamespace.PROJECTION to it },
            field.responseField?.let { QuerySourceNamespace.RESPONSE to it },
        ).any { (namespace, source) -> schema.protectedSources.overlaps(namespace, source.toPathTemplate().segments) }
}

internal fun isFieldProtected(schema: QueryModelSchema, path: QueryPathTemplate): Boolean =
    schema.hasMaskedFields && schema.protectedSources.overlaps(QuerySourceNamespace.LOGICAL, path.segments)

internal data class QueryMaskValue(
    val masked: QueryValueSchema,
    val allowed: QueryValueSchema,
    val excludedKeys: Map<Int, Set<String>> = emptyMap(),
)

internal enum class QuerySourceNamespace { LOGICAL, PROJECTION, RESPONSE, PHYSICAL }

private sealed interface SourcePart {
    data class Property(val name: String) : SourcePart
    data class Key(val slot: Int, val excluded: Set<String>) : SourcePart
    data object Item : SourcePart
}

/** Prefix intersections only, partitioned by namespace; map keys retain their named exclusions. */
private class SourceIndex<V> {
    private class Node<V> {
        val properties = HashMap<String, Node<V>>()
        val keys = HashMap<SourcePart.Key, Node<V>>()
        var item: Node<V>? = null
        val values = ArrayList<V>()
    }
    private val roots = HashMap<QuerySourceNamespace, Node<V>>()

    fun add(namespace: QuerySourceNamespace, path: List<SourcePart>, value: V) {
        var node = roots.getOrPut(namespace) { Node() }
        path.forEach { part ->
            node = when (part) {
                is SourcePart.Property -> node.properties.getOrPut(part.name) { Node() }
                is SourcePart.Key -> node.keys.getOrPut(part) { Node() }
                SourcePart.Item -> node.item ?: Node<V>().also { node.item = it }
            }
        }
        node.values.add(value)
    }

    fun any(namespace: QuerySourceNamespace, path: List<SourcePart>, accept: (V) -> Boolean): Boolean =
        roots[namespace]?.let { intersect(it, path, 0, accept) } == true

    private fun intersect(node: Node<V>, path: List<SourcePart>, offset: Int, accept: (V) -> Boolean): Boolean {
        if (offset == path.size) return descendants(node, accept)
        if (node.values.any(accept)) return true
        return when (val part = path[offset]) {
            is SourcePart.Property ->
                node.properties[part.name]?.let { intersect(it, path, offset + 1, accept) } == true ||
                    node.keys.any { (key, child) -> part.name !in key.excluded && intersect(child, path, offset + 1, accept) }
            is SourcePart.Key ->
                node.properties.any { (name, child) -> name !in part.excluded && intersect(child, path, offset + 1, accept) } ||
                    node.keys.values.any { intersect(it, path, offset + 1, accept) }
            SourcePart.Item -> node.item?.let { intersect(it, path, offset + 1, accept) } == true
        }
    }

    private fun descendants(node: Node<V>, accept: (V) -> Boolean): Boolean =
        node.values.any(accept) || node.properties.values.any { descendants(it, accept) } ||
            node.keys.values.any { descendants(it, accept) } || node.item?.let { descendants(it, accept) } == true
}

/** Immutable source relations; key exclusions preserve named-property shadowing across aliases. */
internal class QueryProtectedSources(schema: QueryModelSchema) {
    private data class Source(val namespace: QuerySourceNamespace, val path: List<SourcePart>)
    private data class Edge(val from: Source, val to: Source)
    private data class FactKey(
        val source: Source,
        val rule: MaskRule?,
        val kind: me.ahoo.wow.api.query.schema.QueryValueKind
    )
    private val protected = SourceIndex<Unit>()
    internal val responseMasks: List<Pair<QueryPathTemplate, QueryMaskValue>>

    init {
        if (schema.maskedValues.isEmpty()) {
            responseMasks = emptyList()
        } else {
            fun source(namespace: QuerySourceNamespace, path: QueryPathTemplate, logical: QueryPathTemplate): Source {
                val exclusions = schema.definition.keyExclusions(logical)
                val parts = path.segments.map { part ->
                    when (part) {
                        is QueryPathSegment.Property -> SourcePart.Property(part.name)
                        is QueryPathSegment.Key -> SourcePart.Key(part.slot, exclusions[part.slot].orEmpty())
                        QueryPathSegment.Item -> SourcePart.Item
                    }
                }
                return Source(
                    namespace,
                    if (namespace == QuerySourceNamespace.PHYSICAL) parts.withoutItems() else parts
                )
            }
            val edges = schema.bindings.flatMap { (path, native) ->
                val logical = source(QuerySourceNamespace.LOGICAL, path, path)
                val targets = listOfNotNull(
                    native.projectionPath?.let { source(QuerySourceNamespace.PROJECTION, it, path) },
                    native.responsePath?.let { source(QuerySourceNamespace.RESPONSE, it, path) },
                ) + native.bindings.values.map { source(QuerySourceNamespace.PHYSICAL, it.physicalPath, path) }
                targets.flatMap { listOf(Edge(logical, it), Edge(it, logical)) }
            }.toSet()
            val edgeIndex = SourceIndex<Edge>()
            edges.forEach { edgeIndex.add(it.from.namespace, it.from.path, it) }
            val facts = schema.maskedValues.mapTo(linkedSetOf()) {
                source(QuerySourceNamespace.LOGICAL, it.first, it.first) to
                    QueryMaskValue(it.second, checkNotNull(schema.definition.value(it.first)))
            }
            val visited = facts.mapTo(
                hashSetOf()
            ) { (source, value) -> FactKey(source, value.masked.maskRule, value.masked.kind) }
            val pending = ArrayDeque(facts)
            while (pending.isNotEmpty()) {
                val (origin, domains) = pending.removeFirst()
                edgeIndex.any(origin.namespace, origin.path) { edge ->
                    var path = transport(origin.path, edge.from.path, edge.to.path) ?: return@any false
                    if (edge.to.namespace == QuerySourceNamespace.PHYSICAL) path = path.withoutItems()
                    val targetValue = if (edge.to.namespace == QuerySourceNamespace.LOGICAL) {
                        schema.definition.value(path.template()) ?: return@any false
                    } else {
                        null
                    }
                    val narrowed = domains.masked.descendant(edge.from.path.drop(origin.path.size)) ?: return@any false
                    val allowed = domains.allowed.descendant(edge.from.path.drop(origin.path.size)) ?: narrowed
                    // Physical array flattening can relate a member to an array alias of that same source.
                    val rule = checkNotNull(domains.masked.maskRule)
                    val propagated = QueryMaskValue(
                        if (targetValue == null) {
                            narrowed.withMask(rule)
                        } else {
                            targetValue.stringMaskShape(rule)
                                ?: throw QuerySchemaConflictException("Protected aliases disagree on their string wire domain.")
                        },
                        targetValue ?: allowed,
                    )
                    val next = Source(edge.to.namespace, path)
                    if (visited.add(FactKey(next, propagated.masked.maskRule, propagated.masked.kind))) {
                        val fact = next to propagated
                        facts.add(fact)
                        pending.add(fact)
                    }
                    false
                }
            }
            facts.mapTo(
                linkedSetOf()
            ) { it.first }.forEach { protected.add(it.namespace, it.path.withoutItems(), Unit) }
            responseMasks = facts.filter { it.first.namespace == QuerySourceNamespace.RESPONSE }.map { (source, values) ->
                source.path.template() to values.copy(excludedKeys = source.path.exclusions())
            } + schema.maskedValues.filter { schema.bindings[it.first]?.responsePath == null }.map {
                it.first to QueryMaskValue(it.second, checkNotNull(schema.definition.value(it.first)), schema.definition.keyExclusions(it.first))
            }
        }
    }

    fun overlaps(namespace: QuerySourceNamespace, path: List<QueryPathSegment>): Boolean {
        val candidate = path.map {
            when (it) {
                is QueryPathSegment.Property -> SourcePart.Property(it.name)
                is QueryPathSegment.Key -> SourcePart.Key(it.slot, emptySet())
                QueryPathSegment.Item -> SourcePart.Item
            }
        }.withoutItems()
        return protected.any(namespace, candidate) { true }
    }
}

private fun LogicalQuerySchema.keyExclusions(path: QueryPathTemplate): Map<Int, Set<String>> = buildMap {
    path.segments.forEachIndexed { index, part ->
        if (part is QueryPathSegment.Key) {
            val parent = value(QueryPathTemplate(path.segments.take(index)))
            val branches = parent?.alternativesOrSelf().orEmpty()
            put(
                part.slot,
                branches.map { it.properties.keys }.reduceOrNull { left, right -> left.intersect(right) }.orEmpty()
            )
        }
    }
}

private fun QueryValueSchema.descendant(suffix: List<SourcePart>): QueryValueSchema? =
    if (suffix.isEmpty()) this else mergeQueryValues(lookup(suffix.template()))

private fun List<SourcePart>.withoutItems(): List<SourcePart> = filterNot { it == SourcePart.Item }
private fun List<SourcePart>.template(): QueryPathTemplate {
    var slot = 0
    return QueryPathTemplate(
        map {
            when (it) {
                is SourcePart.Property -> QueryPathSegment.Property(it.name)
                is SourcePart.Key -> QueryPathSegment.Key(slot++)
                SourcePart.Item -> QueryPathSegment.Item
            }
        }
    )
}
private fun List<SourcePart>.exclusions(): Map<Int, Set<String>> =
    filterIsInstance<SourcePart.Key>().mapIndexed { index, key -> index to key.excluded }.toMap()

private fun overlap(left: List<SourcePart>, right: List<SourcePart>): Boolean = left.zip(right).all { (a, b) ->
    when {
        a is SourcePart.Key && b is SourcePart.Property -> b.name !in a.excluded
        b is SourcePart.Key && a is SourcePart.Property -> a.name !in b.excluded
        a is SourcePart.Key && b is SourcePart.Key -> true
        else -> a == b
    }
}

/** Carry only the intersecting subtree through an alias; never widen it to a sibling. */
private fun transport(mask: List<SourcePart>, source: List<SourcePart>, target: List<SourcePart>): List<SourcePart>? {
    if (!overlap(mask, source)) return null
    val keys = mutableMapOf<Int, SourcePart>()
    source.zip(mask).forEach { (native, incoming) ->
        if (native is SourcePart.Key) {
            keys[native.slot] = when (incoming) {
                is SourcePart.Key -> incoming.copy(excluded = incoming.excluded + native.excluded)
                else -> incoming
            }
        }
    }
    var slot = 0
    return (target.map { if (it is SourcePart.Key) keys[it.slot] ?: it else it } + mask.drop(source.size)).map {
        if (it is SourcePart.Key) it.copy(slot = slot++) else it
    }
}

internal fun QueryValueSchema.withMask(rule: MaskRule): QueryValueSchema = QueryValueSchema(
    kind = kind, title = title, description = description, enumValues = enumValues, valueTypes = valueTypes,
    properties = properties, items = items, additionalProperties = additionalProperties, alternatives = alternatives,
    nullable = nullable, required = required, semanticType = semanticType, maskRule = rule,
)

private fun QueryValueSchema.stringMaskShape(rule: MaskRule): QueryValueSchema? = when (kind) {
    me.ahoo.wow.api.query.schema.QueryValueKind.SCALAR -> if (me.ahoo.wow.api.query.schema.QueryValueType.STRING in valueTypes) {
        QueryValueSchema(
            kind,
            valueTypes = setOf(me.ahoo.wow.api.query.schema.QueryValueType.STRING),
            nullable = nullable,
            maskRule = rule
        )
    } else {
        null
    }
    me.ahoo.wow.api.query.schema.QueryValueKind.ARRAY -> items?.stringMaskShape(rule)?.let {
        QueryValueSchema(kind, items = it, nullable = nullable, maskRule = rule)
    }
    me.ahoo.wow.api.query.schema.QueryValueKind.UNION -> alternatives.mapNotNull { it.stringMaskShape(rule) }.let {
        when (it.size) {
            0 -> null
            1 -> it.single()
            else -> QueryValueSchema(kind, alternatives = it, maskRule = rule)
        }
    }
    else -> null
}
