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

package me.ahoo.wow.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport

/** One measured run: what was offered, what came back and what it cost. */
data class Measurement(
    val workload: String,
    val routing: String,
    val load: String,
    val offeredRate: Double,
    val repetition: Int,
    val throughput: Double,
    val p50Millis: Double,
    val p99Millis: Double,
    val p999Millis: Double,
    val maxMillis: Double,
    val measured: Int,
    val committed: Long,
    val rejected: Long,
    val contended: Long,
    val busy: Long,
    val failed: Long,
    val incomplete: Long,
    val cacheHitRate: Double,
    val readsPerCommand: Double,
    val stateReadsPerCommand: Double,
    val conflictsPerCommand: Double,
    val contendedRatio: Double,
    val forwardRatio: Double,
    val meanForwardMicros: Double,
    val cpuMicrosPerCommand: Double,
    /** The median over the measured seconds of each second's p99: the tail without isolated stalls. */
    val steadyP99Millis: Double,
    /** The worst second's p99. */
    val worstSecondP99Millis: Double,
    /** JVM garbage-collection time within the measured window. */
    val gcMillis: Long,
)

/** Builds the commands of one run and sends them through a [Cluster], respecting the workload's per-aggregate order. */
class Driver(
    private val cluster: Cluster,
    private val workload: Workload,
    private val type: AggregateType,
    private val runId: String,
) {
    private val clientLanes = ConcurrentHashMap<Int, Mutex>()
    private val memo = "m".repeat(workload.shape.memoLength)

    suspend fun send(sequence: Long): Result {
        val random = ThreadLocalRandom.current()
        val account = workload.access.next(random)
        val command = command(sequence, account, random)
        if (!workload.sequential) {
            return cluster.send(command, random).result
        }
        // A sequential client sends an aggregate's next command once the previous one has answered.
        return clientLanes.computeIfAbsent(account) { Mutex() }.withLock { cluster.send(command, random).result }
    }

    private fun command(sequence: Long, account: Int, random: ThreadLocalRandom): Command<Any> {
        val amount = random.nextLong(1, MAX_AMOUNT)
        val withdraw = random.nextInt(PERCENT) < WITHDRAW_PERCENT
        val body: Any = if (withdraw) Withdraw(amount, memo) else Deposit(amount, memo)
        return Command(
            id = "$runId-$sequence",
            requestId = "$runId-$sequence",
            ref = type.ref(TENANT, accountId(account)),
            body = body,
        )
    }

    companion object {
        const val TENANT = "tenant"
        private const val MAX_AMOUNT = 100L
        private const val PERCENT = 100
        private const val WITHDRAW_PERCENT = 10

        fun accountId(index: Int) = "account-$index"
    }
}

private val cpu = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

private val collectors = ManagementFactory.getGarbageCollectorMXBeans()

private class Window(val counters: Map<String, Long>, val cpuNanos: Long, val gcMillis: Long)

private fun window(counters: Counters) =
    Window(counters.read(), cpu.processCpuTime, collectors.sumOf { it.collectionTime.coerceAtLeast(0) })

/**
 * The p99 of each second of arrivals ([perSecond] of them; incomplete ones count as unbounded): a stall of the
 * database or the JVM lands in one or two seconds, so the median of these is the steady tail and the maximum the
 * stalled one.
 */
private fun secondP99s(latenciesNanos: LongArray, perSecond: Int): List<Double> {
    if (perSecond <= 0) return emptyList()
    return latenciesNanos.toList().chunked(perSecond).filter { it.size * 2 >= perSecond }.map { second ->
        val sorted = second.map { if (it < 0) Long.MAX_VALUE else it }.sorted()
        val nanos = sorted[(kotlin.math.ceil(P99 * sorted.size).toInt() - 1).coerceAtLeast(0)]
        if (nanos == Long.MAX_VALUE) Double.POSITIVE_INFINITY else nanos / NANOS_PER_MILLI
    }
}

/** The counter ratios between [begin] and [end], per command executed. */
@Suppress("LongParameterList", "CyclomaticComplexMethod") // One flat mapping of counters to fields.
private fun measurement(
    workload: Workload,
    routing: Routing,
    load: String,
    offeredRate: Double,
    repetition: Int,
    seconds: Double,
    committedInWindow: Long,
    latenciesNanos: LongArray,
    outcomes: Map<Result, Long>,
    begin: Window,
    end: Window,
    perSecond: Int,
): Measurement {
    fun delta(key: String) = (end.counters.getValue(key) - begin.counters.getValue(key)).toDouble()
    val seconds99 = secondP99s(latenciesNanos, perSecond).sorted()
    val executed = delta("executed").coerceAtLeast(1.0)
    val reads = delta("dedupReads") + delta("logReads") + delta("snapshotLoads")
    val sorted = latenciesNanos.filter { it >= 0 }.sorted()
    val incomplete = latenciesNanos.count { it < 0 }.toLong()

    fun percentile(q: Double): Double {
        val all = sorted.size + incomplete
        if (all == 0L) return Double.NaN
        val index = kotlin.math.ceil(q * all).toLong() - 1
        return if (index >= sorted.size) Double.POSITIVE_INFINITY else sorted[index.toInt()] / NANOS_PER_MILLI
    }
    val finished = outcomes.values.sum().coerceAtLeast(1)
    return Measurement(
        workload = workload.name,
        routing = routing.label,
        load = load,
        offeredRate = offeredRate,
        repetition = repetition,
        throughput = committedInWindow / seconds,
        p50Millis = percentile(P50),
        p99Millis = percentile(P99),
        p999Millis = percentile(P999),
        maxMillis = if (incomplete > 0) Double.POSITIVE_INFINITY else (sorted.lastOrNull() ?: 0) / NANOS_PER_MILLI,
        measured = latenciesNanos.size,
        committed = outcomes[Result.COMMITTED] ?: 0,
        rejected = outcomes[Result.REJECTED] ?: 0,
        contended = outcomes[Result.CONTENDED] ?: 0,
        busy = outcomes[Result.BUSY] ?: 0,
        failed = outcomes[Result.FAILED] ?: 0,
        incomplete = incomplete,
        cacheHitRate = delta("cacheHits") / delta("cacheTakes").coerceAtLeast(1.0),
        readsPerCommand = reads / executed,
        stateReadsPerCommand = (reads - delta("dedupReads")) / executed,
        conflictsPerCommand = delta("conflicts") / executed,
        contendedRatio = (outcomes[Result.CONTENDED] ?: 0).toDouble() / finished,
        forwardRatio = delta("forwards") / executed,
        meanForwardMicros = if (delta("forwards") > 0) delta("forwardNanos") / delta("forwards") / NANOS_PER_MICRO else 0.0,
        cpuMicrosPerCommand = (end.cpuNanos - begin.cpuNanos) / executed / NANOS_PER_MICRO,
        steadyP99Millis = seconds99.getOrElse(seconds99.size / 2) { Double.NaN },
        worstSecondP99Millis = seconds99.lastOrNull() ?: Double.NaN,
        gcMillis = end.gcMillis - begin.gcMillis,
    )
}

/**
 * Offers commands at a fixed [rate] for [warmupSeconds] then [measureSeconds], open loop: a command is sent at its
 * scheduled time whether or not earlier ones have answered, and its latency is measured from that scheduled time, so
 * a stalled system cannot hide its queue (no coordinated omission). Only commands scheduled in the measured window
 * count; the throughput is the commits completed within it.
 */
@Suppress("LongParameterList", "LongMethod", "InjectDispatcher")
fun openLoop(
    driver: Driver,
    counters: Counters,
    workload: Workload,
    routing: Routing,
    load: String,
    rate: Double,
    repetition: Int,
    warmupSeconds: Double,
    measureSeconds: Double,
): Measurement {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val interval = NANOS_PER_SECOND / rate
    val warmupCount = (rate * warmupSeconds).toLong()
    val total = warmupCount + (rate * measureSeconds).toLong()
    val latencies = LongArray((total - warmupCount).toInt()) { -1 }
    val outcomes = ConcurrentHashMap<Result, LongAdder>()
    val committedInWindow = LongAdder()
    val inFlight = AtomicInteger()
    val start = System.nanoTime() + START_DELAY_NANOS
    val windowStart = start + (warmupCount * interval).toLong()
    val windowEnd = start + (total * interval).toLong()
    var begin: Window? = null
    var end: Window? = null
    var sequence = 0L
    while (sequence < total) {
        val due = start + (sequence * interval).toLong()
        val wait = due - System.nanoTime()
        if (wait > SPIN_NANOS) {
            LockSupport.parkNanos(wait - SPIN_NANOS)
            continue
        }
        if (sequence == warmupCount) {
            begin = window(counters)
        }
        val measuredIndex = sequence - warmupCount
        val current = sequence
        inFlight.incrementAndGet()
        scope.launch {
            try {
                val result = runCatching { driver.send(current) }.getOrDefault(Result.FAILED)
                val done = System.nanoTime()
                if (result == Result.COMMITTED && done in windowStart..windowEnd) {
                    committedInWindow.increment()
                }
                if (measuredIndex >= 0) {
                    latencies[measuredIndex.toInt()] = done - due
                    outcomes.computeIfAbsent(result) { LongAdder() }.increment()
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }
        sequence++
    }
    while (System.nanoTime() < windowEnd) {
        LockSupport.parkNanos(windowEnd - System.nanoTime())
    }
    end = window(counters)
    val drainDeadline = System.nanoTime() + DRAIN_NANOS
    while (inFlight.get() > 0 && System.nanoTime() < drainDeadline) {
        LockSupport.parkNanos(POLL_NANOS)
    }
    scope.cancel()
    return measurement(
        workload, routing, load, rate, repetition, measureSeconds, committedInWindow.sum(), latencies,
        outcomes.mapValues { it.value.sum() }, checkNotNull(begin), end, rate.toInt(),
    )
}

/**
 * The saturation throughput: [workers] clients each sending its next command as soon as the previous one answers
 * (closed loop), counting the commits completed in the [measureSeconds] after [warmupSeconds].
 */
@Suppress("LongParameterList", "InjectDispatcher")
fun closedLoop(
    driver: Driver,
    counters: Counters,
    workload: Workload,
    routing: Routing,
    workers: Int,
    warmupSeconds: Double,
    measureSeconds: Double,
): Measurement {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val start = System.nanoTime()
    val windowStart = start + (warmupSeconds * NANOS_PER_SECOND).toLong()
    val windowEnd = windowStart + (measureSeconds * NANOS_PER_SECOND).toLong()
    val stop = AtomicBoolean()
    val sequence = java.util.concurrent.atomic.AtomicLong()
    val committed = LongAdder()
    val latencies = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    val outcomes = ConcurrentHashMap<Result, LongAdder>()
    val running = AtomicInteger(workers)
    repeat(workers) {
        scope.launch {
            try {
                while (!stop.get()) {
                    val sent = System.nanoTime()
                    val result = runCatching { driver.send(sequence.getAndIncrement()) }.getOrDefault(Result.FAILED)
                    val done = System.nanoTime()
                    if (sent >= windowStart && done <= windowEnd) {
                        latencies += done - sent
                        outcomes.computeIfAbsent(result) { LongAdder() }.increment()
                        if (result == Result.COMMITTED) committed.increment()
                    }
                }
            } finally {
                running.decrementAndGet()
            }
        }
    }
    LockSupport.parkNanos(windowStart - System.nanoTime())
    val begin = window(counters)
    LockSupport.parkNanos(windowEnd - System.nanoTime())
    val end = window(counters)
    stop.set(true)
    val drainDeadline = System.nanoTime() + DRAIN_NANOS
    while (running.get() > 0 && System.nanoTime() < drainDeadline) {
        LockSupport.parkNanos(POLL_NANOS)
    }
    scope.cancel()
    return measurement(
        workload, routing, "saturation", Double.NaN, 1, measureSeconds, committed.sum(),
        latencies.toLongArray(), outcomes.mapValues { it.value.sum() }, begin, end, 0,
    )
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val NANOS_PER_MILLI = 1_000_000.0
private const val NANOS_PER_MICRO = 1_000.0
private const val START_DELAY_NANOS = 20_000_000L
private const val SPIN_NANOS = 60_000L
private const val DRAIN_NANOS = 30_000_000_000L
private const val POLL_NANOS = 5_000_000L
private const val P50 = 0.50
private const val P99 = 0.99
private const val P999 = 0.999
