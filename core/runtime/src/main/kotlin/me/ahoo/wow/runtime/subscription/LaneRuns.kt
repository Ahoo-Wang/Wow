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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.spi.observation.Observation
import kotlin.coroutines.CoroutineContext

/** An entry of a run, and the [context] of its own observation, in which its handler does the entry's share. */
internal class RunEntry(val entry: SubscribedEntry, val context: CoroutineContext)

/**
 * What a run handler did with a run of one aggregate's entries: the first [done] of them are handled, durably; when
 * [failure] is not null, the next one failed with it and the rest were not tried.
 */
internal class RunHandled(val done: Int, val failure: Throwable?)

/**
 * Runs a run of one aggregate's live entries, in version order, and answers how many are done. It returns once their
 * effects are durable. It throws only a fatal error or its lane's cancellation: a failure, of one entry or of the
 * whole run, is its answer.
 */
internal typealias RunHandler = suspend (List<RunEntry>) -> RunHandled

/**
 * What [LaneRuns] needs of the [SubscriptionWorker] whose lanes it runs: its monitor sections and its paths, over the
 * worker's own item ([I]) and lane ([L]) types, which stay the worker's.
 */
internal interface LaneOps<I, L> {
    /**
     * One monitor section: [first] and the live entries queued behind it in [lane], at most [max], stopping at the
     * first that is not live; each with whether the lane skips it and whether its aggregate is parked at it, evaluated
     * in version order.
     */
    fun take(first: I, lane: L, max: Int): List<Taken<I>>

    /**
     * One monitor section: remembers the entries of [done] it does not skip when [handled], completes every one, and
     * puts [rest] back in [lane], in order, behind the operator's actions queued there; false when it puts nothing
     * back because a stop abandoned the worker, which forgot its lanes: [rest] is then dropped, not handled.
     */
    fun settle(done: List<Taken<I>>, handled: Boolean, lane: L, rest: List<Taken<I>>): Boolean

    /** Handles [item] alone, as an entry of a lane that runs each alone is. */
    suspend fun handleAlone(item: I)

    /** Parks [entry]'s aggregate from it, as a failure of its handling does. */
    suspend fun park(entry: SourcedEntry, failure: Throwable)

    /**
     * Takes from [item] the observation of its handling that a run started and did not finish ([hold]): its next
     * handling goes on in it. Null when it holds none.
     */
    fun held(item: I): Observation?

    /** Keeps [observation], of [item]'s handling, with [item], for the handling that goes on in it ([held]). */
    fun hold(item: I, observation: Observation)
}

/** An entry a lane took: its worker's [item], whether the lane [skipped] it, and whether it was [parked] there. */
internal class Taken<I>(val item: I, val entry: SourcedEntry, val skipped: Boolean, val parked: Boolean)

/**
 * How a lane runs its live entries together, for a subscription that does not run each alone: one that
 * [Subscription.coalesces] is handed only the latest entry of the batch it takes, and one made
 * [Subscription.batching] the run of them.
 */
internal class LaneRuns<I, L>(
    private val subscription: Subscription,
    private val source: EntrySource,
    private val handling: EntryHandling,
    private val hops: WaitHops,
    private val ops: LaneOps<I, L>,
) {
    /** Runs [first], live in [lane], with the live entries queued behind it. */
    suspend fun live(first: I, lane: L) {
        val runHandler = subscription.runHandler
        if (runHandler == null) coalesced(first, lane) else run(first, lane, runHandler)
    }

    /**
     * Runs [first] and the live entries queued behind it in its [lane] as one batch, for a subscription that
     * [Subscription.coalesces]: the handler gets only the latest entry it does not skip. When it fails, the aggregate
     * is parked from the earliest such entry, since none of them is reflected. Then every entry of the batch
     * completes and is signalled.
     */
    private suspend fun coalesced(first: I, lane: L) {
        val batch = ops.take(first, lane, Int.MAX_VALUE)
        val handled = batch.filterNot { it.skipped }
        val result = handled.lastOrNull()?.let { latest -> handling.handle(latest.entry) } ?: Result.success(HandledEntry.NOTHING)
        result.exceptionOrNull()?.let { ops.park(handled.first().entry, it) }
        ops.settle(batch, result.isSuccess, lane, emptyList())
        batch.forEach {
            if (it.skipped) {
                hops.signal(it.entry, Result.success(HandledEntry.NOTHING), it.parked)
            } else {
                hops.signal(it.entry, result)
            }
        }
    }

    /**
     * Runs [first] and the live entries queued behind it in its [lane], at most [Subscription.maxRun], as one run of
     * [runHandler]. The entries it answers done complete, each on its own, and are signalled; the rest go back in the
     * lane, behind any action an operator queued meanwhile, before the entry the run ended at is handled alone, retried
     * and parked as any entry is. While the partition is paused, [first] is handled alone: that is the pause's probe.
     *
     * Each entry's handling is observed once ([observed]): the entry the run ended at is handled alone in the
     * observation the run started for it, as a retry of it, and each entry after it goes on in its own when its lane
     * takes it again. An entry the lane skips is not handled: the observation a run before started for it ends as
     * skipped ([me.ahoo.wow.spi.observation.ObservedOutcome.SKIPPED]) before it completes. When a stop abandoned the
     * worker while the run went on, the entries after the one it ended at are dropped, not put back: their
     * observations end with the stop's cancellation, as those of the entries the stop found queued do.
     */
    private suspend fun run(first: I, lane: L, runHandler: RunHandler) {
        if (handling.paused) {
            ops.handleAlone(first)
            return
        }
        val taken = withoutCopies(ops.take(first, lane, subscription.maxRun))
        val handed = taken.filterNot { it.skipped }
        val entries = decoded(handed)
        val done = if (entries.isEmpty()) 0 else observed(handed, entries, runHandler)
        // The entry the run ended at: one that failed, or the first that did not decode.
        val failing = handed.getOrNull(done)
        val at = failing?.let { taken.indexOf(it) } ?: taken.size
        val settled = taken.subList(0, at)
        settled.forEach { if (it.skipped) ops.held(it.item)?.skipped() }
        val rest = taken.subList(minOf(at + 1, taken.size), taken.size)
        if (!ops.settle(settled, true, lane, rest)) {
            rest.forEach { ops.held(it.item)?.abandoned() }
        }
        settled.forEach {
            hops.signal(it.entry, Result.success(HandledEntry.NOTHING), it.skipped && it.parked)
        }
        failing?.let { ops.handleAlone(it.item) }
    }

    /**
     * [taken], an entry it does not skip marked skipped when its ordinal is at or below one taken before it: a copy a
     * source that [EntrySource.repeats] delivered again, which the lane does not know is handled until the run is.
     */
    private fun withoutCopies(taken: List<Taken<I>>): List<Taken<I>> {
        var highest = Long.MIN_VALUE
        return taken.map {
            val ordinal = source.ordinalOf(it.entry)
            when {
                it.skipped -> it
                ordinal <= highest -> Taken(it.item, it.entry, skipped = true, parked = false)
                else -> it.also { highest = ordinal }
            }
        }
    }

    /** The entries of [handed] as the run handler gets them, up to the first that does not decode. */
    // A decode that fails ends the run, whatever it fails with: the entry's handling alone meets it again.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun decoded(handed: List<Taken<I>>): List<SubscribedEntry> {
        val entries = ArrayList<SubscribedEntry>(handed.size)
        for (taken in handed) {
            try {
                entries += handling.decode(taken.entry)
            } catch (failure: Throwable) {
                if (NodeFatals.isFatal(failure)) {
                    throw failure
                }
                currentCoroutineContext().ensureActive()
                break
            }
        }
        return entries
    }

    /**
     * Runs [entries], the first of [handed], as one attempt of [runHandler], each entry observed in its own trace;
     * returns how many are done. An answer outside the contract is taken as what it can mean: at most every entry
     * done, and an entry not done as the one the run ended at, whatever its failure.
     *
     * The observation of each entry done ends with the run. That of each entry not done is kept with it ([LaneOps.hold])
     * and goes on with its next handling: the run's failure is an attempt of the entry it ended at, which is handled
     * alone next, and the entries after it were not tried. An entry a run before did not finish goes on in the
     * observation that run started ([LaneOps.held]). When the run throws (a fatal error, the lane's cancellation),
     * every observation ends with it, as an entry's handled alone does.
     */
    @Suppress("TooGenericExceptionCaught") // Told to every observation of the run, then rethrown unchanged.
    private suspend fun observed(handed: List<Taken<I>>, entries: List<SubscribedEntry>, runHandler: RunHandler): Int {
        val observations = entries.indices.map { ops.held(handed[it].item) ?: handling.observation(handed[it].entry) }
        val answer = try {
            handling.attemptRun(handed.first().entry) {
                runHandler(entries.mapIndexed { index, entry -> RunEntry(entry, observations[index].context) })
            }
        } catch (failure: Throwable) {
            observations.forEach {
                it.failed(failure)
                it.end()
            }
            throw failure
        }
        val done = answer.done.coerceIn(0, entries.size)
        observations.forEachIndexed { index, observation ->
            if (index < done) observation.end() else ops.hold(handed[index].item, observation)
        }
        if (done < entries.size) {
            logger.debug {
                "The run of ${subscription.name} ended at ${entries[done].ref}@${entries[done].version}, " +
                    "$done of ${entries.size} done (${answer.failure ?: "answered without a failure"}): it is " +
                    "handled alone next."
            }
        }
        return done
    }
}

/** The worker's: an operator filters a subscription's lines by it, as by those of an entry handled alone. */
private val logger = KotlinLogging.logger(SubscriptionWorker::class.java.name)
