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

package me.ahoo.wow.test

import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode

/**
 * The differences between an expected and an actual value, field by field: each a line naming its
 * path, `items[1].price: expected 10, was 12`, read from the values' JSON trees as Wow writes them. A value of another
 * class is a difference of its own, since two classes may share a JSON shape; maps, and collections, of other
 * classes are compared by their entries.
 */
internal object StructuralDiff {
    /**
     * The difference lines between [expected] and [actual], each path under [path]; empty when they are the same.
     */
    fun of(expected: Any?, actual: Any?, path: String = ""): List<String> =
        buildList { compare(path, expected, actual, this) }

    /** The difference of [expected] and [actual] as a message body, one difference per line. */
    fun describe(expected: Any?, actual: Any?): String = lines(of(expected, actual))

    /** [differences] as a message body, one per line. */
    fun lines(differences: List<String>): String = differences.joinToString(separator = "\n", prefix = "\n") { "  $it" }

    private fun compare(path: String, expected: Any?, actual: Any?, into: MutableList<String>) {
        when {
            expected is List<*> && actual is List<*> -> compareLists(path, expected, actual, into)
            expected != null && actual != null && expected.javaClass != actual.javaClass && !sameKind(expected, actual) ->
                into += "${label(path)}: expected a ${expected.javaClass.simpleName}, was a ${actual.javaClass.simpleName}"
            else -> compareTrees(path, tree(expected), tree(actual), into)
        }
    }

    private fun compareLists(path: String, expected: List<*>, actual: List<*>, into: MutableList<String>) {
        if (expected.size != actual.size) {
            into += "${label(path)}: expected ${expected.size} items, was ${actual.size}"
        }
        expected.indices.intersect(actual.indices).forEach { compare("$path[$it]", expected[it], actual[it], into) }
        actual.indices.drop(expected.size).forEach { into += "$path[$it]: unexpected ${text(actual[it])}" }
        expected.indices.drop(actual.size).forEach { into += "$path[$it]: missing ${text(expected[it])}" }
    }

    private fun compareTrees(path: String, expected: JsonNode, actual: JsonNode, into: MutableList<String>) {
        when {
            expected.isObject && actual.isObject -> {
                val names = (expected.propertyNames() + actual.propertyNames()).distinct()
                names.forEach { name ->
                    val at = if (path.isEmpty()) name else "$path.$name"
                    compareTrees(at, expected.path(name), actual.path(name), into)
                }
            }
            expected.isArray && actual.isArray -> {
                if (expected.size() != actual.size()) {
                    into += "${label(path)}: expected ${expected.size()} items, was ${actual.size()}"
                }
                (0 until minOf(expected.size(), actual.size())).forEach {
                    compareTrees("$path[$it]", expected[it], actual[it], into)
                }
            }
            expected != actual -> into += "${label(path)}: expected ${expected.render()}, was ${actual.render()}"
        }
    }

    /** Whether [expected] and [actual] are both maps, or both collections, whatever their classes: a value's shape. */
    private fun sameKind(expected: Any, actual: Any): Boolean =
        expected is Map<*, *> && actual is Map<*, *> || expected is Collection<*> && actual is Collection<*>

    private fun tree(value: Any?): JsonNode = WowJson.mapper.valueToTree(value)

    private fun text(value: Any?): String = value?.let { "${it.javaClass.simpleName} ${tree(it).render()}" } ?: "null"

    private fun JsonNode.render(): String = if (isMissingNode) "nothing" else toString()

    private fun label(path: String): String = path.ifEmpty { "the value" }
}
