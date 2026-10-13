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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.manifest.Manifest
import java.security.MessageDigest

/**
 * The query indexes an aggregate state declares with `@QueryIndex` (decision D151), written to the root of the state
 * schema as `"x-wow-index": [{"name": "…", "keys": ["state.status", "state.createdAt"]}]`: one entry per
 * annotation, the class-level ones first, then the property ones in the order of the schema's properties.
 *
 * - On a state property the property leads: its first key is `state.<the property's JSON name>`, and `on` stays
 *   empty. On the state class `on` is required and leads. `with` lists the further keys, in order.
 * - Every key is a logical path of the snapshot query model: a system field ([SYSTEM_FIELDS], decision D128) or a
 *   `state.` path the state schema holds, through nested objects, array items, `$ref`s and unions. No key repeats in
 *   one index.
 * - An empty `name` is derived: `ix_<aggregate>_<keys>`, each in snake case with its dots as `_`; one longer than 63
 *   characters, PostgreSQL's identifier limit, keeps its first 54 and appends `_` and 8 hex digits of the SHA-256 of
 *   the whole name. Names are unique within a bounded context.
 * - A state class whose schema declares indexes is the state of one aggregate only, which the derived names name.
 */
internal object QueryIndexes {
    val SYSTEM_FIELDS = setOf(
        "tenantId",
        "aggregateId",
        "version",
        "ownerId",
        "spaceId",
        "deleted",
        "tags",
        "firstEventTime",
        "lastEventTime",
    )
    const val MAX_NAME_LENGTH = 63
    private const val HASH_LENGTH = 8
    private const val STATE_PREFIX = "state."
    private const val MAX_SCHEMA_DEPTH = 64

    /** The indexes [aggregate]'s [state] declares, as the `x-wow-index` entries; validated against [defs]. */
    fun of(
        aggregate: String,
        state: Class<*>,
        propertyIndexes: List<PropertyIndex>,
        defs: Map<String, Any?>,
    ): List<Map<String, Any>> {
        val classIndexes = QueryAnnotations.named(state, QueryAnnotations.QUERY_INDEX).map { index ->
            val on = index.string("on")
            check(on.isNotEmpty()) {
                "@QueryIndex on the state class ${state.name} must name its leading key in on."
            }
            index to on
        }
        val declared = classIndexes + propertyIndexes.map { it.index to it.path }
        return declared.map { (index, leading) ->
            val keys = listOf(leading) + index.strings("with")
            val declarer = "@QueryIndex(${index.string("name").ifEmpty { keys.joinToString() }}) of ${state.name}"
            keys.groupBy { it }.filterValues { it.size > 1 }.keys.let { repeated ->
                check(repeated.isEmpty()) { "$declarer repeats the key ${repeated.joinToString()}." }
            }
            keys.forEach { key ->
                check(isKnownPath(key, state, defs)) {
                    "$declarer has the unknown key [$key]: a key is one of ${SYSTEM_FIELDS.joinToString()} or a " +
                        "state.* path of the state schema."
                }
            }
            mapOf("name" to index.string("name").ifEmpty { defaultName(aggregate, keys) }, "keys" to keys)
        }
    }

    /** The derived name of an index of [aggregate] on [keys]: at most [MAX_NAME_LENGTH] characters. */
    fun defaultName(aggregate: String, keys: List<String>): String {
        val name = (listOf("ix", aggregate) + keys).joinToString("_") { it.snakeCase() }
        if (name.length <= MAX_NAME_LENGTH) return name
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
        return name.take(MAX_NAME_LENGTH - HASH_LENGTH - 1).trimEnd('_') + "_" + hash
    }

    private fun String.snakeCase(): String =
        replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .lowercase()

    private fun isKnownPath(key: String, state: Class<*>, defs: Map<String, Any?>): Boolean {
        if (key in SYSTEM_FIELDS) return true
        if (!key.startsWith(STATE_PREFIX)) return false
        val segments = key.removePrefix(STATE_PREFIX).split('.')
        if (segments.any { it.isEmpty() }) return false
        return holds(defs[state.name], segments, defs, 0)
    }

    /** Whether [schema] holds the property path [segments]. */
    private fun holds(schema: Any?, segments: List<String>, defs: Map<String, Any?>, depth: Int): Boolean {
        if (schema !is Map<*, *> || depth > MAX_SCHEMA_DEPTH) return false
        if (segments.isEmpty()) return true
        val next = depth + 1
        (schema["\$ref"] as? String)?.let { ref ->
            if (holds(defs[Manifest.schemaKey(ref)], segments, defs, next)) return true
        }
        val property = (schema["properties"] as? Map<*, *>)?.get(segments.first())
        if (property != null && holds(property, segments.drop(1), defs, next)) return true
        if (holds(schema["items"], segments, defs, next)) return true
        return UNIONS.any { union ->
            (schema[union] as? List<*>).orEmpty().any { holds(it, segments, defs, next) }
        }
    }

    private val UNIONS = listOf("anyOf", "oneOf", "allOf")
}
