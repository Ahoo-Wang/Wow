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
 * The closed-loop saturation finder (decision D224): [concurrency] workers each send an operation and send the next
 * when it completes, for a step of [stepSeconds] after [warmUpSeconds]; the concurrency doubles from
 * [startConcurrency] until the throughput stops growing by [minGain] two steps in a row, errors pass [maxErrorRate],
 * or [maxConcurrency] is reached ([flatSteps] steps in a row, two by default). With [refineSteps] above zero, a
 * bisection follows: between the knee (the lowest concurrency within [minGain] of the best throughput) and the highest
 * concurrency below it, at most [refineSteps] more steps, so short steps and a single flat step still find the knee
 * (decision D224's measurement tiers). The highest throughput seen is the saturation point the open-loop runs take
 * their 50% and 80% rates from. A closed loop's latencies understate a saturated server's (coordinated omission): they are
 * recorded only to show where the knee is, never as a result.
 */
class ClosedLoop(
    private val client: Client,
    private val scenario: Scenario,
    private val keysOf: (Int) -> KeyChooser,
    private val targets: List<URI>,
    private val startConcurrency: Int,
    private val maxConcurrency: Int,
    private val warmUpSeconds: Int,
    private val stepSeconds: Int,
    private val minGain: Double = DEFAULT_MIN_GAIN,
    private val maxErrorRate: Double = DEFAULT_MAX_ERROR_RATE,
    private val flatSteps: Int = FLAT_STEPS,
    private val refineSteps: Int = 0,
    private val log: (String) -> Unit = {},
) {
    /** One step: its concurrency, throughput and latencies. */
    data class Step(
        val concurrency: Int,
        val ok: Long,
        val errors: Map<String, Long>,
        val seconds: Int,
        val latency: Histogram
    ) {
        val throughput: Double get() = ok.toDouble() / seconds
        val errorRate: Double get() = errors.values.sum().toDouble() / maxOf(1, ok + errors.values.sum())
    }

    fun run(): List<Step> {
        val steps = mutableListOf<Step>()
        var concurrency = startConcurrency
        var flat = 0
        var failed = false
        while (concurrency <= maxConcurrency) {
            val step = logged(step(concurrency))
            val best = steps.maxOfOrNull { it.throughput } ?: 0.0
            steps += step
            if (step.errorRate > maxErrorRate) {
                failed = true
                break
            }
            flat = if (step.throughput < best * (1 + minGain)) flat + 1 else 0
            if (flat >= flatSteps) break
            concurrency *= 2
        }
        if (!failed) refine(steps)
        return steps
    }

    /** Bisects between the knee and the concurrency below it, at most [refineSteps] steps. */
    private fun refine(steps: MutableList<Step>) {
        repeat(refineSteps) {
            val best = steps.maxOf { it.throughput }
            val knee = steps.filter { it.throughput >= best * (1 - minGain) }.minBy { it.concurrency }
            val below = steps.filter { it.concurrency < knee.concurrency }.maxByOrNull { it.concurrency } ?: return
            val middle = (below.concurrency + knee.concurrency) / 2
            if (middle <= below.concurrency || steps.any { it.concurrency == middle }) return
            val step = logged(step(middle))
            steps += step
            if (step.errorRate > maxErrorRate) return
        }
    }

    private fun logged(step: Step): Step = step.also {
        log(
            "concurrency=${it.concurrency} throughput=${"%.1f".format(it.throughput)}/s " +
                "p50=${it.latency.percentile(P50) / MICROS_PER_MILLI}ms errors=${it.errors}",
        )
    }

    private fun step(concurrency: Int): Step {
        val latency = Histogram()
        val errors = ConcurrentHashMap<String, LongAdder>()
        val ok = AtomicLong()
        val measuring = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        val workers = (0 until concurrency).map { worker ->
            val keys = keysOf(worker)
            val finished = CompletableFuture<Unit>()
            fun next() {
                if (!running.get()) {
                    finished.complete(Unit)
                    return
                }
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
        Thread.sleep(stepSeconds * MILLIS_PER_SECOND)
        measuring.set(false)
        running.set(false)
        CompletableFuture.allOf(*workers.toTypedArray()).join()
        return Step(concurrency, ok.get(), errors.mapValues { it.value.sum() }, stepSeconds, latency)
    }

    companion object {
        const val DEFAULT_MIN_GAIN = 0.05
        const val DEFAULT_MAX_ERROR_RATE = 0.01
        const val FLAT_STEPS = 2
        private const val P50 = 50.0
        private const val MICROS_PER_MILLI = 1_000
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
