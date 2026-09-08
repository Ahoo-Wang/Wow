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

/** An original value node and the path evidence used to reach it. */
internal data class QueryValueMatch(
    val value: QueryValueSchema,
    val keys: List<QueryPathSegment>,
    val elementAncestors: List<QueryPathTemplate>,
    val complete: Boolean,
    val path: QueryPathTemplate,
)

/**
 * Looks up a logical path. Symbolic keys select only the map default; named overrides
 * are separate concrete paths. Logical key slots follow path order, unlike physical templates.
 * An incomplete match witnesses an unknown suffix, never a binding for that suffix.
 */
@Suppress("CyclomaticComplexMethod") // Exhaustive traversal of the six value shapes.
internal fun QueryValueSchema.lookup(path: QueryPathTemplate): List<QueryValueMatch> {
    require(path.hasOrderedKeys) { "Logical path key slots must follow path order." }
    val logicalPath = ArrayList<QueryPathSegment>()
    val keys = ArrayList<QueryPathSegment>()
    val ancestors = ArrayList<QueryPathTemplate>()
    return buildList {
        fun visit(value: QueryValueSchema, offset: Int) {
            if (offset == path.segments.size || value.kind == QueryValueKind.UNKNOWN) {
                add(
                    QueryValueMatch(
                        value = value,
                        keys = java.util.List.copyOf(keys),
                        elementAncestors = java.util.List.copyOf(ancestors),
                        complete = offset == path.segments.size,
                        path = if (ancestors.isEmpty()) path else QueryPathTemplate(logicalPath),
                    ),
                )
                return
            }
            val segment = path.segments[offset]
            when (value.kind) {
                QueryValueKind.UNION -> value.alternatives.forEach { visit(it, offset) }
                QueryValueKind.ARRAY -> {
                    ancestors.add(QueryPathTemplate(logicalPath))
                    logicalPath.add(QueryPathSegment.Item)
                    visit(checkNotNull(value.items), if (segment == QueryPathSegment.Item) offset + 1 else offset)
                    logicalPath.removeAt(logicalPath.lastIndex)
                    ancestors.removeAt(ancestors.lastIndex)
                }
                QueryValueKind.OBJECT -> {
                    if (segment == QueryPathSegment.Item) return
                    val named = (segment as? QueryPathSegment.Property)?.let { value.properties[it.name] }
                    val child = named ?: value.additionalProperties ?: return
                    logicalPath.add(segment)
                    if (named == null) keys.add(segment)
                    visit(child, offset + 1)
                    if (named == null) keys.removeAt(keys.lastIndex)
                    logicalPath.removeAt(logicalPath.lastIndex)
                }
                else -> Unit
            }
        }
        visit(this@lookup, 0)
    }
}

/** Visits each real node once per tree position, retaining union branches at the same path. */
internal fun QueryValueSchema.valuePaths(): List<Pair<QueryPathTemplate, QueryValueSchema>> = buildList {
    fun visit(value: QueryValueSchema, segments: List<QueryPathSegment>, keyCount: Int) {
        add(QueryPathTemplate(segments) to value)
        value.properties.forEach { (name, child) ->
            visit(child, segments + QueryPathSegment.Property(name), keyCount)
        }
        value.items?.let { visit(it, segments + QueryPathSegment.Item, keyCount) }
        value.additionalProperties?.let { visit(it, segments + QueryPathSegment.Key(keyCount), keyCount + 1) }
        value.alternatives.forEach { visit(it, segments, keyCount) }
    }
    visit(this@valuePaths, emptyList(), 0)
}
