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

/**
 * Compares the JSON Schemas of a baseline Manifest ([baselineDefs]) with the current ones ([currentDefs]), following
 * `$ref`s into each side's `$defs`, so a type whose class was renamed but whose shape was kept is no change.
 *
 * | Change | Kind |
 * | --- | --- |
 * | a property removed (no `x-wow-alias` of a state property keeps its query path) | breaking |
 * | a required property added, or an optional one made required | breaking |
 * | an optional property added, or a required one made optional | additive |
 * | a type narrowed (`["string","null"]` to `"string"`, `number` to `integer`, any to a type) | breaking |
 * | a type widened | additive |
 * | an enum value removed, or an enum imposed | breaking |
 * | an enum value added, or the enum lifted | additive |
 * | an alternative of `anyOf` / `oneOf` no current alternative accepts | breaking |
 * | an alternative added | additive |
 * | additional properties no longer accepted / now accepted | breaking / additive |
 * | an `x-wow-alias` path removed / added | breaking / additive |
 * | a query semantics keyword (`x-wow-sensitive`, `x-wow-mask`, `x-wow-temporal`, …) changed | breaking |
 * | any other validation keyword (`format`, `pattern`, `minimum`, …) added or changed / removed | breaking / additive |
 *
 * Annotations (`description`, `title`, `default`, …) and `x-wow-index` are not contract and are ignored.
 */
internal class SchemaCompatibility(
    private val baselineDefs: Map<String, Any?>,
    private val currentDefs: Map<String, Any?>,
) {
    /**
     * The changes from the schema [baseline] refers to to the one [current] refers to, at [path]. With [queryRoot],
     * the schema is an aggregate state: its properties are query fields at `state.<name>`, which `x-wow-alias` keeps.
     */
    fun compare(path: String, baseline: String, current: String, queryRoot: Boolean = false): List<ManifestChange> {
        val comparison = Comparison()
        comparison.compare(path, refSchema(baseline), refSchema(current), if (queryRoot) STATE else null)
        return comparison.changes
    }

    private fun refSchema(ref: String): Map<String, Any?> = mapOf(REF to ref)

    private inner class Comparison {
        val changes = mutableListOf<ManifestChange>()

        /** The pairs of definitions being compared, so a recursive type ends the comparison instead of looping. */
        private val comparing = mutableSetOf<Pair<String, String>>()

        private fun breaking(path: String, description: String) {
            changes += ManifestChange(ChangeKind.BREAKING, path, description)
        }

        private fun additive(path: String, description: String) {
            changes += ManifestChange(ChangeKind.ADDITIVE, path, description)
        }

        /** [queryPath] is the logical query path of the schema (`state.address`), when it is a state's field. */
        fun compare(path: String, baseline: Any?, current: Any?, queryPath: String?) {
            val oldKey = refKey(baseline)
            val newKey = refKey(current)
            val pair = if (oldKey != null && newKey != null) oldKey to newKey else null
            if (pair != null && !comparing.add(pair)) return
            val old = resolve(baseline, baselineDefs)
            val new = resolve(current, currentDefs)
            if (old === FALSE || new === FALSE) {
                compareFalse(path, old, new)
            } else {
                compareTypes(path, old, new)
                compareEnums(path, old, new)
                compareProperties(path, old, new, queryPath)
                compareChild("$path[]", old, new, ITEMS)
                compareChild("$path{}", old, new, ADDITIONAL_PROPERTIES)
                ALTERNATIVES.forEach { compareAlternatives(path, old, new, it) }
                compareKeywords(path, old, new)
            }
            if (pair != null) comparing.remove(pair)
        }

        private fun compareFalse(path: String, old: Map<String, Any?>, new: Map<String, Any?>) {
            if (old === new) return
            if (new === FALSE) breaking(path, "no value accepted any more") else additive(path, "values accepted")
        }

        private fun compareTypes(path: String, old: Map<String, Any?>, new: Map<String, Any?>) {
            val oldTypes = types(old)
            val newTypes = types(new)
            when {
                oldTypes == newTypes -> Unit
                newTypes == null -> additive(path, "type widened from ${oldTypes.orEmpty().sorted()} to any")
                oldTypes == null -> breaking(path, "type narrowed from any to ${newTypes.sorted()}")
                oldTypes.any { !covers(newTypes, it) } ->
                    breaking(path, "type narrowed from ${oldTypes.sorted()} to ${newTypes.sorted()}")

                newTypes.any { !covers(oldTypes, it) } ->
                    additive(path, "type widened from ${oldTypes.sorted()} to ${newTypes.sorted()}")
            }
        }

        private fun compareEnums(path: String, old: Map<String, Any?>, new: Map<String, Any?>) {
            val oldValues = old[ENUM] as List<*>?
            val newValues = new[ENUM] as List<*>?
            when {
                oldValues == null && newValues == null -> Unit
                oldValues == null -> breaking(path, "values restricted to the enum $newValues")
                newValues == null -> additive(path, "enum lifted")
                else -> {
                    (oldValues - newValues.toSet()).forEach { breaking(path, "enum value $it removed") }
                    (newValues - oldValues.toSet()).forEach { additive(path, "enum value $it added") }
                }
            }
        }

        private fun compareProperties(path: String, old: Map<String, Any?>, new: Map<String, Any?>, queryPath: String?) {
            val oldProperties = properties(old)
            val newProperties = properties(new)
            val oldRequired = required(old)
            val newRequired = required(new)
            val renamedTo = mutableSetOf<String>()
            oldProperties.forEach { (name, schema) ->
                val fieldQueryPath = queryPath?.let { "$it.$name" }
                val target = target(name, fieldQueryPath, newProperties)
                if (target == null) {
                    breaking("$path.$name", "field removed")
                    return@forEach
                }
                if (target != name) {
                    renamedTo += target
                    additive("$path.$name", "field renamed to $target, which keeps $fieldQueryPath as a query alias")
                }
                val targetPath = "$path.$target"
                if (target in newRequired && name !in oldRequired) breaking(targetPath, "field made required")
                if (target !in newRequired && name in oldRequired) additive(targetPath, "field made optional")
                compareAliases(targetPath, schema, newProperties[target])
                compare(targetPath, schema, newProperties[target], queryPath?.let { "$it.$target" })
            }
            (newProperties.keys - oldProperties.keys - renamedTo).forEach { name ->
                if (name in newRequired) {
                    breaking("$path.$name", "required field added")
                } else {
                    additive("$path.$name", "optional field added")
                }
            }
        }

        /** The current property [name] became: itself, or the one whose `x-wow-alias` keeps its [queryPath]. */
        private fun target(name: String, queryPath: String?, properties: Map<String, Any?>): String? = when {
            name in properties -> name
            queryPath == null -> null
            else -> properties.entries.firstOrNull { queryPath in aliases(it.value) }?.key
        }

        private fun compareAliases(path: String, old: Any?, new: Any?) {
            val oldAliases = aliases(old)
            val newAliases = aliases(new)
            (oldAliases - newAliases.toSet()).forEach { breaking(path, "query alias $it removed") }
            (newAliases - oldAliases.toSet()).forEach { additive(path, "query alias $it added") }
        }

        /** A sub-schema keyword (`items`, `additionalProperties`): absent accepts anything, `true` as well. */
        private fun compareChild(path: String, old: Map<String, Any?>, new: Map<String, Any?>, keyword: String) {
            if (keyword !in old && keyword !in new) return
            compare(path, old[keyword] ?: true, new[keyword] ?: true, null)
        }

        /**
         * `anyOf` / `oneOf`: each baseline alternative needs a current one that accepts it without a breaking change;
         * a current alternative that none of the baseline's matches is added. A schema without the keyword is a single
         * alternative accepting anything.
         */
        private fun compareAlternatives(path: String, old: Map<String, Any?>, new: Map<String, Any?>, keyword: String) {
            if (keyword !in old && keyword !in new) return
            val oldAlternatives = old[keyword] as List<*>? ?: listOf(true)
            val newAlternatives = new[keyword] as List<*>? ?: listOf(true)
            val matched = mutableSetOf<Int>()
            oldAlternatives.forEachIndexed { index, alternative ->
                val match = newAlternatives.indices.firstOrNull { candidate ->
                    val trial = Comparison()
                    trial.compare(path, alternative, newAlternatives[candidate], null)
                    trial.changes.none { it.kind == ChangeKind.BREAKING }
                }
                if (match == null) {
                    breaking("$path.$keyword[$index]", "alternative no longer accepted")
                } else {
                    matched += match
                }
            }
            (newAlternatives.indices - matched).forEach { additive("$path.$keyword[$it]", "alternative added") }
        }

        /** The keywords no other rule reads: query semantics must not change; a validation keyword may only go. */
        private fun compareKeywords(path: String, old: Map<String, Any?>, new: Map<String, Any?>) {
            (old.keys + new.keys).filter { it !in HANDLED && it !in IGNORED }.sorted().forEach { keyword ->
                val oldValue = old[keyword]
                val newValue = new[keyword]
                when {
                    oldValue == newValue -> Unit
                    keyword in QUERY_SEMANTICS -> breaking(path, "$keyword changed from $oldValue to $newValue")
                    newValue == null -> additive(path, "$keyword $oldValue lifted")
                    oldValue == null -> breaking(path, "$keyword $newValue imposed")
                    else -> breaking(path, "$keyword changed from $oldValue to $newValue")
                }
            }
        }
    }

    private fun refKey(schema: Any?): String? =
        ((schema as? Map<*, *>)?.get(REF) as? String)?.takeIf { it.startsWith(DEFS_PREFIX) }?.let(Manifest::schemaKey)

    /** The schema as a map: `$ref` replaced by its definition (with the reference's siblings), `true` by `{}`. */
    private fun resolve(schema: Any?, defs: Map<String, Any?>): Map<String, Any?> {
        if (schema == false) return FALSE
        @Suppress("UNCHECKED_CAST")
        val map = schema as? Map<String, Any?> ?: return emptyMap()
        val key = refKey(map) ?: return map
        val definition = resolve(defs[key] ?: true, defs)
        return if (definition === FALSE) FALSE else definition + (map - REF)
    }

    private fun types(schema: Map<String, Any?>): Set<String>? = when (val type = schema[TYPE]) {
        is String -> setOf(type)
        is List<*> -> type.map { it.toString() }.toSet()
        else -> null
    }

    /** Whether a value of [type] is still accepted by [types]: an `integer` is a `number`. */
    private fun covers(types: Set<String>, type: String): Boolean =
        type in types || (type == INTEGER && NUMBER in types)

    @Suppress("UNCHECKED_CAST")
    private fun properties(schema: Map<String, Any?>): Map<String, Any?> =
        schema[PROPERTIES] as Map<String, Any?>? ?: emptyMap()

    private fun required(schema: Map<String, Any?>): Set<String> =
        (schema[REQUIRED] as List<*>?).orEmpty().map { it.toString() }.toSet()

    /** The `x-wow-alias` paths of a property schema, as written on the property (beside a `$ref` too). */
    private fun aliases(schema: Any?): List<String> =
        ((schema as? Map<*, *>)?.get(QueryKeywords.ALIAS_KEYWORD) as List<*>?).orEmpty().map { it.toString() }

    private companion object {
        const val STATE = "state"
        const val REF = "\$ref"
        const val DEFS_PREFIX = "#/\$defs/"
        const val TYPE = "type"
        const val ENUM = "enum"
        const val PROPERTIES = "properties"
        const val REQUIRED = "required"
        const val ITEMS = "items"
        const val ADDITIONAL_PROPERTIES = "additionalProperties"
        const val INTEGER = "integer"
        const val NUMBER = "number"
        val ALTERNATIVES = listOf("anyOf", "oneOf")

        /** The schema that accepts nothing, `false`. */
        val FALSE: Map<String, Any?> = mapOf("not" to emptyMap<String, Any?>())

        val HANDLED = setOf(
            REF, TYPE, ENUM, PROPERTIES, REQUIRED, ITEMS, ADDITIONAL_PROPERTIES, QueryKeywords.ALIAS_KEYWORD,
        ) + ALTERNATIVES

        val IGNORED = setOf(
            "\$schema", "\$id", "\$comment", "\$defs", "title", "description", "default", "examples", "deprecated",
            "readOnly", "writeOnly", QueryKeywords.INDEX_KEYWORD,
        )

        val QUERY_SEMANTICS = setOf(
            QueryKeywords.SENSITIVE_KEYWORD,
            QueryKeywords.MASK_KEYWORD,
            QueryKeywords.TEMPORAL_KEYWORD,
            QueryKeywords.DECIMAL_KEYWORD,
            QueryKeywords.MONEY_KEYWORD,
            QueryKeywords.DURATION_KEYWORD,
            QueryKeywords.REFERENCE_KEYWORD,
        )
    }
}
