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

import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.spec.OperatorTarget
import me.ahoo.wow.api.query.spec.ValueRule
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.filter.childFilters
import me.ahoo.wow.query.filter.predicateField

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
fun FilterExpression.requireScalarMetricFilterFields(
    parent: QueryField?,
    schema: QueryModelSchema,
) {
    val spec = spec
    when (spec.target) {
        OperatorTarget.NONE, OperatorTarget.SYSTEM_FIELD -> Unit
        OperatorTarget.MODEL_OR_FIELDS -> throw QuerySchemaValidationException(QueryViolation.MetricFilterSearch)
        OperatorTarget.LOGICAL -> childFilters().forEach { it.requireScalarMetricFilterFields(parent, schema) }
        OperatorTarget.FIELD -> {
            if (spec.valueRule == ValueRule.ELEMENT_SCOPE) {
                throw QuerySchemaValidationException(QueryViolation.MetricFilterElementMatch)
            }
            checkNotNull(predicateField()).requireScalarMetricFilterField(parent, schema)
        }
    }
}

private fun QueryField.requireScalarMetricFilterField(parent: QueryField?, schema: QueryModelSchema) {
    val logical = absoluteLogicalField(this, parent)
    val value = schema.field(logical)?.value ?: return
    requireValid(!value.hasArrayBranch()) { QueryViolation.MetricFilterArrayField(logical) }
}
