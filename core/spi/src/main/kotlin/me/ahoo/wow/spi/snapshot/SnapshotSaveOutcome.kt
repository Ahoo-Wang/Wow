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

package me.ahoo.wow.spi.snapshot

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * How the save of one snapshot of a [SnapshotStore.saveAll] ended: [Written], [Refused] by the version guard (the store
 * holds the same or a later version, or one of a higher state revision, which the save then marked stale), or
 * [Failed] alone, the others of the batch unaffected. A failure of the whole call is thrown instead.
 */
sealed interface SnapshotSaveOutcome {
    /** Whether the snapshot was written: what [SnapshotStore.save] returns for it. */
    val written: Boolean

    /** The snapshot replaced the stored one, or there was none. */
    data object Written : SnapshotSaveOutcome {
        override val written: Boolean get() = true
    }

    /** The version guard refused the snapshot, which is not an error. */
    data object Refused : SnapshotSaveOutcome {
        override val written: Boolean get() = false
    }

    /**
     * The store failed to save this snapshot alone ([cause]): a value it refuses, say. Whether it was written is not
     * known; saving it again is safe, the save being version-guarded.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class Failed @InternalWowApi constructor(val cause: Throwable) : SnapshotSaveOutcome {
        override val written: Boolean get() = false

        override fun equals(other: Any?): Boolean = this === other || other is Failed && cause == other.cause

        override fun hashCode(): Int = cause.hashCode()

        override fun toString(): String = "Failed(cause=$cause)"

        companion object {
            /** A Failed of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(cause: Throwable): Failed = Failed(cause)
        }
    }

    @InternalWowApi
    companion object {
        /** [Written] when [written], else [Refused]. */
        fun of(written: Boolean): SnapshotSaveOutcome = if (written) Written else Refused

        /**
         * The indexes of [snapshots] in rounds a backend writes one after the other, each round holding a ref once, in
         * the order given: a batch that holds a ref more than once is applied as saving its snapshots in order, while
         * a backend's one statement over a round may touch each key only once.
         */
        fun rounds(snapshots: List<SnapshotRecord>): List<List<Int>> {
            val rounds = ArrayList<ArrayList<Int>>()
            val seen = HashMap<AggregateRef, Int>()
            snapshots.forEachIndexed { index, snapshot ->
                val round = seen.merge(snapshot.ref, 0) { last, _ -> last + 1 }!!
                if (round == rounds.size) {
                    rounds += ArrayList<Int>()
                }
                rounds[round] += index
            }
            return rounds
        }
    }
}
