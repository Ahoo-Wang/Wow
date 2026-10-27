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
 * pass [maxErrorRate], or [maxConcurrency] is reached. The highest throughput seen is the saturation point the open-loop runs take their
 * 50% and 80% rates from. A closed loop's latencies understate a saturated server's (coordinated omission): they are
 * recorded only to show where the knee is, never as a result.
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
    /** This process's CPU time so far, in seconds: what a step's [Step.generatorCores] is measured with. */
    private val cpuSeconds: () -> Double = ::ownCpuSeconds,
    private val log: (String) -> Unit = {},
) {
    /**
     * One step: its concurrency, throughput and latencies, and the cores the load generator used in its measured
     * phase, so a step the generator could not drive is told from one the servers could not keep up with.
     */
    data class Step(
        val concurrency: Int,
        val ok: Long,
        val errors: Map<String, Long>,
        val seconds: Int,
        val latency: Histogram,
        val generatorCores: Double = 0.0,
    ) {
        val throughput: Double get() = ok.toDouble() / seconds
        val errorRate: Double get() = errors.values.sum().toDouble() / maxOf(1, ok + errors.values.sum())
    }

    init {
        require(growth >= 2) { "The concurrency grows at least twofold a step." }
    }

    fun run(): List<Step> {
        val steps = mutableListOf<Step>()
        var concurrency = startConcurrency
        var flat = 0
        while (concurrency <= maxConcurrency) {
            val step = step(concurrency)
            log(
                "concurrency=$concurrency throughput=${"%.1f".format(step.throughput)}/s " +
                    "p50=${step.latency.percentile(P50) / MICROS_PER_MILLI}ms errors=${step.errors}",
            )
            val best = steps.maxOfOrNull { it.throughput } ?: 0.0
            steps += step
            if (step.errorRate > maxErrorRate) break
            flat = if (step.throughput < best * (1 + minGain)) flat + 1 else 0
            if (flat >= FLAT_STEPS && !sweep) break
            concurrency *= growth
        }
        return steps
    }

    private fun step(concurrency: Int): Step {
        val latency = Histogram()
        val errors = ConcurrentHashMap<String, LongAdder>()
        val ok = AtomicLong()
        val measuring = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        val picks = AtomicLong()
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
        Thread.sleep(stepSeconds * MILLIS_PER_SECOND)
        val cores = (cpuSeconds() - cpuBefore) / ((System.nanoTime() - wallBefore) / NANOS_PER_SECOND)
        measuring.set(false)
        running.set(false)
        CompletableFuture.allOf(*workers.toTypedArray()).join()
        return Step(concurrency, ok.get(), errors.mapValues { it.value.sum() }, stepSeconds, latency, cores)
    }

    companion object {
        const val DEFAULT_MIN_GAIN = 0.05
        const val DEFAULT_MAX_ERROR_RATE = 0.01
        private const val FLAT_STEPS = 2
        private const val P50 = 50.0
        private const val MICROS_PER_MILLI = 1_000
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** This JVM's CPU time so far, in seconds. */
        fun ownCpuSeconds(): Double =
            ProcessHandle.current().info().totalCpuDuration().map { it.toNanos() / NANOS_PER_SECOND }.orElse(0.0)
    }
}
