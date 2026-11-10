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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.ahoo.wow.api.aggregate.AggregateRef

/**
 * The aggregates a [SubscriptionWorker] holds back in its partition, and the operator's action running on each:
 *
 * - **parked** from an ordinal ([EntrySource.ordinalOf]): its entries from there on are skipped until the parking is
 *   resolved;
 * - **retrying**: a retry handles the parked entries in the aggregate's lane, and its new entries queue behind it;
 * - **skipping**: a skip resolves the ledger entry; the entries the parking skips stay skipped meanwhile, and a retry
 *   asked for meanwhile starts nothing, so what the skip discards is never handled.
 *
 * A retry and a skip of one aggregate exclude each other: whichever claims it first runs, the other is refused. Once a
 * retry resolves the aggregate, a later copy of an entry it handled ([retried]) is skipped.
 *
 * Not thread-safe: the worker calls it under its monitor, and nothing in it suspends.
 */
internal class ParkStates(parked: Map<AggregateRef, Long>) {
    private val states = HashMap<AggregateRef, State>(parked.mapValues { State.Parked(it.value) })

    /** Per aggregate, the ordinal of the last entry a retry handled: a later copy of one of those is skipped. */
    private val retriedThrough = HashMap<AggregateRef, Long>()

    /** Whether [ref]'s entry at [ordinal] is held back: its aggregate is parked at or before it. */
    fun holds(ref: AggregateRef, ordinal: Long): Boolean {
        val from = states[ref]?.fromOrdinal ?: return false
        return ordinal >= from
    }

    /** Whether [ref]'s entry at [ordinal], arriving, is a copy of what a retry handled before it resolved the aggregate. */
    fun retriedCopy(ref: AggregateRef, ordinal: Long): Boolean {
        val retried = retriedThrough[ref] ?: return false
        return states[ref] == null && ordinal <= retried
    }

    /**
     * Whether [ref]'s entry at [ordinal], in its lane, is a copy of what a retry handled. Entries arrive in a lane in
     * ordinal order, so once a later one does, every entry after it is newer than what the retry read.
     */
    fun retriedCopyInLane(ref: AggregateRef, ordinal: Long): Boolean {
        val retried = retriedThrough[ref] ?: return false
        if (ordinal <= retried) {
            return true
        }
        retriedThrough.remove(ref)
        return false
    }

    /**
     * Parks [ref] from [ordinal], or from the earlier ordinal it is parked from. A retry or a skip running settles the
     * aggregate's state when it ends, so it is left to it: a skip resolves in the aggregate's lane after the failure
     * that parks it here, so it resolves that parking too.
     */
    fun park(ref: AggregateRef, ordinal: Long) {
        when (val state = states[ref]) {
            is State.Retrying, is State.Skipping -> Unit
            is State.Parked -> states[ref] = State.Parked(minOf(state.fromOrdinal, ordinal))
            null -> states[ref] = State.Parked(ordinal)
        }
    }

    /**
     * The result of the operator's action running on [ref] that a retry asked for now answers with: the retry running,
     * or false while a skip runs, since the skip discards what is parked. Null when neither runs.
     */
    fun running(ref: AggregateRef): Deferred<Boolean>? = when (val state = states[ref]) {
        is State.Retrying -> state.result
        is State.Skipping -> SKIPPING
        else -> null
    }

    /** Marks [ref] retrying, with the retry's [result]: from now on its new entries queue behind the retry. */
    fun retrying(ref: AggregateRef, result: CompletableDeferred<Boolean>) {
        states[ref] = State.Retrying(result)
    }

    /** [ref]'s retry that answers with [result] failed or stopped: the aggregate is parked again from [ordinal]. */
    fun parkedAgain(ref: AggregateRef, result: CompletableDeferred<Boolean>, ordinal: Long) {
        if ((states[ref] as? State.Retrying)?.result === result) {
            states[ref] = State.Parked(ordinal)
        }
    }

    /** [ref]'s retry handled its entries up to [last] (null for none): later copies of those are skipped. */
    fun retried(ref: AggregateRef, last: Long?) {
        if (last != null) {
            retriedThrough[ref] = last
        }
    }

    /** [ref] is no longer parked: the ledger has no entry of it, or its retry resolved it. */
    fun resolved(ref: AggregateRef) {
        states.remove(ref)
    }

    /**
     * Claims [ref] for a skip, in the same step as checking that no retry or other skip of it runs; false when one does.
     * [skipped] ends the skip.
     */
    fun skipping(ref: AggregateRef): Boolean = when (val state = states[ref]) {
        is State.Retrying, is State.Skipping -> false
        else -> {
            states[ref] = State.Skipping(state as State.Parked?)
            true
        }
    }

    /**
     * Ends the skip of [ref] ([skipping]): the aggregate is no longer parked when its ledger entry was [resolved], and
     * is parked as it was when the ledger write failed. Only one skip of it runs at a time, so the skip is this one.
     */
    fun skipped(ref: AggregateRef, resolved: Boolean) {
        val parked = (states[ref] as? State.Skipping ?: return).parked
        if (resolved || parked == null) states.remove(ref) else states[ref] = parked
    }

    private sealed interface State {
        /** The ordinal the aggregate's entries are skipped from, null when none is. */
        val fromOrdinal: Long?

        class Parked(override val fromOrdinal: Long) : State

        class Retrying(val result: CompletableDeferred<Boolean>) : State {
            override val fromOrdinal: Long? get() = null
        }

        /** A skip resolves the ledger entry: the entries [parked] skips stay skipped until it is resolved. */
        class Skipping(val parked: Parked?) : State {
            override val fromOrdinal: Long? get() = parked?.fromOrdinal
        }
    }

    private companion object {
        /** A retry's answer while a skip runs: nothing is handled. Completed when made, and by nobody after. */
        val SKIPPING: Deferred<Boolean> = CompletableDeferred(false)
    }
}
