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

package me.ahoo.wow.query.tck

import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.spec.FilterOperatorSpec
import me.ahoo.wow.query.spec.ValueRule

/**
 * The shape of the field a [SemanticCase] runs against. Each shape comes with a fixed set of [SemanticProbe]s: stored
 * values that cover a missing field, an explicit `null`, and the ordinary and edge values of that shape.
 */
enum class SemanticShape {
    /** A single-valued string field. */
    STRING,

    /** A single-valued numeric field. */
    NUMBER,

    /** An array of strings. */
    STRING_ARRAY,
}

/**
 * One stored value of a [SemanticShape], as the JSON text of the state property: [json] is `null` when the field is
 * absent from the stored record, and `"null"` when the record stores an explicit `null`.
 */
class SemanticProbe private constructor(
    val shape: SemanticShape,
    val name: String,
    val json: String?,
) {
    override fun toString(): String = "$shape.$name"

    companion object {
        const val MISSING = "missing"
        const val NULL = "null"
        const val EMPTY = "empty"
        const val VALUE = "value"
        const val CASE_VARIANT = "caseVariant"
        const val OTHER = "other"
        const val ONE = "one"
        const val TWO = "two"
        const val THREE = "three"
        const val VALUE_AND_OTHER = "valueAndOther"

        /** The value followed by a newline: an end anchor must not match before it (finding R2.2-4). */
        const val TRAILING_NEWLINE = "trailingNewline"

        private fun missing(shape: SemanticShape) = SemanticProbe(shape, MISSING, null)
        private fun nullValue(shape: SemanticShape) = SemanticProbe(shape, NULL, "null")

        /** The probes of each shape; names are unique within a shape. */
        val ALL: Map<SemanticShape, List<SemanticProbe>> = mapOf(
            SemanticShape.STRING to listOf(
                missing(SemanticShape.STRING),
                nullValue(SemanticShape.STRING),
                SemanticProbe(SemanticShape.STRING, EMPTY, "\"\""),
                SemanticProbe(SemanticShape.STRING, VALUE, "\"Wow\""),
                SemanticProbe(SemanticShape.STRING, CASE_VARIANT, "\"wow\""),
                SemanticProbe(SemanticShape.STRING, OTHER, "\"Other\""),
                SemanticProbe(SemanticShape.STRING, TRAILING_NEWLINE, "\"Wow\\n\""),
            ),
            SemanticShape.NUMBER to listOf(
                missing(SemanticShape.NUMBER),
                nullValue(SemanticShape.NUMBER),
                SemanticProbe(SemanticShape.NUMBER, ONE, "1"),
                SemanticProbe(SemanticShape.NUMBER, TWO, "2"),
                SemanticProbe(SemanticShape.NUMBER, THREE, "3"),
            ),
            SemanticShape.STRING_ARRAY to listOf(
                missing(SemanticShape.STRING_ARRAY),
                nullValue(SemanticShape.STRING_ARRAY),
                SemanticProbe(SemanticShape.STRING_ARRAY, EMPTY, "[]"),
                SemanticProbe(SemanticShape.STRING_ARRAY, VALUE, "[\"Wow\"]"),
                SemanticProbe(SemanticShape.STRING_ARRAY, VALUE_AND_OTHER, "[\"Wow\",\"Other\"]"),
                SemanticProbe(SemanticShape.STRING_ARRAY, OTHER, "[\"Other\"]"),
            ),
        )
    }
}

/**
 * One cell of the semantic matrix: [filter], applied to a field of [shape], matches exactly the probes named in
 * [matches]. [id] is stable and unique, so a backend's test can name the cells it is known to diverge on.
 */
class SemanticCase(
    val id: String,
    val shape: SemanticShape,
    val matches: Set<String>,
    private val filter: (QueryField) -> FilterExpression,
) {
    init {
        val names = checkNotNull(SemanticProbe.ALL[shape]).map { it.name }.toSet()
        require(names.containsAll(matches)) { "Case [$id] names probes outside $shape: ${matches - names}." }
    }

    fun filter(field: QueryField): FilterExpression = filter.invoke(field)

    /** The operator this case covers, as the request names it before normalization. */
    val operator: FilterOperator = filter(QueryField("probe")).operator

    override fun toString(): String = id
}

/**
 * The semantic specification of the field filter operators (design §6.2, ported from 9.x wow-tck): how each operator
 * treats a missing field, an explicit `null`, array elements and letter case. The expected matches are the MongoDB
 * behavior, which is canonical; every backend must reproduce them, and [QueryBackendSpec] runs every case.
 *
 * Operators with no case here are listed in [UNCOVERED] with the reason; a test keeps the two lists exhaustive.
 */
object FilterSemantics {
    private const val MISSING = SemanticProbe.MISSING
    private const val NULL = SemanticProbe.NULL
    private const val EMPTY = SemanticProbe.EMPTY
    private const val VALUE = SemanticProbe.VALUE
    private const val CASE_VARIANT = SemanticProbe.CASE_VARIANT
    private const val OTHER = SemanticProbe.OTHER
    private const val ONE = SemanticProbe.ONE
    private const val TWO = SemanticProbe.TWO
    private const val THREE = SemanticProbe.THREE
    private const val VALUE_AND_OTHER = SemanticProbe.VALUE_AND_OTHER
    private const val TRAILING_NEWLINE = SemanticProbe.TRAILING_NEWLINE

    private fun string(id: String, vararg matches: String, filter: (QueryField) -> FilterExpression) =
        SemanticCase("string.$id", SemanticShape.STRING, matches.toSet(), filter)

    private fun number(id: String, vararg matches: String, filter: (QueryField) -> FilterExpression) =
        SemanticCase("number.$id", SemanticShape.NUMBER, matches.toSet(), filter)

    private fun array(id: String, vararg matches: String, filter: (QueryField) -> FilterExpression) =
        SemanticCase("array.$id", SemanticShape.STRING_ARRAY, matches.toSet(), filter)

    val CASES: List<SemanticCase> = listOf(
        // Equality is exact and case-sensitive; negations match records without the field.
        string("eq", VALUE) { EqualFilter(it, "Wow") },
        string("eq-null", MISSING, NULL) { EqualFilter(it, null) },
        string("ne", MISSING, NULL, EMPTY, CASE_VARIANT, OTHER, TRAILING_NEWLINE) { NotEqualFilter(it, "Wow") },
        string("ne-null", EMPTY, VALUE, CASE_VARIANT, OTHER, TRAILING_NEWLINE) { NotEqualFilter(it, null) },
        string("in", VALUE, OTHER) { InFilter(it, listOf("Wow", "Other")) },
        string("not-in", MISSING, NULL, EMPTY, CASE_VARIANT, TRAILING_NEWLINE) { NotInFilter(it, listOf("Wow", "Other")) },
        // Literal matches: case-sensitive unless asked otherwise; never match a missing or null field.
        string("contains", VALUE, CASE_VARIANT, TRAILING_NEWLINE) { ContainsFilter(it, "o") },
        string("contains-ignore-case", VALUE, CASE_VARIANT, OTHER, TRAILING_NEWLINE) {
            ContainsFilter(it, "o", StringComparison.CASE_INSENSITIVE)
        },
        string("starts-with", VALUE, TRAILING_NEWLINE) { StartsWithFilter(it, "W") },
        string("starts-with-ignore-case", VALUE, CASE_VARIANT, TRAILING_NEWLINE) {
            StartsWithFilter(it, "W", StringComparison.CASE_INSENSITIVE)
        },
        // An end anchors at the very end: a trailing newline is not stepped over (finding R2.2-4).
        string("ends-with") { EndsWithFilter(it, "OW") },
        string("ends-with-exact-case", VALUE, CASE_VARIANT) { EndsWithFilter(it, "ow") },
        string("ends-with-ignore-case", VALUE, CASE_VARIANT) {
            EndsWithFilter(it, "OW", StringComparison.CASE_INSENSITIVE)
        },
        // Presence: null and missing are both "null"; only a missing field does not exist.
        string("is-null", MISSING, NULL) { IsNullFilter(it) },
        string("is-not-null", EMPTY, VALUE, CASE_VARIANT, OTHER, TRAILING_NEWLINE) { IsNotNullFilter(it) },
        string("exists", NULL, EMPTY, VALUE, CASE_VARIANT, OTHER, TRAILING_NEWLINE) { ExistsFilter(it) },
        string("not-exists", MISSING) { NotExistsFilter(it) },
        string("is-empty-string", EMPTY) { IsEmptyStringFilter(it) },
        string("is-not-empty-string", VALUE, CASE_VARIANT, OTHER, TRAILING_NEWLINE) { IsNotEmptyStringFilter(it) },
        // Ranges never match a missing or null field.
        number("eq", TWO) { EqualFilter(it, 2) },
        number("ne", MISSING, NULL, ONE, THREE) { NotEqualFilter(it, 2) },
        number("in", ONE, THREE) { InFilter(it, listOf(1, 3)) },
        number("gt", TWO, THREE) { GreaterThanFilter(it, 1) },
        number("gte", TWO, THREE) { GreaterThanOrEqualFilter(it, 2) },
        number("lt", ONE) { LessThanFilter(it, 2) },
        number("lte", ONE, TWO) { LessThanOrEqualFilter(it, 2) },
        number("between", TWO, THREE) { BetweenFilter(it, 2, 3) },
        // Arrays: a scalar operand matches any element; an array operand of EQ matches the whole array exactly.
        array("eq", VALUE, VALUE_AND_OTHER) { EqualFilter(it, "Wow") },
        array("eq-array", VALUE_AND_OTHER) { EqualFilter(it, listOf("Wow", "Other")) },
        array("ne", MISSING, NULL, EMPTY, OTHER) { NotEqualFilter(it, "Wow") },
        array("in", VALUE_AND_OTHER, OTHER) { InFilter(it, listOf("Other")) },
        array("not-in", MISSING, NULL, EMPTY, VALUE) { NotInFilter(it, listOf("Other")) },
        array("contains-all", VALUE_AND_OTHER) { ContainsAllFilter(it, listOf("Wow", "Other")) },
        array("contains-all-single", VALUE, VALUE_AND_OTHER) { ContainsAllFilter(it, listOf("Wow")) },
        // An empty array is present, not null, and the only empty collection.
        array("is-empty", EMPTY) { IsEmptyFilter(it) },
        array("is-null", MISSING, NULL) { IsNullFilter(it) },
        array("is-not-null", EMPTY, VALUE, VALUE_AND_OTHER, OTHER) { IsNotNullFilter(it) },
        array("exists", NULL, EMPTY, VALUE, VALUE_AND_OTHER, OTHER) { ExistsFilter(it) },
        array("not-exists", MISSING) { NotExistsFilter(it) },
    )

    /** Field operators the matrix does not cover, with the reason. */
    val UNCOVERED: Map<FilterOperator, String> = buildMap {
        put(FilterOperator.SEARCH, "Full-text relevance is storage-defined; the TCK covers search separately.")
        put(FilterOperator.ELEMENT_MATCH, "A scope, not a comparison; its predicates follow this matrix per element.")
        FilterOperator.entries.filter { FilterOperatorSpec.of(it).valueRule == ValueRule.TEMPORAL }.forEach {
            put(it, "Resolved to a range at admission; the range cases apply.")
        }
    }

    init {
        require(CASES.map { it.id }.toSet().size == CASES.size) { "Semantic case ids must be unique." }
    }
}
