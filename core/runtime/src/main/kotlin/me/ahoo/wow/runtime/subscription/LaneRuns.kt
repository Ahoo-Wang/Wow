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

package me.ahoo.wow.runtime.subscription

/**
 * What [LaneRuns] needs of the [SubscriptionWorker] whose lanes it runs: its monitor sections and its paths, over the
 * worker's own item ([I]) and lane ([L]) types, which stay the worker's.
 */
internal interface LaneOps<I, L> {
    /**
     * One monitor section: [first] and the live entries queued behind it in [lane], stopping at the first that is not
     * live; each with whether the lane skips it and whether its aggregate is parked at it, evaluated in version order.
     */
    fun take(first: I, lane: L): List<Taken<I>>

    /** One monitor section: remembers the entries of [done] it does not skip when [handled], and completes every one. */
    fun settle(done: List<Taken<I>>, handled: Boolean)

    /** Parks [entry]'s aggregate from it, as a failure of its handling does. */
    suspend fun park(entry: SourcedEntry, failure: Throwable)
}

/** An entry a lane took: its worker's [item], whether the lane [skipped] it, and whether it was [parked] there. */
internal class Taken<I>(val item: I, val entry: SourcedEntry, val skipped: Boolean, val parked: Boolean)

/**
 * How a lane runs its live entries together, for a subscription that does not run each alone: one that
 * [Subscription.coalesces] is handed only the latest entry of the batch it takes.
 */
internal class LaneRuns<I, L>(
    private val handling: EntryHandling,
    private val hops: WaitHops,
    private val ops: LaneOps<I, L>,
) {
    /** Runs [first], live in [lane], with the live entries queued behind it. */
    suspend fun live(first: I, lane: L) = coalesced(first, lane)

    /**
     * Runs [first] and the live entries queued behind it in its [lane] as one batch, for a subscription that
     * [Subscription.coalesces]: the handler gets only the latest entry it does not skip. When it fails, the aggregate
     * is parked from the earliest such entry, since none of them is reflected. Then every entry of the batch
     * completes and is signalled.
     */
    private suspend fun coalesced(first: I, lane: L) {
        val batch = ops.take(first, lane)
        val handled = batch.filterNot { it.skipped }
        val result = handled.lastOrNull()?.let { latest -> handling.handle(latest.entry) } ?: Result.success(HandledEntry.NOTHING)
        result.exceptionOrNull()?.let { ops.park(handled.first().entry, it) }
        ops.settle(batch, result.isSuccess)
        batch.forEach {
            if (it.skipped) {
                hops.signal(it.entry, Result.success(HandledEntry.NOTHING), it.parked)
            } else {
                hops.signal(it.entry, result)
            }
        }
    }
}
