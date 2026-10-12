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

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.spec.MetricSpec
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.api.query.windowFunction
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
@WowSpi
val AggregationQuery.denseGroup: AggregationGroup.DateHistogram?
    get() = (groupBy.singleOrNull() as? AggregationGroup.DateHistogram)?.takeIf { it.dense }

/**
 * The sole dense DATE_PART group, or `null`. Its fixed domain is filled by the core on every storage, so the storage
 * never sees it dense.
 */
private val AggregationQuery.denseDatePart: AggregationGroup.DatePart?
    get() = (groupBy.singleOrNull() as? AggregationGroup.DatePart)?.takeIf { it.dense }

/** Whether the effective sort orders groups by a metric, which only a complete set of groups can answer. */
@WowSpi
val AggregationQuery.metricSorted: Boolean
    get() {
        val metrics = metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        return sort.any { it.field.path in metrics }
    }

/** Which operators of an aggregation the core computes instead of the storage. */
private class Residual(val dense: Boolean, val windows: Boolean, val having: Boolean, val topN: Boolean) {
    val any: Boolean
        get() = dense || windows || having || topN

    /** Whether an operator needs every group: dense fill alone walks the groups the limit keeps. */
    val allGroups: Boolean
        get() = windows || having || topN

    companion object {
        fun of(query: AggregationQuery, support: AggregationSupport): Residual {
            val dense = query.denseGroup != null && support.denseFill == SupportMode.RESIDUAL
            // Windows read every group, the fill rows included.
            val windows = query.windows.let { windows ->
                windows.isNotEmpty() &&
                    (dense || windows.any { support.window(it.windowFunction) == SupportMode.RESIDUAL })
            }
            // HAVING and a metric sort must see the fill rows the core adds and may read a window: run natively,
            // they would act on the storage's rows before the fill, and a native top-N would also break the key
            // order the fill walks.
            val after = dense || windows
            return Residual(
                dense = dense,
                windows = windows,
                having = query.having != null && (support.having == SupportMode.RESIDUAL || after),
                topN = query.metricSorted &&
                    (after || support.topN == SupportMode.RESIDUAL && !query.termsTopN(support)),
            )
        }
    }
}

/**
 * Whether the storage takes this query's top N groups by a metric itself ([AggregationSupport.termsTopN]): one TERMS
 * group that reads the counting unit, no HAVING, and every metric the effective sort names of a type the storage
 * orders by.
 */
private fun AggregationQuery.termsTopN(support: AggregationSupport): Boolean {
    val group = groupBy.singleOrNull() as? AggregationGroup.Terms ?: return false
    if (having != null || elements.isNotEmpty() && group.scope == AggregationGroupScope.ROOT) return false
    val specs = metrics.associate { it.alias to it.spec }
    return sort.mapNotNull { specs[it.field.path] }.all(support.termsTopN::contains)
}

/**
 * How the core runs one aggregation over a storage's [AggregationSupport]: the query and window it sends down, and
 * the residual operators it applies, in order, to the rows that come back (dense fill, then windows, then HAVING,
 * then top-N or the limit). A feature the storage declares [SupportMode.NONE] was rejected at admission
 * ([requireSupported]). When dense fill is residual, windows, HAVING and top-N are residual too, since they must see
 * the fill rows; when windows are residual, HAVING and top-N are too, since they may read a window.
 */
internal class AggregationPlan private constructor(
    val query: AggregationQuery,
    val native: AggregationQuery,
    val window: GroupWindow,
    val dense: DenseFill?,
    val having: Boolean,
    val topN: Boolean,
    val windows: GroupWindows? = null,
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
            query.denseDatePart?.let { return datePart(query, it) }
            val residual = Residual.of(query, support)
            if (!residual.any) {
                return AggregationPlan(query, query, GroupWindow.First(query.limit), null, false, false)
            }
            val dense = query.denseGroup?.takeIf { residual.dense }
            val windowed = if (residual.windows) windowedMetrics(query) else emptySet()
            val native = query.native(dense, residual.having, residual.topN, windowed)
            val window = if (residual.allGroups) GroupWindow.All else GroupWindow.First(query.limit)
            return AggregationPlan(
                query,
                native,
                window,
                dense?.let { DateHistogramFill(it, native.metrics) },
                residual.having,
                residual.topN,
                windowed.takeIf { it.isNotEmpty() }?.let { GroupWindows(query, it) },
            )
        }

        /**
         * A dense DATE_PART: the storage returns the (at most 31) present keys in key order, and the core fills the
         * domain, then applies HAVING and a metric sort itself, since both must see the fill rows.
         */
        private fun datePart(query: AggregationQuery, group: AggregationGroup.DatePart): AggregationPlan {
            val direction = query.effectiveSort().first { it.field.path == group.alias }.direction
            val windowed = windowedMetrics(query)
            val native = query.copy(
                groupBy = listOf(group.copy(dense = false)),
                metrics = query.metrics.filterNot { it.alias in windowed },
                sort = query.sort.filter { it.field.path == group.alias },
                having = null,
            )
            return AggregationPlan(
                query,
                native,
                GroupWindow.All,
                DatePartFill(group, direction, native.metrics),
                query.having != null,
                query.metricSorted,
                windowed.takeIf { it.isNotEmpty() }?.let { GroupWindows(query, it) },
            )
        }

        /** The query sent down: without the residual [dense] flag, HAVING, metric sort or [windowed] metrics. */
        private fun AggregationQuery.native(
            dense: AggregationGroup.DateHistogram?,
            residualHaving: Boolean,
            residualTopN: Boolean,
            windowed: Set<String>,
        ): AggregationQuery {
            val groupAliases = groupBy.mapTo(hashSetOf(), AggregationGroup::alias)
            return copy(
                groupBy = dense?.let { listOf(it.copy(dense = false)) } ?: groupBy,
                metrics = metrics.filterNot { it.alias in windowed },
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
    support.rootGroups.require(
        query.elements.isNotEmpty() && query.groupBy.any { it.scope == AggregationGroupScope.ROOT },
        "grouping elements by a root field",
    )
    val used = query.metrics.mapTo(hashSetOf()) { it.spec }
    OFFERED_METRICS.forEach { (spec, feature) ->
        requireValid(spec !in used || storage.offers(spec)) { QueryViolation.StorageUnsupported(feature) }
    }
    query.windows.mapTo(linkedSetOf()) { it.windowFunction }.forEach { function ->
        support.window(function).require(true, "the $function window")
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
