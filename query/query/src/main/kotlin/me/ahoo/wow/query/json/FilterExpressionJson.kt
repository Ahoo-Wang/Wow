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

package me.ahoo.wow.query.json

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JavaType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.annotation.JsonTypeResolver
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.jsontype.NamedType
import tools.jackson.databind.jsontype.TypeDeserializer
import tools.jackson.databind.jsontype.impl.AsPropertyTypeDeserializer
import tools.jackson.databind.jsontype.impl.StdTypeResolverBuilder
import tools.jackson.databind.node.TreeTraversingParser
import java.time.Duration
import java.time.format.DateTimeFormatter

internal const val OP = "op"
private const val OPERATOR = "operator"
private const val OPERANDS = "operands"
private const val PREDICATE = "predicate"

/** The filter class each operator names on the wire; a new operator does not compile until it has one. */
@Suppress("CyclomaticComplexMethod")
internal fun FilterOperator.filterType(): Class<out FilterExpression> = when (this) {
    FilterOperator.MATCH_ALL -> MatchAllFilter::class.java
    FilterOperator.MATCH_NONE -> MatchNoneFilter::class.java
    FilterOperator.ID -> IdFilter::class.java
    FilterOperator.IDS -> IdsFilter::class.java
    FilterOperator.AGGREGATE_ID -> AggregateIdFilter::class.java
    FilterOperator.AGGREGATE_IDS -> AggregateIdsFilter::class.java
    FilterOperator.TENANT_ID -> TenantIdFilter::class.java
    FilterOperator.OWNER_ID -> OwnerIdFilter::class.java
    FilterOperator.SPACE_ID -> SpaceIdFilter::class.java
    FilterOperator.AND -> AndFilter::class.java
    FilterOperator.OR -> OrFilter::class.java
    FilterOperator.NOR -> NorFilter::class.java
    FilterOperator.EQ -> EqualFilter::class.java
    FilterOperator.NE -> NotEqualFilter::class.java
    FilterOperator.GT -> GreaterThanFilter::class.java
    FilterOperator.GTE -> GreaterThanOrEqualFilter::class.java
    FilterOperator.LT -> LessThanFilter::class.java
    FilterOperator.LTE -> LessThanOrEqualFilter::class.java
    FilterOperator.CONTAINS -> ContainsFilter::class.java
    FilterOperator.STARTS_WITH -> StartsWithFilter::class.java
    FilterOperator.ENDS_WITH -> EndsWithFilter::class.java
    FilterOperator.IN -> InFilter::class.java
    FilterOperator.NOT_IN -> NotInFilter::class.java
    FilterOperator.BETWEEN -> BetweenFilter::class.java
    FilterOperator.CONTAINS_ALL -> ContainsAllFilter::class.java
    FilterOperator.IS_EMPTY -> IsEmptyFilter::class.java
    FilterOperator.IS_EMPTY_STRING -> IsEmptyStringFilter::class.java
    FilterOperator.IS_NOT_EMPTY_STRING -> IsNotEmptyStringFilter::class.java
    FilterOperator.IS_NULL -> IsNullFilter::class.java
    FilterOperator.IS_NOT_NULL -> IsNotNullFilter::class.java
    FilterOperator.EXISTS -> ExistsFilter::class.java
    FilterOperator.NOT_EXISTS -> NotExistsFilter::class.java
    FilterOperator.DELETION -> DeletionFilter::class.java
    FilterOperator.ELEMENT_MATCH -> ElementMatchFilter::class.java
    FilterOperator.SEARCH -> SearchFilter::class.java
    FilterOperator.TODAY -> TodayFilter::class.java
    FilterOperator.BEFORE_TODAY -> BeforeTodayFilter::class.java
    FilterOperator.TOMORROW -> TomorrowFilter::class.java
    FilterOperator.THIS_WEEK -> ThisWeekFilter::class.java
    FilterOperator.NEXT_WEEK -> NextWeekFilter::class.java
    FilterOperator.LAST_WEEK -> LastWeekFilter::class.java
    FilterOperator.THIS_MONTH -> ThisMonthFilter::class.java
    FilterOperator.LAST_MONTH -> LastMonthFilter::class.java
    FilterOperator.RECENT_DAYS -> RecentDaysFilter::class.java
    FilterOperator.EARLIER_DAYS -> EarlierDaysFilter::class.java
    FilterOperator.YESTERDAY -> YesterdayFilter::class.java
    FilterOperator.NEXT_MONTH -> NextMonthFilter::class.java
    FilterOperator.LAST_YEAR -> LastYearFilter::class.java
    FilterOperator.THIS_YEAR -> ThisYearFilter::class.java
    FilterOperator.NEXT_YEAR -> NextYearFilter::class.java
    FilterOperator.BEFORE_NOW -> BeforeNowFilter::class.java
    FilterOperator.AFTER_NOW -> AfterNowFilter::class.java
    FilterOperator.EXPRESSION -> ExpressionFilter::class.java
}

/** Every filter class under its operator name, the `op` the wire carries. */
internal fun filterSubtypes(): List<NamedType> = FilterOperator.entries.map { NamedType(it.filterType(), it.name) }

/** A bare JSON string: the path. */
internal abstract class QueryFieldMixin
@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
constructor(
    @Suppress("UNUSED_PARAMETER", "unused") path: String,
) {
    @get:JsonValue
    abstract val path: String
}

/**
 * Polymorphic on `op`, an existing property: [FilterExpression.operator] is written as `op` and never as `operator`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = OP)
@JsonTypeResolver(FilterExpressionTypeResolverBuilder::class)
internal interface FilterExpressionMixin {
    @get:JsonProperty(OP)
    val operator: FilterOperator
}

internal interface DeletionFilterMixin {
    @get:JsonProperty("state")
    val deletionState: DeletionState
}

/** A formatter given in process is never written. */
internal interface RelativeTimeFilterMixin {
    @get:JsonIgnore
    val dateFormatter: DateTimeFormatter?
}

/** The offset parsed from `offset`; derived, never written. */
internal interface RelativeNowFilterMixin {
    @get:JsonIgnore
    val offsetDuration: Duration
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

/**
 * Reads a filter only by its `op`: an object without one (a 9.x legacy `Condition`) is rejected, at the top, as a
 * property value and nested in the tree. EQ and NE read from JSON take a scalar only; the model also takes a list,
 * for in-process array equality.
 */
private class FilterExpressionTypeDeserializer(
    source: AsPropertyTypeDeserializer,
    private val property: BeanProperty? = null,
) : AsPropertyTypeDeserializer(source, property) {
    override fun forProperty(prop: BeanProperty?): TypeDeserializer =
        if (prop === property) this else FilterExpressionTypeDeserializer(this, prop)

    override fun deserializeTypedFromObject(p: JsonParser, ctxt: DeserializationContext): Any {
        val node = ctxt.readTree(p)
        require(!(node.has(OP) && node.has(OPERATOR))) { "op and operator cannot be used together." }
        if (!node.has(OP) && property != null) {
            throw MismatchedInputException.from(
                p,
                FilterExpression::class.java,
                "Filter expression properties must use op."
            )
        }
        node.requireCanonicalFilterPayload(nested = false)
        return TreeTraversingParser(node, ctxt).use { parser ->
            parser.nextToken()
            (super.deserializeTypedFromObject(parser, ctxt) as FilterExpression)
                .also(FilterExpression::requireScalarEqualityValue)
        }
    }
}

private fun JsonNode.requireCanonicalFilterPayload(nested: Boolean) {
    require(has(OP)) { if (nested) "Nested filter expression must use op." else "Filter expression must use op." }
    when (get(OP).asString()) {
        FilterOperator.AND.name,
        FilterOperator.OR.name,
        FilterOperator.NOR.name,
        -> get(OPERANDS)?.forEach { it.requireCanonicalFilterPayload(nested = true) }

        FilterOperator.ELEMENT_MATCH.name -> get(PREDICATE)?.requireCanonicalFilterPayload(nested = true)
    }
}

private fun FilterExpression.requireScalarEqualityValue() {
    when (this) {
        is EqualFilter -> value.requireScalarEqualityValue(operator)
        is NotEqualFilter -> value.requireScalarEqualityValue(operator)
        else -> Unit
    }
}

private fun Any?.requireScalarEqualityValue(operator: FilterOperator) {
    require(this == null || this is String || this is Number || this is Boolean) {
        "$operator value must be a JSON scalar in filter payloads."
    }
}
