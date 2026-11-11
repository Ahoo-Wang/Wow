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

package me.ahoo.wow.elasticsearch.query.schema

import co.elastic.clients.elasticsearch._types.mapping.Property
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappedField
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryStorageFamily
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.alternativesOrSelf
import me.ahoo.wow.query.schema.operationValues
import me.ahoo.wow.query.schema.storageFamilies

@Suppress("CyclomaticComplexMethod") // One exhaustive table of native capability requirements.
internal fun ElasticsearchMappedField.supports(
    capability: QueryCapability,
    logical: QueryValueSchema,
    flattenedDescendant: Boolean = false,
): Boolean {
    if (!enabled || nullValue != null || !logical.provesIndexedValues(ignoreAbove)) return false
    if (normalizer != null && capability in NORMALIZED_VALUE_CAPABILITIES) return false
    val executable = when (capability) {
        // `_ignored` names no value a flattened field drops, so presence could not see it (see ElasticsearchFilterCompiler).
        // An object is present when any of its fields is: `exists` reads an object path so (a snapshot's operator).
        QueryCapability.PRESENCE ->
            (queryable && (kind != Property.Kind.Flattened || ignoreAbove == null)) || kind == Property.Kind.Object
        QueryCapability.EXACT_MATCH -> queryable && kind in EXACT_KINDS
        QueryCapability.LITERAL_MATCH -> indexed && kind in LITERAL_KINDS
        QueryCapability.RANGE -> queryable && kind in RANGE_KINDS
        QueryCapability.FULL_TEXT_TERMS -> indexed && kind in SEARCH_KINDS
        QueryCapability.FULL_TEXT_PHRASE -> indexed && ignoreAbove == null && kind in PHRASE_SEARCH_KINDS
        QueryCapability.SORT -> sortable && (kind in EXACT_KINDS || (indexed && kind == Property.Kind.Text))
        QueryCapability.CURSOR_SORT -> {
            sortable && (kind in CURSOR_SORT_KINDS || (indexed && kind == Property.Kind.Text))
        }
        QueryCapability.ELEMENT_SCOPE -> kind == Property.Kind.Nested
        QueryCapability.AGGREGATE_TERMS -> aggregatable && (kind in EXACT_KINDS || kind == Property.Kind.Text)
        QueryCapability.AGGREGATE_NUMERIC -> aggregatable && kind in NUMERIC_KINDS
        QueryCapability.AGGREGATE_TEMPORAL -> aggregatable && (kind in NUMERIC_KINDS || kind in DATE_KINDS)
    }
    return executable && (
        capability == QueryCapability.PRESENCE ||
            logical.proves(capability, kind) ||
            flattenedDescendant && capability == QueryCapability.EXACT_MATCH && logical.alternativesOrSelf().all {
                it.kind == QueryValueKind.SCALAR && it.valueTypes == setOf(QueryValueType.STRING)
            }
        )
}

/**
 * The smallest `ignore_above` read as indexing every value: Wow's default index templates cap their keywords at
 * 8191 characters, the most that fit Lucene's 32766-byte term limit at four UTF-8 bytes per character. Without the cap
 * a longer value fails the whole document write.
 *
 * A keyword capped at this length or more has the operators of an uncapped keyword. Its limit: a value longer than
 * `ignore_above` is stored in `_source` but not indexed, so no filter matches it (an `eq`, `in`, `contains` or prefix
 * with that value finds nothing; `ne` and `notIn` include the document), it sorts as missing, it falls into no terms
 * bucket and an expression reads no value. `exists` and `isNull` still see it through `_ignored`
 * (`ElasticsearchFilterCompiler`), so an over-long access attribute never reads as a missing one; inside a
 * nested element it reads as missing.
 */
internal const val UNCAPPED_IGNORE_ABOVE = 8191

/** Whether a keyword with [ignoreAbove] counts as indexing every value (see [UNCAPPED_IGNORE_ABOVE]). */
private fun indexesEveryValue(ignoreAbove: Int?): Boolean = ignoreAbove == null || ignoreAbove >= UNCAPPED_IGNORE_ABOVE

/**
 * A field indexing every value ([UNCAPPED_IGNORE_ABOVE]) represents any logical domain; a field with a smaller
 * `ignore_above` only a string enum whose every declared value fits.
 */
private fun QueryValueSchema.provesIndexedValues(ignoreAbove: Int?): Boolean {
    if (ignoreAbove == null || indexesEveryValue(ignoreAbove)) return true
    return operationValues().all { value ->
        if (value.kind == QueryValueKind.NULL) return@all true
        if (value.kind != QueryValueKind.SCALAR || value.valueTypes != setOf(QueryValueType.STRING)) return@all false
        val values = value.enumValues ?: return@all false
        values.isNotEmpty() && values.all { it.isNull || it.isString && it.asString().length <= ignoreAbove }
    }
}

/** Whether some field kind proves [capability] for every value branch: [proves] without a mapped kind. */
internal fun QueryValueSchema.provesSomeKind(capability: QueryCapability): Boolean {
    val values = scalarBranches()
    return values.isNotEmpty() && values.all { value ->
        value.kind == QueryValueKind.SCALAR && value.storageKinds(capability).let { requirements ->
            requirements.isNotEmpty() && requirements.all { it.isNotEmpty() }
        }
    }
}

internal fun QueryValueSchema.scalarValueTypes(): Set<QueryValueType> =
    scalarBranches().flatMapTo(linkedSetOf()) { it.valueTypes }

private fun QueryValueSchema.scalarBranches(): List<QueryValueSchema> =
    alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }.flatMap {
        if (it.kind == QueryValueKind.ARRAY) checkNotNull(it.items).alternativesOrSelf() else listOf(it)
    }.filter { it.kind != QueryValueKind.NULL }

private fun QueryValueSchema.proves(capability: QueryCapability, kind: Property.Kind): Boolean {
    // Whether the value is an element scope at all is the Catalog's rule; storage proves only the nested mapping.
    if (capability == QueryCapability.ELEMENT_SCOPE) return kind in NESTED_KINDS
    val values = scalarBranches()
    return values.isNotEmpty() && values.all { value ->
        value.kind == QueryValueKind.SCALAR && value.storageKinds(capability).let { requirements ->
            requirements.isNotEmpty() && requirements.all { kind in it }
        }
    }
}

/** The field kinds [capability] needs: the Catalog's strict table ([storageFamilies]) in Elasticsearch kinds. */
internal fun QueryValueSchema.storageKinds(capability: QueryCapability): List<Set<Property.Kind>> =
    storageFamilies(capability).map { families -> families.flatMapTo(linkedSetOf()) { it.kinds } }

/** The Elasticsearch field kinds that store each storage family. */
internal val QueryStorageFamily.kinds: Set<Property.Kind>
    get() = when (this) {
        QueryStorageFamily.STRING -> STRING_KINDS
        QueryStorageFamily.EXACT_STRING -> KEYWORD_KINDS
        QueryStorageFamily.INTEGRAL -> INTEGER_KINDS
        QueryStorageFamily.SIGNED_INTEGRAL -> SIGNED_INTEGER_KINDS
        // A decimal on an integral field is truncated when indexed (`coerce`): its sums, ranges and sorts are wrong.
        QueryStorageFamily.NUMERIC -> FLOATING_KINDS
        QueryStorageFamily.BOOLEAN -> BOOLEAN_KINDS
        QueryStorageFamily.DATE -> DATE_KINDS
    }

private val ElasticsearchMappedField.queryable: Boolean
    get() = indexed || (sortable && kind in DOC_VALUE_QUERY_KINDS)

/** The capabilities of a search by words, which only a value its schema marks as prose is granted. */
internal val FULL_TEXT_CAPABILITIES: Set<QueryCapability> =
    setOf(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)

/**
 * Whether this value is prose a reader searches by words: every string it holds (an array's items, a union's
 * strings) is marked so ([QueryValueSchema.text]). A field that is not is never searched by words, whatever its
 * mapping: one an operator mapped as `text`, or one whose marker was removed.
 */
internal fun QueryValueSchema.searchedByWords(): Boolean =
    operationValues().filter { it.kind != QueryValueKind.NULL }.let { values ->
        values.isNotEmpty() && values.all(QueryValueSchema::text)
    }

internal val SIGNED_INTEGER_KINDS = setOf(
    Property.Kind.Byte,
    Property.Kind.Short,
    Property.Kind.Integer,
    Property.Kind.Long,
)

internal val INTEGER_KINDS = SIGNED_INTEGER_KINDS + setOf(
    Property.Kind.TokenCount,
    Property.Kind.UnsignedLong,
)

internal val FLOATING_KINDS = setOf(
    Property.Kind.HalfFloat,
    Property.Kind.Float,
    Property.Kind.Double,
    Property.Kind.ScaledFloat,
)

internal val NUMERIC_KINDS = INTEGER_KINDS + FLOATING_KINDS

internal val KEYWORD_KINDS = setOf(
    Property.Kind.Keyword,
    Property.Kind.ConstantKeyword,
    Property.Kind.CountedKeyword,
    Property.Kind.IcuCollationKeyword,
)

private val TERM_KINDS = KEYWORD_KINDS + Property.Kind.Wildcard

internal val BOOLEAN_KINDS = setOf(Property.Kind.Boolean)

internal val DATE_KINDS = setOf(Property.Kind.Date, Property.Kind.DateNanos)

private val NESTED_KINDS = setOf(Property.Kind.Nested)

private val RANGE_FIELD_KINDS = setOf(
    Property.Kind.IntegerRange,
    Property.Kind.FloatRange,
    Property.Kind.LongRange,
    Property.Kind.DoubleRange,
    Property.Kind.DateRange,
    Property.Kind.IpRange,
)

private val DOC_VALUE_QUERY_KINDS = NUMERIC_KINDS + KEYWORD_KINDS + setOf(
    Property.Kind.Boolean,
    Property.Kind.Date,
    Property.Kind.DateNanos,
    Property.Kind.Ip,
    Property.Kind.Version,
)

private val EXACT_KINDS = NUMERIC_KINDS + TERM_KINDS + setOf(
    Property.Kind.Boolean,
    Property.Kind.Date,
    Property.Kind.DateNanos,
    Property.Kind.Flattened,
    Property.Kind.Ip,
    Property.Kind.Version,
)

private val CURSOR_SORT_KINDS = EXACT_KINDS - setOf(Property.Kind.UnsignedLong, Property.Kind.Flattened)

private val LITERAL_KINDS = TERM_KINDS

private val RANGE_KINDS = NUMERIC_KINDS + KEYWORD_KINDS + RANGE_FIELD_KINDS + setOf(
    Property.Kind.Date,
    Property.Kind.DateNanos,
    Property.Kind.Ip,
)

private val SEARCH_KINDS = setOf(
    Property.Kind.Text,
    Property.Kind.MatchOnlyText,
    Property.Kind.SearchAsYouType,
    Property.Kind.SemanticText,
)

internal val STRING_KINDS = TERM_KINDS + SEARCH_KINDS + setOf(
    Property.Kind.Ip,
    Property.Kind.Version,
)

private val PHRASE_SEARCH_KINDS = SEARCH_KINDS - Property.Kind.SemanticText

private val NORMALIZED_VALUE_CAPABILITIES = setOf(
    QueryCapability.EXACT_MATCH,
    QueryCapability.LITERAL_MATCH,
    QueryCapability.RANGE,
    QueryCapability.SORT,
    QueryCapability.CURSOR_SORT,
    QueryCapability.AGGREGATE_TERMS,
)
