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
import me.ahoo.wow.api.query.schema.QueryModel
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
        resolvedParent: QueryField? = null,
        physicalParent: QueryField? = null,
    ): QuerySchemaResolution<FilterExpression> = when (filter) {
        MatchAllFilter,
        MatchNoneFilter,
        -> QuerySchemaResolution(filter, QueryCompatibilityLevel.EXACT)

        is IdFilter,
        is IdsFilter,
        -> metadata(
            filter,
            if (schema.model == QueryModel.EVENT_STREAM) {
                MessageRecords.ID
            } else {
                MessageRecords.AGGREGATE_ID
            },
        )
        is AggregateIdFilter,
        is AggregateIdsFilter,
        -> metadata(filter, MessageRecords.AGGREGATE_ID)

        is TenantIdFilter -> metadata(filter, MessageRecords.TENANT_ID)
        is OwnerIdFilter -> metadata(filter, MessageRecords.OWNER_ID)
        is SpaceIdFilter -> metadata(filter, MessageRecords.SPACE_ID)
        is DeletionFilter -> metadata(filter, StateAggregateRecords.DELETED)
        is AndFilter -> resolveOperands(
            filter,
            filter.operands,
            logicalParent,
            resolvedParent,
            physicalParent,
            ::AndFilter
        )
        is OrFilter -> resolveOperands(
            filter,
            filter.operands,
            logicalParent,
            resolvedParent,
            physicalParent,
            ::OrFilter
        )
        is NorFilter -> resolveOperands(
            filter,
            filter.operands,
            logicalParent,
            resolvedParent,
            physicalParent,
            ::NorFilter
        )
        is EqualFilter -> resolveFieldFilter(
            filter,
            filter.field,
            if (filter.value.isNull) QueryCapability.PRESENCE else QueryCapability.EXACT_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
            filter.value.queryValues(),
        ) {
            filter.copy(field = it)
        }
        is NotEqualFilter -> resolveFieldFilter(
            filter,
            filter.field,
            if (filter.value.isNull) QueryCapability.PRESENCE else QueryCapability.EXACT_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
            filter.value.queryValues(),
        ) { filter.copy(field = it) }
        is InFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
            filter.values,
        ) {
            filter.copy(field = it)
        }
        is NotInFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
            filter.values,
        ) {
            filter.copy(field = it)
        }
        is ContainsAllFilter -> resolveCollectionFieldFilter(
            filter,
            filter.field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
            filter.values,
        ) { filter.copy(field = it) }
        is ContainsFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.LITERAL_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is StartsWithFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.LITERAL_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is EndsWithFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.LITERAL_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is GreaterThanFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            resolvedParent,
            physicalParent,
            listOf(filter.value),
        ) { filter.copy(field = it) }
        is GreaterThanOrEqualFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            resolvedParent,
            physicalParent,
            listOf(filter.value),
        ) { filter.copy(field = it) }
        is LessThanFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            resolvedParent,
            physicalParent,
            listOf(filter.value),
        ) {
            filter.copy(field = it)
        }
        is LessThanOrEqualFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            resolvedParent,
            physicalParent,
            listOf(filter.value),
        ) { filter.copy(field = it) }
        is BetweenFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            resolvedParent,
            physicalParent,
            listOf(filter.lowerBound, filter.upperBound),
        ) {
            filter.copy(field = it)
        }
        is IsEmptyFilter -> resolveCollectionFieldFilter(
            filter,
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is IsEmptyStringFilter -> resolveStringFieldFilter(
            filter,
            filter.field,
            logicalParent,
            resolvedParent,
            physicalParent
        ) {
            filter.copy(field = it)
        }
        is IsNotEmptyStringFilter -> resolveStringFieldFilter(
            filter,
            filter.field,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) {
            filter.copy(field = it)
        }
        is IsNullFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) {
            filter.copy(field = it)
        }
        is IsNotNullFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is ExistsFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) {
            filter.copy(field = it)
        }
        is NotExistsFilter -> resolveFieldFilter(
            filter,
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            resolvedParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is RelativeTimeFilter -> resolveRelativeTime(filter, logicalParent, resolvedParent, physicalParent)
        is SearchFilter -> resolveSearch(filter, logicalParent, resolvedParent, physicalParent)
        is ElementMatchFilter -> resolveElementMatch(filter, logicalParent, resolvedParent, physicalParent)
    }

    private fun resolveElementMatch(
        filter: ElementMatchFilter,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
    ): QuerySchemaResolution<FilterExpression> {
        val container = fieldResolver.resolve(
            filter.field,
            QueryCapability.ELEMENT_SCOPE,
            logicalParent,
            resolvedParent,
            physicalParent,
        )
        val predicate = resolve(
            filter.predicate,
            container.logical,
            container.resolvedField,
            container.physicalField,
        )
        val value = if (
            schema.rewriteMode == QueryRewriteMode.NONE ||
            container.value === filter.field && predicate.value === filter.predicate
        ) {
            filter
        } else {
            ElementMatchFilter(container.value, predicate.value)
        }
        return QuerySchemaResolution(
            value,
            maxOf(container.compatibility, predicate.compatibility),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun resolveSearch(
        filter: SearchFilter,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
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
        var allExact = true
        var resolvedFields: LinkedHashSet<QueryField>? = null
        filter.fields.forEach { field ->
            val resolved = fieldResolver.resolve(
                field,
                capability,
                logicalParent,
                resolvedParent,
                physicalParent,
            )
            if (!resolved.elementScopeAccepted) {
                return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
            }
            allExact = allExact && resolved.compatibility == QueryCompatibilityLevel.EXACT
            if (schema.rewriteMode != QueryRewriteMode.NONE) {
                val values = resolvedFields
                    ?: LinkedHashSet<QueryField>(filter.fields.size).also { resolvedFields = it }
                values += resolved.value
            }
        }
        if (allExact) {
            return QuerySchemaResolution(
                if (resolvedFields == null || resolvedFields == filter.fields) {
                    filter
                } else {
                    filter.copy(fields = resolvedFields)
                },
                QueryCompatibilityLevel.EXACT,
            )
        }
        if (capability in schema.capabilities) {
            return QuerySchemaResolution(
                filter.copy(fields = emptySet()),
                QueryCompatibilityLevel.COMPATIBLE,
            )
        }
        return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
    }

    private fun resolveOperands(
        original: FilterExpression,
        operands: List<FilterExpression>,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
        create: (List<FilterExpression>) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        var values: ArrayList<FilterExpression>? = null
        var compatibility = QueryCompatibilityLevel.EXACT
        operands.forEachIndexed { index, operand ->
            val resolved = resolve(operand, logicalParent, resolvedParent, physicalParent)
            if (values != null) {
                values += resolved.value
            } else if (resolved.value !== operand) {
                values = ArrayList<FilterExpression>(operands.size).apply {
                    addAll(operands.subList(0, index))
                    add(resolved.value)
                }
            }
            compatibility = maxOf(compatibility, resolved.compatibility)
        }
        return QuerySchemaResolution(values?.let(create) ?: original, compatibility)
    }

    private inline fun resolveFieldFilter(
        original: FilterExpression,
        field: QueryField,
        capability: QueryCapability,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
        values: Iterable<JsonNode> = emptyList(),
        copy: (QueryField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(field, capability, logicalParent, resolvedParent, physicalParent)
        return QuerySchemaResolution(
            if (schema.rewriteMode == QueryRewriteMode.NONE || resolved.value === field) {
                original
            } else {
                copy(resolved.value)
            },
            maxOf(resolved.compatibility, resolved.valueCompatibility(values)),
        )
    }

    private inline fun resolveCollectionFieldFilter(
        original: FilterExpression,
        field: QueryField,
        capability: QueryCapability,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
        values: Iterable<JsonNode> = emptyList(),
        copy: (QueryField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(field, capability, logicalParent, resolvedParent, physicalParent)
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
        return QuerySchemaResolution(
            if (schema.rewriteMode == QueryRewriteMode.NONE || resolved.value === field) {
                original
            } else {
                copy(resolved.value)
            },
            compatibility,
        )
    }

    private inline fun resolveStringFieldFilter(
        original: FilterExpression,
        field: QueryField,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
        copy: (QueryField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(
            field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            resolvedParent,
            physicalParent,
        )
        val stringField = when {
            resolved.compatibility != QueryCompatibilityLevel.EXACT -> QueryCompatibilityLevel.EXACT
            resolved.fieldSchema?.cardinality == QueryCardinality.SINGLE &&
                resolved.fieldSchema.valueTypes == setOf(QueryValueType.STRING) -> QueryCompatibilityLevel.EXACT
            else -> QueryCompatibilityLevel.INCOMPATIBLE
        }
        val compatibility = maxOf(resolved.compatibility, stringField)
        return QuerySchemaResolution(
            if (schema.rewriteMode == QueryRewriteMode.NONE || resolved.value === field) {
                original
            } else {
                copy(resolved.value)
            },
            compatibility,
        )
    }

    private fun resolveRelativeTime(
        filter: RelativeTimeFilter,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = fieldResolver.resolve(
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            resolvedParent,
            physicalParent,
        )
        if (resolved.compatibility != QueryCompatibilityLevel.EXACT) {
            return QuerySchemaResolution(filter, resolved.compatibility)
        }
        if (filter.dateFormatter != null) {
            return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
        }
        val resolvedField = resolved.value
        val configured = when (val temporal = resolved.fieldSchema?.semanticType) {
            is Temporal.Epoch -> {
                if (filter.datePattern != null) {
                    return QuerySchemaResolution(
                        filter,
                        QueryCompatibilityLevel.INCOMPATIBLE,
                    )
                }
                filter.copyResolved(resolvedField, timeUnit = temporal.timeUnit)
            }
            is Temporal.Formatted -> {
                if (filter.datePattern != null && filter.datePattern != temporal.pattern) {
                    return QuerySchemaResolution(filter, QueryCompatibilityLevel.INCOMPATIBLE)
                }
                filter.copyResolved(resolvedField, datePattern = temporal.pattern)
            }
            Temporal.Date -> {
                if (filter.datePattern != null) {
                    return QuerySchemaResolution(
                        filter,
                        QueryCompatibilityLevel.INCOMPATIBLE,
                    )
                }
                filter.copyResolved(resolvedField)
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
    ): RelativeTimeFilter {
        if (field === this.field && datePattern == this.datePattern && timeUnit == this.timeUnit) return this
        return when (this) {
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
    }

    private fun metadata(
        filter: FilterExpression,
        field: String,
    ): QuerySchemaResolution<FilterExpression> = QuerySchemaResolution(
        filter,
        fieldResolver.resolve(QueryField(field), QueryCapability.EXACT_MATCH, null, null, null).compatibility,
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
