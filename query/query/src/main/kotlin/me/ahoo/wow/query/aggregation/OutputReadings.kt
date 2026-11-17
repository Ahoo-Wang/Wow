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
package me.ahoo.wow.query.aggregation

import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.schema.Temporal

/**
 * How each output column of this aggregation reads, by its alias, when it carries a field's own value read with a
 * [Temporal]: a TERMS group of a field, and an ANY, FIRST or LAST metric. The core compares such a column by that
 * reading ([me.ahoo.wow.query.backend.orderOf], [me.ahoo.wow.query.backend.valueKey]) wherever it orders, partitions
 * or pairs groups itself, so a date ([Temporal.Date]) is its instant there as in a filter or a list. Every other
 * column is a number or a plain value, and is absent.
 */
internal fun AdmittedQuery<AggregationQuery>.outputReadings(): Map<String, Temporal> {
    val readings = LinkedHashMap<String, Temporal>()
    query.groupBy.forEach { group ->
        if (group is AggregationGroup.Terms && group.expression == null) {
            field(checkNotNull(group.field)).temporal?.let { readings[group.alias] = it }
        }
    }
    query.metrics.forEach { metric ->
        val field = when (metric) {
            is AggregationMetric.Any -> metric.field
            is AggregationMetric.Edge -> metric.field
            else -> null
        }
        field?.let { field(it).temporal?.let { temporal -> readings[metric.alias] = temporal } }
    }
    return readings
}
