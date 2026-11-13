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

package me.ahoo.wow.runtime.log

import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * What a [PartitionAppendQueue]'s calls to its log carried, and the lanes' groups waiting for a committer, from the
 * first [PartitionAppendQueue.counts] on: a diagnostic, which the queue keeps only once something reads it.
 *
 * - [calls], [streams] and [full] are cumulative: the calls to the log (one per merged call when the queue merges, one
 *   per lane's batch otherwise), the streams they carried, and the calls at their cap
 *   ([AppendQueueOptions.mergeMax] merged, [AppendQueueOptions.maxBatch] otherwise). Streams over calls is the mean
 *   call, and a call at its cap carried all it could.
 * - [handedGroups] and [handedStreams] are now: the lanes' groups handed to the committers and not yet taken into a
 *   call, and their streams. A lane hands one group at a time and waits for it, so the groups are at most the log's
 *   partitions, and the streams at most the node's in-flight bound, which every append's command holds.
 *
 * Each is an atomic add, per call or per group, never per append; nothing waits on a lock.
 */
@InternalWowApi
class AppendQueueCounts internal constructor() {
    private val called = LongAdder()
    private val carried = LongAdder()
    private val atCap = LongAdder()
    private val groups = AtomicInteger()
    private val groupStreams = AtomicInteger()

    val calls: Long get() = called.sum()
    val streams: Long get() = carried.sum()
    val full: Long get() = atCap.sum()
    val handedGroups: Int get() = groups.get()
    val handedStreams: Int get() = groupStreams.get()

    internal fun called(streams: Int, cap: Int) {
        called.increment()
        carried.add(streams.toLong())
        if (streams >= cap) {
            atCap.increment()
        }
    }

    internal fun handed(streams: Int) {
        groups.incrementAndGet()
        groupStreams.addAndGet(streams)
    }

    internal fun taken(streams: Int) {
        groups.decrementAndGet()
        groupStreams.addAndGet(-streams)
    }
}
