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

package me.ahoo.wow.mongo.log

import me.ahoo.wow.api.aggregate.AggregateRef

/** An aggregate's last stream as a [MongoEventLog] saw it: its version and sequence. */
internal class LastSequence(val version: Int, val sequence: Long)

/**
 * Each aggregate's last stream as a [MongoEventLog] saw it, for at most [capacity] aggregates, the least recently used
 * dropped first: an aggregate it does not hold is read from the log.
 */
internal class LastSequences(private val capacity: Int) {
    private val sequences = object : LinkedHashMap<AggregateRef, LastSequence>(INITIAL, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AggregateRef, LastSequence>): Boolean =
            size > capacity
    }

    operator fun get(ref: AggregateRef): LastSequence? = synchronized(sequences) { sequences[ref] }

    /** Records [last] as [ref]'s, unless a later version is recorded. */
    operator fun set(ref: AggregateRef, last: LastSequence) {
        synchronized(sequences) {
            sequences.merge(ref, last) { held, seen -> if (seen.version > held.version) seen else held }
        }
    }

    fun remove(ref: AggregateRef) {
        synchronized(sequences) { sequences.remove(ref) }
    }

    private companion object {
        const val INITIAL = 1_024
        const val LOAD_FACTOR = 0.75f
    }
}
