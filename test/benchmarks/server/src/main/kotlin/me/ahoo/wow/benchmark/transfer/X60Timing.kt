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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

/**
 * TEMPORARY (X-60, never merged): the node's stage timings, logged once a second as `x60-timing {json}`, cumulative
 * from the start, so a run's node logs show where an E3 transfer waits on each node:
 *
 * - `append.<command>`: the kernel's append of the command's stream, from its start to its outcome;
 * - `execute.<command>`: the kernel's execution of the command, from taking it to its outcome;
 * - `deliver.<subscription>`: from this node's append of a stream returning to the subscription starting to handle
 *   it (the hand-off to the tail, the tail, the worker's lane); `deliverRemote.<subscription>` for a stream this node
 *   did not append, from its commit time;
 * - `handle.<subscription>`: the handling, retries and the derived commands' forwards included;
 * - `admit.<command>`: the gateway's admission of a request.
 *
 * Each value is `[count, sum µs, bucket counts…]` over [EDGES_MICROS] (the last bucket above the last edge).
 */
class X60Timing : Observer, AutoCloseable {
    private val timers = ConcurrentHashMap<String, Timer>()
    private val appended = ConcurrentHashMap<String, Long>()
    private val started = System.currentTimeMillis()
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "x60-timing").apply { isDaemon = true }
    }

    fun start() {
        ticker.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
    }

    override fun close() {
        ticker.shutdownNow()
        log.warn("x60-timing {}", snapshot())
    }

    private fun tick() {
        val horizon = System.nanoTime() - RETAIN_NANOS
        appended.values.removeIf { it < horizon }
        log.warn("x60-timing {}", snapshot())
    }

    private class Timer {
        val count = LongAdder()
        val sum = LongAdder()
        val buckets = Array(EDGES_MICROS.size + 1) { LongAdder() }

        fun record(micros: Long) {
            count.increment()
            sum.add(micros)
            val at = EDGES_MICROS.indexOfFirst { micros <= it }
            buckets[if (at < 0) EDGES_MICROS.size else at].increment()
        }
    }

    private fun record(name: String, nanos: Long) =
        timers.computeIfAbsent(name) { Timer() }.record(nanos.coerceAtLeast(0) / NANOS_PER_MICRO)

    fun snapshot(): String = buildString {
        append("{\"t\":").append((System.currentTimeMillis() - started) / MILLIS_PER_SECOND)
        timers.toSortedMap().forEach { (name, timer) ->
            append(",\"").append(name).append("\":[").append(timer.count.sum()).append(',').append(timer.sum.sum())
            timer.buckets.forEach { append(',').append(it.sum()) }
            append(']')
        }
        append('}')
    }

    /** Records [name] once, at the first of its outcome or its end. */
    private inner class Timed(private val name: String, private val then: (Long) -> Unit = {}) : Observation {
        private val start = System.nanoTime()
        private val done = AtomicBoolean()

        private fun stop() {
            if (done.compareAndSet(false, true)) {
                val now = System.nanoTime()
                record(name, now - start)
                then(now)
            }
        }

        override fun outcome(outcome: ObservedOutcome) = stop()

        override fun end() = stop()
    }

    override fun admitting(context: String, aggregate: String, command: String, trace: Map<String, String>): Observation =
        Timed("admit.${command.substringAfterLast('.')}")

    override fun executing(command: Command<*>, commandType: String): Observation =
        Timed("execute.${commandType.substringAfterLast('.')}")

    override fun appending(stream: EventStreamRecord): Observation =
        Timed("append.${stream.commandType.substringAfterLast('.')}") { appended[keyOf(stream)] = it }

    override fun handling(
        group: String,
        subscription: String,
        context: String,
        stream: EventStreamRecord,
        trace: Map<String, String>,
        topic: String?,
    ): Observation {
        val local = appended[keyOf(stream)]
        if (local != null) {
            record("deliver.$subscription", System.nanoTime() - local)
        } else {
            record("deliverRemote.$subscription", (System.currentTimeMillis() - stream.commitTime) * NANOS_PER_MILLI)
        }
        return Timed("handle.$subscription")
    }

    private fun keyOf(stream: EventStreamRecord) = "${stream.ref}@${stream.version}"

    private companion object {
        private val log = LoggerFactory.getLogger(X60Timing::class.java)
        private const val MILLIS_PER_SECOND = 1_000
        private const val NANOS_PER_MICRO = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val RETAIN_NANOS = 30_000_000_000L
        val EDGES_MICROS = longArrayOf(
            100, 250, 500, 1_000, 2_000, 3_000, 5_000, 8_000, 13_000, 20_000, 30_000, 50_000, 80_000, 130_000, 200_000,
            500_000, 1_000_000, 2_000_000, 5_000_000,
        )
    }
}

/** TEMPORARY (X-60): [X60Timing] as an observer of the node, with the bench signals. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bench.signals", name = ["enabled"], havingValue = "true")
class X60TimingConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun x60Timing(): X60Timing = X60Timing()
}
