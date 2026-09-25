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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.spec.SystemField
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.filter.requiredCapability
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.absoluteLogicalField
import me.ahoo.wow.query.schema.firstLastOrderBy
import me.ahoo.wow.query.schema.hasArrayBranch
import me.ahoo.wow.query.schema.projectionField
import me.ahoo.wow.query.schema.requireIdentityField
import me.ahoo.wow.query.schema.requireSchema
import me.ahoo.wow.query.schema.requireValid
import me.ahoo.wow.query.schema.requiredElementAncestors
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.POJONode
import java.util.IdentityHashMap

/**
 * Admission step 6's registration: rebuilds a validated, normalized query with a fresh instance for every node that
 * carries a field and registers each node's [ResolvedField] by identity in the same traversal.
 *
 * Field references are keyed by their [QueryField] instance; system-field filters (ID, TENANT_ID, DELETION, ...),
 * which name no field, are keyed by the filter node. Fresh instances make identity unique by construction: two equal
 * conditions in different element scopes, or one [QueryField] a caller reused across scopes, become distinct keys.
 * One resolver serves one admitted query.
 */
internal class FieldResolver(private val schema: QueryModelSchema) {
    val fields = IdentityHashMap<Any, ResolvedField>()

    private class Scope(val logical: QueryField?, val physical: QueryField?)

    fun single(query: ISingleQuery): ISingleQuery =
        SingleQuery(filter(query.filter), projection(query.projection), sort(query.sort, QueryCapability.SORT))

    fun list(query: IListQuery): IListQuery = ListQuery(
        filter(query.filter),
        projection(query.projection),
        sort(query.sort, QueryCapability.SORT),
        query.limit,
    )

    fun paged(query: IPagedQuery): IPagedQuery = PagedQuery(
        filter(query.filter),
        projection(query.projection),
        sort(query.sort, QueryCapability.SORT),
        query.pagination,
    )

    fun cursor(query: ICursorQuery): ICursorQuery {
        val sort = sort(query.sort, QueryCapability.CURSOR_SORT)
        val physical = sort.map { checkNotNull(fields[it.field]).physicalField }
        if (physical.distinct().size != physical.size) {
            throw QuerySchemaValidationException("Cursor sort fields must map to unique physical fields.")
        }
        return CursorQuery(filter(query.filter), projection(query.projection), sort, query.size, query.cursor)
    }

    fun filter(expression: FilterExpression): FilterExpression = filter(expression, ROOT)

    fun aggregate(query: AggregationQuery): AggregationQuery {
        val filter = filter(query.filter, ROOT)
        var scope = ROOT
        val elements = query.elements.map { element ->
            val path = reference(element.path, QueryCapability.ELEMENT_SCOPE, scope)
            val container = checkNotNull(fields[path])
            scope = Scope(container.logicalField, container.physicalField)
            AggregationElement(path, filter(element.filter, scope))
        }
        val inner = scope
        return query.copy(
            filter = filter,
            elements = elements,
            groupBy = query.groupBy.map { group(it, inner) },
            metrics = query.metrics.map { metric(it, inner) },
        )
    }

    private fun sort(sort: List<Sort>, capability: QueryCapability): List<Sort> {
        val resolved = sort.map { Sort(reference(it.field, capability, ROOT), it.direction) }
        if (schema.storage.parallelArraySort == SupportMode.NONE) {
            requireNoParallelArrays(resolved.map { checkNotNull(fields[it.field]) })
        }
        return resolved
    }

    /** Two array-valued sort fields may combine only on one array path, one nested in the other. */
    private fun requireNoParallelArrays(sort: List<ResolvedField>) {
        val arrays = sort.filter { it.value.hasArrayBranch() }
        arrays.forEachIndexed { index, left ->
            arrays.drop(index + 1).firstOrNull { right -> left.physicalField.independentOf(right.physicalField) }
                ?.let { right ->
                    throw QuerySchemaValidationException(
                        QueryViolation.ParallelArraySort(right.logicalField, left.logicalField)
                    )
                }
        }
    }

    private fun QueryField.independentOf(other: QueryField): Boolean =
        this != other && !path.startsWith("${other.path}.") && !other.path.startsWith("$path.")

    private fun projection(projection: Projection): Projection =
        if (projection.include.isEmpty() && projection.exclude.isEmpty()) {
            projection
        } else {
            Projection(projection.include.map(::projected), projection.exclude.map(::projected))
        }

    private fun projected(field: QueryField): QueryField {
        val physical = schema.projectionField(field)
        return register(field, ResolvedField(field, checkNotNull(schema.field(field)), null, null, physical))
    }

    private fun group(group: AggregationGroup, scope: Scope): AggregationGroup {
        val field = reference(group.field, group.spec.capability, scope)
        return when (group) {
            is AggregationGroup.Terms -> group.copy(field = field)
            is AggregationGroup.Histogram -> group.copy(field = field)
            is AggregationGroup.DateHistogram -> group.copy(field = field)
            is AggregationGroup.DatePart -> group.copy(field = field)
        }
    }

    private fun metric(metric: AggregationMetric, scope: Scope): AggregationMetric = when (metric) {
        is AggregationMetric.Count -> metric.copy(filter = metricFilter(metric.filter, scope))
        is AggregationMetric.Any -> metric.copy(
            field = reference(metric.field, QueryCapability.AGGREGATE_TERMS, scope),
            filter = metricFilter(metric.filter, scope),
        )
        is AggregationMetric.Numeric -> metric.copy(
            expression = expression(metric.expression, scope),
            filter = metricFilter(metric.filter, scope),
        )
        is AggregationMetric.Percentile -> metric.copy(
            expression = expression(metric.expression, scope),
            filter = metricFilter(metric.filter, scope),
        )
        is AggregationMetric.DistinctCount -> metric.copy(
            expression = when (val expression = metric.expression) {
                is AggregationExpression.Field -> AggregationExpression.Field(
                    reference(expression.field, distinctCountCapability(expression.field, scope), scope),
                )
                else -> expression(expression, scope)
            },
            filter = metricFilter(metric.filter, scope),
        )
        is AggregationMetric.First -> metric.copy(
            field = reference(metric.field, distinctCountCapability(metric.field, scope), scope),
            orderBy = reference(schema.firstLastOrderBy(metric, scope.logical), QueryCapability.SORT, scope),
            filter = metricFilter(metric.filter, scope),
        )
        is AggregationMetric.Last -> metric.copy(
            field = reference(metric.field, distinctCountCapability(metric.field, scope), scope),
            orderBy = reference(schema.firstLastOrderBy(metric, scope.logical), QueryCapability.SORT, scope),
            filter = metricFilter(metric.filter, scope),
        )
        is AggregationMetric.Derived -> metric
    }

    private fun metricFilter(filter: FilterExpression, scope: Scope): FilterExpression =
        if (filter === MatchAllFilter) filter else filter(filter, scope)

    /** DISTINCT_COUNT, FIRST and LAST read terms when the field is bound for them, otherwise numeric values. */
    private fun distinctCountCapability(field: QueryField, scope: Scope): QueryCapability {
        val definition = schema.field(absoluteLogicalField(field, scope.logical))
        return if (definition?.binding(QueryCapability.AGGREGATE_TERMS) != null) {
            QueryCapability.AGGREGATE_TERMS
        } else {
            QueryCapability.AGGREGATE_NUMERIC
        }
    }

    private fun expression(expression: AggregationExpression, scope: Scope): AggregationExpression =
        when (expression) {
            is AggregationExpression.Field ->
                AggregationExpression.Field(reference(expression.field, QueryCapability.AGGREGATE_NUMERIC, scope))
            is AggregationExpression.Constant -> expression
            is AggregationExpression.Binary -> expression.copy(
                left = expression(expression.left, scope),
                right = expression(expression.right, scope),
            )
        }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun filter(expression: FilterExpression, scope: Scope): FilterExpression = when (expression) {
        MatchAllFilter, MatchNoneFilter -> expression
        is AndFilter -> AndFilter(expression.operands.map { filter(it, scope) })
        is OrFilter -> OrFilter(expression.operands.map { filter(it, scope) })
        is NorFilter -> NorFilter(expression.operands.map { filter(it, scope) })
        is IdFilter -> system(expression.copy(), expression)
        is IdsFilter -> system(expression.copy(), expression)
        is AggregateIdFilter -> system(expression.copy(), expression)
        is AggregateIdsFilter -> system(expression.copy(), expression)
        is TenantIdFilter -> system(expression.copy(), expression)
        is OwnerIdFilter -> system(expression.copy(), expression)
        is SpaceIdFilter -> system(expression.copy(), expression)
        is DeletionFilter -> system(expression.copy(), expression)
        is EqualFilter -> expression.copy(field = equality(expression, expression.field, expression.value, scope))
        is NotEqualFilter -> expression.copy(field = equality(expression, expression.field, expression.value, scope))
        is GreaterThanFilter -> expression.copy(field = field(expression, expression.field, scope))
        is GreaterThanOrEqualFilter -> expression.copy(field = field(expression, expression.field, scope))
        is LessThanFilter -> expression.copy(field = field(expression, expression.field, scope))
        is LessThanOrEqualFilter -> expression.copy(field = field(expression, expression.field, scope))
        is ContainsFilter -> expression.copy(field = field(expression, expression.field, scope))
        is StartsWithFilter -> expression.copy(field = field(expression, expression.field, scope))
        is EndsWithFilter -> expression.copy(field = field(expression, expression.field, scope))
        is InFilter -> expression.copy(field = field(expression, expression.field, scope))
        is NotInFilter -> expression.copy(field = field(expression, expression.field, scope))
        is BetweenFilter -> expression.copy(field = field(expression, expression.field, scope))
        is ContainsAllFilter -> expression.copy(field = field(expression, expression.field, scope))
        is IsEmptyFilter -> expression.copy(field = field(expression, expression.field, scope))
        is IsNullFilter -> expression.copy(field = field(expression, expression.field, scope))
        is IsNotNullFilter -> expression.copy(field = field(expression, expression.field, scope))
        is ExistsFilter -> expression.copy(field = field(expression, expression.field, scope))
        is NotExistsFilter -> expression.copy(field = field(expression, expression.field, scope))
        is ElementMatchFilter -> {
            val container = field(expression, expression.field, scope)
            val resolved = checkNotNull(fields[container])
            ElementMatchFilter(
                container,
                filter(expression.predicate, Scope(resolved.logicalField, resolved.physicalField)),
            )
        }
        is SearchFilter -> {
            val capability = expression.requiredCapability()
            expression.copy(fields = expression.fields.mapTo(LinkedHashSet()) { reference(it, capability, scope) })
        }
        is IsEmptyStringFilter, is IsNotEmptyStringFilter, is RelativeTimeFilter ->
            error("Filter [${expression.operator}] must be normalized before its fields are resolved.")
    }

    private fun field(node: FilterExpression, field: QueryField, scope: Scope): QueryField =
        reference(field, node.requiredCapability(), scope)

    /** An equality field; its operand must be a scalar when the storage cannot compare whole arrays. */
    private fun equality(node: FilterExpression, field: QueryField, value: JsonNode, scope: Scope): QueryField {
        val resolved = field(node, field, scope)
        if (schema.storage.arrayEquality == SupportMode.NONE) {
            val canonical = if (value is POJONode) JsonSerializer.valueToTree<JsonNode>(value.pojo) else value
            val logical = checkNotNull(fields[resolved]).logicalField
            requireValid(!canonical.isArray) { QueryViolation.ArrayEquality(logical) }
        }
        return resolved
    }

    private fun system(fresh: FilterExpression, original: FilterExpression): FilterExpression {
        val field = when (checkNotNull(original.spec.systemField)) {
            SystemField.IDENTITY -> schema.requireIdentityField()
            SystemField.AGGREGATE_ID -> QueryField(MessageRecords.AGGREGATE_ID)
            SystemField.TENANT_ID -> QueryField(MessageRecords.TENANT_ID)
            SystemField.OWNER_ID -> QueryField(MessageRecords.OWNER_ID)
            SystemField.SPACE_ID -> QueryField(MessageRecords.SPACE_ID)
            SystemField.DELETED -> QueryField(StateAggregateRecords.DELETED)
        }
        fields[fresh] = resolve(field, original.requiredCapability(), ROOT)
        return fresh
    }

    /** Resolves [field] in [scope] for [capability] and returns the fresh reference registered for it. */
    private fun reference(field: QueryField, capability: QueryCapability, scope: Scope): QueryField =
        register(field, resolve(field, capability, scope))

    private fun register(field: QueryField, resolved: ResolvedField): QueryField =
        QueryField(field.path).also { fields[it] = resolved }

    private fun resolve(field: QueryField, capability: QueryCapability, scope: Scope): ResolvedField {
        val logical = absoluteLogicalField(field, scope.logical)
        val definition = schema.field(logical)
        requireValid(definition != null) { QueryViolation.UnknownField(logical) }
        checkNotNull(definition)
        requireValid(
            definition.elementAncestors != null &&
                definition.elementAncestors == schema.requiredElementAncestors(scope.logical)
        ) { QueryViolation.ElementScopeRequired(logical) }
        val physical = definition.binding(capability)?.physicalField
        requireValid(physical != null) { QueryViolation.UnsupportedCapability(logical, setOf(capability)) }
        checkNotNull(physical)
        requireSchema(scope.physical == null || physical.relativeTo(scope.physical) != null) {
            "Physical field [$physical] is outside element scope [${scope.physical}]."
        }
        return ResolvedField(logical, definition, scope.physical, capability, physical)
    }

    private companion object {
        val ROOT = Scope(null, null)
    }
}
