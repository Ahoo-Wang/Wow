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

package me.ahoo.wow.spi.access

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The stamps of a store that keeps an aggregate's history with its access attributes ([AccessValues]): a log, or an
 * index of its streams. A stamp writes the aggregate's current attributes over its earlier streams, so that a query
 * of its history authorizes each stream by what the aggregate is now.
 *
 * Each stream keeps the version its attributes are of, its stamp: its own version when it is appended with them, the
 * version of the commit that changed them when a stamp wrote them. A stamp as of a version writes only the streams
 * that are not stamped, or stamped as of that version or an earlier one. So stamps of different versions, in any order
 * and as many times as a retry or a replay repeats them, leave each stream with the latest one's attributes, and a
 * stamp of the same version writes them again, as a new model derives them.
 */
@InternalWowApi
interface AccessStamps {
    /**
     * Writes [access], the attributes of [ref] as of its version [asOf], over its streams of the versions from [from]
     * until [until] (exclusive) that are not stamped or are stamped as of [asOf] or earlier; returns how many it wrote.
     * A version the store does not hold is passed over.
     */
    suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int

    /**
     * Writes each of [stamps] as [stamp] writes it, in as few writes as the store makes of them: the access walk's
     * stamps of a page of aggregates. Returns how many streams they wrote. Each is one stamp: [stamp] one at a time,
     * unless the store writes them together.
     */
    suspend fun stampAll(stamps: List<AccessStamp>): Int =
        stamps.sumOf { stamp(it.ref, it.access, it.asOf, it.from, it.until) }

    @InternalWowApi
    companion object {
        /** Whether no two of [stamps] cover one stream: a store may then write them in any order, or at once. */
        fun disjoint(stamps: List<AccessStamp>): Boolean = stamps.groupBy { it.ref }.values.all { ranges ->
            ranges.sortedBy { it.from }.zipWithNext().all { (before, after) -> before.until <= after.from }
        }
    }
}

/** One stamp of [AccessStamps.stampAll]: [access] as of [asOf] over the streams of [ref] from [from] until [until]. */
@InternalWowApi
data class AccessStamp(
    val ref: AggregateRef,
    val access: Map<String, Any?>,
    val asOf: Int,
    val from: Int,
    val until: Int,
)
