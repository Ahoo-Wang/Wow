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

import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The walk of a masked read that answers only what its schema declares: it removes, in place, each property of a
 * closed object ([QueryValueSchema.declaredNames]) that its schema does not declare, such as one a newer node's state
 * holds and this node's schema does not know, which could be sensitive in that newer version. An open object, a
 * typed map's or a free one, keeps every property, its values walked where they can hold a closed object; a value of
 * unknown shape is left alone. A value of a union is walked by its alternatives together: an object keeps every name
 * one of them declares, and every property when one of them is open.
 *
 * Compiled once per schema ([of]): it descends only into what the schema says can hold a closed object, so a value
 * with nothing undeclared is answered as the same nodes, nothing copied for it.
 */
internal class DeclaredOnly private constructor(private val root: Shape) {
    /** [node] with every property its schema does not declare removed, in place: the same node. */
    fun prune(node: JsonNode): JsonNode {
        root.prune(node)
        return node
    }

    /** What a value's schema says of the closed objects it may hold: as an object ([members]), as an array ([items]). */
    private class Shape(val members: Members?, val items: Shape?) {
        /** Whether a value of this shape may hold a closed object, so a walk descends into it. */
        val walks: Boolean = members?.walks == true || items != null

        fun prune(node: JsonNode) {
            when (node) {
                is ObjectNode -> members?.prune(node)
                is ArrayNode -> items?.let { item -> node.forEach(item::prune) }
                else -> Unit
            }
        }

        /** This shape and [other]'s together, the shape of a value of either: a union's. */
        fun merge(other: Shape): Shape = Shape(
            members = merge(members, other.members, Members::merge),
            items = merge(items, other.items, Shape::merge),
        )
    }

    /**
     * What an object holds: [declared], every name it may hold when it is closed (null when it is open); the shapes
     * of its named [properties] that walk; and of its other properties' values ([values]), named or not in [named].
     */
    private class Members(
        val declared: Set<String>?,
        val properties: Map<String, Shape>,
        val named: Set<String>,
        val values: Shape?,
    ) {
        val walks: Boolean = declared != null || properties.isNotEmpty() || values != null

        fun prune(node: ObjectNode) {
            declared?.let { names ->
                if (node.propertyNames().any { it !in names }) node.retain(names)
            }
            if (properties.size <= node.size()) {
                properties.forEach { (name, shape) -> node.get(name)?.let(shape::prune) }
            } else {
                node.forEachEntry { name, child -> properties[name]?.prune(child) }
            }
            values?.let { shape -> node.forEachEntry { name, child -> if (name !in named) shape.prune(child) } }
        }

        fun merge(other: Members): Members = Members(
            declared = if (declared != null && other.declared != null) declared + other.declared else null,
            properties = (properties.keys + other.properties.keys).associateWith { name ->
                checkNotNull(merge(properties[name], other.properties[name], Shape::merge))
            },
            named = named + other.named,
            values = merge(values, other.values, Shape::merge),
        )
    }

    companion object {
        /** The walk of values of [schema], or `null` when no value of it can hold a closed object. */
        fun of(schema: QueryValueSchema): DeclaredOnly? = shapeOf(schema)?.takeIf { it.walks }?.let(::DeclaredOnly)

        /** A value that may be an object of any property, unknown or free: one that leaves an object open. */
        private val OPEN = Shape(Members(null, emptyMap(), emptySet(), null), null)

        private fun shapeOf(schema: QueryValueSchema): Shape? = when (schema.kind) {
            QueryValueKind.OBJECT -> Shape(
                members = Members(
                    declared = schema.declaredNames,
                    properties = schema.properties.mapNotNull { (name, value) ->
                        shapeOf(value)?.takeIf { it.walks }?.let { name to it }
                    }.toMap(),
                    named = schema.properties.keys,
                    values = schema.additionalProperties?.let(::shapeOf)?.takeIf { it.walks },
                ),
                items = null,
            )
            QueryValueKind.ARRAY -> schema.items?.let(::shapeOf)?.takeIf { it.walks }?.let { Shape(null, it) }
            QueryValueKind.UNION -> schema.alternatives.mapNotNull(::shapeOf).reduceOrNull(Shape::merge)
            QueryValueKind.UNKNOWN -> OPEN
            QueryValueKind.SCALAR, QueryValueKind.NULL -> null
        }

        private fun <T> merge(left: T?, right: T?, both: (T, T) -> T): T? =
            if (left == null || right == null) left ?: right else both(left, right)
    }
}
