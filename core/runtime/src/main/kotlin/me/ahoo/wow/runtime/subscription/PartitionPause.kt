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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClass
import java.time.Clock

/**
 * Whether one subscription's partition is paused on a systemic failure, and the gate its lanes pass
 * before each attempt of the handler.
 *
 * The partition pauses when an attempt fails with a [FailureClass.SYSTEMIC] failure, or when
 * [SystemicFailureOptions.failingAggregates] different aggregates failed with a [FailureClass.RECOVERABLE] one within
 * [SystemicFailureOptions.window] and no handling succeeded since: every aggregate would fail the same way, so none is
 * charged for it. While paused, the lanes take turns, first come first served, to run one attempt at a time (the
 * probe), each after the probe backoff ([SystemicFailureOptions.backoff], growing with each failed probe); the others
 * wait. Any failure meanwhile is the partition's: it spends no attempt of the aggregate's budget and parks nothing,
 * an unrecoverable one included, which parks once the partition runs again if it fails again. The first handling that
 * succeeds, or meets a rejection (the handler reached a decision), resumes the partition.
 *
 * A pause and a resume are logged once each and told to [observer]; [state] is what the health model reads
 * ([SubscriptionPauses]).
 */
internal class PartitionPause(
    private val group: String,
    private val subscription: String,
    private val partition: Int,
    private val options: SystemicFailureOptions,
    private val clock: Clock,
    private val observer: Observer = Observer.NONE,
) {
    /** Whether a caller holds the probe while paused; guarded by the monitor. */
    private var probeHeld = false

    /** The callers waiting for the probe, first come first served, so the lanes take turns; guarded by the monitor. */
    private val probeTurns = ArrayDeque<CompletableDeferred<Unit>>()

    /** Guarded by the monitor. */
    private var pause: Pause? = null

    /** Since the last success, when each aggregate that failed recoverably last failed. Guarded by the monitor. */
    private val failing = LinkedHashMap<AggregateRef, Long>()

    /** Whether the partition is paused now. */
    val isPaused: Boolean get() = synchronized(this) { pause != null }

    /** The pause now, null when the partition runs. */
    val state: PausedPartition?
        get() = synchronized(this) {
            pause?.let { PausedPartition(group, subscription, partition, it.since, it.reason, it.failure) }
        }

    /**
     * Passes the gate before an attempt: at once while the partition runs (false); while it is paused, once it is
     * this caller's turn to probe and the probe backoff has passed (true: the caller holds the probe and [release]s
     * it after the attempt), or as soon as the partition resumes meanwhile (false); null once [stop] completes first,
     * holding nothing: the attempt is given up.
     */
    suspend fun admit(stop: Deferred<Unit>): Boolean? {
        if (!isPaused) {
            return false
        }
        if (!takeProbe(stop)) {
            return null
        }
        var probing = false
        try {
            val current = synchronized(this) { pause } ?: return false
            // Null once the backoff passed: the caller probes. True once the stop came first, false once resumed.
            val stopped = withTimeoutOrNull(options.backoff(current.probes + 1)) {
                select<Boolean> {
                    stop.onAwait { true }
                    current.resumed.onAwait { false }
                }
            }
            probing = stopped == null
            return if (stopped == true) null else probing
        } finally {
            if (!probing) {
                giveProbe()
            }
        }
    }

    /** Waits for the turn to hold the probe: true once taken; false once [stop] comes first, taking nothing. */
    private suspend fun takeProbe(stop: Deferred<Unit>): Boolean {
        val turn = synchronized(this) {
            if (!probeHeld) {
                probeHeld = true
                return true
            }
            CompletableDeferred<Unit>().also { probeTurns.addLast(it) }
        }
        val taken = try {
            select<Boolean> {
                stop.onAwait { false }
                turn.onAwait { true }
            }
        } catch (cancelled: CancellationException) {
            leaveProbe(turn)
            throw cancelled
        }
        if (!taken) {
            leaveProbe(turn)
        }
        return taken
    }

    /** Leaves the wait for [turn]: the probe handed over as it left is passed on. */
    private fun leaveProbe(turn: CompletableDeferred<Unit>) {
        val handed = synchronized(this) { !probeTurns.remove(turn) }
        if (handed) {
            giveProbe()
        }
    }

    /** Gives the probe to the longest waiter, or frees it. */
    private fun giveProbe() {
        val next = synchronized(this) { probeTurns.removeFirstOrNull().also { if (it == null) probeHeld = false } }
        next?.complete(Unit)
    }

    /** Releases the probe [admit] gave, when it gave it. */
    fun release(probing: Boolean) {
        if (probing) {
            giveProbe()
        }
    }

    /** An attempt for [ref] succeeded: the partition runs, and the recoverable failures counted so far are forgotten. */
    fun succeeded(ref: AggregateRef) {
        val resumed = synchronized(this) {
            failing.clear()
            pause.also { pause = null }
        } ?: return
        val pausedMillis = clock.millis() - resumed.since
        logger.info {
            "Subscription $subscription resumes partition $partition of group $group, paused ${pausedMillis}ms " +
                "(${resumed.probes} failed probes), as the handling of $ref succeeded."
        }
        resumed.resumed.complete(Unit)
        observer.observed(ObservedEvent.PartitionResumed(group, subscription, partition, pausedMillis))
    }

    /**
     * An attempt for [ref] failed with [failure], of class [kind], as the probe when [probing]: whether the failure is
     * the partition's, so the caller tries again after [admit] and spends nothing. It is when the partition is paused
     * already, and when [failure] pauses it: a systemic one, or the recoverable one of the last of
     * [SystemicFailureOptions.failingAggregates] different aggregates within the window. Otherwise it is the
     * aggregate's own: retried within its budget, or, unrecoverable, parked.
     */
    fun failed(ref: AggregateRef, kind: FailureClass, failure: Throwable, probing: Boolean): Boolean {
        val reason = synchronized(this) {
            pause?.let {
                if (probing) {
                    it.probes++
                }
                logger.debug(failure) {
                    "Subscription $subscription keeps partition $partition paused: $ref failed (${it.probes} failed " +
                        "probes)."
                }
                return true
            }
            when (kind) {
                // Backpressure is the downstream's load, never a reason to pause.
                FailureClass.UNRECOVERABLE, FailureClass.BACKPRESSURE -> return false
                FailureClass.SYSTEMIC -> "a systemic failure: ${describe(failure)}"
                FailureClass.RECOVERABLE -> {
                    if (!countFailing(ref)) {
                        return false
                    }
                    "${failing.size} aggregates failed within ${options.window}, the last ($ref) with " +
                        describe(failure)
                }
            }.also {
                pause = Pause(clock.millis(), it, failure.javaClass.name)
                failing.clear()
            }
        }
        logger.warn(failure) {
            "Subscription $subscription pauses partition $partition of group $group on $reason; it retries until a " +
                "handling succeeds, parking nothing."
        }
        observer.observed(ObservedEvent.PartitionPaused(group, subscription, partition, reason))
        return true
    }

    /** Waits until the partition runs: what writes the failure ledger, which may sit on what failed, waits for it. */
    suspend fun awaitRunning() {
        while (true) {
            val current = synchronized(this) { pause } ?: return
            current.resumed.await()
        }
    }

    /** Called under the monitor: counts [ref]'s failure; whether the aggregates failing within the window are enough. */
    private fun countFailing(ref: AggregateRef): Boolean {
        if (options.failingAggregates == 0) {
            return false
        }
        val now = clock.millis()
        val since = now - options.window.inWholeMilliseconds
        failing.remove(ref)
        failing[ref] = now
        failing.values.removeIf { it < since }
        return failing.size >= options.failingAggregates
    }

    private fun describe(failure: Throwable): String = "${failure.javaClass.name}: ${failure.message}"

    private class Pause(val since: Long, val reason: String, val failure: String) {
        val resumed = CompletableDeferred<Unit>()
        var probes = 0
    }
}

private val logger = KotlinLogging.logger {}
