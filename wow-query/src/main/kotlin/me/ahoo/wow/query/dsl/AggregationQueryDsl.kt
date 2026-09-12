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
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import java.time.ZoneId
import java.time.ZoneOffset

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

    fun expand(path: String) = elements.add(AggregationElement(QueryField(path)))

    fun expand(path: String, block: FilterDsl.() -> Unit) = elements.add(
        AggregationElement(QueryField(path), me.ahoo.wow.query.dsl.filter(block)),
    )

    fun terms(field: String, alias: String) {
        groups += AggregationGroup.Terms(QueryField(field), alias)
    }

    fun histogram(field: String, interval: Double, alias: String) {
        groups += AggregationGroup.Histogram(QueryField(field), alias, interval)
    }

    fun dateHistogram(
        field: String,
        unit: AggregationDateUnit,
        alias: String,
        timeZone: ZoneId = ZoneOffset.UTC,
    ) {
        groups += AggregationGroup.DateHistogram(QueryField(field), alias, unit, timeZone.id)
    }

    fun count(alias: String) {
        metrics += AggregationMetric.Count(alias)
    }

    fun count(alias: String, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.Count(alias, me.ahoo.wow.query.dsl.filter(init))
    }

    fun any(field: String, alias: String) {
        metrics += AggregationMetric.Any(QueryField(field), alias)
    }

    fun any(field: String, alias: String, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.Any(QueryField(field), alias, me.ahoo.wow.query.dsl.filter(init))
    }

    fun field(name: String): AggregationExpression = AggregationExpression.Field(QueryField(name))

    fun constant(value: Double): AggregationExpression = AggregationExpression.Constant(value)

    operator fun AggregationExpression.plus(other: AggregationExpression): AggregationExpression =
        binary(AggregationExpressionOperator.ADD, other)

    operator fun AggregationExpression.minus(other: AggregationExpression): AggregationExpression =
        binary(AggregationExpressionOperator.SUBTRACT, other)

    operator fun AggregationExpression.times(other: AggregationExpression): AggregationExpression =
        binary(AggregationExpressionOperator.MULTIPLY, other)

    operator fun AggregationExpression.div(other: AggregationExpression): AggregationExpression =
        binary(AggregationExpressionOperator.DIVIDE, other)

    private fun AggregationExpression.binary(
        operator: AggregationExpressionOperator,
        other: AggregationExpression,
    ): AggregationExpression = AggregationExpression.Binary(operator, this, other)

    fun sum(field: String, alias: String) = sum(field(field), alias)

    fun sum(field: String, alias: String, init: FilterDsl.() -> Unit) = sum(field(field), alias, init)

    fun avg(field: String, alias: String) = avg(field(field), alias)

    fun avg(field: String, alias: String, init: FilterDsl.() -> Unit) = avg(field(field), alias, init)

    fun min(field: String, alias: String) = min(field(field), alias)

    fun min(field: String, alias: String, init: FilterDsl.() -> Unit) = min(field(field), alias, init)

    fun max(field: String, alias: String) = max(field(field), alias)

    fun max(field: String, alias: String, init: FilterDsl.() -> Unit) = max(field(field), alias, init)

    fun sum(expression: AggregationExpression, alias: String) =
        numeric(AggregationFunction.SUM, expression, alias)

    fun sum(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.SUM, expression, alias, init)

    fun avg(expression: AggregationExpression, alias: String) =
        numeric(AggregationFunction.AVG, expression, alias)

    fun avg(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.AVG, expression, alias, init)

    fun min(expression: AggregationExpression, alias: String) =
        numeric(AggregationFunction.MIN, expression, alias)

    fun min(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.MIN, expression, alias, init)

    fun max(expression: AggregationExpression, alias: String) =
        numeric(AggregationFunction.MAX, expression, alias)

    fun max(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.MAX, expression, alias, init)

    fun distinctCount(field: String, alias: String) = distinctCount(field(field), alias)

    fun distinctCount(field: String, alias: String, init: FilterDsl.() -> Unit) =
        distinctCount(field(field), alias, init)

    fun distinctCount(expression: AggregationExpression, alias: String) {
        metrics += AggregationMetric.DistinctCount(expression, alias)
    }

    fun distinctCount(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.DistinctCount(expression, alias, me.ahoo.wow.query.dsl.filter(init))
    }

    fun stddev(field: String, alias: String) = stddev(field(field), alias)

    fun stddev(field: String, alias: String, init: FilterDsl.() -> Unit) =
        stddev(field(field), alias, init)

    fun stddev(expression: AggregationExpression, alias: String) =
        numeric(AggregationFunction.STDDEV, expression, alias)

    fun stddev(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.STDDEV, expression, alias, init)

    fun variance(field: String, alias: String) = variance(field(field), alias)

    fun variance(field: String, alias: String, init: FilterDsl.() -> Unit) =
        variance(field(field), alias, init)

    fun variance(expression: AggregationExpression, alias: String) =
        numeric(AggregationFunction.VARIANCE, expression, alias)

    fun variance(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.VARIANCE, expression, alias, init)

    fun percentile(field: String, p: Double, alias: String) = percentile(field(field), p, alias)

    fun percentile(field: String, p: Double, alias: String, init: FilterDsl.() -> Unit) =
        percentile(field(field), p, alias, init)

    fun percentile(expression: AggregationExpression, p: Double, alias: String) {
        metrics += AggregationMetric.Percentile(expression, p, alias)
    }

    fun percentile(expression: AggregationExpression, p: Double, alias: String, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.Percentile(expression, p, alias, me.ahoo.wow.query.dsl.filter(init))
    }

    fun median(field: String, alias: String) = median(field(field), alias)

    fun median(field: String, alias: String, init: FilterDsl.() -> Unit) =
        median(field(field), alias, init)

    fun median(expression: AggregationExpression, alias: String) = percentile(expression, 50.0, alias)

    fun median(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        percentile(expression, 50.0, alias, init)

    private fun numeric(
        function: AggregationFunction,
        expression: AggregationExpression,
        alias: String,
    ) {
        metrics += AggregationMetric.Numeric(function, expression, alias)
    }

    private fun numeric(
        function: AggregationFunction,
        expression: AggregationExpression,
        alias: String,
        init: FilterDsl.() -> Unit,
    ) {
        metrics += AggregationMetric.Numeric(function, expression, alias, me.ahoo.wow.query.dsl.filter(init))
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
