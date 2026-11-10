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

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.annotation.InternalWowApi
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * A query's projection over a whole record, for a backend that reads the record and projects it itself: what the
 * includes keep (the whole record when there are none), less what the excludes remove. Each path is the field's
 * segments in the record as the backend built it.
 *
 * An include keeps the subtree at its path. Through an array it keeps that subtree in every element, at the element's
 * position: an object element by its own fields, an array element (an array of arrays) by its own elements, so the
 * nesting is kept. A scalar element holds nothing to keep: it becomes an empty object where a later element keeps
 * something, so the kept elements stay at their positions, and is left out after the last one (`[1, {"a": 2}, 3]`
 * projected to `a` is `[{}, {"a": 2}]`; an array of scalars alone is `[]`). An exclude removes the subtree at its
 * path in every element on the way and leaves scalar elements as they are.
 */
@InternalWowApi
object RecordProjection {
    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    /** [record] projected by [include] and [exclude]; [record] itself when both are empty. */
    fun project(record: ObjectNode, include: List<List<String>>, exclude: List<List<String>>): ObjectNode {
        if (include.isEmpty() && exclude.isEmpty()) return record
        val projected = if (include.isEmpty()) {
            record
        } else {
            nodes.objectNode().also { target -> include.forEach { include(record, target, it) } }
        }
        exclude.forEach { exclude(projected, it) }
        return projected
    }

    private fun include(source: JsonNode, target: ObjectNode, path: List<String>) {
        val name = path.first()
        val value = source.get(name) ?: return
        when {
            path.size == 1 -> target.set(name, value.deepCopy())
            value.isObject -> include(value, target.objectAt(name), path.drop(1))
            value.isArray -> includeElements(value, target.arrayAt(name), path.drop(1))
        }
    }

    private fun includeElements(source: JsonNode, target: ArrayNode, path: List<String>) {
        source.forEachIndexed { index, item ->
            when {
                item.isObject -> include(item, target.objectAt(index), path)
                item.isArray -> includeElements(item, target.arrayAt(index), path)
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

    private fun ObjectNode.objectAt(name: String): ObjectNode = (get(name) as? ObjectNode) ?: putObject(name)

    private fun ObjectNode.arrayAt(name: String): ArrayNode = (get(name) as? ArrayNode) ?: putArray(name)

    private fun ArrayNode.objectAt(index: Int): ObjectNode =
        (padTo(index).get(index) as? ObjectNode) ?: nodes.objectNode().also { set(index, it) }

    private fun ArrayNode.arrayAt(index: Int): ArrayNode =
        (padTo(index).get(index) as? ArrayNode) ?: nodes.arrayNode().also { set(index, it) }

    /** This target array with a position at [index]; the positions it adds hold empty objects, as a skipped scalar's. */
    private fun ArrayNode.padTo(index: Int): ArrayNode = apply { while (size() <= index) addObject() }
}
