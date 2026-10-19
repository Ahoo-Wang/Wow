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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport

/**
 * The open-loop driver (decision D224): operations are scheduled at a fixed arrival rate, the `i`th at
 * `start + i / rate`, whether or not the earlier ones have completed, so a slow server cannot slow the arrivals down
 * (coordinated omission). Each operation's latency is measured from its scheduled time, not from when it was sent,
 * so a late send counts against it too.
 *
 * The first [warmUpSeconds] are not recorded; the next [measureSeconds] are, into [Measured]. An operation belongs to
 * the phase and the second its scheduled time falls in. At most [maxInFlight] operations are outstanding: one
 * scheduled beyond that is not sent and is counted as `generator-overload`, which marks the run as not keeping up.
 */
class OpenLoop(
    private val client: Client,
    private val scenario: Scenario,
    private val keys: KeyChooser,
    private val targets: List<URI>,
    private val rate: Double,
    private val warmUpSeconds: Int,
    private val measureSeconds: Int,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    private val onPhase: (String) -> Unit = {},
) {
    init {
        require(rate > 0) { "The rate is positive." }
        require(targets.isNotEmpty()) { "At least one target." }
    }

    /** What the measured phase recorded. */
    class Measured(seconds: Int, warmUpSeconds: Int = 0) {
        val latency = Histogram()
        val errorLatency = Histogram()
        val perSecond = AtomicReferenceArray<Histogram>(seconds)

        /**
         * The warm-up's successful latencies, per second: not part of the results, they let the harness check whether
         * a shorter warm-up would have reached the same steady state (`stability.py`).
         */
        val warmUpPerSecond = AtomicReferenceArray<Histogram>(warmUpSeconds)
        val errors = ConcurrentHashMap<String, LongAdder>()
        val scheduled = AtomicLong()
        val maxSendLagNanos = AtomicLong()

        fun second(index: Int): Histogram {
            perSecond.get(index)?.let { return it }
            perSecond.compareAndSet(index, null, Histogram())
            return perSecond.get(index)
        }

        /** Records a warm-up operation scheduled at [scheduled] in [second] of the warm-up, when it succeeded. */
        fun warmUp(second: Int, scheduled: Long, error: String?, failure: Throwable?) {
            if (error != null || failure != null || second !in 0 until warmUpPerSecond.length()) return
            warmUpPerSecond.get(second) ?: warmUpPerSecond.compareAndSet(second, null, Histogram())
            warmUpPerSecond.get(second).record((System.nanoTime() - scheduled) / NANOS_PER_MICRO)
        }

        fun error(kind: String) = errors.computeIfAbsent(kind) { LongAdder() }.increment()

        /**
         * Records the operation scheduled at [scheduled] in [second] of the measured phase, which completed with
         * [error] (null when it reached its completion point) or failed with [failure].
         */
        fun complete(second: Int, scheduled: Long, error: String?, failure: Throwable?) {
            val latencyMicros = (System.nanoTime() - scheduled) / NANOS_PER_MICRO
            val kind = error ?: failure?.let { "exception-${rootCause(it).javaClass.simpleName}" }
            if (kind == null) {
                latency.record(latencyMicros)
                second(second).record(latencyMicros)
            } else {
                errorLatency.record(latencyMicros)
                error(kind)
            }
        }
    }

    /** Runs the warm-up and the measured phase, and waits for the operations still in flight. */
    fun run(): Measured {
        val measured = Measured(measureSeconds, warmUpSeconds)
        val interval = NANOS_PER_SECOND / rate
        val total = ((warmUpSeconds + measureSeconds) * rate).toLong()
        val warmUpEnd = warmUpSeconds * NANOS_PER_SECOND
        val inFlight = AtomicInteger()
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
            val account = keys.next()
            val target = Target(
                targets[(i % targets.size).toInt()],
                account,
                if (scenario.transfers) keys.nextOtherThan(account) else account,
            )
            if (inFlight.incrementAndGet() > maxInFlight) {
                inFlight.decrementAndGet()
                if (inMeasure) measured.error(OVERLOAD)
                continue
            }
            outstanding.incrementAndGet()
            val second = ((offset - warmUpEnd) / NANOS_PER_SECOND).toInt()
            val warmUpSecond = (offset / NANOS_PER_SECOND).toInt()
            scenario.run(client, target).handle { error, failure ->
                if (inMeasure) {
                    measured.complete(second, scheduled, error, failure)
                } else {
                    measured.warmUp(warmUpSecond, scheduled, error, failure)
                }
                inFlight.decrementAndGet()
                if (outstanding.decrementAndGet() == 0L) done.countDown()
            }
        }
        onPhase("drain")
        if (outstanding.decrementAndGet() == 0L) done.countDown()
        done.await(VersionAdapter.REQUEST_TIMEOUT.toSeconds() + DRAIN_MARGIN_SECONDS, TimeUnit.SECONDS)
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
        const val DEFAULT_MAX_IN_FLIGHT = 20_000
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
