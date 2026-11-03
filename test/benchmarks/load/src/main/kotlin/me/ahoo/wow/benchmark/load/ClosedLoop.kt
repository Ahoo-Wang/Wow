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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * The closed-loop saturation finder: [concurrency] workers each send an operation and send the next
 * when it completes, for a step of [stepSeconds] after [warmUpSeconds]; the concurrency grows [growth] times a step
 * from [startConcurrency] (doubles by default; 4 sweeps 1 to 1,024 in six steps instead of eleven) until the throughput stops growing by [minGain] two steps in a row (unless it [sweep]s), errors
 * pass [maxErrorRate], or [maxConcurrency] is reached; or, with [concurrencies], exactly those steps in that order, up
 * to the first whose errors pass [maxErrorRate]. The highest throughput seen is the saturation point the open-loop
 * runs take their 50% and 80% rates from. A closed loop's latencies understate a saturated server's (coordinated omission): they are
 * recorded only to show where the knee is, never as a result.
 *
 * With [failAfterSeconds], a failing step ends early: once it has measured that long, its errors so far pass
 * [maxErrorRate] and its throughput so far is below an earlier step's, it stops (stopsEarly). That is long enough to
 * record what failed, and such a step is never the saturation point, so the point is measured as long as before.
 */
class ClosedLoop(
    private val client: Client,
    private val mix: Mix,
    private val keysOf: (Int) -> KeyChooser,
    private val targets: List<URI>,
    private val startConcurrency: Int,
    private val maxConcurrency: Int,
    private val warmUpSeconds: Int,
    private val stepSeconds: Int,
    private val minGain: Double = DEFAULT_MIN_GAIN,
    private val maxErrorRate: Double = DEFAULT_MAX_ERROR_RATE,
    /** Every step up to [maxConcurrency], however little one gains: the whole curve rather than its knee. */
    private val sweep: Boolean = false,
    /** How many times the concurrency grows from one step to the next. */
    private val growth: Int = 2,
    /** The steps' concurrencies, in order, instead of [startConcurrency] grown to [maxConcurrency]. */
    private val concurrencies: List<Int>? = null,
    /** How long a failing step measures at least before it ends early (stopsEarly); none ends early without it. */
    private val failAfterSeconds: Int? = null,
    /** This process's CPU time so far, in seconds: what a step's [Step.generatorCores] is measured with. */
    private val cpuSeconds: () -> Double = ::ownCpuSeconds,
    private val log: (String) -> Unit = {},
) {
    /**
     * One step: its concurrency, throughput and latencies, and the cores the load generator used in its measured
     * phase, so a step the generator could not drive is told from one the servers could not keep up with; and every
     * operation it sent, its warm-up's too, that reached its completion point ([sentOk]) or failed ([sentFailed]).
     */
    data class Step(
        val concurrency: Int,
        val ok: Long,
        val errors: Map<String, Long>,
        val seconds: Int,
        val latency: Histogram,
        val generatorCores: Double = 0.0,
        val sentOk: Long = 0,
        val sentFailed: Long = 0,
    ) {
        val throughput: Double get() = ok.toDouble() / seconds
        val errorRate: Double get() = errors.values.sum().toDouble() / maxOf(1, ok + errors.values.sum())

        /** Whether it measured less than [stepSeconds], a failing step ended early. */
        fun endedEarly(stepSeconds: Int): Boolean = seconds < stepSeconds
    }

    init {
        require(growth >= 2) { "The concurrency grows at least twofold a step." }
        require(concurrencies == null || concurrencies.isNotEmpty() && concurrencies.all { it >= 1 }) {
            "The steps' concurrencies are one at least each."
        }
    }

    fun run(): List<Step> {
        val steps = mutableListOf<Step>()
        var concurrency = concurrencies?.first() ?: startConcurrency.takeIf { it <= maxConcurrency }
        var flat = 0
        while (concurrency != null) {
            val best = steps.maxOfOrNull { it.throughput } ?: 0.0
            val step = step(concurrency, best)
            log(describe(step))
            steps += step
            if (step.errorRate > maxErrorRate) break
            flat = if (step.throughput < best * (1 + minGain)) flat + 1 else 0
            concurrency = next(steps.size, concurrency, flat)
        }
        return steps
    }

    /** The concurrency of the step after [done] steps, the last at [concurrency] and [flat] in a row levelled, if any. */
    private fun next(done: Int, concurrency: Int, flat: Int): Int? = when {
        concurrencies != null -> concurrencies.getOrNull(done)
        flat >= FLAT_STEPS && !sweep -> null
        else -> (concurrency * growth).takeIf { it <= maxConcurrency }
    }

    private fun describe(step: Step): String =
        "concurrency=${step.concurrency} throughput=${"%.1f".format(step.throughput)}/s " +
            "p50=${step.latency.percentile(P50) / MICROS_PER_MILLI}ms errors=${step.errors}" +
            if (step.endedEarly(stepSeconds)) " ended after ${step.seconds}s" else ""

    /** One step at [concurrency]; [best], the most an earlier step did, is what a failing one stays below to end early. */
    private fun step(concurrency: Int, best: Double): Step {
        val latency = Histogram()
        val errors = ConcurrentHashMap<String, LongAdder>()
        val ok = AtomicLong()
        val measuring = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        val picks = AtomicLong()
        // The warm-up's too: every operation that reached its completion point, and every one that failed.
        val sentOk = AtomicLong()
        val sentFailed = AtomicLong()
        val workers = (0 until concurrency).map { worker ->
            val keys = keysOf(worker)
            val finished = CompletableFuture<Unit>()
            fun next() {
                if (!running.get()) {
                    finished.complete(Unit)
                    return
                }
                val scenario = mix.pick(picks.getAndIncrement())
                val account = keys.next()
                val other = if (scenario.transfers) keys.nextOtherThan(account) else account
                val sent = System.nanoTime()
                scenario.run(client, Target(targets[worker % targets.size], account, other)).handle { error, failure ->
                    if (error == null && failure == null) sentOk.incrementAndGet() else sentFailed.incrementAndGet()
                    if (measuring.get()) {
                        val kind = error ?: failure?.let { "exception-${OpenLoop.rootCause(it).javaClass.simpleName}" }
                        if (kind == null) {
                            ok.incrementAndGet()
                            latency.record((System.nanoTime() - sent) / OpenLoop.NANOS_PER_MICRO)
                        } else {
                            errors.computeIfAbsent(kind) { LongAdder() }.increment()
                        }
                    }
                    next()
                }
            }
            next()
            finished
        }
        Thread.sleep(warmUpSeconds * MILLIS_PER_SECOND)
        measuring.set(true)
        val cpuBefore = cpuSeconds()
        val wallBefore = System.nanoTime()
        val seconds = measure(wallBefore, best) { ok.get() to errors.values.sumOf { it.sum() } }
        val cores = (cpuSeconds() - cpuBefore) / ((System.nanoTime() - wallBefore) / NANOS_PER_SECOND)
        measuring.set(false)
        running.set(false)
        CompletableFuture.allOf(*workers.toTypedArray()).join()
        return Step(
            concurrency,
            ok.get(),
            errors.mapValues { it.value.sum() },
            seconds,
            latency,
            cores,
            sentOk.get(),
            sentFailed.get(),
        )
    }

    /**
     * The measured phase from [wallBefore]: the whole step at once, or, when a failing step may end early, to
     * failAfterSeconds, then a second at a time while [counts] (done, failed) say it goes on. The seconds it measured.
     */
    private fun measure(wallBefore: Long, best: Double, counts: () -> Pair<Long, Long>): Int {
        var seconds = 0
        while (seconds < stepSeconds) {
            seconds = when (failAfterSeconds) {
                null -> stepSeconds
                else -> minOf(stepSeconds, maxOf(seconds + 1, failAfterSeconds))
            }
            val left = wallBefore + seconds * NANOS_PER_SECOND.toLong() - System.nanoTime()
            if (left > 0) Thread.sleep(left / NANOS_PER_MILLI, (left % NANOS_PER_MILLI).toInt())
            val (ok, failed) = counts()
            if (stopsEarly(seconds, ok, failed, best, failAfterSeconds, maxErrorRate)) break
        }
        return seconds
    }

    companion object {
        const val DEFAULT_MIN_GAIN = 0.05
        const val DEFAULT_MAX_ERROR_RATE = 0.01
        private const val FLAT_STEPS = 2
        private const val P50 = 50.0
        private const val MICROS_PER_MILLI = 1_000
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Whether a step that has measured [seconds], [ok] operations done and [failed] failed so far ends now: with
         * [failAfterSeconds] set, once it has measured that long, its errors pass [maxErrorRate] and its throughput is
         * below [best], an earlier step's. A step that may yet be the saturation point never ends early.
         */
        fun stopsEarly(
            seconds: Int,
            ok: Long,
            failed: Long,
            best: Double,
            failAfterSeconds: Int?,
            maxErrorRate: Double = DEFAULT_MAX_ERROR_RATE,
        ): Boolean {
            if (failAfterSeconds == null || seconds < failAfterSeconds) return false
            val rate = failed.toDouble() / maxOf(1, ok + failed)
            return rate > maxErrorRate && ok.toDouble() / seconds < best
        }

        /** This JVM's CPU time so far, in seconds. */
        fun ownCpuSeconds(): Double =
            ProcessHandle.current().info().totalCpuDuration().map { it.toNanos() / NANOS_PER_SECOND }.orElse(0.0)
    }
}
