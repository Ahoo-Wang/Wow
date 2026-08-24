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
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort

@QueryDslMarker
class AggregationQueryDsl {
    private var filter: FilterExpression = MatchAllFilter
    private val elements = mutableListOf<AggregationElement>()
    private val groups = mutableListOf<AggregationGroup>()
    private val metrics = mutableListOf<AggregationMetric>()
    private var sort: List<Sort> = emptyList()
    private var limit: Int = AggregationQuery.DEFAULT_LIMIT

    fun filter(filter: FilterExpression) {
        this.filter = filter
    }

    fun filter(block: FilterDsl.() -> Unit) {
        filter(me.ahoo.wow.query.dsl.filter(block))
    }

    fun expand(path: String) = elements.add(AggregationElement(LogicalField(path)))

    fun expand(path: String, block: FilterDsl.() -> Unit) = elements.add(
        AggregationElement(LogicalField(path), me.ahoo.wow.query.dsl.filter(block)),
    )

    fun terms(field: String, alias: String) {
        groups += AggregationGroup.Terms(LogicalField(field), alias)
    }

    fun histogram(field: String, alias: String, interval: Double) {
        groups += AggregationGroup.Histogram(LogicalField(field), alias, interval)
    }

    fun dateHistogram(field: String, alias: String, unit: AggregationDateUnit, timeZone: String = "UTC") {
        groups += AggregationGroup.DateHistogram(LogicalField(field), alias, unit, timeZone)
    }

    fun count(alias: String) {
        metrics += AggregationMetric.Count(alias)
    }

    fun sum(field: String, alias: String) = numeric(AggregationFunction.SUM, field, alias)

    fun avg(field: String, alias: String) = numeric(AggregationFunction.AVG, field, alias)

    fun min(field: String, alias: String) = numeric(AggregationFunction.MIN, field, alias)

    fun max(field: String, alias: String) = numeric(AggregationFunction.MAX, field, alias)

    private fun numeric(function: AggregationFunction, field: String, alias: String) {
        metrics += AggregationMetric.Numeric(
            function,
            AggregationExpression.Field(LogicalField(field)),
            alias,
        )
    }

    fun sort(sort: List<Sort>) {
        this.sort = sort
    }

    fun sort(block: SortDsl.() -> Unit) {
        sort(me.ahoo.wow.query.dsl.sort(block))
    }

    fun limit(limit: Int) {
        this.limit = limit
    }

    fun build(): AggregationQuery = AggregationQuery(filter, elements, groups, metrics, sort, limit)
}
