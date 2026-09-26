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

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JavaType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.annotation.JsonTypeResolver
import tools.jackson.databind.jsontype.NamedType
import tools.jackson.databind.jsontype.TypeDeserializer
import tools.jackson.databind.jsontype.impl.AsPropertyTypeDeserializer
import tools.jackson.databind.jsontype.impl.StdTypeResolverBuilder
import tools.jackson.databind.node.TreeTraversingParser

data class QueryField(
    @get:JsonValue val path: String,
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

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(path: String): QueryField = QueryField(path)
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
 * Numeric predicates compare the selected backend's stored or indexed representation, including its precision
 * and quantization. Exact-match capability does not promise arbitrary-precision equality with a source value.
 * Explicit string-comparison contracts remain independent of numeric representation.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = QueryProtocol.FilterExpression.OP,
)
@JsonSubTypes(
    JsonSubTypes.Type(MatchAllFilter::class, name = QueryProtocol.FilterExpression.Operator.MATCH_ALL),
    JsonSubTypes.Type(MatchNoneFilter::class, name = QueryProtocol.FilterExpression.Operator.MATCH_NONE),
    JsonSubTypes.Type(IdFilter::class, name = QueryProtocol.FilterExpression.Operator.ID),
    JsonSubTypes.Type(IdsFilter::class, name = QueryProtocol.FilterExpression.Operator.IDS),
    JsonSubTypes.Type(AggregateIdFilter::class, name = QueryProtocol.FilterExpression.Operator.AGGREGATE_ID),
    JsonSubTypes.Type(AggregateIdsFilter::class, name = QueryProtocol.FilterExpression.Operator.AGGREGATE_IDS),
    JsonSubTypes.Type(TenantIdFilter::class, name = QueryProtocol.FilterExpression.Operator.TENANT_ID),
    JsonSubTypes.Type(OwnerIdFilter::class, name = QueryProtocol.FilterExpression.Operator.OWNER_ID),
    JsonSubTypes.Type(SpaceIdFilter::class, name = QueryProtocol.FilterExpression.Operator.SPACE_ID),
    JsonSubTypes.Type(AndFilter::class, name = QueryProtocol.FilterExpression.Operator.AND),
    JsonSubTypes.Type(OrFilter::class, name = QueryProtocol.FilterExpression.Operator.OR),
    JsonSubTypes.Type(NorFilter::class, name = QueryProtocol.FilterExpression.Operator.NOR),
    JsonSubTypes.Type(EqualFilter::class, name = QueryProtocol.FilterExpression.Operator.EQ),
    JsonSubTypes.Type(NotEqualFilter::class, name = QueryProtocol.FilterExpression.Operator.NE),
    JsonSubTypes.Type(GreaterThanFilter::class, name = QueryProtocol.FilterExpression.Operator.GT),
    JsonSubTypes.Type(GreaterThanOrEqualFilter::class, name = QueryProtocol.FilterExpression.Operator.GTE),
    JsonSubTypes.Type(LessThanFilter::class, name = QueryProtocol.FilterExpression.Operator.LT),
    JsonSubTypes.Type(LessThanOrEqualFilter::class, name = QueryProtocol.FilterExpression.Operator.LTE),
    JsonSubTypes.Type(ContainsFilter::class, name = QueryProtocol.FilterExpression.Operator.CONTAINS),
    JsonSubTypes.Type(StartsWithFilter::class, name = QueryProtocol.FilterExpression.Operator.STARTS_WITH),
    JsonSubTypes.Type(EndsWithFilter::class, name = QueryProtocol.FilterExpression.Operator.ENDS_WITH),
    JsonSubTypes.Type(InFilter::class, name = QueryProtocol.FilterExpression.Operator.IN),
    JsonSubTypes.Type(NotInFilter::class, name = QueryProtocol.FilterExpression.Operator.NOT_IN),
    JsonSubTypes.Type(BetweenFilter::class, name = QueryProtocol.FilterExpression.Operator.BETWEEN),
    JsonSubTypes.Type(ContainsAllFilter::class, name = QueryProtocol.FilterExpression.Operator.CONTAINS_ALL),
    JsonSubTypes.Type(IsEmptyFilter::class, name = QueryProtocol.FilterExpression.Operator.IS_EMPTY),
    JsonSubTypes.Type(IsEmptyStringFilter::class, name = QueryProtocol.FilterExpression.Operator.IS_EMPTY_STRING),
    JsonSubTypes.Type(
        IsNotEmptyStringFilter::class,
        name = QueryProtocol.FilterExpression.Operator.IS_NOT_EMPTY_STRING,
    ),
    JsonSubTypes.Type(IsNullFilter::class, name = QueryProtocol.FilterExpression.Operator.IS_NULL),
    JsonSubTypes.Type(IsNotNullFilter::class, name = QueryProtocol.FilterExpression.Operator.IS_NOT_NULL),
    JsonSubTypes.Type(ExistsFilter::class, name = QueryProtocol.FilterExpression.Operator.EXISTS),
    JsonSubTypes.Type(NotExistsFilter::class, name = QueryProtocol.FilterExpression.Operator.NOT_EXISTS),
    JsonSubTypes.Type(DeletionFilter::class, name = QueryProtocol.FilterExpression.Operator.DELETION),
    JsonSubTypes.Type(ElementMatchFilter::class, name = QueryProtocol.FilterExpression.Operator.ELEMENT_MATCH),
    JsonSubTypes.Type(SearchFilter::class, name = QueryProtocol.FilterExpression.Operator.SEARCH),
    JsonSubTypes.Type(TodayFilter::class, name = QueryProtocol.FilterExpression.Operator.TODAY),
    JsonSubTypes.Type(BeforeTodayFilter::class, name = QueryProtocol.FilterExpression.Operator.BEFORE_TODAY),
    JsonSubTypes.Type(TomorrowFilter::class, name = QueryProtocol.FilterExpression.Operator.TOMORROW),
    JsonSubTypes.Type(ThisWeekFilter::class, name = QueryProtocol.FilterExpression.Operator.THIS_WEEK),
    JsonSubTypes.Type(NextWeekFilter::class, name = QueryProtocol.FilterExpression.Operator.NEXT_WEEK),
    JsonSubTypes.Type(LastWeekFilter::class, name = QueryProtocol.FilterExpression.Operator.LAST_WEEK),
    JsonSubTypes.Type(ThisMonthFilter::class, name = QueryProtocol.FilterExpression.Operator.THIS_MONTH),
    JsonSubTypes.Type(LastMonthFilter::class, name = QueryProtocol.FilterExpression.Operator.LAST_MONTH),
    JsonSubTypes.Type(RecentDaysFilter::class, name = QueryProtocol.FilterExpression.Operator.RECENT_DAYS),
    JsonSubTypes.Type(EarlierDaysFilter::class, name = QueryProtocol.FilterExpression.Operator.EARLIER_DAYS),
    JsonSubTypes.Type(YesterdayFilter::class, name = QueryProtocol.FilterExpression.Operator.YESTERDAY),
    JsonSubTypes.Type(NextMonthFilter::class, name = QueryProtocol.FilterExpression.Operator.NEXT_MONTH),
    JsonSubTypes.Type(LastYearFilter::class, name = QueryProtocol.FilterExpression.Operator.LAST_YEAR),
    JsonSubTypes.Type(ThisYearFilter::class, name = QueryProtocol.FilterExpression.Operator.THIS_YEAR),
    JsonSubTypes.Type(NextYearFilter::class, name = QueryProtocol.FilterExpression.Operator.NEXT_YEAR),
    JsonSubTypes.Type(BeforeNowFilter::class, name = QueryProtocol.FilterExpression.Operator.BEFORE_NOW),
    JsonSubTypes.Type(AfterNowFilter::class, name = QueryProtocol.FilterExpression.Operator.AFTER_NOW),
    JsonSubTypes.Type(ExpressionFilter::class, name = QueryProtocol.FilterExpression.Operator.EXPRESSION),
)
@JsonTypeResolver(FilterExpressionTypeResolverBuilder::class)
sealed interface FilterExpression : RewritableFilter<FilterExpression> {
    @get:JsonProperty(QueryProtocol.FilterExpression.OP)
    val operator: FilterOperator

    override fun withFilter(newFilter: FilterExpression): FilterExpression = newFilter

    override fun appendFilter(append: FilterExpression): FilterExpression =
        if (this === MatchAllFilter) append else AndFilter(listOf(this, append))
}

internal class FilterExpressionTypeResolverBuilder : StdTypeResolverBuilder() {
    override fun buildTypeDeserializer(
        ctxt: DeserializationContext,
        baseType: JavaType,
        subtypes: Collection<NamedType>,
    ): TypeDeserializer = FilterExpressionTypeDeserializer(
        requireNotNull(super.buildTypeDeserializer(ctxt, baseType, subtypes)) as AsPropertyTypeDeserializer,
    )
}

@Suppress("DEPRECATION")
private class FilterExpressionTypeDeserializer(
    source: AsPropertyTypeDeserializer,
    private val property: BeanProperty? = null,
) : AsPropertyTypeDeserializer(source, property) {
    override fun forProperty(prop: BeanProperty?): TypeDeserializer =
        FilterExpressionTypeDeserializer(this, prop)

    override fun deserializeTypedFromObject(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): Any {
        val node = ctxt.readTree(p)
        require(
            !(node.has(QueryProtocol.FilterExpression.OP) && node.has(QueryProtocol.Condition.OPERATOR))
        ) { "op and operator cannot be used together." }
        if (!node.has(QueryProtocol.FilterExpression.OP)) {
            if (property != null) {
                return ctxt.reportInputMismatch(
                    FilterExpression::class.java,
                    "Filter expression properties must use op.",
                )
            }
            return node.toLegacyFilterExpression(ctxt)
        }
        node.requireCanonicalFilterPayload()
        return TreeTraversingParser(node, ctxt).use { parser ->
            parser.nextToken()
            (super.deserializeTypedFromObject(parser, ctxt) as FilterExpression)
                .also(FilterExpression::requireScalarEqualityValue)
        }
    }
}

private fun JsonNode.requireCanonicalFilterPayload() {
    require(has(QueryProtocol.FilterExpression.OP)) { "Nested filter expression must use op." }
    when (get(QueryProtocol.FilterExpression.OP).asString()) {
        QueryProtocol.FilterExpression.Operator.AND,
        QueryProtocol.FilterExpression.Operator.OR,
        QueryProtocol.FilterExpression.Operator.NOR ->
            get(QueryProtocol.FilterExpression.OPERANDS)?.forEach { it.requireCanonicalFilterPayload() }
        QueryProtocol.FilterExpression.Operator.ELEMENT_MATCH ->
            get(QueryProtocol.FilterExpression.PREDICATE)?.requireCanonicalFilterPayload()
    }
}

private fun FilterExpression.requireScalarEqualityValue() {
    when (this) {
        is EqualFilter -> value.requireScalarEqualityValue(operator.name)
        is NotEqualFilter -> value.requireScalarEqualityValue(operator.name)
        else -> Unit
    }
}

private fun JsonNode.requireScalarEqualityValue(operator: String) {
    require(isNull || isString || isNumber || isBoolean) {
        "$operator value must be a JSON scalar in filter payloads."
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.MATCH_ALL)
data object MatchAllFilter : FilterExpression {
    override val operator: FilterOperator = FilterOperator.MATCH_ALL
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.MATCH_NONE)
data object MatchNoneFilter : FilterExpression {
    override val operator: FilterOperator = FilterOperator.MATCH_NONE
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.AND)
data class AndFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.AND

    init {
        require(operands.isNotEmpty()) { "AND operands cannot be empty." }
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.OR)
data class OrFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.OR

    init {
        require(operands.isNotEmpty()) { "OR operands cannot be empty." }
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.NOR)
data class NorFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.NOR

    init {
        require(operands.isNotEmpty()) { "NOR operands cannot be empty." }
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.DELETION)
data class DeletionFilter(
    @get:JsonProperty("state") val deletionState: DeletionState,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.DELETION
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.ELEMENT_MATCH)
data class ElementMatchFilter(
    val field: QueryField,
    val predicate: FilterExpression,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.ELEMENT_MATCH

    init {
        require(predicate.containsElementUnsupportedFilter().not()) {
            "ELEMENT_MATCH predicate cannot contain root filters."
        }
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.SEARCH)
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
 * Whether this filter holds a node that cannot apply to one element: a system-field or deletion filter, or a
 * model-wide `SEARCH`. A `SEARCH` naming fields is an element predicate when [fieldSearch] allows it; whether each
 * field can be searched is a capability of the model, checked at admission.
 */
internal fun FilterExpression.containsElementUnsupportedFilter(fieldSearch: Boolean = true): Boolean = when (this) {
    is SearchFilter -> !fieldSearch || fields.isEmpty()
    is DeletionFilter,
    is IdFilter,
    is IdsFilter,
    is AggregateIdFilter,
    is AggregateIdsFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    -> true
    is AndFilter -> operands.any { it.containsElementUnsupportedFilter(fieldSearch) }
    is OrFilter -> operands.any { it.containsElementUnsupportedFilter(fieldSearch) }
    is NorFilter -> operands.any { it.containsElementUnsupportedFilter(fieldSearch) }
    is ElementMatchFilter -> predicate.containsElementUnsupportedFilter(fieldSearch)
    // A computed comparison cannot run inside ELEMENT_MATCH on every storage; aggregation elements accept it.
    is ExpressionFilter -> fieldSearch
    else -> false
}
