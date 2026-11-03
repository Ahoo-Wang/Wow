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

package me.ahoo.wow.spi.access

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * An aggregate's access attributes as every record stores them (a stream, a snapshot, a published state event): by
 * name, a single attribute's value or null, a multiple one's values as a list, sorted and distinct; each value a
 * `String` (an enum by its JSON name), a `Long` or a `Boolean`. A record has this one form whatever wrote or read it,
 * so two records hold the same attributes exactly when their maps are equal.
 *
 * A stream's attributes, and a published state event's, also hold the built-in ones, [OWNER] and [SPACE], from the
 * scope after the commit: they follow a transfer as the declared ones follow the state. A snapshot's hold the declared
 * ones only; its owner and space are fields of their own.
 */
@InternalWowApi
object AccessValues {
    /** The built-in attribute of the aggregate's owner. */
    const val OWNER: String = "owner"

    /** The built-in attribute of the aggregate's space. */
    const val SPACE: String = "space"

    private val BUILT_IN = setOf(OWNER, SPACE)

    private val ORDER: Comparator<Any> = compareBy<Any> { rank(it) }.thenComparator { left, right ->
        @Suppress("UNCHECKED_CAST") // Values of one rank are of one comparable class.
        (left as Comparable<Any>).compareTo(right)
    }

    /**
     * The attributes [node], a JSON object of them, holds, in this form: an integral number as a `Long`, an array as
     * its distinct values in order; null when [node] is absent or JSON null.
     */
    fun of(node: JsonNode?): Map<String, Any?>? {
        if (node == null || node.isNull || node.isMissingNode) {
            return null
        }
        require(node.isObject) { "Access attributes are a JSON object, not $node." }
        val values = LinkedHashMap<String, Any?>(node.size() * 2)
        node.properties().forEach { (name, value) -> values[name] = valueOf(name, value) }
        return values
    }

    /**
     * [values], attributes a store's driver decoded (a number of any integral class, a collection of values), in
     * this form.
     */
    fun normalized(values: Map<String, *>): Map<String, Any?> {
        val access = LinkedHashMap<String, Any?>(values.size * 2)
        values.forEach { (name, value) ->
            access[name] = when (value) {
                null -> null
                is Collection<*> -> value.filterNotNull().map { scalarOf(name, it) }.distinct().sortedWith(ORDER)
                else -> scalarOf(name, value)
            }
        }
        return access
    }

    /** The attributes [text], the JSON text of an object of them, holds; null for null text. */
    fun parse(text: String?): Map<String, Any?>? = text?.let { of(WowJson.mapper.readTree(it)) }

    /** [access] as a JSON object. */
    fun json(access: Map<String, Any?>): ObjectNode = WowJson.mapper.createObjectNode().also { node ->
        access.forEach { (name, value) ->
            when (value) {
                null -> node.putNull(name)
                is List<*> -> node.putArray(name).also { array -> value.forEach { array.add(it) } }
                else -> node.set(name, WowJson.mapper.valueToTree<JsonNode>(value))
            }
        }
    }

    /** [access] as JSON text. */
    fun text(access: Map<String, Any?>): String = WowJson.mapper.writeValueAsString(json(access))

    /**
     * The attributes of a stream: the built-in ones of [scope], then [declared], the declared ones in this form; what
     * a commit records, and what a stamp writes over the earlier streams.
     */
    fun ofStream(declared: Map<String, Any?>, scope: Scope): Map<String, Any?> =
        LinkedHashMap<String, Any?>((declared.size + BUILT_IN.size) * 2).apply {
            put(OWNER, scope.ownerId)
            put(SPACE, scope.spaceId)
            putAll(declared)
        }

    /** [access] without the built-in attributes: what a snapshot keeps. */
    fun declared(access: Map<String, Any?>): Map<String, Any?> = access.filterKeys { it !in BUILT_IN }

    private fun ArrayNode.add(value: Any?) {
        when (value) {
            is String -> add(value)
            is Long -> add(value)
            is Boolean -> add(value)
            else -> throw IllegalArgumentException("An access value is a String, a Long or a Boolean, not $value.")
        }
    }

    private fun valueOf(name: String, value: JsonNode): Any? = when {
        value.isNull -> null
        value.isArray -> value.iterator().asSequence()
            .filterNot { it.isNull }
            .map { scalarOf(name, it) }
            .distinct()
            .sortedWith(ORDER)
            .toList()

        else -> scalarOf(name, value)
    }

    private fun scalarOf(name: String, value: JsonNode): Any = when {
        value.isString -> value.asString()
        value.isIntegralNumber -> value.longValue()
        value.isBoolean -> value.booleanValue()
        else -> throw IllegalArgumentException(
            "The access attribute $name holds $value: a string, an integer or a boolean is required.",
        )
    }

    private fun scalarOf(name: String, value: Any): Any = when (value) {
        is String, is Boolean -> value
        is Long -> value
        is Int, is Short, is Byte -> (value as Number).toLong()
        else -> throw IllegalArgumentException(
            "The access attribute $name holds $value: a string, an integer or a boolean is required.",
        )
    }

    private fun rank(value: Any): Int = when (value) {
        is Boolean -> 0
        is Long -> 1
        else -> 2
    }
}
