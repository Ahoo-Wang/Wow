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

import me.ahoo.wow.manifest.tools.SchemaShapes.ADDITIONAL_PROPERTIES
import me.ahoo.wow.manifest.tools.SchemaShapes.ALL_OF
import me.ahoo.wow.manifest.tools.SchemaShapes.ANNOTATIONS
import me.ahoo.wow.manifest.tools.SchemaShapes.ANY_OF
import me.ahoo.wow.manifest.tools.SchemaShapes.CONST
import me.ahoo.wow.manifest.tools.SchemaShapes.ENUM
import me.ahoo.wow.manifest.tools.SchemaShapes.FALSE
import me.ahoo.wow.manifest.tools.SchemaShapes.FORMAT
import me.ahoo.wow.manifest.tools.SchemaShapes.ITEMS
import me.ahoo.wow.manifest.tools.SchemaShapes.LOWER_BOUNDS
import me.ahoo.wow.manifest.tools.SchemaShapes.ONE_OF
import me.ahoo.wow.manifest.tools.SchemaShapes.PROPERTIES
import me.ahoo.wow.manifest.tools.SchemaShapes.REF
import me.ahoo.wow.manifest.tools.SchemaShapes.REQUIRED
import me.ahoo.wow.manifest.tools.SchemaShapes.TYPE
import me.ahoo.wow.manifest.tools.SchemaShapes.UPPER_BOUNDS
import me.ahoo.wow.manifest.tools.SchemaShapes.aliases
import me.ahoo.wow.manifest.tools.SchemaShapes.covers
import me.ahoo.wow.manifest.tools.SchemaShapes.properties
import me.ahoo.wow.manifest.tools.SchemaShapes.refers
import me.ahoo.wow.manifest.tools.SchemaShapes.required
import me.ahoo.wow.manifest.tools.SchemaShapes.resolve
import me.ahoo.wow.manifest.tools.SchemaShapes.types

/**
 * Compares the JSON Schemas of a baseline Manifest ([baselineDefs]) with the current ones ([currentDefs]), following
 * `$ref`s into each side's `$defs`, so a type whose class was renamed but whose shape was kept is no change.
 *
 * The table is an [input][SchemaDirection.INPUT]'s, a schema old clients send. An [output][SchemaDirection.OUTPUT]'s,
 * a schema old clients read, mirrors it: what accepts more breaks it and what accepts less does not, and an optional
 * field removed is additive. A [stored][SchemaDirection.STORED] one's, the service's own records, is an input's but for
 * the bounds, `pattern` and `multipleOf`, which are additive whichever way they move.
 *
 * | Change | Kind |
 * | --- | --- |
 * | a property removed (no `x-wow-alias` of a state property keeps its query path) | breaking |
 * | a required property added, or an optional one made required | breaking |
 * | an optional property added, or a required one made optional | additive |
 * | a `readOnly` property added (a computed property: written, never read back) | additive |
 * | a `readOnly` property made a field the schema requires / a required field made `readOnly` | breaking / additive |
 * | a type narrowed (`["string","null"]` to `"string"`, `number` to `integer`, any to a type) | breaking |
 * | a type widened | additive |
 * | an enum value removed, or an enum imposed | breaking |
 * | an enum value added, or the enum lifted | additive |
 * | an alternative of `anyOf` / `oneOf` no current alternative accepts | breaking |
 * | an alternative added | additive |
 * | an alternative of `oneOf` added or widened that may accept a value another accepts | breaking |
 * | an alternative of `oneOf` added whose required discriminator no other alternative's values hold | additive |
 * | an output's alternative of `oneOf` removed or narrowed that another overlaps, so a value both took is produced | breaking |
 * | a member of `allOf` changed, each of its changes compared in place | as the change is |
 * | a member of `allOf` added / removed | breaking / additive |
 * | additional properties no longer accepted / now accepted | breaking / additive |
 * | an `x-wow-alias` path removed / added | breaking / additive |
 * | a query semantics keyword (`x-wow-sensitive`, `x-wow-mask`, `x-wow-temporal`, …) changed | breaking |
 * | a lower bound (`minLength`, `minItems`, `minProperties`, `minimum`, `exclusiveMinimum`) raised / lowered | breaking / additive |
 * | an upper bound (`maxLength`, `maxItems`, `maxProperties`, `maximum`, `exclusiveMaximum`) lowered / raised | breaking / additive |
 * | a numeric `format` (`int32`, `int64`, `bigint`, `float`, `decimal`) narrowed / widened | breaking / additive |
 * | any other validation keyword (`format`, `pattern`, …) changed | breaking |
 * | a validation keyword, a bound included, added / removed | breaking / additive |
 *
 * One rule decides each row: a change that refuses a value the old schema accepted (narrowed) breaks what is read, an
 * input and a stored record; a change that allows a value the old schema did not (widened) breaks what is written,
 * an output. A numeric `format` is a range of its `type` ([WireTypes.holds]): `int64` to `int32` (a `Long` to an
 * `Int`) refuses a number old clients sent, and `int32` to `int64` produces one an `Int` reader cannot hold; two
 * formats neither of which holds the other break both ways. A value of `oneOf` must match exactly one alternative, so
 * an alternative added or widened that shares a JSON type with another (an `integer` is a `number`) may make a value
 * old clients sent match two, refused: breaking for what is read, unless a property both require holds values that
 * cannot coincide, a discriminator (`kind: {"const": "wallet"}` beside `"card"`), so a discriminated `oneOf` may grow.
 * `anyOf` takes a value any alternative accepts.
 *
 * What is required depends on who reads. A reader, an input or a stored record read back, never requires a `readOnly`
 * property, whatever `required` lists: it is a computed property, which Wow's JSON writes and reads nothing into, so a
 * body or a record without it reads. A writer's promise, an output, is `required` as it stands: the property is always
 * written. A `readOnly` property removed is still a property removed: a reader that refuses an unknown property, a
 * command's body's, refuses the value an old client hands back.
 *
 * Annotations (`description`, `title`, `default`, …), `x-wow-index` and `x-wow-text` are not contract and are
 * ignored: what a field can be searched by is the capability descriptor's, which a store's mapping decides as much as
 * the marker. So are `x-wow-class`, the class a value is written from (a class renamed with its shape kept is no
 * change), and `uniqueItems`, which says the class is a set: a reader takes the array either way, and the writer never
 * repeats an item.
 */
/**
 * Which way a schema's values flow: an [INPUT] is what old clients send, such as a command or a
 * route's request, so it may only accept more; an [OUTPUT] is what old clients read, such as a route's response, so it
 * may only produce less: a type widened, an enum value added, a field made optional or an alternative added breaks
 * it, and a required field added or an optional one removed does not. A [STORED] schema is what the service's own
 * records were written with, an event's or a state's, which it reads back: an input for the structure, but no stored
 * record is checked against a constraint (a bound, a `pattern`, a `multipleOf`), so a constraint changed either way
 * is additive there; whether it breaks is its readers' to say, an [OUTPUT]'s. An [INPUT] and a [STORED] schema are read,
 * so neither requires a `readOnly` property; an [OUTPUT] requires what `required` lists.
 */
enum class SchemaDirection { INPUT, OUTPUT, STORED }

internal class SchemaCompatibility(
    private val baselineDefs: Map<String, Any?>,
    private val currentDefs: Map<String, Any?>,
) {
    /**
     * The changes from the schema [baseline] refers to to the one [current] refers to, at [path]. With [queryRoot],
     * the schema is an aggregate state: its properties are query fields at `state.<name>`, which `x-wow-alias` keeps.
     */
    fun compare(
        path: String,
        baseline: String,
        current: String,
        queryRoot: Boolean = false,
        direction: SchemaDirection = SchemaDirection.INPUT,
    ): List<ManifestChange> {
        val comparison = Comparison(direction)
        comparison.compare(path, refSchema(baseline), refSchema(current), if (queryRoot) STATE else null)
        return comparison.changes
    }

    /**
     * The changes from the inline schema [baseline] to [current] at [path], each `$ref` followed into its side's
     * `$defs`: a route's body or answer, a reference or an array of one, compared item by item.
     */
    fun compareInline(
        path: String,
        baseline: Map<String, Any?>,
        current: Map<String, Any?>,
        direction: SchemaDirection,
    ): List<ManifestChange> {
        val comparison = Comparison(direction)
        comparison.compare(path, baseline, current, null)
        return comparison.changes
    }

    private fun refSchema(ref: String): Map<String, Any?> = mapOf(REF to ref)

    /**
     * One comparison in [direction]. [comparing] holds the pairs of references being compared, each as written (a
     * nullable reference is another pair than the bare one), so a recursive type ends the comparison instead of
     * looping; a trial of one alternative starts with the pairs its enclosing comparison holds.
     */
    private inner class Comparison(
        private val direction: SchemaDirection,
        private val comparing: MutableSet<Pair<Any?, Any?>> = mutableSetOf(),
    ) {
        val changes = mutableListOf<ManifestChange>()

        /**
         * A change that accepts fewer values: breaking for an input, which old clients send; additive for an output, and
         * for stored records when it is a [constraint], which no stored record is checked against.
         */
        private fun narrowed(path: String, description: String, constraint: Boolean = false) =
            if (direction == SchemaDirection.OUTPUT || constraint && direction == SchemaDirection.STORED) {
                additive(path, description)
            } else {
                breaking(path, description)
            }

        /** A change that accepts more values: additive for an input; breaking for an output, which old clients read. */
        private fun widened(path: String, description: String) =
            if (direction == SchemaDirection.OUTPUT) breaking(path, description) else additive(path, description)

        private fun breaking(path: String, description: String) {
            changes += ManifestChange(ChangeKind.BREAKING, path, description)
        }

        private fun additive(path: String, description: String) {
            changes += ManifestChange(ChangeKind.ADDITIVE, path, description)
        }

        /** [queryPath] is the logical query path of the schema (`state.address`), when it is a state's field. */
        fun compare(path: String, baseline: Any?, current: Any?, queryPath: String?) {
            val pair = if (refers(baseline) && refers(current)) baseline to current else null
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
                compareMembers(path, old, new, queryPath)
                compareKeywords(path, old, new)
            }
            if (pair != null) comparing.remove(pair)
        }

        private fun compareFalse(path: String, old: Map<String, Any?>, new: Map<String, Any?>) {
            if (old === new) return
            if (new === FALSE) narrowed(path, "no value accepted any more") else widened(path, "values accepted")
        }

        private fun compareTypes(path: String, old: Map<String, Any?>, new: Map<String, Any?>) {
            val oldTypes = types(old)
            val newTypes = types(new)
            when {
                oldTypes == newTypes -> Unit
                newTypes == null -> widened(path, "type widened from ${oldTypes.orEmpty().sorted()} to any")
                oldTypes == null -> narrowed(path, "type narrowed from any to ${newTypes.sorted()}")
                oldTypes.any { !covers(newTypes, it) } ->
                    if (direction == SchemaDirection.OUTPUT && newTypes.any { !covers(oldTypes, it) }) {
                        breaking(path, "type changed from ${oldTypes.sorted()} to ${newTypes.sorted()}")
                    } else {
                        narrowed(path, "type narrowed from ${oldTypes.sorted()} to ${newTypes.sorted()}")
                    }

                newTypes.any { !covers(oldTypes, it) } ->
                    widened(path, "type widened from ${oldTypes.sorted()} to ${newTypes.sorted()}")
            }
        }

        private fun compareEnums(path: String, old: Map<String, Any?>, new: Map<String, Any?>) {
            val oldValues = old[ENUM] as List<*>?
            val newValues = new[ENUM] as List<*>?
            when {
                oldValues == null && newValues == null -> Unit
                oldValues == null -> narrowed(path, "values restricted to the enum $newValues")
                newValues == null -> widened(path, "enum lifted")
                else -> {
                    (oldValues - newValues.toSet()).forEach { narrowed(path, "enum value $it removed") }
                    (newValues - oldValues.toSet()).forEach { widened(path, "enum value $it added") }
                }
            }
        }

        private fun compareProperties(path: String, old: Map<String, Any?>, new: Map<String, Any?>, queryPath: String?) {
            val oldProperties = properties(old)
            val newProperties = properties(new)
            val oldRequired = requiredOf(old)
            val newRequired = requiredOf(new)
            val renamedTo = mutableSetOf<String>()
            oldProperties.forEach { (name, schema) ->
                val fieldQueryPath = queryPath?.let { "$it.$name" }
                val target = target(name, fieldQueryPath, newProperties)
                if (target == null) {
                    // An output's optional field was never sure to be there: a reader already does without it.
                    if (direction == SchemaDirection.OUTPUT && name !in oldRequired) {
                        additive("$path.$name", "optional field removed")
                    } else {
                        breaking("$path.$name", "field removed")
                    }
                    return@forEach
                }
                if (target != name) {
                    renamedTo += target
                    additive("$path.$name", "field renamed to $target, which keeps $fieldQueryPath as a query alias")
                }
                val targetPath = "$path.$target"
                if (target in newRequired && name !in oldRequired) narrowed(targetPath, "field made required")
                if (target !in newRequired && name in oldRequired) widened(targetPath, "field made optional")
                compareAliases(targetPath, schema, newProperties[target])
                compare(targetPath, schema, newProperties[target], queryPath?.let { "$it.$target" })
            }
            (newProperties.keys - oldProperties.keys - renamedTo).forEach { name ->
                if (name in newRequired) {
                    narrowed("$path.$name", "required field added")
                } else {
                    additive("$path.$name", "optional field added")
                }
            }
        }

        /**
         * The properties [schema] requires on the side this comparison judges: what `required` lists, but for a `readOnly`
         * property when the side reads ([SchemaDirection.INPUT], [SchemaDirection.STORED]), which reads nothing into it.
         */
        private fun requiredOf(schema: Map<String, Any?>): Set<String> {
            val required = required(schema)
            if (direction == SchemaDirection.OUTPUT) return required
            val properties = properties(schema)
            return required.filterTo(mutableSetOf()) { (properties[it] as? Map<*, *>)?.get(READ_ONLY) != true }
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
            // The changes of [old] to [new], null when one breaks; the pairs compared around it stay compared, so a
            // recursive alternative ends instead of looping.
            val trial = { old: Any?, new: Any? ->
                val trial = Comparison(direction, comparing.toMutableSet())
                trial.compare(path, old, new, null)
                trial.changes.takeIf { changes -> changes.none { it.kind == ChangeKind.BREAKING } }
            }
            if (direction == SchemaDirection.OUTPUT) {
                compareProduced("$path.$keyword", keyword == ONE_OF, oldAlternatives, newAlternatives, trial)
                return
            }
            // Each current alternative an old one matched, and whether it now accepts the old one's values unchanged.
            val matched = mutableMapOf<Int, Boolean>()
            oldAlternatives.forEachIndexed { index, alternative ->
                val match = newAlternatives.indices.firstNotNullOfOrNull { candidate ->
                    trial(alternative, newAlternatives[candidate])?.let { candidate to it.isEmpty() }
                }
                if (match == null) {
                    breaking("$path.$keyword[$index]", "alternative no longer accepted")
                } else {
                    // Unchanged only when every old alternative it matched is.
                    matched.merge(match.first, match.second, Boolean::and)
                }
            }
            (newAlternatives.indices - matched.keys).forEach { additive("$path.$keyword[$it]", "alternative added") }
            if (keyword == ONE_OF) {
                compareExclusive(path, newAlternatives, newAlternatives.indices.filter { matched[it] != true })
            }
        }

        /**
         * An output's alternatives at [path]: each value a current alternative produces needs an old alternative that
         * accepts it, an unchanged one taken first. Of a `oneOf` ([exclusive]) old clients refuse a value two old
         * alternatives accept (#6285), so the current alternative also needs each other old one it may share a value
         * with ([overlap]) to be [taken] whole by another current alternative, which the current `oneOf` then gives
         * that shared value to instead: removing or narrowing an alternative another overlaps breaks.
         */
        private fun compareProduced(
            path: String,
            exclusive: Boolean,
            oldAlternatives: List<*>,
            newAlternatives: List<*>,
            trial: (Any?, Any?) -> List<ManifestChange>?,
        ) {
            val matched = mutableSetOf<Int>()
            newAlternatives.forEachIndexed { index, alternative ->
                val candidates = oldAlternatives.indices.mapNotNull { old ->
                    trial(oldAlternatives[old], alternative)?.let { old to it.isEmpty() }
                }
                val match = (candidates.firstOrNull { (_, unchanged) -> unchanged } ?: candidates.firstOrNull())?.first
                if (match == null) {
                    breaking("$path[$index]", "alternative added")
                    return@forEachIndexed
                }
                matched += match
                if (!exclusive) return@forEachIndexed
                oldAlternatives.indices.filter { old ->
                    old != match && overlap(oldAlternatives[old], baselineDefs, alternative, currentDefs) &&
                        newAlternatives.indices.none { it != index && taken(path, oldAlternatives[old], newAlternatives[it]) }
                }.forEach { old ->
                    breaking("$path[$index]", "alternative produces a value old alternative $old accepts too: refused")
                }
            }
            (oldAlternatives.indices - matched).forEach { additive("$path[$it]", "alternative no longer produced") }
        }

        /** Whether [new] accepts every value [old] accepts: compared as an input, nothing breaks. */
        private fun taken(path: String, old: Any?, new: Any?): Boolean {
            val trial = Comparison(SchemaDirection.INPUT, comparing.toMutableSet())
            trial.compare(path, old, new, null)
            return trial.changes.none { it.kind == ChangeKind.BREAKING }
        }

        /**
         * A value of `oneOf` matches exactly one of its [alternatives]: each one [grown] (added, or accepting more) that
         * may accept a value another one accepts breaks a value old clients sent, which now matches both. Two
         * alternatives are told apart by their JSON types, or by a property both require whose values cannot coincide
         * (a subtype's discriminator, `kind: {"const": "card"}` against `"bank"`); any other difference is not proven.
         */
        private fun compareExclusive(path: String, alternatives: List<*>, grown: List<Int>) {
            grown.forEach { index ->
                alternatives.indices.filter { it != index && (it !in grown || it > index) }
                    .filter { overlap(alternatives[index], currentDefs, alternatives[it], currentDefs) }
                    .forEach {
                        breaking(
                            "$path.$ONE_OF[$index]",
                            "alternative overlaps alternative $it: a value both accept is refused"
                        )
                    }
            }
        }

        /**
         * Whether two alternatives may accept one value: no property both require has values that cannot coincide
         * ([discriminators]), and they share a JSON type (an `integer` is a `number`).
         */
        private fun overlap(
            left: Any?,
            leftDefs: Map<String, Any?>,
            right: Any?,
            rightDefs: Map<String, Any?>,
        ): Boolean {
            val leftSchema = resolve(left, leftDefs)
            val rightSchema = resolve(right, rightDefs)
            val rightValues = discriminators(rightSchema, rightDefs)
            val apart = discriminators(leftSchema, leftDefs).any { (name, values) ->
                rightValues[name]?.let { values.intersect(it).isEmpty() } == true
            }
            if (apart) return false
            val leftTypes = types(leftSchema) ?: return true
            val rightTypes = types(rightSchema) ?: return true
            return leftTypes.any { covers(rightTypes, it) } || rightTypes.any { covers(leftTypes, it) }
        }

        /** The values each property [schema] requires is held to, by its `const` or its `enum`: a discriminator. */
        private fun discriminators(schema: Map<String, Any?>, defs: Map<String, Any?>): Map<String, Set<Any?>> =
            required(schema).mapNotNull { name ->
                val property = resolve(properties(schema)[name], defs)
                val values = if (CONST in property) setOf(property[CONST]) else (property[ENUM] as List<*>?)?.toSet()
                values?.let { name to it }
            }.toMap()

        /**
         * `allOf`: a value is accepted by every member, so a change to one member is a change to the whole, compared in
         * place, member by member in their order (a polymorphic class's own schema, then its discriminator, as the
         * generator writes them). A member added is one more constraint; a member removed, one fewer. A schema without
         * the keyword has no member. Members that only moved are read as changed: reported, never missed.
         */
        private fun compareMembers(path: String, old: Map<String, Any?>, new: Map<String, Any?>, queryPath: String?) {
            if (ALL_OF !in old && ALL_OF !in new) return
            val oldMembers = old[ALL_OF] as List<*>? ?: emptyList<Any?>()
            val newMembers = new[ALL_OF] as List<*>? ?: emptyList<Any?>()
            oldMembers.zip(newMembers).forEachIndexed { index, (oldMember, newMember) ->
                compare("$path.$ALL_OF[$index]", oldMember, newMember, queryPath)
            }
            newMembers.indices.drop(oldMembers.size).forEach { narrowed("$path.$ALL_OF[$it]", "member added") }
            oldMembers.indices.drop(newMembers.size).forEach { widened("$path.$ALL_OF[$it]", "member removed") }
        }

        /** The keywords no other rule reads: query semantics must not change; a validation keyword may only go. */
        private fun compareKeywords(path: String, old: Map<String, Any?>, new: Map<String, Any?>) {
            (old.keys + new.keys).filter { it !in HANDLED && it !in IGNORED }.sorted().forEach { keyword ->
                val oldValue = old[keyword]
                val newValue = new[keyword]
                when {
                    oldValue == newValue -> Unit
                    keyword == FORMAT && listOfNotNull(oldValue, newValue).all(WireTypes::isNumeric) ->
                        compareRange(path, oldValue as String?, newValue as String?)
                    keyword in QUERY_SEMANTICS -> breaking(path, "$keyword changed from $oldValue to $newValue")
                    newValue == null -> widened(path, "$keyword $oldValue lifted")
                    oldValue == null -> narrowed(path, "$keyword $newValue imposed", keyword in CONSTRAINTS)
                    else -> compareBound(path, keyword, oldValue, newValue)
                }
            }
        }

        /** A numeric format changed: a range narrowed, widened, or neither holding the other ([WireTypes.holds]). */
        private fun compareRange(path: String, old: String?, new: String?) {
            val description = "format changed from ${old ?: "any"} to ${new ?: "any"}"
            when {
                WireTypes.holds(new, old) -> widened(path, description)
                WireTypes.holds(old, new) -> narrowed(path, description)
                else -> breaking(path, description)
            }
        }

        /**
         * A bound moved: a lower one raised, or an upper one lowered, accepts fewer values (the Jakarta constraints of a
         * command's field, which old clients send); the other way, more. Any other keyword changed is breaking.
         */
        private fun compareBound(path: String, keyword: String, oldValue: Any, newValue: Any) {
            val old = (oldValue as? Number)?.toString()?.toBigDecimalOrNull()
            val new = (newValue as? Number)?.toString()?.toBigDecimalOrNull()
            val lower = keyword in LOWER_BOUNDS
            val bound = lower || keyword in UPPER_BOUNDS
            if (!bound || old == null || new == null) {
                val description = "$keyword changed from $oldValue to $newValue"
                if (direction == SchemaDirection.STORED && keyword in CONSTRAINTS) {
                    additive(path, description)
                } else {
                    breaking(path, description)
                }
            } else if (old.compareTo(new) == 0) {
                // The same bound, written as another number (1 and 1.0).
                return
            } else if ((new > old) == lower) {
                narrowed(path, "$keyword ${if (lower) "raised" else "lowered"} from $oldValue to $newValue", true)
            } else {
                widened(path, "$keyword ${if (lower) "lowered" else "raised"} from $oldValue to $newValue")
            }
        }
    }

    private companion object {
        const val STATE = "state"
        const val READ_ONLY = "readOnly"
        val ALTERNATIVES = listOf(ANY_OF, ONE_OF)

        val HANDLED = setOf(
            REF, TYPE, ENUM, PROPERTIES, REQUIRED, ITEMS, ADDITIONAL_PROPERTIES, ALL_OF, QueryKeywords.ALIAS_KEYWORD,
        ) + ALTERNATIVES

        val IGNORED = ANNOTATIONS + setOf(
            "\$schema", "\$id", "\$defs", QueryKeywords.INDEX_KEYWORD, QueryKeywords.TEXT_KEYWORD,
            WireTypes.CLASS_KEYWORD, WireTypes.UNIQUE_ITEMS,
        )

        /**
         * The constraints a value is checked against when it is written, never when a stored record is read back
         * ([SchemaDirection.STORED]). A `format` is not among them: it says how the value is decoded.
         */
        val CONSTRAINTS = LOWER_BOUNDS + UPPER_BOUNDS + setOf("pattern", "multipleOf")

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
