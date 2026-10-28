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

package me.ahoo.wow.benchmark.load

import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil

/**
 * The open-loop driver: operations are scheduled at a fixed arrival rate, the `i`th at
 * `start + i / rate`, whether or not the earlier ones have completed, so a slow server cannot slow the arrivals down
 * (coordinated omission). Each operation's latency is measured from its scheduled time, not from when it was sent,
 * so a late send counts against it too.
 *
 * The first [warmUpSeconds] are not recorded; the next [measureSeconds] are, into [Measured]. An operation belongs to
 * the phase and the second its scheduled time falls in. At most [inFlight] operations are outstanding ([InFlightCap]):
 * one scheduled beyond that is not sent and is counted as `generator-overload`, which marks the run as not keeping up.
 * Operation `i` runs the scenario [mix] picks for it; a mix also records each scenario's latencies and errors apart.
 * The operations go round robin to the [targets] that are up ([Targets]): a node that refuses a connection, such as one
 * a hand-over run stopped, is left out for a moment, and an operation it refused runs on the next node.
 */
class OpenLoop(
    private val client: Client,
    private val mix: Mix,
    private val keys: KeyChooser,
    targets: List<URI>,
    private val rate: Double,
    private val warmUpSeconds: Int,
    private val measureSeconds: Int,
    private val inFlight: InFlightCap = InFlightCap.of(rate, TransferApi.REQUEST_TIMEOUT, targets.size),
    private val onPhase: (String) -> Unit = {},
) {
    init {
        require(rate > 0) { "The rate is positive." }
    }

    private val nodes = Targets(targets)

    /** What the measured phase recorded. */
    class Measured(seconds: Int) {
        val latency = Histogram()
        val errorLatency = Histogram()
        val perSecond = AtomicReferenceArray<Histogram>(seconds)
        val errors = ConcurrentHashMap<String, LongAdder>()
        val scheduled = AtomicLong()
        val maxSendLagNanos = AtomicLong()

        /** The operations of the run a refused connection moved to another node ([Targets.rerouted]). */
        var rerouted = 0L

        /** Each scenario's latencies and failures, apart (a mix's). */
        val byScenario = ConcurrentHashMap<Scenario, Histogram>()
        val failedByScenario = ConcurrentHashMap<Scenario, LongAdder>()

        fun second(index: Int): Histogram {
            perSecond.get(index)?.let { return it }
            perSecond.compareAndSet(index, null, Histogram())
            return perSecond.get(index)
        }

        fun error(kind: String) = errors.computeIfAbsent(kind) { LongAdder() }.increment()

        /**
         * Records the operation scheduled at [scheduled] in [second] of the measured phase, which completed with
         * [error] (null when it reached its completion point) or failed with [failure].
         */
        fun complete(second: Int, scheduled: Long, error: String?, failure: Throwable?, scenario: Scenario? = null) {
            val latencyMicros = (System.nanoTime() - scheduled) / NANOS_PER_MICRO
            val kind = error ?: failure?.let { "exception-${rootCause(it).javaClass.simpleName}" }
            if (kind == null) {
                latency.record(latencyMicros)
                second(second).record(latencyMicros)
                scenario?.let { byScenario.computeIfAbsent(it) { Histogram() }.record(latencyMicros) }
            } else {
                errorLatency.record(latencyMicros)
                // A mix's error kinds say which scenario failed: `E4-list:read-http-503`.
                error(scenario?.let { "${it.label}:$kind" } ?: kind)
                scenario?.let { failedByScenario.computeIfAbsent(it) { LongAdder() }.increment() }
            }
        }
    }

    /** Runs the warm-up and the measured phase, and waits for the operations still in flight. */
    fun run(): Measured {
        val measured = Measured(measureSeconds)
        val interval = NANOS_PER_SECOND / rate
        val total = ((warmUpSeconds + measureSeconds) * rate).toLong()
        val warmUpEnd = warmUpSeconds * NANOS_PER_SECOND
        val outgoing = AtomicInteger()
        val done = CountDownLatch(1)
        val outstanding = AtomicLong(1)
        val start = System.nanoTime() + START_DELAY_NANOS
        onPhase("warm-up")
        var measuring = false
        for (i in 0 until total) {
            val offset = (i * interval).toLong()
            val scheduled = start + offset
            waitUntil(scheduled)
            val inMeasure = offset >= warmUpEnd
            if (inMeasure && !measuring) {
                measuring = true
                onPhase("measure")
            }
            if (inMeasure) {
                measured.scheduled.incrementAndGet()
                measured.maxSendLagNanos.accumulateAndGet(System.nanoTime() - scheduled, ::maxOf)
            }
            val scenario = mix.pick(i)
            val account = keys.next()
            val other = if (scenario.transfers) keys.nextOtherThan(account) else account
            if (outgoing.incrementAndGet() > inFlight.cap) {
                outgoing.decrementAndGet()
                if (inMeasure) measured.error(OVERLOAD)
                continue
            }
            outstanding.incrementAndGet()
            val second = ((offset - warmUpEnd) / NANOS_PER_SECOND).toInt()
            nodes.run(scenario, client, i, account, other).handle { error, failure ->
                if (inMeasure) measured.complete(second, scheduled, error, failure, scenario.takeIf { mix.mixed })
                outgoing.decrementAndGet()
                if (outstanding.decrementAndGet() == 0L) done.countDown()
            }
        }
        onPhase("drain")
        if (outstanding.decrementAndGet() == 0L) done.countDown()
        done.await(TransferApi.REQUEST_TIMEOUT.toSeconds() + DRAIN_MARGIN_SECONDS, TimeUnit.SECONDS)
        measured.rerouted = nodes.rerouted.get()
        return measured
    }

    private fun waitUntil(deadline: Long) {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            if (remaining > SPIN_NANOS) LockSupport.parkNanos(remaining - SPIN_NANOS) else Thread.onSpinWait()
        }
    }

    companion object {
        const val OVERLOAD = "generator-overload"
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MICRO = 1_000L
        private const val START_DELAY_NANOS = 100_000_000L
        private const val SPIN_NANOS = 100_000L
        private const val DRAIN_MARGIN_SECONDS = 5L

        fun rootCause(failure: Throwable): Throwable {
            var cause = failure
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            return cause
        }
    }
}

/**
 * How many operations of an open-loop run may be outstanding: as many as its rate sends within one request's timeout
 * (one sent beyond that could only time out), at most [InFlightCap.PER_TARGET] per target. Each one outstanding holds
 * a connection, so the ceiling keeps an overloaded server's answer `generator-overload` or 429, never a storm of
 * connections it refuses. [boundBy] names the bound that applied, which the run's result records.
 */
class InFlightCap private constructor(val cap: Int, val boundBy: String) {
    override fun toString(): String = "InFlightCap(cap=$cap, boundBy=$boundBy)"

    companion object {
        /** The most operations outstanding per target: a few thousand connections a node accepts at once. */
        const val PER_TARGET: Int = 2_000
        const val BY_RATE: String = "rate x request timeout"
        const val BY_CEILING: String = "per-target ceiling"

        /** The cap of a run at [rate] a second, of requests timing out after [timeout], over [targets] targets. */
        fun of(rate: Double, timeout: Duration, targets: Int, perTarget: Int = PER_TARGET): InFlightCap {
            require(rate > 0) { "The rate is positive." }
            require(targets >= 1) { "A run has a target." }
            require(perTarget >= 1) { "A target takes at least one operation at a time." }
            val derived = ceil(rate * timeout.toNanos() / OpenLoop.NANOS_PER_SECOND).toLong().coerceAtLeast(1)
            val ceiling = perTarget.toLong() * targets
            return if (derived <= ceiling) {
                InFlightCap(derived.toInt(), BY_RATE)
            } else {
                InFlightCap(ceiling.toInt(), BY_CEILING)
            }
        }
    }
}
