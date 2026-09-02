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
import java.util.concurrent.TimeUnit
import java.lang.reflect.Array as ReflectArray

internal class QueryFilterSchemaResolver(
    private val schema: QueryModelSchema,
    private val fieldResolver: QueryFieldSchemaResolver,
) {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun resolve(
        filter: FilterExpression,
        logicalParent: QueryField? = null,
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
        logicalParent: QueryField?,
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
            ElementMatchFilter(fieldResolver.resolveQueryField(container.value), predicate.value)
        }
        return QuerySchemaResolution(
            value,
            maxOf(container.compatibility, predicate.compatibility),
        )
    }

    private fun resolveSearch(
        filter: SearchFilter,
        logicalParent: QueryField?,
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
                filter.copy(fields = fields.mapTo(linkedSetOf()) { fieldResolver.resolveQueryField(it.value) }),
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
        logicalParent: QueryField?,
        physicalParent: String?,
        create: (List<FilterExpression>) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val values = ArrayList<FilterExpression>(operands.size)
        var compatibility = QueryCompatibilityLevel.EXACT
        operands.forEach { operand ->
            val resolved = resolve(operand, logicalParent, physicalParent)
            values += resolved.value
            compatibility = maxOf(compatibility, resolved.compatibility)
        }
        return QuerySchemaResolution(create(values), compatibility)
    }

    private inline fun resolveFieldFilter(
        field: QueryField,
        capability: QueryCapability,
        logicalParent: QueryField?,
        physicalParent: String?,
        values: Iterable<JsonNode> = emptyList(),
        copy: (QueryField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(field, capability, logicalParent, physicalParent)
        return QuerySchemaResolution(
            copy(fieldResolver.resolveQueryField(resolved.value)),
            maxOf(resolved.compatibility, resolved.valueCompatibility(values)),
        )
    }

    private inline fun resolveCollectionFieldFilter(
        field: QueryField,
        capability: QueryCapability,
        logicalParent: QueryField?,
        physicalParent: String?,
        values: Iterable<JsonNode> = emptyList(),
        copy: (QueryField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(field, capability, logicalParent, physicalParent)
        val cardinality = if (schema.fields[resolved.logical]?.cardinality == QueryCardinality.SINGLE) {
            QueryCompatibilityLevel.INCOMPATIBLE
        } else {
            QueryCompatibilityLevel.EXACT
        }
        val compatibility = maxOf(
            resolved.compatibility,
            cardinality,
            resolved.valueCompatibility(values),
        )
        return QuerySchemaResolution(copy(fieldResolver.resolveQueryField(resolved.value)), compatibility)
    }

    private inline fun resolveStringFieldFilter(
        field: QueryField,
        logicalParent: QueryField?,
        physicalParent: String?,
        copy: (QueryField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(field, QueryCapability.EXACT_MATCH, logicalParent, physicalParent)
        val stringField = when {
            resolved.compatibility != QueryCompatibilityLevel.EXACT -> QueryCompatibilityLevel.EXACT
            resolved.fieldSchema?.cardinality == QueryCardinality.SINGLE &&
                resolved.fieldSchema.valueTypes == setOf(QueryValueType.STRING) -> QueryCompatibilityLevel.EXACT
            else -> QueryCompatibilityLevel.INCOMPATIBLE
        }
        val compatibility = maxOf(resolved.compatibility, stringField)
        return QuerySchemaResolution(copy(fieldResolver.resolveQueryField(resolved.value)), compatibility)
    }

    private fun resolveRelativeTime(
        filter: RelativeTimeFilter,
        logicalParent: QueryField?,
        physicalParent: String?,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(filter.field, QueryCapability.RANGE, logicalParent, physicalParent)
        if (resolved.compatibility != QueryCompatibilityLevel.EXACT) {
            return QuerySchemaResolution(filter, resolved.compatibility)
        }
        if (filter.dateFormatter != null) {
            return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
        }
        val physicalField = fieldResolver.resolveQueryField(resolved.value)
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
        field: QueryField,
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
        fieldResolver.resolve(QueryField(field), QueryCapability.EXACT_MATCH, null, null).compatibility,
    )

    private fun QueryFieldResolution.valueCompatibility(values: Iterable<JsonNode>): QueryCompatibilityLevel {
        return if (schema.matchesValueTypes(logical, values)) {
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

private val JsonNode.pojoValue: Any?
    get() = (this as? POJONode)?.pojo
