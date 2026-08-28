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

private val LOGICAL_FIELD_PATTERN =
    Regex("@?[A-Za-z_][A-Za-z0-9_-]*(\\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*")

data class LogicalField(
    @get:JsonValue val value: String,
) {
    init {
        require(LOGICAL_FIELD_PATTERN.matches(value)) { "Logical field is invalid: [$value]." }
    }

    override fun toString(): String = value

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(value: String): LogicalField = LogicalField(value)
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
}

enum class StringComparison {
    CASE_SENSITIVE,
    CASE_INSENSITIVE,
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "op",
)
@JsonSubTypes(
    JsonSubTypes.Type(MatchAllFilter::class, name = "MATCH_ALL"),
    JsonSubTypes.Type(MatchNoneFilter::class, name = "MATCH_NONE"),
    JsonSubTypes.Type(IdFilter::class, name = "ID"),
    JsonSubTypes.Type(IdsFilter::class, name = "IDS"),
    JsonSubTypes.Type(AggregateIdFilter::class, name = "AGGREGATE_ID"),
    JsonSubTypes.Type(AggregateIdsFilter::class, name = "AGGREGATE_IDS"),
    JsonSubTypes.Type(TenantIdFilter::class, name = "TENANT_ID"),
    JsonSubTypes.Type(OwnerIdFilter::class, name = "OWNER_ID"),
    JsonSubTypes.Type(SpaceIdFilter::class, name = "SPACE_ID"),
    JsonSubTypes.Type(AndFilter::class, name = "AND"),
    JsonSubTypes.Type(OrFilter::class, name = "OR"),
    JsonSubTypes.Type(NorFilter::class, name = "NOR"),
    JsonSubTypes.Type(EqualFilter::class, name = "EQ"),
    JsonSubTypes.Type(NotEqualFilter::class, name = "NE"),
    JsonSubTypes.Type(GreaterThanFilter::class, name = "GT"),
    JsonSubTypes.Type(GreaterThanOrEqualFilter::class, name = "GTE"),
    JsonSubTypes.Type(LessThanFilter::class, name = "LT"),
    JsonSubTypes.Type(LessThanOrEqualFilter::class, name = "LTE"),
    JsonSubTypes.Type(ContainsFilter::class, name = "CONTAINS"),
    JsonSubTypes.Type(StartsWithFilter::class, name = "STARTS_WITH"),
    JsonSubTypes.Type(EndsWithFilter::class, name = "ENDS_WITH"),
    JsonSubTypes.Type(InFilter::class, name = "IN"),
    JsonSubTypes.Type(NotInFilter::class, name = "NOT_IN"),
    JsonSubTypes.Type(BetweenFilter::class, name = "BETWEEN"),
    JsonSubTypes.Type(ContainsAllFilter::class, name = "CONTAINS_ALL"),
    JsonSubTypes.Type(IsEmptyFilter::class, name = "IS_EMPTY"),
    JsonSubTypes.Type(IsNullFilter::class, name = "IS_NULL"),
    JsonSubTypes.Type(IsNotNullFilter::class, name = "IS_NOT_NULL"),
    JsonSubTypes.Type(ExistsFilter::class, name = "EXISTS"),
    JsonSubTypes.Type(NotExistsFilter::class, name = "NOT_EXISTS"),
    JsonSubTypes.Type(DeletionFilter::class, name = "DELETION"),
    JsonSubTypes.Type(ElementMatchFilter::class, name = "ELEMENT_MATCH"),
    JsonSubTypes.Type(SearchFilter::class, name = "SEARCH"),
    JsonSubTypes.Type(TodayFilter::class, name = "TODAY"),
    JsonSubTypes.Type(BeforeTodayFilter::class, name = "BEFORE_TODAY"),
    JsonSubTypes.Type(TomorrowFilter::class, name = "TOMORROW"),
    JsonSubTypes.Type(ThisWeekFilter::class, name = "THIS_WEEK"),
    JsonSubTypes.Type(NextWeekFilter::class, name = "NEXT_WEEK"),
    JsonSubTypes.Type(LastWeekFilter::class, name = "LAST_WEEK"),
    JsonSubTypes.Type(ThisMonthFilter::class, name = "THIS_MONTH"),
    JsonSubTypes.Type(LastMonthFilter::class, name = "LAST_MONTH"),
    JsonSubTypes.Type(RecentDaysFilter::class, name = "RECENT_DAYS"),
    JsonSubTypes.Type(EarlierDaysFilter::class, name = "EARLIER_DAYS"),
    JsonSubTypes.Type(YesterdayFilter::class, name = "YESTERDAY"),
    JsonSubTypes.Type(NextMonthFilter::class, name = "NEXT_MONTH"),
    JsonSubTypes.Type(LastYearFilter::class, name = "LAST_YEAR"),
    JsonSubTypes.Type(ThisYearFilter::class, name = "THIS_YEAR"),
    JsonSubTypes.Type(NextYearFilter::class, name = "NEXT_YEAR"),
)
@JsonTypeResolver(FilterExpressionTypeResolverBuilder::class)
sealed interface FilterExpression : RewritableFilter<FilterExpression> {
    @get:JsonProperty("op")
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
        require(!(node.has("op") && node.has("operator"))) { "op and operator cannot be used together." }
        if (!node.has("op")) {
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
    require(has("op")) { "Nested filter expression must use op." }
    when (get("op").asString()) {
        "AND", "OR", "NOR" -> get("operands")?.forEach { it.requireCanonicalFilterPayload() }
        "ELEMENT_MATCH" -> get("predicate")?.requireCanonicalFilterPayload()
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

@JsonTypeName("MATCH_ALL")
data object MatchAllFilter : FilterExpression {
    override val operator: FilterOperator = FilterOperator.MATCH_ALL
}

@JsonTypeName("MATCH_NONE")
data object MatchNoneFilter : FilterExpression {
    override val operator: FilterOperator = FilterOperator.MATCH_NONE
}

@JsonTypeName("AND")
data class AndFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.AND

    init {
        require(operands.isNotEmpty()) { "AND operands cannot be empty." }
    }
}

@JsonTypeName("OR")
data class OrFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.OR

    init {
        require(operands.isNotEmpty()) { "OR operands cannot be empty." }
    }
}

@JsonTypeName("NOR")
data class NorFilter(val operands: List<FilterExpression>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.NOR

    init {
        require(operands.isNotEmpty()) { "NOR operands cannot be empty." }
    }
}

@JsonTypeName("DELETION")
data class DeletionFilter(
    @get:JsonProperty("state") val deletionState: DeletionState,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.DELETION
}

@JsonTypeName("ELEMENT_MATCH")
data class ElementMatchFilter(
    val field: LogicalField,
    val predicate: FilterExpression,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.ELEMENT_MATCH

    init {
        require(predicate.containsElementUnsupportedFilter().not()) {
            "ELEMENT_MATCH predicate cannot contain root filters."
        }
    }
}

@JsonTypeName("SEARCH")
data class SearchFilter(
    val query: String,
    val fields: Set<LogicalField> = emptySet(),
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

internal fun FilterExpression.containsElementUnsupportedFilter(): Boolean = when (this) {
    is DeletionFilter,
    is SearchFilter,
    is IdFilter,
    is IdsFilter,
    is AggregateIdFilter,
    is AggregateIdsFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    -> true
    is AndFilter -> operands.any { it.containsElementUnsupportedFilter() }
    is OrFilter -> operands.any { it.containsElementUnsupportedFilter() }
    is NorFilter -> operands.any { it.containsElementUnsupportedFilter() }
    is ElementMatchFilter -> predicate.containsElementUnsupportedFilter()
    else -> false
}
