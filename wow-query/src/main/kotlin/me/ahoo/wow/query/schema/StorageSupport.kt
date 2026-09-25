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
) {
    init {
        require(percentile != SupportMode.RESIDUAL && distinctCount != SupportMode.RESIDUAL) {
            "Percentile and distinct count have no residual implementation."
        }
    }
}

/** What a storage declares beyond each field's native capabilities; bound into the schema by its adapter. */
data class StorageSupport(
    val paging: PagingSupport = PagingSupport(),
    val aggregation: AggregationSupport = AggregationSupport(),
) {
    companion object {
        /** Everything native: the default for a schema whose storage declares nothing else. */
        @JvmField
        val NATIVE = StorageSupport()
    }
}
