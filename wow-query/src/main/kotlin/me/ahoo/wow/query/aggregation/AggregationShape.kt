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
import me.ahoo.wow.api.query.spec.MetricSpec
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.offers
import me.ahoo.wow.query.schema.requireValid
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
private val AggregationQuery.denseDatePart: AggregationGroup.DatePart?
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
 * limit). A feature the storage declares [SupportMode.NONE] was rejected at admission ([requireSupported]). When
 * dense fill is residual,
 * HAVING and top-N are residual too, since both must see the fill rows.
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

            query.denseDatePart?.let { return datePart(query, it) }
            val residualDense = dense != null && support.denseFill == SupportMode.RESIDUAL
            // HAVING and a metric sort must see the fill rows the core adds: run natively, they would act on the
            // storage's rows before the fill, and a native top-N would also break the key order the fill walks.
            val residualHaving = query.having != null && (support.having == SupportMode.RESIDUAL || residualDense)
            val residualTopN = metricSorted && (support.topN == SupportMode.RESIDUAL || residualDense)
            if (!residualDense && !residualHaving && !residualTopN) {
                return AggregationPlan(query, query, GroupWindow.First(query.limit), null, false, false)
            }
            val native = query.native(dense?.takeIf { residualDense }, residualHaving, residualTopN)
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

        /** The query sent down: without the residual [dense] flag, HAVING or metric sort. */
        private fun AggregationQuery.native(
            dense: AggregationGroup.DateHistogram?,
            residualHaving: Boolean,
            residualTopN: Boolean,
        ): AggregationQuery {
            val groupAliases = groupBy.mapTo(hashSetOf(), AggregationGroup::alias)
            return copy(
                groupBy = dense?.let { listOf(it.copy(dense = false)) } ?: groupBy,
                sort = if (residualTopN) sort.filter { it.field.path in groupAliases } else sort,
                having = if (residualHaving) null else having,
            )
        }
    }
}

/**
 * Rejects, at admission, a feature of [query] that [storage] declares [SupportMode.NONE]: a metric type by the same
 * rule the field records and the descriptor read ([StorageSupport.offers]).
 */
internal fun requireSupported(query: AggregationQuery, storage: StorageSupport) {
    val support = storage.aggregation
    support.having.require(query.having != null, "HAVING")
    support.denseFill.require(query.denseGroup != null, "dense DATE_HISTOGRAM")
    support.topN.require(query.metricSorted, "sorting groups by a metric")
    val used = query.metrics.mapTo(hashSetOf()) { it.spec }
    OFFERED_METRICS.forEach { (spec, feature) ->
        requireValid(spec !in used || storage.offers(spec)) { QueryViolation.StorageUnsupported(feature) }
    }
}

/** The metric types a storage may decline, in the order admission reports them. */
private val OFFERED_METRICS = listOf(
    MetricSpec.PERCENTILE to "PERCENTILE",
    MetricSpec.DISTINCT_COUNT to "DISTINCT_COUNT",
    MetricSpec.FIRST to "FIRST and LAST",
    MetricSpec.LAST to "FIRST and LAST",
)

private fun SupportMode.require(used: Boolean, feature: String) =
    requireValid(!used || this != SupportMode.NONE) { QueryViolation.StorageUnsupported(feature) }
