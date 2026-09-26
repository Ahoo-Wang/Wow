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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.spec.MetricSpec

/** How storage supports a query feature (design §5.5). */
enum class SupportMode {
    /** The backend computes it natively. */
    NATIVE,

    /** The core computes it after the backend with a shared pure operator and adjusts what it sends down. */
    RESIDUAL,

    /** Unavailable: admission rejects a query that needs it. */
    NONE,
}

/** How a storage pages. Neither mode has a residual implementation, so each is NATIVE or NONE. */
data class PagingSupport(
    /** Keyset pages ordered by the cursor sort (cursor queries). */
    val keyset: SupportMode = SupportMode.NATIVE,
    /** A list without a limit, streamed to the end. */
    val unboundedStream: SupportMode = SupportMode.NATIVE,
) {
    init {
        require(keyset != SupportMode.RESIDUAL && unboundedStream != SupportMode.RESIDUAL) {
            "Paging has no residual implementation."
        }
    }
}

/**
 * How a storage aggregates. HAVING, top-N by a metric and dense date-histogram fill may be RESIDUAL; percentiles and
 * distinct counts need the records themselves, so they are NATIVE or NONE.
 */
data class AggregationSupport(
    val having: SupportMode = SupportMode.NATIVE,
    /** Ordering groups by a metric before the limit. */
    val topN: SupportMode = SupportMode.NATIVE,
    /** Filling the empty buckets of a dense date histogram. */
    val denseFill: SupportMode = SupportMode.NATIVE,
    val percentile: SupportMode = SupportMode.NATIVE,
    val distinctCount: SupportMode = SupportMode.NATIVE,
    /** FIRST and LAST: the value on the earliest or latest record of a group by an ordering field. */
    val firstLast: SupportMode = SupportMode.NATIVE,
) {
    init {
        require(percentile != SupportMode.RESIDUAL && distinctCount != SupportMode.RESIDUAL) {
            "Percentile and distinct count have no residual implementation."
        }
        require(firstLast != SupportMode.RESIDUAL) { "FIRST and LAST have no residual implementation." }
    }
}

/** How a storage's presence operators see a stored `null` and an empty array. */
enum class AbsentValues {
    /** Distinct from a missing field: `EXISTS` matches them and `IS_EMPTY` matches only an empty array. */
    DISTINCT,

    /**
     * Indistinguishable from a missing field: the storage indexes no value for them, so `EXISTS`, `NOT_EXISTS`,
     * `IS_NULL`, `IS_NOT_NULL` and `IS_EMPTY` treat them as missing. The descriptor lists the affected fields as a
     * NULL_OR_EMPTY_AS_MISSING constraint.
     */
    AS_MISSING,
}

/** What a storage declares beyond each field's native capabilities; bound into the schema by its adapter. */
data class StorageSupport(
    val paging: PagingSupport = PagingSupport(),
    val aggregation: AggregationSupport = AggregationSupport(),
    /**
     * Sorting by two array-valued fields on independent array paths. NATIVE or NONE: with NONE admission rejects such
     * a sort and the descriptor lists the array-valued sort fields as a PARALLEL_ARRAY_SORT constraint.
     */
    val parallelArraySort: SupportMode = SupportMode.NATIVE,
    /** How presence operators see a stored `null` and an empty array. */
    val absentValues: AbsentValues = AbsentValues.DISTINCT,
    /**
     * `EQ` / `NE` with an array operand (exact array equality). NATIVE or NONE: with NONE admission rejects such an
     * operand and the descriptor lists an ARRAY_EQUALITY constraint.
     */
    val arrayEquality: SupportMode = SupportMode.NATIVE,
) {
    init {
        require(parallelArraySort != SupportMode.RESIDUAL) { "Parallel array sort has no residual implementation." }
        require(arrayEquality != SupportMode.RESIDUAL) { "Array equality has no residual implementation." }
    }

    companion object {
        /** Everything native: the default for a schema whose storage declares nothing else. */
        @JvmField
        val NATIVE = StorageSupport()
    }
}

/**
 * Whether the storage computes [metric] at all. PERCENTILE, DISTINCT_COUNT, FIRST and LAST have no residual form to
 * fall back on, so admission rejects them where the storage declares [SupportMode.NONE], and the descriptor omits them.
 */
internal fun StorageSupport.offers(metric: MetricSpec): Boolean = when (metric) {
    MetricSpec.FIRST, MetricSpec.LAST -> aggregation.firstLast != SupportMode.NONE
    MetricSpec.PERCENTILE -> aggregation.percentile != SupportMode.NONE
    MetricSpec.DISTINCT_COUNT -> aggregation.distinctCount != SupportMode.NONE
    MetricSpec.COUNT, MetricSpec.NUMERIC, MetricSpec.ANY, MetricSpec.DERIVED -> true
}
