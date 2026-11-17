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

package me.ahoo.wow.runtime.snapshot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.CallClock
import me.ahoo.wow.runtime.ClockedCall
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * What a snapshot subscription's writes ([SnapshotWrites]) did, from the first [SnapshotSubscription.writeCounts] on:
 * a diagnostic, which the writes keep only once something reads it.
 *
 * - [saves] and [snapshots] are cumulative: the saves the leaders took into their writes, and the snapshots those
 *   wrote (one an aggregate a write, the highest version of those taken). Over the [clock]'s calls, they are a write's
 *   mean batch and its mean snapshots.
 * - [writing] is now: the leaders in a store's call, at most the store's writes in flight; [allowed] is now: how many
 *   may lead, the store's writes in flight, or its catch-up count while the writes are behind.
 * - [clock] is cumulative: where each write's time went, from its batch taken to its saves ended ([CallClock]).
 * - [loads], [loadCalls] and [tails] are cumulative, the reads of an entry without a view: the snapshots loaded, the
 *   store's calls that loaded them, and the log's reads of a rebuild (its tail after a base, or its replay) or of an
 *   aggregate's first stream. With [snapshots], they are the store's round trips a snapshot written cost.
 *
 * Each is an atomic add, per write, never per save, and per read of an entry without a view; nothing waits on a lock.
 */
@InternalWowApi
class SnapshotWriteCounts internal constructor() {
    private val taken = LongAdder()
    private val written = LongAdder()
    private val inCall = AtomicInteger()
    private val limit = AtomicInteger()
    private val loaded = LongAdder()
    private val loadCalled = LongAdder()
    private val logReads = LongAdder()

    val saves: Long get() = taken.sum()
    val snapshots: Long get() = written.sum()
    val writing: Int get() = inCall.get()
    val allowed: Int get() = limit.get()
    val loads: Long get() = loaded.sum()
    val loadCalls: Long get() = loadCalled.sum()
    val tails: Long get() = logReads.sum()

    /** Where the writes' time went. */
    val clock = CallClock()

    /** A write of [saves] taken, [snapshots] of them written, clocked from now: [ended] once it is done. */
    internal fun start(saves: Int, snapshots: Int): ClockedCall {
        taken.add(saves.toLong())
        written.add(snapshots.toLong())
        inCall.incrementAndGet()
        return clock.start()
    }

    /** How many may lead now. */
    internal fun limited(leaders: Int) {
        limit.set(leaders)
    }

    /** One call of the store loaded [snapshots]. */
    internal fun loaded(snapshots: Int) {
        loaded.add(snapshots.toLong())
        loadCalled.increment()
    }

    /** The log was read once. */
    internal fun logRead() {
        logReads.increment()
    }

    /** A write [start]ed ended, clocked or not. */
    internal fun ended() {
        inCall.decrementAndGet()
    }
}
