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

package me.ahoo.wow.api.query

data class QueryField(
    val path: String,
) {
    init {
        require(isValidPath(path)) { "Query field is invalid: [$path]." }
    }

    fun append(relative: QueryField): QueryField = QueryField("$path.${relative.path}")

    fun absoluteTo(parent: QueryField?): QueryField =
        if (parent == null || this == parent || path.startsWith("${parent.path}.")) this else parent.append(this)

    fun relativeTo(parent: QueryField): QueryField? =
        path.removePrefix("${parent.path}.")
            .takeIf { it != path && it.isNotEmpty() }
            ?.let(::QueryField)

    override fun toString(): String = path

    companion object {
        const val PATTERN = "^@?[A-Za-z_][A-Za-z0-9_-]*(\\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$"

        /**
         * Matches [PATTERN] without a regex: admission builds a field per reference on every request,
         * so the check sits on the query hot path.
         */
        private fun isValidPath(path: String): Boolean {
            var index = 0
            var first = true
            while (true) {
                index = segmentEnd(path, index, first)
                if (index < 0) return false
                if (index == path.length) return true
                if (path[index] != '.') return false
                index++
                first = false
            }
        }

        /** The end of the segment starting at [start], or `-1` when no valid segment starts there. */
        private fun segmentEnd(path: String, start: Int, first: Boolean): Int {
            var index = start
            if (index < path.length && path[index] == '@') index++
            if (index >= path.length) return -1
            val head = path[index]
            if (head == '_' || head in 'A'..'Z' || head in 'a'..'z') {
                index++
                while (index < path.length && path[index].isNamePart()) index++
                return index
            }
            if (first || index != start || head !in '0'..'9') return -1
            while (index < path.length && path[index] in '0'..'9') index++
            return index
        }

        private fun Char.isNamePart(): Boolean =
            this == '_' || this == '-' || this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
    }
}

enum class FilterOperator {
    MATCH_ALL,
    MATCH_NONE,
    ID,
    IDS,
    AGGREGATE_ID,
    AGGREGATE_IDS,
    TENANT_ID,
    OWNER_ID,
    SPACE_ID,
    AND,
    OR,
    NOR,
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    IN,
    NOT_IN,
    BETWEEN,
    CONTAINS_ALL,
    IS_EMPTY,
    IS_EMPTY_STRING,
    IS_NOT_EMPTY_STRING,
    IS_NULL,
    IS_NOT_NULL,
    EXISTS,
    NOT_EXISTS,
    DELETION,
    ELEMENT_MATCH,
    SEARCH,
    TODAY,
    BEFORE_TODAY,
    TOMORROW,
    THIS_WEEK,
    NEXT_WEEK,
    LAST_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    RECENT_DAYS,
    EARLIER_DAYS,
    YESTERDAY,
    NEXT_MONTH,
    LAST_YEAR,
    THIS_YEAR,
    NEXT_YEAR,
    BEFORE_NOW,
    AFTER_NOW,
    EXPRESSION,
}

enum class StringComparison {
    CASE_SENSITIVE,
    CASE_INSENSITIVE,
}

/**
 * A node of a filter tree. On the wire each node is a JSON object whose `op` is its [operator]'s name, with the
 * node's own properties beside it.
 *
 * Numeric predicates compare the selected backend's stored or indexed representation, including its precision
 * and quantization. Exact-match capability does not promise arbitrary-precision equality with a source value.
 * Explicit string-comparison contracts remain independent of numeric representation.
 */
sealed interface FilterExpression : RewritableFilter<FilterExpression> {
    val operator: FilterOperator

    override fun withFilter(newFilter: FilterExpression): FilterExpression = newFilter

    override fun appendFilter(append: FilterExpression): FilterExpression =
        if (this === MatchAllFilter) append else AndFilter(listOf(this, append))
}

/**
 * A filter on one [field] the request names: the comparison, string, membership, presence and relative-time
 * predicates, and `ELEMENT_MATCH`, whose field is the collection its predicate applies to. System-field, logical,
 * search and expression filters name no single field and are not field predicates.
 */
sealed interface FieldPredicate : FilterExpression {
    val field: QueryField

    /** This predicate naming [field] instead; everything else it carries is kept. */
    fun withField(field: QueryField): FilterExpression
}

data object MatchAllFilter : FilterExpression {
    override val operator: FilterOperator = FilterOperator.MATCH_ALL
}

data object MatchNoneFilter : FilterExpression {
    override val operator: FilterOperator = FilterOperator.MATCH_NONE
}

data class AndFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.AND

    init {
        require(operands.isNotEmpty()) { "AND operands cannot be empty." }
    }
}

data class OrFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.OR

    init {
        require(operands.isNotEmpty()) { "OR operands cannot be empty." }
    }
}

data class NorFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.NOR

    init {
        require(operands.isNotEmpty()) { "NOR operands cannot be empty." }
    }
}

/** Filters by deletion state; [deletionState] is written as `state` on the wire. */
data class DeletionFilter(
    val deletionState: DeletionState,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.DELETION
}

data class ElementMatchFilter(
    override val field: QueryField,
    val predicate: FilterExpression,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.ELEMENT_MATCH

    override fun withField(field: QueryField): ElementMatchFilter = copy(field = field)

    init {
        require(predicate.containsElementUnsupportedFilter().not()) {
            "ELEMENT_MATCH predicate cannot contain root filters."
        }
    }
}

data class SearchFilter(
    val query: String,
    val fields: Set<QueryField> = emptySet(),
    val mode: SearchMode = SearchMode.TERMS,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.SEARCH

    init {
        require(query.isNotBlank()) { "SEARCH query cannot be blank." }
    }
}

enum class SearchMode {
    TERMS,
    PHRASE,
}

/**
 * The direct child filters of this node: the operands of `AND` / `OR` / `NOR` and the predicate of `ELEMENT_MATCH`.
 * Every other node is a leaf and returns an empty list. The one walker of a filter tree: consumers that visit the
 * tree read the children from here instead of matching the logical nodes themselves.
 */
fun FilterExpression.childFilters(): List<FilterExpression> = when (this) {
    is AndFilter -> operands
    is OrFilter -> operands
    is NorFilter -> operands
    is ElementMatchFilter -> listOf(predicate)
    is FieldPredicate,
    MatchAllFilter,
    MatchNoneFilter,
    is IdFilter,
    is IdsFilter,
    is AggregateIdFilter,
    is AggregateIdsFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    is DeletionFilter,
    is SearchFilter,
    is ExpressionFilter,
    -> emptyList()
}

/**
 * Whether this node filters a system field the query model defines (identity, aggregate id, tenant, owner, space or
 * deletion) instead of a field the request names.
 */
internal val FilterExpression.isSystemFieldFilter: Boolean
    get() = when (this) {
        is IdFilter,
        is IdsFilter,
        is AggregateIdFilter,
        is AggregateIdsFilter,
        is TenantIdFilter,
        is OwnerIdFilter,
        is SpaceIdFilter,
        is DeletionFilter,
        -> true

        else -> false
    }

/**
 * Whether this filter holds a node that cannot apply to one element: a filter on a system field
 * ([isSystemFieldFilter], deletion included), or a model-wide
 * `SEARCH`. A `SEARCH` naming fields is an element predicate when [fieldSearch] allows it; whether each field can be
 * searched is a capability of the model, checked at admission.
 */
internal fun FilterExpression.containsElementUnsupportedFilter(fieldSearch: Boolean = true): Boolean = when (this) {
    is SearchFilter -> !fieldSearch || fields.isEmpty()
    // A computed comparison cannot run inside ELEMENT_MATCH on every storage; aggregation elements accept it.
    is ExpressionFilter -> fieldSearch
    else -> isSystemFieldFilter || childFilters().any { it.containsElementUnsupportedFilter(fieldSearch) }
}
