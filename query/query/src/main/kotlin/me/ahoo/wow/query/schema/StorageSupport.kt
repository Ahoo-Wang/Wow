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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.query.spec.MetricSpec

/**
 * How a storage supports a query feature: natively, through the core after the storage, or not at all.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
enum class SupportMode {
    /** The backend computes it natively. */
    NATIVE,

    /** The core computes it after the backend with a shared pure operator and adjusts what it sends down. */
    RESIDUAL,

    /** Unavailable: admission rejects a query that needs it. */
    NONE,
}

/**
 * How a storage pages. Neither mode has a residual implementation, so each is NATIVE or NONE. A storage declares it
 * with [of].
 */
class PagingSupport @InternalWowApi constructor(
    /** Keyset pages ordered by the cursor sort (cursor queries). */
    val keyset: SupportMode = SupportMode.NATIVE,
    /** A list without a limit, streamed to the end. */
    val unboundedStream: SupportMode = SupportMode.NATIVE,
    /**
     * The furthest record an offset page may reach (`offset + limit`), such as Elasticsearch's
     * `index.max_result_window`; `null` is unlimited. Admission rejects a paged query whose window ends beyond it.
     */
    val maxOffsetWindow: Int? = null,
) {
    init {
        require(keyset != SupportMode.RESIDUAL && unboundedStream != SupportMode.RESIDUAL) {
            "Paging has no residual implementation."
        }
        require(maxOffsetWindow == null || maxOffsetWindow >= 1) { "maxOffsetWindow must be greater than 0." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PagingSupport && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "PagingSupport(keyset=$keyset, unboundedStream=$unboundedStream, maxOffsetWindow=$maxOffsetWindow)"

    private fun valueFields(): List<Any?> = listOf(keyset, unboundedStream, maxOffsetWindow)

    companion object {
        /** Paging with keyset pages, unbounded streams and an offset window as given; everything native by default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            keyset: SupportMode = SupportMode.NATIVE,
            unboundedStream: SupportMode = SupportMode.NATIVE,
            maxOffsetWindow: Int? = null,
        ): PagingSupport = PagingSupport(keyset, unboundedStream, maxOffsetWindow)
    }
}

/**
 * How a storage aggregates. HAVING, top-N by a metric, dense date-histogram fill and the windows may be RESIDUAL;
 * percentiles and distinct counts need the records themselves, so they are NATIVE or NONE. A storage declares it with
 * [of].
 */
class AggregationSupport @InternalWowApi constructor(
    val having: SupportMode = SupportMode.NATIVE,
    /** Ordering groups by a metric before the limit. */
    val topN: SupportMode = SupportMode.NATIVE,
    /** Filling the empty buckets of a dense date histogram. */
    val denseFill: SupportMode = SupportMode.NATIVE,
    val percentile: SupportMode = SupportMode.NATIVE,
    val distinctCount: SupportMode = SupportMode.NATIVE,
    /** FIRST and LAST: the value on the earliest or latest record of a group by an ordering field. */
    val firstLast: SupportMode = SupportMode.NATIVE,
    /**
     * The window functions of derived metrics; a function absent from the map is NONE. A RESIDUAL window is computed
     * by the core over every group the storage returns.
     */
    val windows: Map<WindowFunction, SupportMode> = RESIDUAL_WINDOWS,
    /**
     * Grouping the elements of a query that expands them by a field of their root record (`scope: ROOT`). NATIVE or
     * NONE: with NONE admission rejects such a group and the descriptor says `rootGroups: false`.
     */
    val rootGroups: SupportMode = SupportMode.NATIVE,
    /**
     * Where [topN] is RESIDUAL: the metric types the storage orders one TERMS group by itself, taking the first
     * `limit` groups exactly, so such a query reads no more groups than it returns. Empty: [topN] alone decides.
     */
    val termsTopN: Set<MetricSpec> = emptySet(),
) {
    init {
        require(rootGroups != SupportMode.RESIDUAL) { "Root groups have no residual implementation." }
        require(percentile != SupportMode.RESIDUAL && distinctCount != SupportMode.RESIDUAL) {
            "Percentile and distinct count have no residual implementation."
        }
        require(firstLast != SupportMode.RESIDUAL) { "FIRST and LAST have no residual implementation." }
    }

    /** How the storage supports [function]. */
    fun window(function: WindowFunction): SupportMode = windows[function] ?: SupportMode.NONE

    override fun equals(other: Any?): Boolean =
        this === other || other is AggregationSupport && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "AggregationSupport(having=$having, topN=$topN, denseFill=$denseFill, percentile=$percentile, " +
            "distinctCount=$distinctCount, firstLast=$firstLast, windows=$windows, rootGroups=$rootGroups, " +
            "termsTopN=$termsTopN)"

    private fun valueFields(): List<Any?> =
        listOf(having, topN, denseFill, percentile, distinctCount, firstLast, windows, rootGroups, termsTopN)

    companion object {
        /** Every window computed by the core, which any storage that groups supports. */
        @JvmField
        val RESIDUAL_WINDOWS: Map<WindowFunction, SupportMode> =
            WindowFunction.entries.associateWith { SupportMode.RESIDUAL }

        /** Aggregation as given; everything native, and the windows computed by the core, by default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            having: SupportMode = SupportMode.NATIVE,
            topN: SupportMode = SupportMode.NATIVE,
            denseFill: SupportMode = SupportMode.NATIVE,
            percentile: SupportMode = SupportMode.NATIVE,
            distinctCount: SupportMode = SupportMode.NATIVE,
            firstLast: SupportMode = SupportMode.NATIVE,
            windows: Map<WindowFunction, SupportMode> = RESIDUAL_WINDOWS,
            rootGroups: SupportMode = SupportMode.NATIVE,
            termsTopN: Set<MetricSpec> = emptySet(),
        ): AggregationSupport = AggregationSupport(
            having, topN, denseFill, percentile, distinctCount, firstLast, windows, rootGroups, termsTopN,
        )
    }
}

/**
 * How a storage's presence operators see a stored `null` and an empty array.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
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

/**
 * What a storage declares beyond each field's native capabilities, bound into the schema by its adapter
 * ([QueryStorageFacts.Builder.storage]); a storage declares it with [of], or takes [NATIVE].
 */
class StorageSupport @InternalWowApi constructor(
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
    /**
     * The restriction of a dataset bound to an aggregate ([me.ahoo.wow.query.dataset.DatasetAccess]): its rows by the
     * current access attributes of the aggregate each row is of. NATIVE or NONE, and NONE unless the storage declares
     * it: an access policy then reads none of such a dataset's rows but for a predicate that grants every aggregate.
     */
    val datasetAccess: SupportMode = SupportMode.NONE,
    /**
     * Sorting by an array whose elements may be `null`, which the query protocol orders by its least element
     * ascending, a `null` element the least. NATIVE or NONE: a storage that does not see a `null` element (it indexes
     * none) declares NONE, and admission refuses such a sort ([sorts]) rather than have it ordered otherwise.
     */
    val nullElementSort: SupportMode = SupportMode.NATIVE,
) {
    init {
        require(parallelArraySort != SupportMode.RESIDUAL) { "Parallel array sort has no residual implementation." }
        require(arrayEquality != SupportMode.RESIDUAL) { "Array equality has no residual implementation." }
        require(datasetAccess != SupportMode.RESIDUAL) { "A dataset's access has no residual implementation." }
        require(nullElementSort != SupportMode.RESIDUAL) { "A null element's sort has no residual implementation." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is StorageSupport && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "StorageSupport(paging=$paging, aggregation=$aggregation, parallelArraySort=$parallelArraySort, " +
            "absentValues=$absentValues, arrayEquality=$arrayEquality, datasetAccess=$datasetAccess, " +
            "nullElementSort=$nullElementSort)"

    private fun valueFields(): List<Any?> =
        listOf(paging, aggregation, parallelArraySort, absentValues, arrayEquality, datasetAccess, nullElementSort)

    /** Whether this storage sorts a field holding [value] as the protocol orders it: see [nullElementSort]. */
    @InternalWowApi
    fun sorts(value: QueryValueSchema): Boolean = nullElementSort != SupportMode.NONE || !value.mayHoldNullElement()

    companion object {
        /**
         * Everything native but a dataset's access, which a storage declares itself: the default for a schema whose
         * storage declares nothing else.
         */
        @JvmField
        val NATIVE = StorageSupport()

        /** Paging, aggregation and the array and presence features as given; everything native by default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            paging: PagingSupport = PagingSupport(),
            aggregation: AggregationSupport = AggregationSupport(),
            parallelArraySort: SupportMode = SupportMode.NATIVE,
            absentValues: AbsentValues = AbsentValues.DISTINCT,
            arrayEquality: SupportMode = SupportMode.NATIVE,
        ): StorageSupport = StorageSupport(paging, aggregation, parallelArraySort, absentValues, arrayEquality)
    }
}

/** Refuses a sort by [field], which holds [value], where this storage cannot order it as the protocol does. */
internal fun StorageSupport.requireSorts(value: QueryValueSchema, field: QueryField<*>) {
    if (!sorts(value)) {
        throw QueryViolation.StorageUnsupported(
            "sorting by an array whose elements may be null, which it does not index: it cannot order a null " +
                "element least",
            field,
        ).rejection()
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
