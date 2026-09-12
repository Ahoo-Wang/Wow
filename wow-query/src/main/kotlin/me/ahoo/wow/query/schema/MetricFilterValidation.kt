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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
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
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryValueKind

/**
 * Validates that an aggregation metric filter references scalar fields only.
 *
 * Metric filters evaluate each record as a whole-value predicate: MongoDB re-expresses them as
 * `$cond` guard conditions and Elasticsearch as filter aggregations. That differs from the
 * element-matching evaluation of array fields in ordinary filters, so array-valued fields —
 * including unions with an array alternative — are rejected, as are [ElementMatchFilter] and
 * [SearchFilter], whose element-matching and full-text semantics have no whole-value translation.
 *
 * @param parent the enclosing element scope, or `null` for root-level metrics
 * @param schema the schema that resolves logical fields to their value kinds
 * @throws QuerySchemaValidationException when the filter is unsupported in metric position
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun FilterExpression.requireScalarMetricFilterFields(
    parent: QueryField?,
    schema: QueryModelSchema,
) {
    when (this) {
        MatchAllFilter, MatchNoneFilter,
        is IdFilter, is IdsFilter, is AggregateIdFilter, is AggregateIdsFilter,
        is TenantIdFilter, is OwnerIdFilter, is SpaceIdFilter, is DeletionFilter,
        -> Unit

        is SearchFilter -> throw QuerySchemaValidationException(
            "Aggregation metric filters do not support search filters.",
        )
        is ElementMatchFilter -> throw QuerySchemaValidationException(
            "Aggregation metric filters do not support [ELEMENT_MATCH].",
        )
        is AndFilter -> operands.forEach { it.requireScalarMetricFilterFields(parent, schema) }
        is OrFilter -> operands.forEach { it.requireScalarMetricFilterFields(parent, schema) }
        is NorFilter -> operands.forEach { it.requireScalarMetricFilterFields(parent, schema) }
        is RelativeTimeFilter -> field.requireScalarMetricFilterField(parent, schema)
        is EqualFilter -> field.requireScalarMetricFilterField(parent, schema)
        is NotEqualFilter -> field.requireScalarMetricFilterField(parent, schema)
        is GreaterThanFilter -> field.requireScalarMetricFilterField(parent, schema)
        is GreaterThanOrEqualFilter -> field.requireScalarMetricFilterField(parent, schema)
        is LessThanFilter -> field.requireScalarMetricFilterField(parent, schema)
        is LessThanOrEqualFilter -> field.requireScalarMetricFilterField(parent, schema)
        is BetweenFilter -> field.requireScalarMetricFilterField(parent, schema)
        is ContainsFilter -> field.requireScalarMetricFilterField(parent, schema)
        is StartsWithFilter -> field.requireScalarMetricFilterField(parent, schema)
        is EndsWithFilter -> field.requireScalarMetricFilterField(parent, schema)
        is InFilter -> field.requireScalarMetricFilterField(parent, schema)
        is NotInFilter -> field.requireScalarMetricFilterField(parent, schema)
        is ContainsAllFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsEmptyFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsEmptyStringFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsNotEmptyStringFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsNullFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsNotNullFilter -> field.requireScalarMetricFilterField(parent, schema)
        is ExistsFilter -> field.requireScalarMetricFilterField(parent, schema)
        is NotExistsFilter -> field.requireScalarMetricFilterField(parent, schema)
    }
}

private fun QueryField.requireScalarMetricFilterField(parent: QueryField?, schema: QueryModelSchema) {
    val logical = absoluteLogicalField(this, parent)
    val value = schema.field(logical)?.value ?: return
    if (value.isArrayValued) {
        throw QuerySchemaValidationException(
            "Aggregation metric filter field [$logical] must be scalar; array fields are not supported in metric filters.",
        )
    }
}

private val QueryValueSchema.isArrayValued: Boolean
    get() = kind == QueryValueKind.ARRAY ||
        (kind == QueryValueKind.UNION && alternatives.any { it.kind == QueryValueKind.ARRAY })
