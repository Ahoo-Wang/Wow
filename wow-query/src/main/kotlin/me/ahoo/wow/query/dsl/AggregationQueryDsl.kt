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
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort

@QueryDslMarker
class AggregationQueryDsl : AggregationScopeDsl("") {
    fun build(): AggregationQuery {
        val leaf = buildLeaf()
        return AggregationQuery(
            filter = filter,
            elements = leaf.elements,
            groupBy = leaf.groupBy,
            metrics = leaf.metrics,
            sort = leaf.sort,
            limit = leaf.limit,
        )
    }
}

@QueryDslMarker
class AggregationElementDsl internal constructor(path: String) : AggregationScopeDsl(path)

abstract class AggregationScopeDsl internal constructor(private val path: String) {
    internal var filter: FilterExpression = MatchAllFilter
    private var child: AggregationElementDsl? = null
    private val groupBy = mutableListOf<AggregationGroup>()
    private val metrics = mutableListOf<AggregationMetric>()
    private var sort: List<Sort> = emptyList()
    private var limit: Int = AggregationQuery.DEFAULT_LIMIT

    fun filter(filter: FilterExpression) {
        this.filter = filter
    }

    fun filter(block: FilterDsl.() -> Unit) {
        filter(FilterDsl(path).apply(block).build())
    }

    fun expand(elementPath: String, block: AggregationElementDsl.() -> Unit) {
        require(child == null) { "Only one child expand is allowed in each aggregation scope." }
        child = AggregationElementDsl(resolvePath(path, elementPath)).apply(block)
    }

    fun groupBy(field: String, alias: String) {
        groupBy += AggregationGroup.Terms(resolvePath(path, field), alias)
    }

    fun histogram(field: String, alias: String, interval: Double) {
        groupBy += AggregationGroup.Histogram(resolvePath(path, field), alias, interval)
    }

    fun dateHistogram(
        field: String,
        alias: String,
        unit: AggregationDateUnit,
        timeZone: String = "UTC",
    ) {
        groupBy += AggregationGroup.DateHistogram(resolvePath(path, field), alias, unit, timeZone)
    }

    fun count(alias: String) {
        metrics += AggregationMetric.Count(alias)
    }

    fun sum(field: String, alias: String) {
        numeric(AggregationFunction.SUM, field, alias)
    }

    fun avg(field: String, alias: String) {
        numeric(AggregationFunction.AVG, field, alias)
    }

    fun min(field: String, alias: String) {
        numeric(AggregationFunction.MIN, field, alias)
    }

    fun max(field: String, alias: String) {
        numeric(AggregationFunction.MAX, field, alias)
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

    internal fun buildLeaf(): AggregationLeaf {
        val nested = child
        if (nested == null) {
            return AggregationLeaf(
                elements = path.takeIf(String::isNotEmpty)?.let { listOf(AggregationElement(it, filter)) }.orEmpty(),
                groupBy = groupBy.toList(),
                metrics = metrics.toList(),
                sort = sort,
                limit = limit,
            )
        }
        require(groupBy.isEmpty() && metrics.isEmpty() && sort.isEmpty() && limit == AggregationQuery.DEFAULT_LIMIT) {
            "groupBy, metrics, sort, and limit must be declared in the innermost aggregation scope."
        }
        val leaf = nested.buildLeaf()
        val current = path.takeIf(String::isNotEmpty)?.let { AggregationElement(it, filter) }
        return leaf.copy(elements = current?.let { listOf(it) + leaf.elements } ?: leaf.elements)
    }

    private fun numeric(function: AggregationFunction, field: String, alias: String) {
        metrics += AggregationMetric.Numeric(
            function = function,
            expression = AggregationExpression.Field(resolvePath(path, field)),
            alias = alias,
        )
    }
}

internal data class AggregationLeaf(
    val elements: List<AggregationElement>,
    val groupBy: List<AggregationGroup>,
    val metrics: List<AggregationMetric>,
    val sort: List<Sort>,
    val limit: Int,
)

private fun resolvePath(parent: String, field: String): String {
    if (parent.isEmpty() || field == parent || field.startsWith("$parent.")) {
        return field
    }
    return "$parent.$field"
}
