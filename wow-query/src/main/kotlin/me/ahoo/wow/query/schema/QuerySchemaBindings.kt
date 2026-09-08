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

/** Indexed native facts; a named key is more specific than a map default. */
internal class QueryBindingIndex(bindings: Map<QueryPathTemplate, QueryValueBindings>) {
    private class Node {
        val properties = linkedMapOf<String, Node>()
        var key: Node? = null
        var item: Node? = null
        var value: QueryValueBindings? = null
    }
    private val root = Node()
    private val exact = HashMap<QueryPathTemplate, QueryValueBindings>()

    init {
        bindings.forEach { (path, value) ->
            require(path.hasOrderedKeys) { "Logical binding keys must follow path order." }
            if (path.keyCount == 0) {
                exact[path] = value
                return@forEach
            }
            var node = root
            path.segments.forEach { segment ->
                node = when (segment) {
                    is QueryPathSegment.Property -> node.properties.getOrPut(segment.name) { Node() }
                    is QueryPathSegment.Key -> node.key ?: Node().also { node.key = it }
                    QueryPathSegment.Item -> node.item ?: Node().also { node.item = it }
                }
            }
            node.value = value
        }
    }

    fun locate(path: QueryPathTemplate): Pair<QueryValueBindings, List<String>>? {
        exact[path]?.let { return it to emptyList() }
        return locate(root, path.segments, 0, emptyList())
    }

    private fun locate(
        node: Node,
        segments: List<QueryPathSegment>,
        offset: Int,
        keys: List<String>,
    ): Pair<QueryValueBindings, List<String>>? {
        if (offset == segments.size) return node.value?.let { it to keys }
        return when (val segment = segments[offset]) {
            is QueryPathSegment.Property -> {
                node.properties[segment.name]?.let { direct ->
                    locate(direct, segments, offset + 1, keys)?.let { return it }
                }
                node.key?.let { locate(it, segments, offset + 1, keys + segment.name) }
            }
            QueryPathSegment.Item -> node.item?.let { locate(it, segments, offset + 1, keys) }
            is QueryPathSegment.Key -> error("Native field lookup requires concrete map keys.")
        }
    }
}

fun absoluteLogicalField(field: QueryField, parent: QueryField?): QueryField = parent?.append(field) ?: field

/** A native compiler consumes a binding; it never guesses physical names from caller input. */
fun QueryModelSchema.physicalField(
    field: QueryField,
    capability: QueryCapability,
    logicalParent: QueryField? = null,
): QueryField {
    val logical = absoluteLogicalField(field, logicalParent)
    val definition = this.field(logical) ?: throw QuerySchemaValidationException("Unknown logical field [$logical].")
    val ancestors = definition.elementAncestors
    requireSchema(ancestors != null && ancestors == requiredElementAncestors(logicalParent)) {
        "Field [$logical] requires its declared element scope."
    }
    return definition.binding(capability)?.physicalField
        ?: throw QuerySchemaValidationException("Field [$logical] does not support [$capability].")
}

fun QueryModelSchema.projectionField(field: QueryField): QueryField =
    this.field(field)?.projectionField ?: throw QuerySchemaValidationException("Field [$field] cannot be projected.")

internal fun QueryModelSchema.requiredElementAncestors(parent: QueryField?): List<QueryField>? =
    if (parent == null) emptyList() else field(parent)?.elementAncestors?.plus(parent)
