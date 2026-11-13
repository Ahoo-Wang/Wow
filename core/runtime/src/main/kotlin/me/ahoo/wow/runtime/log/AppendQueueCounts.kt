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
import me.ahoo.wow.spi.log.AppendCallMarks
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
 * - [clock] is cumulative: where each call's time went, from its group taken to its committer free ([AppendClock]).
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

    /** Where the calls' time went. */
    val clock = AppendClock()

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

/**
 * The time of the calls an [AppendQueueCounts] saw, in microseconds summed over them, cumulative, from the instants
 * each call's [AppendCallMarks] holds:
 *
 * - [calls] clocked, the [call] from the group taken to the log's call returned, and [complete] from there to its
 *   outcomes handed to their callers and the committer free for its next group;
 * - for the [marked] calls, those whose log marked each instant it sees (PostgreSQL's): [enter], the group taken to
 *   the log's call entered; [hop], to the thread of its blocking work; [acquire], to its connection acquired; [bind],
 *   to its first statement sent (its tables found, its statement prepared and its streams' parameters bound and
 *   encoded); [statement], to its last statement's result read; [release], to its connection given back (a
 *   conflict's or a duplicate's lookups among it); and [hopBack], to the call returned to the queue.
 *
 * So for a marked call, [enter] + [hop] + [acquire] + [bind] + [statement] + [release] + [hopBack] is its [call].
 */
@InternalWowApi
class AppendClock internal constructor() {
    private val clocked = LongAdder()
    private val calling = LongAdder()
    private val completing = LongAdder()
    private val markedCalls = LongAdder()
    private val entering = LongAdder()
    private val hopping = LongAdder()
    private val acquiring = LongAdder()
    private val binding = LongAdder()
    private val running = LongAdder()
    private val releasing = LongAdder()
    private val hoppingBack = LongAdder()

    val calls: Long get() = clocked.sum()
    val call: Long get() = micros(calling)
    val complete: Long get() = micros(completing)
    val marked: Long get() = markedCalls.sum()
    val enter: Long get() = micros(entering)
    val hop: Long get() = micros(hopping)
    val acquire: Long get() = micros(acquiring)
    val bind: Long get() = micros(binding)
    val statement: Long get() = micros(running)
    val release: Long get() = micros(releasing)
    val hopBack: Long get() = micros(hoppingBack)

    /** A call clocked from now, its group taken: [ClockedCall.done] once its committer is free. */
    internal fun start(): ClockedCall = ClockedCall(this, AppendCallMarks())

    /** Adds the call [marks] holds, its committer free now. */
    internal fun clocked(marks: AppendCallMarks) {
        marks.done()
        clocked.increment()
        calling.add(marks.returned - marks.taken)
        completing.add(marks.done - marks.returned)
        if (marks.marked) {
            markedCalls.increment()
            entering.add(marks.entered - marks.taken)
            hopping.add(marks.onThread - marks.entered)
            acquiring.add(marks.acquired - marks.onThread)
            binding.add(marks.sent - marks.acquired)
            running.add(marks.read - marks.sent)
            releasing.add(marks.released - marks.read)
            hoppingBack.add(marks.returned - marks.released)
        }
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L

        fun micros(nanos: LongAdder): Long = nanos.sum() / NANOS_PER_MICRO
    }
}

/** One call an [AppendClock] clocks: its [marks], which go in the log call's context, and its clock. */
internal class ClockedCall(private val clock: AppendClock, val marks: AppendCallMarks) {
    /** The call's committer is free: its steps added to the clock. */
    fun done() = clock.clocked(marks)
}
