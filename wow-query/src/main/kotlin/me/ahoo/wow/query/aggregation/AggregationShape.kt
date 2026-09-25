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
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.SupportMode
import tools.jackson.databind.node.ObjectNode

/*
 * The logical shape of an aggregation that every backend and the core read the same way. Element scopes and metric
 * filter scopes are carried by each reference's ResolvedField; these are the query-level facts.
 */

/** The sole dense date histogram, or `null`: dense requires DATE_HISTOGRAM to be the only group. */
val AggregationQuery.denseGroup: AggregationGroup.DateHistogram?
    get() = (groupBy.singleOrNull() as? AggregationGroup.DateHistogram)?.takeIf { it.dense }

/**
 * The sole dense DATE_PART group, or `null`. Its fixed domain is filled by the core on every storage, so the storage
 * never sees it dense.
 */
val AggregationQuery.denseDatePart: AggregationGroup.DatePart?
    get() = (groupBy.singleOrNull() as? AggregationGroup.DatePart)?.takeIf { it.dense }

/** Whether the effective sort orders groups by a metric, which only a complete set of groups can answer. */
val AggregationQuery.metricSorted: Boolean
    get() {
        val metrics = metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        return sort.any { it.field.path in metrics }
    }

/**
 * How the core runs one aggregation over a storage's [AggregationSupport]: the query and window it sends down, and
 * the residual operators it applies, in order, to the rows that come back (dense fill, then HAVING, then top-N or the
 * limit). A feature the storage declares [SupportMode.NONE] is rejected before any I/O.
 */
internal class AggregationPlan private constructor(
    val query: AggregationQuery,
    val native: AggregationQuery,
    val window: GroupWindow,
    val dense: DenseFill?,
    val having: Boolean,
    val topN: Boolean,
) {
    val adjusted: Boolean
        get() = native !== query

    fun rowsLimit(): Int = query.limit

    fun applyHaving(row: ObjectNode): Boolean = !having || row.matchesHaving(checkNotNull(query.having))

    /** Top-N over the effective sort, keeping the backend's group order for group fields. */
    fun topRows(): BoundedTopRows = BoundedTopRows(
        query.effectiveSort(),
        query.limit,
        native.effectiveSort().map { it.field.path },
    )

    companion object {
        fun of(query: AggregationQuery, support: AggregationSupport): AggregationPlan {
            val dense = query.denseGroup
            val metricSorted = query.metricSorted
            support.having.require(query.having != null, "HAVING")
            support.denseFill.require(dense != null, "dense DATE_HISTOGRAM")
            support.topN.require(metricSorted, "sorting groups by a metric")
            support.percentile.require(query.metrics.any { it is AggregationMetric.Percentile }, "PERCENTILE")
            support.distinctCount.require(query.metrics.any { it is AggregationMetric.DistinctCount }, "DISTINCT_COUNT")

            query.denseDatePart?.let { return datePart(query, it) }
            val residualDense = dense != null && support.denseFill == SupportMode.RESIDUAL
            val residualHaving = query.having != null && support.having == SupportMode.RESIDUAL
            val residualTopN = metricSorted && support.topN == SupportMode.RESIDUAL
            if (!residualDense && !residualHaving && !residualTopN) {
                return AggregationPlan(query, query, GroupWindow.First(query.limit), null, false, false)
            }
            val groupAliases = query.groupBy.mapTo(hashSetOf(), AggregationGroup::alias)
            val native = query.copy(
                groupBy = if (residualDense) listOf(checkNotNull(dense).copy(dense = false)) else query.groupBy,
                sort = if (residualTopN) query.sort.filter { it.field.path in groupAliases } else query.sort,
                having = if (residualHaving) null else query.having,
            )
            val window = if (residualHaving || residualTopN) GroupWindow.All else GroupWindow.First(query.limit)
            return AggregationPlan(
                query,
                native,
                window,
                if (residualDense) DateHistogramFill(checkNotNull(dense), query.metrics) else null,
                residualHaving,
                residualTopN,
            )
        }

        /**
         * A dense DATE_PART: the storage returns the (at most 31) present keys in key order, and the core fills the
         * domain, then applies HAVING and a metric sort itself, since both must see the fill rows.
         */
        private fun datePart(query: AggregationQuery, group: AggregationGroup.DatePart): AggregationPlan {
            val direction = query.effectiveSort().first { it.field.path == group.alias }.direction
            val native = query.copy(
                groupBy = listOf(group.copy(dense = false)),
                sort = query.sort.filter { it.field.path == group.alias },
                having = null,
            )
            return AggregationPlan(
                query,
                native,
                GroupWindow.All,
                DatePartFill(group, direction, query.metrics),
                query.having != null,
                query.metricSorted,
            )
        }

        private fun SupportMode.require(used: Boolean, feature: String) {
            if (used && this == SupportMode.NONE) {
                throw QuerySchemaValidationException("Storage does not support $feature.")
            }
        }
    }
}
