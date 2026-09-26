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

package me.ahoo.wow.api.query.spec

import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.schema.QueryCapability

/** What an aggregation metric reads from each record. */
enum class MetricInput {
    /** No field: `COUNT` counts records and `DERIVED` combines other metrics of the group. */
    NONE,

    /** One field holding a single value per record. */
    SINGLE_VALUE_FIELD,

    /**
     * An aggregation expression: a bare field is read with the first of [MetricSpec.fieldCapabilities] it grants, an
     * arithmetic expression reads numeric fields (and temporal ones for a date difference).
     */
    EXPRESSION,
}

/** The type of a metric's value. */
enum class MetricResult {
    /** A record count: an integer, zero for a group without records. */
    COUNT,

    /** A number, `null` for a group without values. */
    NUMBER,

    /** A value of the field the metric reads, `null` for a group without values. */
    FIELD_VALUE,
}

/**
 * The single specification of each aggregation metric type (design §6.1): its wire name (the enum name, equal to the
 * JSON `type`), what it reads and with which capabilities, its result type, whether `HAVING` may compare it and what
 * it costs. Admission, the entry gate, the descriptor and empty-group values read it, so a new metric type is added
 * here and every exhaustive `when` over [AggregationMetric] fails to compile until handled.
 */
enum class MetricSpec(
    val input: MetricInput,
    /** The capabilities a field input may be read with, the first one the field grants; empty when it reads none. */
    val fieldCapabilities: List<QueryCapability>,
    val result: MetricResult,
    /** Whether a `HAVING` condition may compare the metric's value. */
    val havingOperand: Boolean,
    /**
     * The capability the ordering field must grant, for a metric that picks one record of the group by an order;
     * that field, like the input, must hold a single value per record.
     */
    val orderCapability: QueryCapability? = null,
    /** The cost of the metric type in general; [cost] refines it for one metric. */
    val baseCost: OperatorCost = OperatorCost.NORMAL,
) {
    COUNT(MetricInput.NONE, emptyList(), MetricResult.COUNT, havingOperand = true),
    NUMERIC(
        MetricInput.EXPRESSION,
        listOf(QueryCapability.AGGREGATE_NUMERIC),
        MetricResult.NUMBER,
        havingOperand = true
    ),
    ANY(
        MetricInput.SINGLE_VALUE_FIELD,
        listOf(QueryCapability.AGGREGATE_TERMS),
        MetricResult.FIELD_VALUE,
        havingOperand = false
    ),
    DISTINCT_COUNT(
        MetricInput.EXPRESSION,
        listOf(QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC),
        MetricResult.COUNT,
        havingOperand = true,
    ),
    PERCENTILE(
        MetricInput.EXPRESSION,
        listOf(QueryCapability.AGGREGATE_NUMERIC),
        MetricResult.NUMBER,
        havingOperand = true
    ),

    /** Arithmetic over the group's other metrics, computed per group. */
    DERIVED(
        MetricInput.NONE,
        emptyList(),
        MetricResult.NUMBER,
        havingOperand = true,
        baseCost = OperatorCost.EXPENSIVE
    ),

    /** The value on the group's earliest record by an order; storage sorts every group's records to find it. */
    FIRST(
        MetricInput.SINGLE_VALUE_FIELD,
        listOf(QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC),
        MetricResult.FIELD_VALUE,
        havingOperand = false,
        orderCapability = QueryCapability.SORT,
        baseCost = OperatorCost.EXPENSIVE,
    ),

    /** The value on the group's latest record by an order; costs what [FIRST] does. */
    LAST(
        MetricInput.SINGLE_VALUE_FIELD,
        listOf(QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC),
        MetricResult.FIELD_VALUE,
        havingOperand = false,
        orderCapability = QueryCapability.SORT,
        baseCost = OperatorCost.EXPENSIVE,
    ),
    ;

    /** The cost of [metric]: an expression input other than a bare field is computed per record; otherwise [baseCost]. */
    fun cost(metric: AggregationMetric): OperatorCost {
        require(of(metric) == this) { "Metric [${of(metric)}] does not match spec [$this]." }
        val expression = when (metric) {
            is AggregationMetric.Numeric -> metric.expression
            is AggregationMetric.DistinctCount -> metric.expression
            is AggregationMetric.Percentile -> metric.expression
            is AggregationMetric.Count, is AggregationMetric.Any, is AggregationMetric.Derived,
            is AggregationMetric.Edge,
            -> null
        }
        return if (expression != null && expression !is AggregationExpression.Field) OperatorCost.EXPENSIVE else baseCost
    }

    companion object {
        @JvmStatic
        fun of(metric: AggregationMetric): MetricSpec = when (metric) {
            is AggregationMetric.Count -> COUNT
            is AggregationMetric.Numeric -> NUMERIC
            is AggregationMetric.Any -> ANY
            is AggregationMetric.DistinctCount -> DISTINCT_COUNT
            is AggregationMetric.Percentile -> PERCENTILE
            is AggregationMetric.Derived -> DERIVED
            is AggregationMetric.First -> FIRST
            is AggregationMetric.Last -> LAST
        }
    }
}

val AggregationMetric.spec: MetricSpec
    get() = MetricSpec.of(this)
