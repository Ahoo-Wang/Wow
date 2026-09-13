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

import co.elastic.clients.elasticsearch._types.Script
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.mapping.RuntimeField
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.util.NamedValue
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.aggregation.DenseDateGrid

internal data class ElasticsearchAggregationPlan(
    val rootQuery: Query,
    val elements: List<ElasticsearchAggregationElement>,
    val groupSources: List<NamedValue<CompositeAggregationSource>>,
    val metrics: List<ElasticsearchAggregationMetric>,
    val runtimeMappings: Map<String, RuntimeField>,
    val effectiveSort: List<Sort>,
    val limit: Int,
    val metricSorted: Boolean,
    val having: HavingExpression? = null,
    val dense: DenseBucketPlan? = null,
)

/**
 * Client-side dense fill plan for a sole dense date histogram group: [metrics] keeps the ORIGINAL
 * API metrics so empty-value evaluation (declaration order, derived resolution) matches the query.
 */
internal data class DenseBucketPlan(
    val alias: String,
    val grid: DenseDateGrid,
    val metrics: List<AggregationMetric>,
)

internal data class ElasticsearchAggregationElement(
    val path: String,
    val filter: Query,
)

internal sealed interface ElasticsearchAggregationMetric {
    val alias: String
    val filter: Query?

    data class Count(
        override val alias: String,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class Numeric(
        override val alias: String,
        val function: AggregationFunction,
        val field: String,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class Any(
        override val alias: String,
        val field: String,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class DistinctCount(
        override val alias: String,
        val field: String,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class Percentile(
        override val alias: String,
        val field: String,
        val percentile: Double,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class Derived(
        override val alias: String,
        val bucketsPath: Map<String, String>,
        val script: Script,
    ) : ElasticsearchAggregationMetric {
        override val filter: Query? = null
    }
}

internal val ElasticsearchAggregationMetric.valueCountAlias: String
    get() = "__wow_value_count_$alias"
