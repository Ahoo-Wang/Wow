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
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import reactor.core.publisher.Mono
import java.util.Optional

enum class QuerySchemaValidationMode {
    COMPATIBLE,
    STRICT,
    ;

    fun accepts(compatibility: QueryCompatibilityLevel): Boolean = when (this) {
        COMPATIBLE -> compatibility != QueryCompatibilityLevel.INCOMPATIBLE
        STRICT -> compatibility == QueryCompatibilityLevel.EXACT
    }
}

data class QuerySchemaResolution<T>(
    val value: T,
    val compatibility: QueryCompatibilityLevel,
)

fun <T> QuerySchemaResolution<T>.requireAccepted(mode: QuerySchemaValidationMode): T {
    if (!mode.accepts(compatibility)) {
        throw QuerySchemaValidationException(
            "Query compatibility [$compatibility] is rejected by mode [$mode].",
        )
    }
    return value
}

class QuerySchemaResolver(private val schema: QueryModelSchema) {
    fun resolve(query: ISingleQuery): QuerySchemaResolution<ISingleQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            SingleQuery(filter.value, projection.value, sort.value),
            listOf(filter.compatibility, projection.compatibility, sort.compatibility).combined(),
        )
    }

    fun resolve(query: IListQuery): QuerySchemaResolution<IListQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            ListQuery(filter.value, projection.value, sort.value, query.limit),
            listOf(filter.compatibility, projection.compatibility, sort.compatibility).combined(),
        )
    }

    fun resolve(query: IPagedQuery): QuerySchemaResolution<IPagedQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            PagedQuery(filter.value, projection.value, sort.value, query.pagination),
            listOf(filter.compatibility, projection.compatibility, sort.compatibility).combined(),
        )
    }

    fun resolve(filter: FilterExpression): QuerySchemaResolution<FilterExpression> =
        resolveFilter(filter, logicalParent = null, physicalParent = null)

    fun resolve(projection: Projection): QuerySchemaResolution<Projection> {
        val include = projection.include.map { resolvePath(it, QueryCapability.PRESENCE) }
        val exclude = projection.exclude.map { resolvePath(it, QueryCapability.PRESENCE) }
        return QuerySchemaResolution(
            Projection(
                include.map { it.value },
                exclude.map { it.value },
            ),
            (include + exclude).map { it.compatibility }.combined(),
        )
    }

    fun resolve(sort: List<Sort>): QuerySchemaResolution<List<Sort>> {
        val resolved = sort.map { item ->
            resolvePath(item.field, QueryCapability.SORT).let { field ->
                item.copy(field = field.value) to field.compatibility
            }
        }
        return QuerySchemaResolution(
            resolved.map(Pair<Sort, QueryCompatibilityLevel>::first),
            resolved.map(Pair<Sort, QueryCompatibilityLevel>::second).combined(),
        )
    }

    fun resolve(query: AggregationQuery): QuerySchemaResolution<AggregationQuery> {
        val levels = mutableListOf(resolve(query.filter).compatibility)
        var logicalParent: LogicalField? = null
        var physicalParent: String? = null
        query.elements.forEach { element ->
            val container = resolveAggregationField(
                element.path,
                QueryCapability.ELEMENT_SCOPE,
                logicalParent,
                physicalParent,
            )
            levels += container.compatibility
            levels += resolveFilter(element.filter, container.logical, container.physicalPath).compatibility
            logicalParent = container.logical
            physicalParent = container.physicalPath
        }
        query.groupBy.forEach { group ->
            levels += resolveAggregationField(
                group.field,
                group.capability,
                logicalParent,
                physicalParent,
            ).compatibility
        }
        query.metrics.filterIsInstance<AggregationMetric.Numeric>().forEach { metric ->
            collectExpressionLevels(metric.expression, logicalParent, physicalParent, levels)
        }
        return QuerySchemaResolution(query, levels.combined())
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun resolveFilter(
        filter: FilterExpression,
        logicalParent: LogicalField?,
        physicalParent: String?,
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
        is EqualFilter -> resolveFieldFilter(filter.field, QueryCapability.EXACT_MATCH, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is NotEqualFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is InFilter -> resolveFieldFilter(filter.field, QueryCapability.EXACT_MATCH, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is NotInFilter -> resolveFieldFilter(filter.field, QueryCapability.EXACT_MATCH, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is ContainsAllFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.EXACT_MATCH,
            logicalParent,
            physicalParent,
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
        ) { filter.copy(field = it) }
        is GreaterThanOrEqualFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is LessThanFilter -> resolveFieldFilter(filter.field, QueryCapability.RANGE, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is LessThanOrEqualFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.RANGE,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
        is BetweenFilter -> resolveFieldFilter(filter.field, QueryCapability.RANGE, logicalParent, physicalParent) {
            filter.copy(field = it)
        }
        is IsEmptyFilter -> resolveFieldFilter(
            filter.field,
            QueryCapability.PRESENCE,
            logicalParent,
            physicalParent,
        ) { filter.copy(field = it) }
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
        is TodayFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is BeforeTodayFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is TomorrowFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is ThisWeekFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is NextWeekFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is LastWeekFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is ThisMonthFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is LastMonthFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is RecentDaysFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is EarlierDaysFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is YesterdayFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is NextMonthFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is LastYearFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is ThisYearFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is NextYearFilter -> range(filter.field, logicalParent, physicalParent) { filter.copy(field = it) }
        is SearchFilter -> resolveSearch(filter, logicalParent, physicalParent)
        is ElementMatchFilter -> resolveElementMatch(filter, logicalParent, physicalParent)
    }

    private fun resolveElementMatch(
        filter: ElementMatchFilter,
        logicalParent: LogicalField?,
        physicalParent: String?,
    ): QuerySchemaResolution<FilterExpression> {
        val container = resolveField(filter.field, QueryCapability.ELEMENT_SCOPE, logicalParent, physicalParent)
        val predicate = resolveFilter(filter.predicate, container.logical, container.physicalPath)
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
        val fields = filter.fields.map { resolveField(it, capability, logicalParent, physicalParent) }
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
        val resolved = operands.map { resolveFilter(it, logicalParent, physicalParent) }
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
        copy: (LogicalField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> {
        val resolved = resolveField(field, capability, logicalParent, physicalParent)
        return QuerySchemaResolution(copy(LogicalField(resolved.value)), resolved.compatibility)
    }

    private inline fun range(
        field: LogicalField,
        logicalParent: LogicalField?,
        physicalParent: String?,
        copy: (LogicalField) -> FilterExpression,
    ): QuerySchemaResolution<FilterExpression> = resolveFieldFilter(
        field,
        QueryCapability.RANGE,
        logicalParent,
        physicalParent,
        copy,
    )

    private fun metadata(
        filter: FilterExpression,
        field: String,
    ): QuerySchemaResolution<FilterExpression> = QuerySchemaResolution(
        filter,
        resolveField(LogicalField(field), QueryCapability.EXACT_MATCH, null, null).compatibility,
    )

    private fun resolvePath(
        path: String,
        capability: QueryCapability,
    ): QuerySchemaResolution<String> {
        val logicalField = try {
            LogicalField(path)
        } catch (_: IllegalArgumentException) {
            return QuerySchemaResolution(path, QueryCompatibilityLevel.COMPATIBLE)
        }
        val resolved = resolveField(logicalField, capability, null, null)
        return QuerySchemaResolution(resolved.value, resolved.compatibility)
    }

    private fun resolveField(
        field: LogicalField,
        capability: QueryCapability,
        logicalParent: LogicalField?,
        physicalParent: String?,
    ): FieldResolution {
        val logical = field.absoluteTo(logicalParent)
        val fieldSchema = schema.resolve(logical)
            ?: return FieldResolution(logical, field.value, null, QueryCompatibilityLevel.COMPATIBLE)
        val binding = fieldSchema.bindings[capability]
            ?: return FieldResolution(logical, field.value, null, QueryCompatibilityLevel.INCOMPATIBLE)
        val relativePath = binding.physicalPath.relativeTo(physicalParent)
            ?: return FieldResolution(logical, field.value, null, QueryCompatibilityLevel.INCOMPATIBLE)
        return FieldResolution(
            logical,
            relativePath,
            binding.physicalPath,
            QueryCompatibilityLevel.EXACT,
        )
    }

    private fun collectExpressionLevels(
        expression: AggregationExpression,
        logicalParent: LogicalField?,
        physicalParent: String?,
        levels: MutableList<QueryCompatibilityLevel>,
    ) {
        when (expression) {
            is AggregationExpression.Field -> levels += resolveAggregationField(
                expression.field,
                QueryCapability.AGGREGATE_NUMERIC,
                logicalParent,
                physicalParent,
            ).compatibility
            is AggregationExpression.Constant -> Unit
            is AggregationExpression.Binary -> {
                collectExpressionLevels(expression.left, logicalParent, physicalParent, levels)
                collectExpressionLevels(expression.right, logicalParent, physicalParent, levels)
            }
            else -> levels += QueryCompatibilityLevel.INCOMPATIBLE
        }
    }

    private data class FieldResolution(
        val logical: LogicalField,
        val value: String,
        val physicalPath: String?,
        val compatibility: QueryCompatibilityLevel,
    )

    private fun resolveAggregationField(
        field: LogicalField,
        capability: QueryCapability,
        logicalParent: LogicalField?,
        physicalParent: String?,
    ): FieldResolution = resolveField(
        if (logicalParent == null) field else LogicalField("${logicalParent.value}.${field.value}"),
        capability,
        logicalParent = null,
        physicalParent,
    )

    private val AggregationGroup.capability: QueryCapability
        get() = when (this) {
            is AggregationGroup.Terms -> QueryCapability.AGGREGATE_TERMS
            is AggregationGroup.Histogram -> QueryCapability.AGGREGATE_NUMERIC
            is AggregationGroup.DateHistogram -> QueryCapability.AGGREGATE_TEMPORAL
        }

    private fun LogicalField.absoluteTo(parent: LogicalField?): LogicalField =
        if (parent == null || value == parent.value || value.startsWith("${parent.value}.")) {
            this
        } else {
            LogicalField("${parent.value}.$value")
        }

    private fun String.relativeTo(parent: String?): String? = when {
        parent == null -> this
        startsWith("$parent.") -> substring(parent.length + 1)
        else -> null
    }
}

private fun Iterable<QueryCompatibilityLevel>.combined(): QueryCompatibilityLevel = when {
    QueryCompatibilityLevel.INCOMPATIBLE in this -> QueryCompatibilityLevel.INCOMPATIBLE
    QueryCompatibilityLevel.COMPATIBLE in this -> QueryCompatibilityLevel.COMPATIBLE
    else -> QueryCompatibilityLevel.EXACT
}

fun QueryModelSchemaProvider.resolve(
    query: ISingleQuery,
    mode: QuerySchemaValidationMode,
): Mono<ISingleQuery> = schema()
    .map { QuerySchemaResolver(it).resolve(query).requireAccepted(mode) }
    .fallbackUnavailable(mode, query)

fun QueryModelSchemaProvider.resolve(
    query: IListQuery,
    mode: QuerySchemaValidationMode,
): Mono<IListQuery> = schema()
    .map { QuerySchemaResolver(it).resolve(query).requireAccepted(mode) }
    .fallbackUnavailable(mode, query)

fun QueryModelSchemaProvider.resolve(
    query: IPagedQuery,
    mode: QuerySchemaValidationMode,
): Mono<IPagedQuery> = schema()
    .map { QuerySchemaResolver(it).resolve(query).requireAccepted(mode) }
    .fallbackUnavailable(mode, query)

fun QueryModelSchemaProvider.resolve(
    filter: FilterExpression,
    mode: QuerySchemaValidationMode,
): Mono<FilterExpression> = schema()
    .map { QuerySchemaResolver(it).resolve(filter).requireAccepted(mode) }
    .fallbackUnavailable(mode, filter)

fun QueryModelSchemaProvider.resolve(
    query: AggregationQuery,
    mode: QuerySchemaValidationMode,
): Mono<Optional<QueryModelSchema>> = schema()
    .map { schema ->
        QuerySchemaResolver(schema).resolve(query).requireAccepted(mode)
        Optional.of(schema)
    }
    .fallbackUnavailable(mode, Optional.empty())

private fun <T : Any> Mono<T>.fallbackUnavailable(
    mode: QuerySchemaValidationMode,
    fallback: T,
): Mono<T> = onErrorResume(QuerySchemaUnavailableException::class.java) { error ->
    if (mode == QuerySchemaValidationMode.COMPATIBLE) Mono.just(fallback) else Mono.error(error)
}
