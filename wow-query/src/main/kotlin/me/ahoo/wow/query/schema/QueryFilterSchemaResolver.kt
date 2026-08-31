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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.POJONode
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import java.lang.reflect.Array as ReflectArray

private val BUILT_IN_QUERY_VALUE_TYPES = setOf(
    QueryValueType.STRING,
    QueryValueType.INTEGER,
    QueryValueType.DECIMAL,
    QueryValueType.BOOLEAN,
    QueryValueType.OBJECT,
)

internal class QueryFilterSchemaResolver(
    private val schema: QueryModelSchema,
    private val fieldResolver: QueryFieldSchemaResolver,
) {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun resolve(
        filter: FilterExpression,
        logicalParent: LogicalField? = null,
        physicalParent: String? = null,
    ): QuerySchemaResolution<FilterExpression> = when (filter) {
        MatchAllFilter,
        MatchNoneFilter,
        -> QuerySchemaResolution(filter, QueryCompatibilityLevel.EXACT)

        is IdFilter,
        is IdsFilter,
        is AggregateIdFilter,
        is AggregateIdsFilter,
        -> metadata(filter, MessageRecords.AGGREGATE_ID)

        is TenantIdFilter -> metadata(filter, MessageRecords.TENANT_ID)
        is OwnerIdFilter -> metadata(filter, MessageRecords.OWNER_ID)
        is SpaceIdFilter -> metadata(filter, MessageRecords.SPACE_ID)
        is DeletionFilter -> metadata(filter, StateAggregateRecords.DELETED)
        is AndFilter -> resolveOperands(filter.operands, logicalParent, physicalParent, ::AndFilter)
        is OrFilter -> resolveOperands(filter.operands, logicalParent, physicalParent, ::OrFilter)
        is NorFilter -> resolveOperands(filter.operands, logicalParent, physicalParent, ::NorFilter)
        is EqualFilter -> resolveFieldFilter(
            filter.field,
            if (filter.value.isNull) QueryCapability.PRESENCE else QueryCapability.EXACT_MATCH,
            logicalParent,
            physicalParent,
            filter.value.queryValues(),
        ) {
            filter.copy(field = it)
        }
        is NotEqualFilter -> resolveFieldFilter(
            filter.field,
            if (filter.value.isNull) QueryCapability.PRESENCE else QueryCapability.EXACT_MATCH,
            logicalParent,
            physicalParent,
            filter.value.queryValues(),
        ) { filter.copy(field = it) }
        is InFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            physicalParent,
            filter.values,
        ) {
            filter.copy(field = it)
        }
        is NotInFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            physicalParent,
            filter.values,
        ) {
            filter.copy(field = it)
        }
        is ContainsAllFilter -> resolveCollectionFieldFilter(
            filter.field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            physicalParent,
            filter.values,
        ) { filter.copy(field = it) }
        is ContainsFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.LITERAL_MATCH,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is StartsWithFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.LITERAL_MATCH,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is EndsWithFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.LITERAL_MATCH,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is GreaterThanFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            physicalParent,
            listOf(filter.value),
        ) { filter.copy(field = it) }
        is GreaterThanOrEqualFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            physicalParent,
            listOf(filter.value),
        ) { filter.copy(field = it) }
        is LessThanFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            physicalParent,
            listOf(filter.value),
        ) {
            filter.copy(field = it)
        }
        is LessThanOrEqualFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            physicalParent,
            listOf(filter.value),
        ) { filter.copy(field = it) }
        is BetweenFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            physicalParent,
            listOf(filter.lowerBound, filter.upperBound),
        ) {
            filter.copy(field = it)
        }
        is IsEmptyFilter -> resolveCollectionFieldFilter(
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is IsEmptyStringFilter -> resolveStringFieldFilter(filter.field, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is IsNotEmptyStringFilter -> resolveStringFieldFilter(filter.field, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is IsNullFilter -> resolveFieldFilter(filter.field, QueryCapability.PRESENCE, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is IsNotNullFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is ExistsFilter -> resolveFieldFilter(filter.field, QueryCapability.PRESENCE, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is NotExistsFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is RelativeTimeFilter -> resolveRelativeTime(filter, logicalParent, physicalParent)
        is SearchFilter -> resolveSearch(filter, logicalParent, physicalParent)
        is ElementMatchFilter -> resolveElementMatch(filter, logicalParent, physicalParent)
    }

    private fun resolveElementMatch(
        filter: ElementMatchFilter,
        logicalParent: LogicalField?,
        physicalParent: String?,
    ): QuerySchemaResolution<FilterExpression> {
        val container = fieldResolver.resolve(
            filter.field,
            QueryCapability.ELEMENT_SCOPE,
            logicalParent,
            physicalParent,
        )
        val predicate = resolve(filter.predicate, container.logical, container.physicalPath)
        val value = if (container.physicalPath == null) {
            filter
        } else {
            ElementMatchFilter(LogicalField(container.value), predicate.value)
        }
        return QuerySchemaResolution(
            value,
            listOf(container.compatibility, predicate.compatibility).combined(),
        )
    }

    private fun resolveSearch(
        filter: SearchFilter,
        logicalParent: LogicalField?,
        physicalParent: String?,
    ): QuerySchemaResolution<FilterExpression> {
        val capability = when (filter.mode) {
            SearchMode.TERMS -> QueryCapability.FULL_TEXT_TERMS
            SearchMode.PHRASE -> QueryCapability.FULL_TEXT_PHRASE
        }
        if (filter.fields.isEmpty()) {
            val compatibility = if (capability in schema.capabilities) {
                QueryCompatibilityLevel.EXACT
            } else {
                QueryCompatibilityLevel.INCOMPATIBLE
            }
            return QuerySchemaResolution(filter, compatibility)
        }
        val fields = filter.fields.map { fieldResolver.resolve(it, capability, logicalParent, physicalParent) }
        if (fields.any { !it.elementScopeAccepted }) {
            return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
        }
        if (fields.all { it.compatibility == QueryCompatibilityLevel.EXACT }) {
            return QuerySchemaResolution(
                filter.copy(fields = fields.mapTo(linkedSetOf()) { LogicalField(it.value) }),
                QueryCompatibilityLevel.EXACT,
            )
        }
        if (capability in schema.capabilities) {
            return QuerySchemaResolution(filter.copy(fields = emptySet()), QueryCompatibilityLevel.COMPATIBLE)
        }
        return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
    }

    private fun resolveOperands(
        operands: List<FilterExpression>,
        logicalParent: LogicalField?,
        physicalParent: String?,
        create: (List<FilterExpression>) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = operands.map { resolve(it, logicalParent, physicalParent) }
        return QuerySchemaResolution(
            create(resolved.map { it.value }),
            resolved.map { it.compatibility }.combined(),
        )
    }

    private inline fun resolveFieldFilter(
        field: LogicalField,
        capability: QueryCapability,
        logicalParent: LogicalField?,
        physicalParent: String?,
        values: Iterable<JsonNode> = emptyList(),
        copy: (LogicalField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(field, capability, logicalParent, physicalParent)
        return QuerySchemaResolution(
            copy(LogicalField(resolved.value)),
            listOf(resolved.compatibility, resolved.valueCompatibility(values)).combined(),
        )
    }

    private inline fun resolveCollectionFieldFilter(
        field: LogicalField,
        capability: QueryCapability,
        logicalParent: LogicalField?,
        physicalParent: String?,
        values: Iterable<JsonNode> = emptyList(),
        copy: (LogicalField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(field, capability, logicalParent, physicalParent)
        val cardinality = if (schema.fields[resolved.logical]?.cardinality == QueryCardinality.SINGLE) {
            QueryCompatibilityLevel.INCOMPATIBLE
        } else {
            QueryCompatibilityLevel.EXACT
        }
        val compatibility = listOf(
            resolved.compatibility,
            cardinality,
            resolved.valueCompatibility(values),
        ).combined()
        return QuerySchemaResolution(copy(LogicalField(resolved.value)), compatibility)
    }

    private inline fun resolveStringFieldFilter(
        field: LogicalField,
        logicalParent: LogicalField?,
        physicalParent: String?,
        copy: (LogicalField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(field, QueryCapability.EXACT_MATCH, logicalParent, physicalParent)
        val stringField = when {
            resolved.compatibility != QueryCompatibilityLevel.EXACT -> QueryCompatibilityLevel.EXACT
            resolved.fieldSchema?.cardinality == QueryCardinality.SINGLE &&
                resolved.fieldSchema.valueTypes == setOf(QueryValueType.STRING) -> QueryCompatibilityLevel.EXACT
            else -> QueryCompatibilityLevel.INCOMPATIBLE
        }
        val compatibility = listOf(
            resolved.compatibility,
            stringField,
        ).combined()
        return QuerySchemaResolution(copy(LogicalField(resolved.value)), compatibility)
    }

    private fun resolveRelativeTime(
        filter: RelativeTimeFilter,
        logicalParent: LogicalField?,
        physicalParent: String?,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(filter.field, QueryCapability.RANGE, logicalParent, physicalParent)
        if (resolved.compatibility != QueryCompatibilityLevel.EXACT) {
            return QuerySchemaResolution(filter, resolved.compatibility)
        }
        if (filter.dateFormatter != null) {
            return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
        }
        val physicalField = LogicalField(resolved.value)
        val configured = when (val temporal = resolved.fieldSchema?.semanticType) {
            is Temporal.Epoch -> {
                if (filter.datePattern != null) {
                    return QuerySchemaResolution(
                        filter,
                        QueryCompatibilityLevel.INCOMPATIBLE,
                    )
                }
                filter.copyResolved(physicalField, timeUnit = temporal.timeUnit)
            }
            is Temporal.Formatted -> {
                if (filter.datePattern != null && filter.datePattern != temporal.pattern) {
                    return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
                }
                filter.copyResolved(physicalField, datePattern = temporal.pattern)
            }
            Temporal.Date -> {
                if (filter.datePattern != null) {
                    return QuerySchemaResolution(
                        filter,
                        QueryCompatibilityLevel.INCOMPATIBLE,
                    )
                }
                filter.copyResolved(physicalField)
            }
            else -> return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
        }
        return QuerySchemaResolution(configured, QueryCompatibilityLevel.EXACT)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun RelativeTimeFilter.copyResolved(
        field: LogicalField,
        datePattern: String? = this.datePattern,
        timeUnit: TimeUnit = this.timeUnit,
    ): RelativeTimeFilter = when (this) {
        is TodayFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is BeforeTodayFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is TomorrowFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is ThisWeekFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is NextWeekFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is LastWeekFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is ThisMonthFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is LastMonthFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is RecentDaysFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is EarlierDaysFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is YesterdayFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is NextMonthFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is LastYearFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is ThisYearFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is NextYearFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
    }

    private fun metadata(
        filter: FilterExpression,
        field: String,
    ): QuerySchemaResolution<FilterExpression> = QuerySchemaResolution(
        filter,
        fieldResolver.resolve(LogicalField(field), QueryCapability.EXACT_MATCH, null, null).compatibility,
    )

    private fun QueryFieldResolution.valueCompatibility(values: Iterable<JsonNode>): QueryCompatibilityLevel {
        if (declaredValueTypes.isEmpty() || declaredValueTypes.any { it !in BUILT_IN_QUERY_VALUE_TYPES }) {
            return QueryCompatibilityLevel.EXACT
        }
        return if (values.all { value ->
                value.isNull || declaredValueTypes.any { type -> value.matches(type) }
            }
        ) {
            QueryCompatibilityLevel.EXACT
        } else {
            QueryCompatibilityLevel.INCOMPATIBLE
        }
    }
}

private fun JsonNode.queryValues(): Iterable<JsonNode> {
    val pojo = pojoValue
    return when {
        isArray -> this
        pojo is Iterable<*> -> pojo.map { JsonNodeFactory.instance.pojoNode(it) }
        pojo?.javaClass?.isArray == true -> (0 until ReflectArray.getLength(pojo)).map {
            JsonNodeFactory.instance.pojoNode(ReflectArray.get(pojo, it))
        }
        else -> listOf(this)
    }
}

private fun JsonNode.matches(type: QueryValueType): Boolean {
    if (isPojo) return pojoValue.matches(type)
    return when (type) {
        QueryValueType.STRING -> isString
        QueryValueType.INTEGER -> isNumber && canConvertToExactIntegral()
        QueryValueType.DECIMAL -> isNumber
        QueryValueType.BOOLEAN -> isBoolean
        QueryValueType.OBJECT -> isObject
        else -> true
    }
}

private val JsonNode.pojoValue: Any?
    get() = (this as? POJONode)?.pojo

private fun Any?.matches(type: QueryValueType): Boolean = when (this) {
    is CharSequence,
    is Char,
    is Enum<*>,
    -> type == QueryValueType.STRING
    is Boolean -> type == QueryValueType.BOOLEAN
    is Byte,
    is Short,
    is Int,
    is Long,
    is BigInteger,
    -> type == QueryValueType.INTEGER || type == QueryValueType.DECIMAL
    is Float,
    is Double,
    is BigDecimal,
    -> type.matchesNumber(this as Number)
    else -> true
}

private fun QueryValueType.matchesNumber(value: Number): Boolean {
    if (this == QueryValueType.DECIMAL) return true
    if (this != QueryValueType.INTEGER) return false
    val node = when (value) {
        is Float -> JsonNodeFactory.instance.numberNode(value)
        is Double -> JsonNodeFactory.instance.numberNode(value)
        is BigDecimal -> JsonNodeFactory.instance.numberNode(value)
        else -> return true
    }
    return node.canConvertToExactIntegral()
}
