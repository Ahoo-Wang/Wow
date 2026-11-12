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

package me.ahoo.wow.benchmark.transfer.raw

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * One write or read a raw run measures (RawStoreBench): its [name], the [unit] its figures are per (a `row` or an
 * `update` of a snapshot, a `command` of Wow's, a `read`), how many units one flush is ([flushUnits]), and what it
 * prices ([prices]: today's shape, a candidate, or a reference).
 */
data class RawCase(val name: String, val unit: String, val flushUnits: Int, val prices: String)

/** `usage_usec` of a cgroup v2 `cpu.stat`; null when the text has none. */
fun cgroupUsageMicros(cpuStat: String): Long? =
    cpuStat.lineSequence().map { it.trim().split(' ') }.firstOrNull { it.firstOrNull() == "usage_usec" }
        ?.getOrNull(1)?.toLongOrNull()

/** The [percentile] (0 to 100) of [values], sorted in place; 0 when there are none. */
fun percentile(values: LongArray, percentile: Double): Long {
    if (values.isEmpty()) return 0
    values.sort()
    val rank = (percentile / PERCENT * values.size).toInt().coerceIn(0, values.size - 1)
    return values[rank]
}

/** The groups of [units] a statement or call each: groups of at most [perGroup], in order. */
fun groupsOf(units: Int, perGroup: Int): List<Int> {
    require(units >= 1 && perGroup >= 1) { "A flush holds a unit, a group one." }
    return List((units + perGroup - 1) / perGroup) { minOf(perGroup, units - it * perGroup) }
}

/** The store container's CPU, from its cgroup (`docker exec <container> cat /sys/fs/cgroup/cpu.stat`); null without one. */
class ContainerCpu(private val container: String?) {
    fun micros(): Long? {
        container ?: return null
        val process = ProcessBuilder("docker", "exec", container, "cat", "/sys/fs/cgroup/cpu.stat")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(DOCKER_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return cgroupUsageMicros(text)
    }
}

/** One worker of a step: what it does once a flush (and returns its units), and its close. */
interface RawWorker : AutoCloseable {
    fun flush(): Int
}

/**
 * A step's result: its case, its connections, and what it did and cost per unit: [storeCpuMsPerUnit] from the store
 * container's cgroup, [perUnit] the store's own counters' growth per unit, [ratios] those read as a share (PostgreSQL's
 * HOT updates of all updates).
 */
data class RawStep(
    val case: RawCase,
    val connections: Int,
    val rate: Double?,
    val seconds: Double,
    val units: Long,
    val flushes: Long,
    val failures: Long,
    val p50FlushMs: Double,
    val p99FlushMs: Double,
    val storeCpuMsPerUnit: Double?,
    val perUnit: Map<String, Double>,
    val ratios: Map<String, Double> = emptyMap(),
) {
    val unitsPerSecond: Double get() = if (seconds > 0) units / seconds else 0.0
}

/**
 * The check that the clone writes what Wow writes: [metric] per command of Wow's own run on this store ([wow], the
 * domain's commands in this JVM) against the case that copies its shape ([raw], at its median throughput over the
 * connections); it passes within [TOLERANCE]. Every counter of both is kept beside it ([wowCounters], [rawCounters]),
 * so a gap shows where it is. A run that fails it, or could not take it ([wow] or [raw] null), says so at its head, and
 * none of its cases is read.
 */
data class RawCalibration(
    val metric: String,
    val wow: Double?,
    val raw: Double?,
    val wowCounters: Map<String, Double> = emptyMap(),
    val rawCounters: Map<String, Double> = emptyMap(),
) {
    val ratio: Double? get() = if (wow != null && raw != null && wow > 0) raw / wow else null
    val passed: Boolean get() = ratio?.let { it in (1 - TOLERANCE)..(1 + TOLERANCE) } ?: false

    fun toMap(): Map<String, Any?> = mapOf(
        "metric" to metric,
        "wowPerCommand" to wow,
        "rawPerCommand" to raw,
        "ratio" to ratio,
        "tolerance" to TOLERANCE,
        "passed" to passed,
        "wowPerCommandAll" to wowCounters,
        "rawPerCommandAll" to rawCounters,
    )

    companion object {
        const val TOLERANCE = 0.10
    }
}

/**
 * Runs [connections] workers ([worker] by index, each with its own connection) of [case], [timing]'s warm-up seconds
 * unmeasured and then its measured ones, as fast as they go or, with [rate], at that many units a second between them:
 * the units and flushes done inside the window, their flush latencies, the store container's CPU ([cpu]) and the
 * store's own counters ([counters], cumulative, read at the window's edges) per unit, and [ratios] of their growth.
 */
fun runStep(
    case: RawCase,
    connections: Int,
    rate: Double?,
    timing: Pair<Int, Int>,
    cpu: ContainerCpu,
    counters: () -> Map<String, Double>,
    ratios: (Map<String, Double>) -> Map<String, Double> = { emptyMap() },
    worker: (Int) -> RawWorker,
): RawStep {
    val (warmUpSeconds, seconds) = timing
    val tally = Tally(connections, rate?.let { (case.flushUnits * NANOS_PER_SECOND / it).toLong() })
    val workers = List(connections, worker)
    val threads = workers.mapIndexed { index, w ->
        Thread.ofPlatform().name("raw-$index").start { tally.loop(index, w) }
    }
    Thread.sleep(warmUpSeconds * MILLIS)
    val cpuBefore = cpu.micros()
    val before = counters()
    val startedAt = System.nanoTime()
    tally.measuring.set(true)
    Thread.sleep(seconds * MILLIS)
    tally.measuring.set(false)
    val elapsed = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
    val after = counters()
    val cpuAfter = cpu.micros()
    tally.stopping.set(true)
    threads.forEach { it.join() }
    workers.forEach { it.close() }
    val units = tally.counted.sum()
    if (units == 0L) {
        tally.failure.get()?.let { throw IllegalStateException("${case.name} at $connections did nothing", it) }
    }
    val growth = after.mapValues { (key, value) -> value - (before[key] ?: 0.0) }
    val latencies = tally.latencies()
    return RawStep(
        case = case,
        connections = connections,
        rate = rate,
        seconds = elapsed,
        units = units,
        flushes = tally.flushed.sum(),
        failures = tally.failed.sum(),
        p50FlushMs = percentile(latencies.copyOf(), P50) / NANOS_PER_MILLI,
        p99FlushMs = percentile(latencies, P99) / NANOS_PER_MILLI,
        storeCpuMsPerUnit = perUnit(cpuBefore, cpuAfter, units),
        perUnit = growth.mapValues { (_, delta) -> if (units > 0) delta / units else 0.0 },
        ratios = ratios(growth),
    )
}

/** The container's CPU per unit in milliseconds, from its micros at the window's edges; null without them. */
private fun perUnit(before: Long?, after: Long?, units: Long): Double? =
    if (before != null && after != null && units > 0) (after - before) / MICROS_PER_MILLI / units else null

/**
 * What a step's workers did: each one's units, flushes, failures and flush latencies, inside the window only; with
 * [interval], the flushes paced so that between them the workers start one every [interval] nanoseconds.
 */
private class Tally(connections: Int, private val interval: Long?) {
    val measuring = AtomicBoolean(false)
    val stopping = AtomicBoolean(false)
    val counted = LongArray(connections)
    val flushed = LongArray(connections)
    val failed = LongArray(connections)
    val failure = AtomicReference<Throwable?>(null)
    private val times = List(connections) { LongList() }
    private val next = AtomicLong(System.nanoTime())

    /** Worker [index]'s loop: flush until the step stops, counting what lands inside the window. */
    fun loop(index: Int, worker: RawWorker) {
        while (!stopping.get()) {
            interval?.let { pace(it) }
            val started = System.nanoTime()
            val done = try {
                worker.flush()
            } catch (error: Exception) {
                if (measuring.get()) failed[index]++
                failure.compareAndSet(null, error)
                0
            }
            if (measuring.get() && done > 0) {
                counted[index] += done.toLong()
                flushed[index]++
                times[index].add(System.nanoTime() - started)
            }
        }
    }

    /** Waits for this flush's turn: one every [interval] nanoseconds across the workers, never ahead of time. */
    private fun pace(interval: Long) {
        val due = next.getAndUpdate { maxOf(it, System.nanoTime()) + interval }
        val wait = due - System.nanoTime()
        if (wait > 0) LockSupport.parkNanos(wait)
    }

    /** Every worker's flush latencies, in nanoseconds. */
    fun latencies(): LongArray = LongArray(times.sumOf { it.size }).also { array ->
        var at = 0
        times.forEach { list -> for (i in 0 until list.size) array[at++] = list[i] }
    }
}

/** A growable list of longs, a worker's flush latencies. */
class LongList {
    private var values = LongArray(INITIAL)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Long = values[index]
}

/** raw.json's text: the [calibration] first, then the run's [settings] and the [store]'s, then each step. */
fun rawJson(
    calibration: RawCalibration,
    settings: Map<String, Any?>,
    store: Map<String, Any?>,
    steps: List<RawStep>,
): String {
    val rows = steps.map { step ->
        mapOf(
            "case" to step.case.name,
            "unit" to step.case.unit,
            "prices" to step.case.prices,
            "connections" to step.connections,
            "rate" to step.rate,
            "seconds" to step.seconds,
            "units" to step.units,
            "flushes" to step.flushes,
            "failures" to step.failures,
            "unitsPerSecond" to step.unitsPerSecond,
            "p50FlushMs" to step.p50FlushMs,
            "p99FlushMs" to step.p99FlushMs,
            "storeCpuMsPerUnit" to step.storeCpuMsPerUnit,
            "perUnit" to step.perUnit,
            "ratios" to step.ratios,
        )
    }
    return jsonOf(
        mapOf("schema" to "wow-e2e-bench/raw/1", "calibration" to calibration.toMap()) + settings +
            mapOf("store" to store, "steps" to rows),
    )
}

/** [value] as JSON text: maps, lists, numbers (a non-finite one null), booleans, null and strings. */
fun jsonOf(value: Any?): String = when (value) {
    null -> "null"
    is Double -> if (value.isFinite()) value.toString() else "null"
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonOf(k.toString())}:${jsonOf(v)}" }
    is List<*> -> value.joinToString(",", "[", "]") { jsonOf(it) }
    else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

private const val PERCENT = 100.0
private const val P50 = 50.0
private const val P99 = 99.0
private const val MILLIS = 1_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val NANOS_PER_MILLI = 1_000_000.0
private const val MICROS_PER_MILLI = 1_000.0
private const val DOCKER_SECONDS = 10L
private const val INITIAL = 1_024
