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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Projections
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.AdmittedQuery
import org.bson.conversions.Bson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * A query's projection in two steps. MongoDB returns a superset of what the projection keeps ([native]): whole
 * subtrees of at most two segments (`tenant_id`, `state.name`, `tags.region`), which never cross an array, plus the
 * sort fields a keyset page reads its positions from. The record built from it is then projected exactly
 * ([project]), with the reference semantics: an include keeps the path's subtree (through arrays of objects, keeping
 * their positions), an exclude removes it, and both apply when a projection names both. A native projection alone
 * would differ where a path crosses an array of mixed items, and MongoDB refuses to mix inclusion and exclusion.
 */
internal object MongoProjections {
    private const val NATIVE_DEPTH = 2
    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    /**
     * The native projection of [query]: the superset of its projection, with the physical [sortPaths] a keyset page
     * reads; `_id` always excluded.
     */
    fun native(query: AdmittedQuery<Queryable<*>>, sortPaths: List<String> = emptyList()): Bson {
        val projection = query.query.projection
        val includes = projection.include.map { physical(query, it) }
        if (includes.isNotEmpty()) {
            val paths = (includes + sortPaths).map(::subtree).distinct()
            val kept = paths.filterNot { path -> paths.any { path.startsWith("$it.") } }
            return Projections.fields(Projections.include(kept), Projections.excludeId())
        }
        val excludes = projection.exclude.map { physical(query, it) }
            .filter { path -> path.count { it == '.' } < NATIVE_DEPTH }
            .filterNot { path -> sortPaths.any { it == path || it.startsWith("$path.") } }
            .distinct()
        val dropped = excludes.filterNot { path -> excludes.any { path.startsWith("$it.") } }
        return Projections.exclude(dropped + MongoSnapshotDocuments.ID)
    }

    private fun physical(query: AdmittedQuery<Queryable<*>>, field: QueryField): String =
        MongoSnapshotDocuments.checked(query.physicalPath(field))

    private fun subtree(path: String): String = path.split('.').take(NATIVE_DEPTH).joinToString(".")

    /** [record] with only what [query]'s projection keeps: its includes (all when none), less its excludes. */
    fun project(query: AdmittedQuery<Queryable<*>>, record: ObjectNode): ObjectNode {
        val projection: Projection = query.query.projection
        if (projection.include.isEmpty() && projection.exclude.isEmpty()) return record
        val projected = if (projection.include.isEmpty()) {
            record
        } else {
            nodes.objectNode().also { target ->
                projection.include.forEach { include(record, target, logical(query, it)) }
            }
        }
        projection.exclude.forEach { exclude(projected, logical(query, it)) }
        return projected
    }

    private fun logical(query: AdmittedQuery<Queryable<*>>, field: QueryField): List<String> =
        query.field(field).logicalField.path.split('.')

    private fun include(source: JsonNode, target: ObjectNode, path: List<String>) {
        val name = path.first()
        val value = source.get(name) ?: return
        if (path.size == 1) {
            target.set(name, value.deepCopy())
            return
        }
        when {
            value.isObject -> include(value, (target.get(name) as? ObjectNode) ?: target.putObject(name), path.drop(1))
            value.isArray -> {
                val items = target.get(name) as? ArrayNode ?: target.putArray(name)
                value.forEachIndexed { index, item ->
                    if (!item.isObject) return@forEachIndexed
                    while (items.size() <= index) items.addObject()
                    include(item, items.get(index) as ObjectNode, path.drop(1))
                }
            }
        }
    }

    private fun exclude(node: JsonNode, path: List<String>) {
        when {
            node is ObjectNode && path.size == 1 -> node.remove(path.first())
            node is ObjectNode -> node.get(path.first())?.let { exclude(it, path.drop(1)) }
            node.isArray -> node.forEach { exclude(it, path) }
        }
    }
}
