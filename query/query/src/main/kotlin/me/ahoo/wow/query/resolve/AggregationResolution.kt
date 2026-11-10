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

package me.ahoo.wow.query.resolve

import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.inputExpression
import me.ahoo.wow.query.aggregation.PreviousStep
import me.ahoo.wow.query.aggregation.requireSupported
import me.ahoo.wow.query.resolve.QueryResolver.At
import me.ahoo.wow.query.resolve.QueryResolver.Companion.ROOT
import me.ahoo.wow.query.resolve.QueryResolver.Scope
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.firstLastOrderBy
import me.ahoo.wow.query.schema.requireValid
import me.ahoo.wow.query.schema.temporal
import me.ahoo.wow.query.spec.spec

/**
 * Admission steps 5 and 6 for an [AggregationQuery]: its elements, groups and metrics, resolved through [resolver],
 * whose field binding, filters and expressions they share with every other query kind. `QueryAdmission` builds one
 * per aggregation query, with that query's resolver.
 */
internal class AggregationResolution(private val resolver: QueryResolver) {
    fun aggregate(query: AggregationQuery): AggregationQuery {
        // What the storage cannot run at all is rejected first; the field records then assume it can.
        requireSupported(query, resolver.schema.storage)
        val filter = resolver.filter(query.filter, At(ROOT))
        var scope = ROOT
        val elements = query.elements.map { element ->
            val reference = resolver.reference(element.path, scope, QueryCapability.ELEMENT_SCOPE)
            val path = resolver.register(reference, scope)
            scope = resolver.elementScope(reference, path)
            AggregationElement(path, resolver.storedEvents(resolver.filter(element.filter, At(scope)), scope))
        }
        val inner = scope
        val aggregation = query.copy(
            filter = filter,
            elements = elements,
            // A ROOT group reads its field on the record the elements belong to (AggregationGroup.scope).
            groupBy = query.groupBy.map { group(it, if (it.scope == AggregationGroupScope.ROOT) ROOT else inner) },
            metrics = query.metrics.map { metric(it, inner) },
        )
        PreviousStep.of(aggregation).forEach { step ->
            requireValid(step.shift(aggregation.filter, resolver.fields[step.field]?.temporal) != null) {
                QueryViolation.PreviousUnshiftable(step.field)
            }
        }
        return aggregation
    }

    private fun group(group: AggregationGroup, scope: Scope): AggregationGroup {
        group.inputExpression?.let { input ->
            val resolved = resolver.expression(input, scope)
            return when (group) {
                is AggregationGroup.Terms -> group.copy(expression = resolved)
                is AggregationGroup.Histogram -> group.copy(expression = resolved)
                is AggregationGroup.DateHistogram, is AggregationGroup.DatePart -> error("No expression input.")
            }
        }
        val reference = resolver.aggregated(checkNotNull(group.field), scope, group.spec.capability)
        when (group) {
            is AggregationGroup.Terms -> if (group.missingKey != null) {
                requireValid(
                    reference.effective.missingKey
                ) { QueryViolation.MissingKeyRequiresString(reference.logical) }
            }
            is AggregationGroup.Histogram -> Unit
            // The field's record lists the groups it admits; a date group also needs instants.
            is AggregationGroup.DateHistogram, is AggregationGroup.DatePart -> requireValid(
                group.spec in reference.effective.groups
            ) { QueryViolation.TemporalAggregationUnsupported(reference.logical) }
        }
        val field = resolver.register(reference, scope)
        return when (group) {
            is AggregationGroup.Terms -> group.copy(field = field)
            is AggregationGroup.Histogram -> group.copy(field = field)
            is AggregationGroup.DateHistogram -> group.copy(field = field)
            is AggregationGroup.DatePart -> group.copy(field = field)
        }
    }

    /** A metric: its filter first, then its inputs. */
    private fun metric(metric: AggregationMetric, scope: Scope): AggregationMetric {
        val filter = if (metric.filter === MatchAllFilter) {
            metric.filter
        } else {
            resolver.filter(metric.filter, At(scope, metric = true))
        }
        return when (metric) {
            is AggregationMetric.Count -> metric.copy(filter = filter)
            is AggregationMetric.Any -> {
                val reference = resolver.aggregated(metric.field, scope, metric.spec.fieldCapabilities)
                requireValid(metric.spec in reference.effective.metrics) { QueryViolation.AnyRequiresSingleValue }
                metric.copy(field = resolver.register(reference, scope), filter = filter)
            }
            is AggregationMetric.Numeric -> metric.copy(
                expression = resolver.expression(metric.expression, scope),
                filter = filter
            )
            is AggregationMetric.Percentile ->
                metric.copy(expression = resolver.expression(metric.expression, scope), filter = filter)
            is AggregationMetric.DistinctCount -> metric.copy(
                expression = when (val expression = metric.expression) {
                    is AggregationExpression.Field -> AggregationExpression.Field(
                        resolver.register(
                            resolver.aggregated(expression.field, scope, metric.spec.fieldCapabilities),
                            scope,
                        ),
                    )
                    else -> resolver.expression(expression, scope)
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

    /** FIRST / LAST read one single value and order by one single sortable value, neither of them protected. */
    private fun edge(metric: AggregationMetric.Edge, scope: Scope): Pair<QueryField<*>, QueryField<*>> {
        val spec = metric.spec
        val value = resolver.aggregated(metric.field, scope, spec.fieldCapabilities)
        requireValid(spec in value.effective.metrics) { QueryViolation.FirstLastRequiresSingleValue(value.logical) }
        val orderBy = resolver.aggregated(
            resolver.schema.firstLastOrderBy(metric, scope.logical),
            scope,
            checkNotNull(spec.orderCapability)
        )
        requireValid(orderBy.effective.cardinality == QueryCardinality.SINGLE) {
            QueryViolation.FirstLastRequiresSingleValue(orderBy.logical)
        }
        return resolver.register(value, scope) to resolver.register(orderBy, scope)
    }
}
