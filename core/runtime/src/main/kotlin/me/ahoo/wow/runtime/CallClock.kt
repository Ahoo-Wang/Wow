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

package me.ahoo.wow.runtime

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.store.StoreCallMarks
import java.util.concurrent.atomic.LongAdder

/**
 * The time of a caller's calls to a store (the append queue's to its log, the snapshot writes' to their store), in
 * microseconds summed over them, cumulative, from the instants each call's [StoreCallMarks] holds:
 *
 * - [calls] clocked, the [call] from its batch taken to the store's call returned, and [complete] from there to its
 *   outcomes handed to their callers and the caller's committer free for its next batch;
 * - for the [marked] calls, those whose store marked each instant it sees: [enter], the batch taken to the store's
 *   call entered; [hop], to the thread of its blocking work; [acquire], to its connection acquired; [bind], to its
 *   first command or statement sent (what the store builds of the batch: tables found, statements prepared,
 *   parameters bound, documents encoded); [statement], to its last result read; [release], to its connection given
 *   back (a transaction's commit, a conflict's or a duplicate's lookups among it); and [hopBack], to the call
 *   returned to the caller. So for a marked call, [enter] + [hop] + [acquire] + [bind] + [statement] + [release] +
 *   [hopBack] is its [call];
 * - for the [commanded] calls, those whose driver reported its commands' own time: [command], their sum, from each
 *   command's message sent to its reply. Of a marked call's [statement], what [command] leaves is the driver's work
 *   around its commands (encoding a command, checking a connection out, decoding its reply), and the store's own
 *   work between them;
 * - of [bind], for every call whose store tells: [encode], the time spent encoding the batch's records, and [lookup],
 *   the time of the [lookups] the store made while building it (MongoDB's log: each stream's document, and a read of
 *   an aggregate's last sequence it did not remember). What they leave of [bind] is the rest of the building (on
 *   MongoDB's log, sequencing: the partitions' blocks and the streams' sequences).
 */
@InternalWowApi
class CallClock internal constructor() {
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
    private val commandedCalls = LongAdder()
    private val commanding = LongAdder()
    private val encoding = LongAdder()
    private val lookingUp = LongAdder()
    private val lookedUp = LongAdder()

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
    val commanded: Long get() = commandedCalls.sum()
    val command: Long get() = micros(commanding)
    val encode: Long get() = micros(encoding)
    val lookup: Long get() = micros(lookingUp)
    val lookups: Long get() = lookedUp.sum()

    /** A call clocked from now, its batch taken: [ClockedCall.done] once the caller's committer is free. */
    internal fun start(): ClockedCall = ClockedCall(this, StoreCallMarks())

    /** Adds the call [marks] holds, the caller's committer free now. */
    internal fun clocked(marks: StoreCallMarks) {
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
        if (marks.commands > 0) {
            commandedCalls.increment()
            commanding.add(marks.command)
        }
        encoding.add(marks.encode)
        lookingUp.add(marks.lookup)
        lookedUp.add(marks.lookups.toLong())
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L

        fun micros(nanos: LongAdder): Long = nanos.sum() / NANOS_PER_MICRO
    }
}

/** One call a [CallClock] clocks: its [marks], which go in the store call's context, and its clock. */
internal class ClockedCall(private val clock: CallClock, val marks: StoreCallMarks) {
    /** The caller's committer is free: the call's steps added to the clock. */
    fun done() = clock.clocked(marks)
}
