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
import me.ahoo.wow.api.query.ExpressionFilter
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
import me.ahoo.wow.api.query.inputExpression
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.spec.ValueRule
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.filter.predicateField
import me.ahoo.wow.query.filter.requiredCapability
import me.ahoo.wow.query.filter.withPredicateField
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.absoluteLogicalField
import me.ahoo.wow.query.schema.accepts
import me.ahoo.wow.query.schema.alternativesOrSelf
import me.ahoo.wow.query.schema.firstLastOrderBy
import me.ahoo.wow.query.schema.hasArrayBranch
import me.ahoo.wow.query.schema.operationValues
import me.ahoo.wow.query.schema.profile
import me.ahoo.wow.query.schema.requireSchema
import me.ahoo.wow.query.schema.requireValid
import me.ahoo.wow.query.schema.systemField
import me.ahoo.wow.query.schema.temporal
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.POJONode
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Admission steps 5 and 6 in one traversal (design §5.4, P3): every field reference of a query is canonicalized
 * (an alias replaced by its canonical field), looked up once, checked against the capability table and the value
 * rules, normalized with the value it resolved to (relative time encoded as the field stores time, derived operators
 * lowered, logical nodes simplified), and registered as a fresh node with its [ResolvedField].
 *
 * Every rejection is a [QueryViolation] naming the absolute logical field. Fresh instances make node identity unique
 * by construction, so the registration is keyed by identity: two equal conditions in different element scopes, or
 * one [QueryField] a caller reused, become distinct keys.
 *
 * Conditions in [trusted] (and below them) were appended by admission itself: the caller scope, the route selection,
 * policies and the model default. They are checked for what execution needs (the field exists, grants the
 * capability, sits in its element scope and accepts the values) but not against the gates that protect a field from
 * callers: a policy may compare a field callers cannot.
 *
 * One resolver serves one admitted query; [now] is the moment every relative time of it resolves against.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class QueryResolver(
    private val schema: QueryModelSchema,
    private val now: Instant,
    trusted: Collection<FilterExpression> = emptyList(),
) {
    val fields = IdentityHashMap<Any, ResolvedField>()

    private val trusted: Set<FilterExpression> =
        Collections.newSetFromMap(IdentityHashMap<FilterExpression, Boolean>()).apply { addAll(trusted) }

    /** An element scope: its canonical logical container, its physical container and the ancestors it requires. */
    private class Scope(val logical: QueryField?, val physical: QueryField?, val ancestors: List<QueryField>?)

    /** Where a filter node sits: its scope, whether admission appended it, and whether it is a metric filter. */
    private class At(val scope: Scope, val trusted: Boolean = false, val metric: Boolean = false) {
        fun trusting(): At = if (trusted) this else At(scope, true, metric)
    }

    /** A field reference looked up once: its canonical name as written, and the model's record of it. */
    private class Reference(
        val name: QueryField,
        val logical: QueryField,
        val definition: QueryFieldSchema,
        val capability: QueryCapability,
    )

    fun single(query: ISingleQuery): ISingleQuery {
        val filter = filter(query.filter)
        return SingleQuery(filter, projection(query.projection), sort(query.sort, cursor = false))
    }

    fun list(query: IListQuery): IListQuery {
        val filter = filter(query.filter)
        return ListQuery(filter, projection(query.projection), sort(query.sort, cursor = false), query.limit)
    }

    fun paged(query: IPagedQuery): IPagedQuery {
        val filter = filter(query.filter)
        return PagedQuery(filter, projection(query.projection), sort(query.sort, cursor = false), query.pagination)
    }

    fun cursor(query: ICursorQuery): ICursorQuery {
        val filter = filter(query.filter)
        val projection = projection(query.projection)
        return CursorQuery(filter, projection, sort(query.sort, cursor = true), query.size, query.cursor)
    }

    fun filter(expression: FilterExpression): FilterExpression = filter(expression, At(ROOT))

    fun aggregate(query: AggregationQuery): AggregationQuery {
        val filter = filter(query.filter, At(ROOT))
        var scope = ROOT
        val elements = query.elements.map { element ->
            val reference = reference(element.path, scope, QueryCapability.ELEMENT_SCOPE)
            val path = register(reference, scope)
            scope = elementScope(reference, path)
            AggregationElement(path, filter(element.filter, At(scope)))
        }
        val inner = scope
        return query.copy(
            filter = filter,
            elements = elements,
            groupBy = query.groupBy.map { group(it, inner) },
            metrics = query.metrics.map { metric(it, inner) },
        )
    }

    /** The canonical form of [field], which is relative to [scope]'s canonical container. */
    private fun canonical(field: QueryField, scope: Scope): QueryField {
        if (!schema.hasAliases) return field
        val parent = scope.logical ?: return schema.definition.canonical(field)
        return schema.definition.canonical(parent.append(field)).relativeTo(parent) ?: field
    }

    /**
     * Looks [field] up once: canonical, known, granting the first of [capabilities] it has, and in its declared
     * element scope.
     */
    private fun reference(field: QueryField, scope: Scope, vararg capabilities: QueryCapability): Reference {
        val name = canonical(field, scope)
        val logical = absoluteLogicalField(name, scope.logical)
        val definition = schema.field(logical) ?: throw QueryViolation.UnknownField(logical).rejection()
        val capability = capabilities.firstOrNull { definition.binding(it) != null }
            ?: throw QueryViolation.UnsupportedCapability(logical, capabilities.toSet()).rejection()
        requireValid(definition.elementAncestors != null && definition.elementAncestors == scope.ancestors) {
            QueryViolation.ElementScopeRequired(logical)
        }
        return Reference(name, logical, definition, capability)
    }

    /** A fresh instance of [reference]'s name, registered with its physical binding for [capability]. */
    private fun register(
        reference: Reference,
        scope: Scope,
        capability: QueryCapability = reference.capability,
    ): QueryField = QueryField(reference.name.path).also { fields[it] = bind(reference, scope, capability) }

    private fun bind(reference: Reference, scope: Scope, capability: QueryCapability): ResolvedField {
        val physical = reference.definition.binding(capability)?.physicalField
            ?: throw QueryViolation.UnsupportedCapability(reference.logical, setOf(capability)).rejection()
        requireSchema(scope.physical == null || physical.relativeTo(scope.physical) != null) {
            "Physical field [$physical] is outside element scope [${scope.physical}]."
        }
        return ResolvedField(reference.logical, reference.definition, scope.physical, capability, physical)
    }

    private fun elementScope(container: Reference, path: QueryField): Scope = Scope(
        container.logical,
        checkNotNull(fields[path]).physicalField,
        container.definition.elementAncestors?.plus(container.logical),
    )

    /** A field whose raw value is compared; callers may not compare a protected field, admission itself may. */
    private fun compared(reference: Reference, trusted: Boolean): Reference = reference.also {
        requireValid(trusted || it.definition.comparable) { QueryViolation.ProtectedComparison(it.logical) }
    }

    /** A field whose values are aggregated; a protected field never is. */
    private fun aggregated(field: QueryField, scope: Scope, vararg capabilities: QueryCapability): Reference =
        reference(field, scope, *capabilities).also {
            requireValid(!it.definition.protected) { QueryViolation.ProtectedAggregation(it.logical) }
        }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun filter(expression: FilterExpression, parent: At): FilterExpression {
        val at = if (expression in trusted) parent.trusting() else parent
        return when (expression) {
            MatchAllFilter, MatchNoneFilter -> expression
            is AndFilter -> normalizer.simplifyAnd(expression.operands.map { filter(it, at) })
            is OrFilter -> normalizer.simplifyOr(expression.operands.map { filter(it, at) })
            is NorFilter -> normalizer.simplifyNor(expression.operands.map { filter(it, at) })
            is IdFilter -> system(expression.copy(), expression, at)
            is IdsFilter -> system(expression.copy(), expression, at)
            is AggregateIdFilter -> system(expression.copy(), expression, at)
            is AggregateIdsFilter -> system(expression.copy(), expression, at)
            is TenantIdFilter -> system(expression.copy(), expression, at)
            is OwnerIdFilter -> system(expression.copy(), expression, at)
            is SpaceIdFilter -> system(expression.copy(), expression, at)
            is DeletionFilter -> system(expression.copy(), expression, at)
            is SearchFilter -> search(expression, at)
            is ExpressionFilter -> expression.copy(
                expression = expression(expression.expression, at.scope, at.trusted, scalarOnly = at.metric),
            )
            is ElementMatchFilter -> elementMatch(expression, at)
            is EqualFilter, is NotEqualFilter, is GreaterThanFilter, is GreaterThanOrEqualFilter, is LessThanFilter,
            is LessThanOrEqualFilter, is ContainsFilter, is StartsWithFilter, is EndsWithFilter, is InFilter,
            is NotInFilter, is BetweenFilter, is ContainsAllFilter, is IsEmptyFilter, is IsEmptyStringFilter,
            is IsNotEmptyStringFilter, is IsNullFilter, is IsNotNullFilter, is ExistsFilter, is NotExistsFilter,
            is RelativeTimeFilter,
            -> predicate(expression, at)
        }
    }

    /** A system-field filter (ID, TENANT_ID, DELETION, ...): keyed by the fresh filter node itself. */
    private fun system(fresh: FilterExpression, original: FilterExpression, at: At): FilterExpression {
        val field = schema.systemField(checkNotNull(original.spec.systemField))
        val reference = reference(field, at.scope, original.requiredCapability())
        fields[fresh] = bind(reference, at.scope, reference.capability)
        return fresh
    }

    private fun search(expression: SearchFilter, at: At): FilterExpression {
        val capability = expression.requiredCapability()
        if (expression.fields.isEmpty()) {
            requireValid(schema.supports(capability) && !schema.hasIncomparableFields) {
                QueryViolation.ModelSearchUnsupported
            }
        }
        val fields = expression.fields.mapTo(LinkedHashSet()) {
            register(compared(reference(it, at.scope, capability), at.trusted), at.scope)
        }
        requireValid(!at.metric) { QueryViolation.MetricFilterSearch }
        return expression.copy(fields = fields)
    }

    private fun elementMatch(expression: ElementMatchFilter, at: At): FilterExpression {
        val reference = reference(expression.field, at.scope, expression.requiredCapability())
        requireValid(!at.metric) { QueryViolation.MetricFilterElementMatch }
        val container = register(reference, at.scope)
        val inner = At(elementScope(reference, container), at.trusted, at.metric)
        return ElementMatchFilter(container, filter(expression.predicate, inner))
    }

    /** A predicate on one field: checked by its value rule, then lowered with the resolved value and registered. */
    private fun predicate(expression: FilterExpression, at: At): FilterExpression {
        val reference = reference(checkNotNull(expression.predicateField()), at.scope, expression.requiredCapability())
        when (expression.spec.valueRule) {
            ValueRule.NONE -> compared(reference, at.trusted)
            ValueRule.DOMAIN -> domainValues(expression)
                ?.let { values(reference, it, at.trusted) }
                ?: compared(reference, at.trusted)
            ValueRule.COLLECTION_DOMAIN -> {
                collection(reference, at.trusted)
                values(reference, checkNotNull(domainValues(expression)), at.trusted)
            }
            ValueRule.COLLECTION -> collection(reference, at.trusted)
            ValueRule.SINGLE_STRING -> string(reference, at.trusted)
            ValueRule.TEMPORAL -> compared(reference, at.trusted).also {
                (expression as RelativeTimeFilter).temporal(it.definition.value, it.logical)
            }
            ValueRule.ELEMENT_SCOPE -> error("ELEMENT_MATCH is resolved as an element scope.")
        }
        if (at.metric) {
            requireValid(!reference.definition.value.hasArrayBranch()) {
                QueryViolation.MetricFilterArrayField(reference.logical)
            }
        }
        val lowered = normalizer.lower(expression.withPredicateField(reference.name), now, reference.definition.value)
        return registered(lowered, reference, at.scope)
    }

    /** Registers each predicate [lowered] consists of, for the capability that predicate itself requires. */
    private fun registered(lowered: FilterExpression, reference: Reference, scope: Scope): FilterExpression {
        if (lowered is AndFilter) {
            return AndFilter(lowered.operands.map { registered(it, reference, scope) })
        }
        val field = register(reference, scope, lowered.requiredCapability())
        when (lowered) {
            is EqualFilter -> requireScalarEquality(lowered.value, reference)
            is NotEqualFilter -> requireScalarEquality(lowered.value, reference)
            else -> Unit
        }
        return lowered.withPredicateField(field)
    }

    /** An equality operand must be a scalar when the storage cannot compare whole arrays. */
    private fun requireScalarEquality(value: JsonNode, reference: Reference) {
        if (schema.storage.arrayEquality != SupportMode.NONE) return
        requireValid(!value.canonical().isArray) { QueryViolation.ArrayEquality(reference.logical) }
    }

    /** The values checked against the field's domain; `null` when equality asks for presence instead. */
    private fun domainValues(expression: FilterExpression): Iterable<JsonNode>? = when (expression) {
        is EqualFilter -> equalityValues(expression.value)
        is NotEqualFilter -> equalityValues(expression.value)
        is InFilter -> expression.values
        is NotInFilter -> expression.values
        is ContainsAllFilter -> expression.values
        is GreaterThanFilter -> listOf(expression.value)
        is GreaterThanOrEqualFilter -> listOf(expression.value)
        is LessThanFilter -> listOf(expression.value)
        is LessThanOrEqualFilter -> listOf(expression.value)
        is BetweenFilter -> listOf(expression.lowerBound, expression.upperBound)
        else -> error("Filter [${expression.operator}] carries no domain values.")
    }

    private fun equalityValues(value: JsonNode): Iterable<JsonNode>? {
        if (value.isNull) return null
        val canonical = value.canonical()
        return if (canonical.isArray) canonical else listOf(canonical)
    }

    private fun JsonNode.canonical(): JsonNode = if (this is POJONode) JsonSerializer.valueToTree(pojo) else this

    private fun values(reference: Reference, values: Iterable<JsonNode>, trusted: Boolean) {
        requireValid(compared(reference, trusted).definition.value.accepts(values)) {
            QueryViolation.ValueMismatch(reference.logical)
        }
    }

    private fun collection(reference: Reference, trusted: Boolean) {
        val value = compared(reference, trusted).definition.value
        val alternatives = value.alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }
        requireValid(alternatives.isNotEmpty() && alternatives.all { it.kind == QueryValueKind.ARRAY }) {
            QueryViolation.NotCollection(reference.logical)
        }
    }

    private fun string(reference: Reference, trusted: Boolean) {
        val value = compared(reference, trusted).definition.value
        requireValid(value.isSingleString()) { QueryViolation.NotSingleString(reference.logical) }
    }

    private fun QueryValueSchema.isSingleString(): Boolean =
        cardinality == QueryCardinality.SINGLE && operationValues().all {
            it.kind == QueryValueKind.NULL || it.valueTypes == setOf(QueryValueType.STRING)
        }

    private fun projection(projection: Projection): Projection {
        requireValid(projection.include.isNotEmpty() || schema.fullProjectionAvailable) {
            QueryViolation.IncompleteProjection
        }
        val resolved = if (projection.include.isEmpty() && projection.exclude.isEmpty()) {
            projection
        } else {
            Projection(projection.include.map(::projected), projection.exclude.map(::projected))
        }
        schema.profile?.validateProjection(resolved)
        return resolved
    }

    private fun projected(field: QueryField): QueryField {
        val canonical = canonical(field, ROOT)
        val definition = schema.field(canonical)
        val physical = definition?.projectionField
            ?: throw QueryViolation.NotProjectable(canonical).rejection()
        return QueryField(canonical.path).also {
            fields[it] = ResolvedField(canonical, definition, null, null, physical)
        }
    }

    /**
     * Every sort: at most [AggregationQuery.MAX_SORT_FIELDS] fields, each sortable by its caller, no two independent
     * arrays when the storage cannot sort by both, and no two fields bound to one physical field.
     */
    private fun sort(sort: List<Sort>, cursor: Boolean): List<Sort> {
        requireValid(sort.size <= AggregationQuery.MAX_SORT_FIELDS) {
            QueryViolation.SortTooMany(AggregationQuery.MAX_SORT_FIELDS)
        }
        val capability = if (cursor) QueryCapability.CURSOR_SORT else QueryCapability.SORT
        val resolved = sort.map {
            val reference = reference(it.field, ROOT, capability)
            if (cursor) {
                requireValid(reference.definition.cursorSortable) { QueryViolation.CursorNotAllowed(reference.logical) }
            } else {
                compared(reference, trusted = false)
            }
            Sort(register(reference, ROOT), it.direction)
        }
        val references = resolved.map { checkNotNull(fields[it.field]) }
        if (schema.storage.parallelArraySort == SupportMode.NONE) {
            requireNoParallelArrays(references)
        }
        references.groupBy { it.physicalField }.values.firstOrNull { it.size > 1 }?.let { duplicates ->
            throw QueryViolation.DuplicateSortField(duplicates.last().logicalField).rejection()
        }
        return resolved
    }

    /** Two array-valued sort fields may combine only on one array path, one nested in the other. */
    private fun requireNoParallelArrays(sort: List<ResolvedField>) {
        val arrays = sort.filter { it.value.hasArrayBranch() }
        arrays.forEachIndexed { index, left ->
            arrays.drop(index + 1).firstOrNull { right -> left.physicalField.independentOf(right.physicalField) }
                ?.let { right ->
                    throw QueryViolation.ParallelArraySort(right.logicalField, left.logicalField).rejection()
                }
        }
    }

    private fun QueryField.independentOf(other: QueryField): Boolean =
        this != other && !path.startsWith("${other.path}.") && !other.path.startsWith("$path.")

    private fun group(group: AggregationGroup, scope: Scope): AggregationGroup {
        group.inputExpression?.let { input ->
            val resolved = expression(input, scope)
            return when (group) {
                is AggregationGroup.Terms -> group.copy(expression = resolved)
                is AggregationGroup.Histogram -> group.copy(expression = resolved)
                is AggregationGroup.DateHistogram, is AggregationGroup.DatePart -> error("No expression input.")
            }
        }
        val reference = aggregated(checkNotNull(group.field), scope, group.spec.capability)
        requireTermsMissingKeySupport(group, reference)
        val field = register(reference, scope)
        return when (group) {
            is AggregationGroup.Terms -> group.copy(field = field)
            is AggregationGroup.Histogram -> group.copy(field = field)
            is AggregationGroup.DateHistogram -> group.copy(field = field)
            is AggregationGroup.DatePart -> group.copy(field = field)
        }
    }

    private fun requireTermsMissingKeySupport(group: AggregationGroup, reference: Reference) {
        if (group is AggregationGroup.Terms && group.missingKey != null) {
            requireValid(reference.definition.value.isSingleString()) {
                QueryViolation.MissingKeyRequiresString(reference.logical)
            }
        }
    }

    /** A metric: its filter first, then its inputs. */
    private fun metric(metric: AggregationMetric, scope: Scope): AggregationMetric {
        val filter = if (metric.filter === MatchAllFilter) {
            metric.filter
        } else {
            filter(metric.filter, At(scope, metric = true))
        }
        return when (metric) {
            is AggregationMetric.Count -> metric.copy(filter = filter)
            is AggregationMetric.Any -> {
                val reference = aggregated(metric.field, scope, QueryCapability.AGGREGATE_TERMS)
                requireValid(reference.definition.value.cardinality == QueryCardinality.SINGLE) {
                    QueryViolation.AnyRequiresSingleValue
                }
                metric.copy(field = register(reference, scope), filter = filter)
            }
            is AggregationMetric.Numeric -> metric.copy(
                expression = expression(metric.expression, scope),
                filter = filter
            )
            is AggregationMetric.Percentile ->
                metric.copy(expression = expression(metric.expression, scope), filter = filter)
            is AggregationMetric.DistinctCount -> metric.copy(
                expression = when (val expression = metric.expression) {
                    is AggregationExpression.Field -> AggregationExpression.Field(
                        register(aggregated(expression.field, scope, *TERMS_OR_NUMERIC), scope),
                    )
                    else -> expression(expression, scope)
                },
                filter = filter,
            )
            is AggregationMetric.First -> {
                val (field, orderBy) = edge(metric, scope)
                metric.copy(field = field, orderBy = orderBy, filter = filter)
            }
            is AggregationMetric.Last -> {
                val (field, orderBy) = edge(metric, scope)
                metric.copy(field = field, orderBy = orderBy, filter = filter)
            }
            is AggregationMetric.Derived -> metric
        }
    }

    /** FIRST / LAST read one scalar value and order by one sortable scalar, neither of them protected. */
    private fun edge(metric: AggregationMetric.Edge, scope: Scope): Pair<QueryField, QueryField> {
        val value = aggregated(metric.field, scope, *TERMS_OR_NUMERIC)
        requireValid(value.definition.value.cardinality == QueryCardinality.SINGLE) {
            QueryViolation.FirstLastRequiresSingleValue(value.logical)
        }
        val orderBy = aggregated(schema.firstLastOrderBy(metric, scope.logical), scope, QueryCapability.SORT)
        requireValid(orderBy.definition.value.cardinality == QueryCardinality.SINGLE) {
            QueryViolation.FirstLastRequiresSingleValue(orderBy.logical)
        }
        return register(value, scope) to register(orderBy, scope)
    }

    /**
     * An aggregation expression: numeric fields and temporal date-difference operands, never protected unless
     * admission appended it ([trusted]); in a metric filter ([scalarOnly]) every field must be scalar.
     */
    private fun expression(
        expression: AggregationExpression,
        scope: Scope,
        trusted: Boolean = false,
        scalarOnly: Boolean = false,
    ): AggregationExpression {
        fun field(field: QueryField, capability: QueryCapability): QueryField {
            val reference = if (trusted) {
                reference(field, scope, capability)
            } else {
                aggregated(field, scope, capability)
            }
            if (scalarOnly) {
                requireValid(!reference.definition.value.hasArrayBranch()) {
                    QueryViolation.MetricFilterArrayField(reference.logical)
                }
            }
            return register(reference, scope)
        }
        return when (expression) {
            is AggregationExpression.Field ->
                AggregationExpression.Field(field(expression.field, QueryCapability.AGGREGATE_NUMERIC))
            is AggregationExpression.Constant -> expression
            is AggregationExpression.Binary -> expression.copy(
                left = expression(expression.left, scope, trusted, scalarOnly),
                right = expression(expression.right, scope, trusted, scalarOnly),
            )
            is AggregationExpression.DateDiff -> expression.copy(
                from = field(expression.from, QueryCapability.AGGREGATE_TEMPORAL),
                to = field(expression.to, QueryCapability.AGGREGATE_TEMPORAL),
            )
        }
    }

    private companion object {
        val ROOT = Scope(null, null, emptyList())
        val TERMS_OR_NUMERIC = arrayOf(QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC)
        val normalizer = FilterNormalizer()
    }
}

private fun QueryViolation.rejection() = QuerySchemaValidationException(this)
