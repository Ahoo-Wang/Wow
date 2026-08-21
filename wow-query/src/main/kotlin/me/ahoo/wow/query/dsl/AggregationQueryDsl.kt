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

package me.ahoo.wow.query.dsl

import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Sort

@QueryDslMarker
class AggregationQueryDsl {
    private var condition: Condition = Condition.ALL
    private val groupBy = mutableListOf<AggregationGroup>()
    private val metrics = mutableListOf<AggregationMetric>()
    private var sort: List<Sort> = emptyList()
    private var limit: Int = AggregationQuery.DEFAULT_LIMIT

    fun condition(condition: Condition) {
        this.condition = condition
    }

    fun condition(block: ConditionDsl.() -> Unit) {
        condition(ConditionDsl().apply(block).build())
    }

    fun groupBy(field: String, alias: String) {
        groupBy += AggregationGroup.Terms(field, alias)
    }

    fun histogram(field: String, alias: String, interval: Double, offset: Double = 0.0) {
        groupBy += AggregationGroup.Histogram(field, alias, interval, offset)
    }

    fun dateHistogram(
        field: String,
        alias: String,
        unit: AggregationDateUnit,
        timeZone: String = "UTC",
    ) {
        groupBy += AggregationGroup.DateHistogram(field, alias, unit, timeZone)
    }

    fun count(alias: String) {
        metrics += AggregationMetric.Count(alias)
    }

    fun sum(field: String, alias: String) {
        metrics += AggregationMetric.Sum(field, alias)
    }

    fun avg(field: String, alias: String) {
        metrics += AggregationMetric.Avg(field, alias)
    }

    fun min(field: String, alias: String) {
        metrics += AggregationMetric.Min(field, alias)
    }

    fun max(field: String, alias: String) {
        metrics += AggregationMetric.Max(field, alias)
    }

    fun sort(sort: List<Sort>) {
        this.sort = sort
    }

    fun sort(block: SortDsl.() -> Unit) {
        sort(SortDsl().apply(block).build())
    }

    fun limit(limit: Int) {
        this.limit = limit
    }

    fun build(): AggregationQuery = AggregationQuery(
        condition = condition,
        groupBy = groupBy.toList(),
        metrics = metrics.toList(),
        sort = sort,
        limit = limit,
    )
}
