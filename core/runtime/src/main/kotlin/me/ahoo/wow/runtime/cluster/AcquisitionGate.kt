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

package me.ahoo.wow.runtime.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The acquisitions of one node on one lease store, held back while the store fails (#6675): a store that cannot answer
 * (a replica set without a majority, say) is not asked for one acquisition per missing partition and lease group every
 * reconcile, each holding a connection until it times out, which kept a degraded store at its limits.
 *
 * Every lease group of the node on the store shares it, through the node's [ServiceMembership] there: a failure is a
 * fact about the store, met by whichever call met it first, and one call that the store answers speaks for all of them.
 *
 * It is closed while the store answers, and has two open states, in both of which no acquisition is asked for a window
 * of [base] doubled per consecutive failure, at most [cap], drawn at random below that bound (full jitter, so the nodes
 * of a service do not probe in step); once a window passed, one acquisition at a time is asked, a probe ([admit]), and
 * a probe that fails widens the window again:
 *
 * - **Suspected** ([State.SUSPECTED]): a renewal or a heartbeat failed ([failed]) and no acquisition has been wanted
 *   since. The next call the store answers clears it: a blip on a node that misses no partition would otherwise stay
 *   open for good, since no probe would ever come.
 * - **Holding** ([State.HOLDING]): an acquisition failed, one was held back, or leases this node held current went
 *   unrenewed past the margin (`leaseTtl − renewInterval`), which is what drops partitions in the first place. Only the
 *   probe's answer closes it, whether the probe got the lease or another node holds it. Any other call the store
 *   answers meanwhile (a heartbeat, a renewal, an acquisition asked before the gate opened) ends the window, so the
 *   probe goes at the next reconcile, and closes nothing: a slow store that answers heartbeats while its acquisitions
 *   time out would otherwise be let into a wave of every missing partition after each heartbeat, its windows never
 *   growing.
 *
 * A healthy takeover, which retries partitions another node still holds every round, never opens it: the store answers
 * each of those acquisitions.
 *
 * It says so in the log once when it opens, with what opened it, in the same words in either state, and once when it
 * closes or is cleared. Renewals and heartbeats are never held back: one call per round each, they keep what the node
 * holds and tell when the store answers again.
 */
internal class AcquisitionGate(
    private val node: String,
    private val service: String,
    private val base: Duration,
    private val cap: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    /** How an acquisition was let through: freely, the store answering, or as the one probe of an open gate. */
    enum class Admission { FREE, PROBE }

    /** Whether the gate holds acquisitions back, and what lets them go again. */
    enum class State {
        /** The store answers: every acquisition is let through. */
        CLOSED,

        /** A renewal or a heartbeat failed, and no acquisition was wanted since: the next answered call clears it. */
        SUSPECTED,

        /** An acquisition failed, was held back, or leases lapsed: only the probe's answer closes it. */
        HOLDING,
    }

    /** Whether the gate holds acquisitions back, and how it lets them go. */
    @get:Synchronized
    var state = State.CLOSED
        private set

    private var failures = 0
    private var openedAt: TimeMark? = null
    private var until: TimeMark? = null
    private var probing = false

    /** Whether the gate holds acquisitions back: the store failed and has not answered since. */
    val open: Boolean get() = state != State.CLOSED

    /**
     * How an acquisition may be asked now; null when it may not, which makes the gate hold. A [Admission.PROBE] must be
     * [recorded], which frees it for the next.
     */
    @Synchronized
    fun admit(): Admission? {
        if (state == State.CLOSED) {
            return Admission.FREE
        }
        if (probing || until?.hasNotPassedNow() == true) {
            state = State.HOLDING
            return null
        }
        probing = true
        return Admission.PROBE
    }

    /**
     * Runs [call], [what] on the store, and records its outcome: answered, it closes or clears the gate, or ends its
     * window; failed, it opens or widens it. An acquisition passes its [admission]; a renewal or a heartbeat none. A
     * probe's admission is freed whatever happens, a cancellation included.
     */
    suspend fun <T> recorded(what: String, admission: Admission? = null, call: suspend () -> T): T {
        try {
            return call().also { answered(what, admission) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            failed(what, failure, acquisition = admission != null)
            throw failure
        } finally {
            if (admission == Admission.PROBE) {
                freeProbe()
            }
        }
    }

    /**
     * Opens or widens the gate: [what] failed, by [failure] when the store threw. A renewal or a heartbeat suspects the
     * store; an [acquisition] that failed, or leases that lapsed, make it hold.
     */
    @Synchronized
    fun failed(what: String, failure: Throwable? = null, acquisition: Boolean = false) {
        val opening = state == State.CLOSED
        if (acquisition) {
            state = State.HOLDING
        } else if (opening) {
            state = State.SUSPECTED
        }
        failures++
        val exponent = (failures - 1).coerceAtMost(MAX_EXPONENT)
        val ceiling = (base * (1 shl exponent)).coerceAtMost(cap)
        val window = ceiling * random.nextDouble()
        until = timeSource.markNow() + window
        if (opening) {
            openedAt = timeSource.markNow()
            logger.warn {
                "$node holds back its lease acquisitions in $service on its lease store: $what failed" +
                    failure.describe() + ". It asks one again within $ceiling, then one at a time until the store " +
                    "answers."
            }
        } else {
            logger.debug { "$node still holds back its lease acquisitions in $service: $what failed; next within $window." }
        }
    }

    @Synchronized
    private fun answered(what: String, admission: Admission?) {
        when {
            state == State.CLOSED -> return
            state == State.HOLDING && admission != Admission.PROBE -> {
                if (until != null) {
                    until = null
                    logger.debug { "$node probes its lease store in $service at its next reconcile: $what succeeded." }
                }
                return
            }
        }
        val how = if (admission == Admission.PROBE) "its probe, $what, was answered" else "$what succeeded"
        logger.info {
            "$node acquires leases in $service again: $how, " +
                "${openedAt?.elapsedNow()?.toString(DurationUnit.SECONDS, 1)} after its lease store first failed " +
                "($failures failed calls)."
        }
        state = State.CLOSED
        failures = 0
        until = null
        openedAt = null
    }

    @Synchronized
    private fun freeProbe() {
        probing = false
    }

    private fun Throwable?.describe(): String = this?.let { " (${it.javaClass.simpleName}: ${it.message})" }.orEmpty()

    private companion object {
        const val MAX_EXPONENT = 16
        val logger = KotlinLogging.logger(PartitionOwnership::class.java.name)
    }
}
