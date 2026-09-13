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

package me.ahoo.wow.elasticsearch.query.aggregation

import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.elasticsearch.query.toObjectNode
import me.ahoo.wow.query.aggregation.EmptyAggregationValues
import reactor.core.publisher.Flux
import tools.jackson.databind.node.ObjectNode

/**
 * Client-side dense fill between two consecutive actual bucket keys: the gap rows follow each
 * metric's empty semantics and participate in having like any other row. Rows are generated on
 * demand — a wide gap (e.g. two SECOND buckets a year apart) spans more buckets than the client
 * heap can hold, so materialization stays bounded by downstream demand.
 */
internal fun fillGapRows(
    fromKey: Long,
    toKey: Long,
    plan: ElasticsearchAggregationPlan,
): Flux<ObjectNode> {
    val dense = requireNotNull(plan.dense)
    val grid = dense.grid
    // Every fill row of one gap carries identical empty metrics: evaluate them once per gap and
    // copy the template per row instead of re-evaluating derived expressions per row. Aliases are
    // unique per AST validation, so the dense alias key cannot collide with a metric alias.
    val emptyMetrics = EmptyAggregationValues.values(dense.metrics)
    return Flux.fromStream { grid.gapIndices(fromKey, toKey).mapToObj(grid::keyOf) }
        .map { key ->
            val values = LinkedHashMap<String, Any?>(emptyMetrics.size + 1)
            values[dense.alias] = key
            values.putAll(emptyMetrics)
            values.toObjectNode()
        }
        .filter { row -> plan.having == null || row.matchesHaving(plan.having) }
}

/**
 * Evaluates a HAVING expression client-side against a produced aggregation row (Elasticsearch has
 * no bucket_selector under composite aggregations). Null-fails semantics: a missing or JSON-null
 * metric alias makes every comparison false; [HavingExpression.IsNull] captures exactly those rows.
 */
internal fun ObjectNode.matchesHaving(having: HavingExpression): Boolean = when (having) {
    is HavingExpression.And -> having.operands.all { matchesHaving(it) }
    is HavingExpression.Or -> having.operands.any { matchesHaving(it) }
    is HavingExpression.IsNull -> isNullMetric(having.metric) != having.negated
    is HavingExpression.Condition -> metricDouble(having.metric)
        ?.let { compare(it, having.operator, having.value) } == true

    is HavingExpression.Between -> metricDouble(having.metric)
        ?.let { it >= having.lower && it <= having.upper } == true

    is HavingExpression.In -> metricDouble(having.metric)
        ?.let { value -> having.values.any { it == value } } == true
}

private fun ObjectNode.isNullMetric(metric: String): Boolean {
    val value = get(metric)
    return value == null || value.isNull
}

private fun ObjectNode.metricDouble(metric: String): Double? {
    val value = get(metric) ?: return null
    if (value.isNull) return null
    return value.asDouble()
}

private fun compare(left: Double, operator: ComparisonOperator, right: Double): Boolean = when (operator) {
    ComparisonOperator.EQ -> left == right
    ComparisonOperator.NE -> left != right
    ComparisonOperator.GT -> left > right
    ComparisonOperator.GTE -> left >= right
    ComparisonOperator.LT -> left < right
    ComparisonOperator.LTE -> left <= right
}
