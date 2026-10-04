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

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal

/**
 * A storage-neutral family of native types a logical value may be stored as. A storage adapter maps each family to
 * its own native types (BSON types, Elasticsearch field kinds); which families a logical value needs for a capability
 * is the Catalog's decision ([storageFamilies]).
 */
@WowSpi
enum class QueryStorageFamily {
    /** Any string representation, analyzed text included. */
    STRING,

    /** A string kept verbatim and ordered by its characters, as a formatted date needs for a range. */
    EXACT_STRING,

    /** Any integral number. */
    INTEGRAL,

    /** A signed integral number, as an epoch needs to hold an instant before 1970. */
    SIGNED_INTEGRAL,

    /** Any number, integral or not. */
    NUMERIC,

    BOOLEAN,

    /** A native date or timestamp. */
    DATE,
}

/**
 * Where a storage still departs from the strict table of [storageFamilies]. Each rule is a known divergence between
 * storages, kept so that every adapter reproduces today's capabilities exactly; the defaults are the strict table.
 */
@WowSpi
data class QueryStorageFamilyRules(
    /**
     * Whether a [Temporal.Date] value supports the capabilities that compare operands to it (exact match, literal
     * match, range, numeric aggregation); `false` for a storage whose compilers cannot send an operand as a date.
     */
    val dateOperands: Boolean = true,
    /**
     * Whether a capability applies only when every declared value type fits it: a [Temporal.Date] declared as a
     * STRING, a [Temporal.Epoch] declared as an INTEGER, and a range over either numbers or strings, not a mix.
     * With `false` each declared value type contributes its own family instead.
     */
    val strictValueTypes: Boolean = true,
) {
    companion object {
        /** The strict table: every rule on. */
        @JvmField
        val STRICT = QueryStorageFamilyRules()
    }
}

/**
 * The native type families this scalar value needs for [capability]: one set per declared alternative, and storage
 * proves the capability when its native types cover every set and fall in some set. An empty list, or a list with an
 * empty set, means no storage can prove the capability for this value.
 *
 * Only the value's [semantic type][QueryValueSchema.semanticType] and [value types][QueryValueSchema.valueTypes] are
 * read. Capabilities that do not compare values ([QueryCapability.PRESENCE], [QueryCapability.ELEMENT_SCOPE]) have no
 * families: they are storage structure, not value types.
 */
@WowSpi
@Suppress("CyclomaticComplexMethod") // One exhaustive table: capability by logical value.
fun QueryValueSchema.storageFamilies(
    capability: QueryCapability,
    rules: QueryStorageFamilyRules = QueryStorageFamilyRules.STRICT,
): List<Set<QueryStorageFamily>> {
    val semantic = semanticType
    if (!rules.dateOperands && semantic == Temporal.Date && capability in DATE_OPERAND_CAPABILITIES) return emptyList()
    val instant = semantic == Temporal.Date || semantic is Temporal.Epoch
    return when (capability) {
        QueryCapability.EXACT_MATCH,
        QueryCapability.SORT,
        QueryCapability.CURSOR_SORT,
        QueryCapability.AGGREGATE_TERMS,
        -> if (instant) temporalFamilies(rules) else valueTypes.map { it.family() }
        QueryCapability.LITERAL_MATCH,
        QueryCapability.FULL_TEXT_TERMS,
        QueryCapability.FULL_TEXT_PHRASE,
        -> if (instant) emptyList() else valueTypes.map { it.stringFamily() }
        QueryCapability.RANGE -> when {
            instant -> temporalFamilies(rules)
            semantic is Temporal.Formatted && valueTypes == setOf(QueryValueType.STRING) -> ONLY_EXACT_STRING
            semantic is Temporal.Formatted && rules.strictValueTypes -> emptyList()
            rules.strictValueTypes -> strictRangeFamilies()
            else -> valueTypes.map { it.rangeFamily() }
        }
        QueryCapability.AGGREGATE_NUMERIC -> when {
            semantic == Temporal.Date -> emptyList()
            semantic is Temporal.Epoch && valueTypes == setOf(QueryValueType.INTEGER) -> ONLY_SIGNED_INTEGRAL
            semantic is Temporal.Epoch && rules.strictValueTypes -> emptyList()
            else -> valueTypes.map { it.numericFamily() }
        }
        QueryCapability.AGGREGATE_TEMPORAL -> temporalFamilies(rules)
        QueryCapability.PRESENCE, QueryCapability.ELEMENT_SCOPE -> emptyList()
    }
}

/** A date is stored as a native date; an epoch as a signed integral. Strictly, only as the value type they declare. */
private fun QueryValueSchema.temporalFamilies(rules: QueryStorageFamilyRules): List<Set<QueryStorageFamily>> =
    when (semanticType) {
        Temporal.Date -> if (!rules.strictValueTypes || valueTypes == setOf(QueryValueType.STRING)) ONLY_DATE else emptyList()
        is Temporal.Epoch -> if (!rules.strictValueTypes || valueTypes == setOf(QueryValueType.INTEGER)) {
            ONLY_SIGNED_INTEGRAL
        } else {
            emptyList()
        }
        else -> emptyList()
    }

/** A strict range reads numbers, or strings, never a mix: numeric when every type is numeric, else only a string. */
private fun QueryValueSchema.strictRangeFamilies(): List<Set<QueryStorageFamily>> = when {
    valueTypes.all { it == QueryValueType.INTEGER || it == QueryValueType.DECIMAL } -> valueTypes.map { it.numericFamily() }
    valueTypes == setOf(QueryValueType.STRING) -> ONLY_STRING
    else -> emptyList()
}

private fun QueryValueType.family(): Set<QueryStorageFamily> = when (this) {
    QueryValueType.STRING -> setOf(QueryStorageFamily.STRING)
    QueryValueType.BOOLEAN -> setOf(QueryStorageFamily.BOOLEAN)
    else -> numericFamily()
}

private fun QueryValueType.stringFamily(): Set<QueryStorageFamily> =
    if (this == QueryValueType.STRING) setOf(QueryStorageFamily.STRING) else emptySet()

private fun QueryValueType.rangeFamily(): Set<QueryStorageFamily> =
    if (this == QueryValueType.STRING) setOf(QueryStorageFamily.STRING) else numericFamily()

private fun QueryValueType.numericFamily(): Set<QueryStorageFamily> = when (this) {
    QueryValueType.INTEGER -> setOf(QueryStorageFamily.INTEGRAL)
    QueryValueType.DECIMAL -> setOf(QueryStorageFamily.NUMERIC)
    else -> emptySet()
}

private val ONLY_STRING = listOf(setOf(QueryStorageFamily.STRING))
private val ONLY_EXACT_STRING = listOf(setOf(QueryStorageFamily.EXACT_STRING))
private val ONLY_SIGNED_INTEGRAL = listOf(setOf(QueryStorageFamily.SIGNED_INTEGRAL))
private val ONLY_DATE = listOf(setOf(QueryStorageFamily.DATE))

private val DATE_OPERAND_CAPABILITIES = setOf(
    QueryCapability.EXACT_MATCH,
    QueryCapability.LITERAL_MATCH,
    QueryCapability.RANGE,
    QueryCapability.AGGREGATE_NUMERIC,
)
