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

package me.ahoo.wow.api.query

/*
 * Validation of the window nodes of a derived metric: what each window may read and
 * which group aliases it may name.
 */

/** The calendar periods a [DerivedExpression.Previous] may step by, for each unit of its `along` group. */
private val PREVIOUS_PERIODS: Map<AggregationDateUnit, Set<AggregationDateUnit>> = run {
    val calendar = setOf(
        AggregationDateUnit.YEAR,
        AggregationDateUnit.QUARTER,
        AggregationDateUnit.MONTH,
        AggregationDateUnit.WEEK,
    )
    mapOf(
        AggregationDateUnit.YEAR to setOf(AggregationDateUnit.YEAR),
        AggregationDateUnit.QUARTER to setOf(AggregationDateUnit.YEAR, AggregationDateUnit.QUARTER),
        AggregationDateUnit.MONTH to setOf(
            AggregationDateUnit.YEAR,
            AggregationDateUnit.QUARTER,
            AggregationDateUnit.MONTH,
        ),
        AggregationDateUnit.WEEK to setOf(AggregationDateUnit.WEEK),
        AggregationDateUnit.DAY to calendar,
        AggregationDateUnit.HOUR to calendar,
        AggregationDateUnit.MINUTE to calendar,
        AggregationDateUnit.SECOND to calendar,
    )
}

/** Whether [metric] adds up across groups, so its total and running sum mean something. */
private fun additive(metric: AggregationMetric): Boolean =
    metric is AggregationMetric.Count ||
        (metric is AggregationMetric.Numeric && metric.function == AggregationFunction.SUM)

internal fun DerivedExpression.Window.requireValidWindow(
    alias: String,
    declared: Map<String, AggregationMetric>,
    groupBy: List<AggregationGroup>,
) {
    val type = windowFunction.name
    val target = declared[metric]
    require(target != null) {
        "$type window of derived metric [$alias] must read a metric declared before it, but was [$metric]."
    }
    require(target !is AggregationMetric.Derived && nonNumericType(target) == null) {
        "$type window of derived metric [$alias] must read a non-derived numeric metric, but was [$metric]."
    }
    val groups = groupBy.associateBy(AggregationGroup::alias)
    when (this) {
        is DerivedExpression.Total -> {
            requireAdditive(type, alias, target)
            requireWithin(type, alias, within, groups.keys)
        }

        is DerivedExpression.Running -> {
            if (function == RunningFunction.SUM) requireAdditive("$type SUM", alias, target)
            requireWithin(type, alias, within, groups.keys)
            val orderBy = order.field.path
            val orderMetric = declared[orderBy]
            require(
                orderBy in groups ||
                    (orderMetric != null && orderMetric !is AggregationMetric.Derived && nonNumericType(orderMetric) == null),
            ) {
                "$type window of derived metric [$alias] must order by a group or a non-derived numeric metric, " +
                    "but was [$orderBy]."
            }
            require(orderBy !in within) {
                "$type window of derived metric [$alias] cannot order by its partition group [$orderBy]."
            }
        }

        is DerivedExpression.Previous -> {
            val group = groups[along]
            require(group is AggregationGroup.DateHistogram) {
                "$type window of derived metric [$alias] must step along a DATE_HISTOGRAM group, but was [$along]."
            }
            period?.let { period ->
                require(period in PREVIOUS_PERIODS.getValue(group.unit)) {
                    "$type window of derived metric [$alias] cannot step a ${group.unit} group by $period."
                }
            }
        }

        is DerivedExpression.Rank -> requireWithin(type, alias, within, groups.keys)
    }
}

private fun requireAdditive(type: String, alias: String, target: AggregationMetric) {
    require(additive(target)) {
        "$type window of derived metric [$alias] reads only COUNT or SUM, but [${target.alias}] is neither."
    }
}

private fun requireWithin(type: String, alias: String, within: List<String>, groups: Set<String>) {
    require(within.distinct().size == within.size) {
        "$type window of derived metric [$alias] must not repeat a partition group."
    }
    within.forEach { group ->
        require(group in groups) {
            "$type window of derived metric [$alias] must partition by groups of the query, but was [$group]."
        }
    }
}
