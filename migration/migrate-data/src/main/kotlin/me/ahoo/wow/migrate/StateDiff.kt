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

package me.ahoo.wow.migrate

import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * A field whose value differs between a 9.x snapshot and the V10 state rebuilt from the imported log: [path] names it
 * (`items[0].price`; a metadata field is `$deleted`, `$ownerId`, `$spaceId` or `$tags`), and [nineX] and [v10] are
 * its two values, null where that side lacks the field.
 */
internal data class FieldDifference(val path: String, val nineX: JsonNode?, val v10: JsonNode?) {
    /** The class of the difference: its path with the array indexes left out (`items[].price`), for the summary. */
    val kind: String get() = path.replace(INDEX, "[]")

    fun render(): String = "$path: 9.x ${nineX?.let(::text) ?: "(none)"}, V10 ${v10?.let(::text) ?: "(none)"}"

    private fun text(node: JsonNode): String = WowJson.mapper.writeValueAsString(node)

    private companion object {
        val INDEX = Regex("\\[\\d+]")
    }
}

/**
 * The normalised comparison of two JSON states (design 07 §3 step 4 and §6):
 *
 * - objects compare field by field, in any order, and a `null` field is the same as a missing one;
 * - numbers compare by value: the 9.x `25.00` (a `Decimal128`) is the V10 `25.0`, and `2` is `2.0`;
 * - arrays compare element by element, in order: an element only one side has is a difference of its own;
 * - anything else compares by equality, so the string `"1"` is not the number `1`.
 *
 * Before comparing, [StateMapping.fields] renames the 9.x state's top-level fields and [StateMapping.ignore] removes
 * the listed fields from both states.
 */
internal object StateDiff {
    /** The differences of the 9.x state [nineX] read through [mapping] from the V10 state [v10]; empty when equal. */
    fun diff(nineX: JsonNode, v10: JsonNode, mapping: StateMapping = StateMapping()): List<FieldDifference> {
        val left = renamed(nineX.deepCopy(), mapping.fields)
        val right = v10.deepCopy()
        mapping.ignore.forEach { path ->
            val names = path.split('.')
            remove(left, names)
            remove(right, names)
        }
        return buildList { compare("", left, right, this) }
    }

    private fun renamed(state: JsonNode, fields: Map<String, String>): JsonNode {
        if (fields.isEmpty() || state !is ObjectNode) {
            return state
        }
        val renamed = WowJson.mapper.createObjectNode()
        state.properties().forEach { (name, value) -> renamed.set(fields[name] ?: name, value) }
        return renamed
    }

    private fun remove(node: JsonNode?, names: List<String>) {
        when (node) {
            is ArrayNode -> node.forEach { remove(it, names) }
            is ObjectNode -> if (names.size == 1) node.remove(names[0]) else remove(node.get(names[0]), names.drop(1))
            else -> Unit
        }
    }

    private fun compare(path: String, left: JsonNode?, right: JsonNode?, into: MutableList<FieldDifference>) {
        val nineX = left.present()
        val v10 = right.present()
        when {
            nineX is ObjectNode && v10 is ObjectNode -> {
                val names = (nineX.properties() + v10.properties()).map { it.key }.toSortedSet()
                for (name in names) {
                    compare(if (path.isEmpty()) name else "$path.$name", nineX.get(name), v10.get(name), into)
                }
            }

            nineX is ArrayNode && v10 is ArrayNode -> {
                for (index in 0 until maxOf(nineX.size(), v10.size())) {
                    compare("$path[$index]", nineX.get(index), v10.get(index), into)
                }
            }

            !same(nineX, v10) -> into += FieldDifference(path.ifEmpty { "(state)" }, nineX, v10)
        }
    }

    /** This node, or null for a JSON `null` or a missing node: a `null` field is the same as a missing one. */
    private fun JsonNode?.present(): JsonNode? = this?.takeUnless { it.isNull || it.isMissingNode }

    private fun same(left: JsonNode?, right: JsonNode?): Boolean = if (left?.isNumber == true && right?.isNumber == true) {
        left.decimalValue().compareTo(right.decimalValue()) == 0
    } else {
        left == right
    }
}
