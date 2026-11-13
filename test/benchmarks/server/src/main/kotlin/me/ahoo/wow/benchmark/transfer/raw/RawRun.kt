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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One write or read a raw run measures (RawStoreBench): its [name], the [unit] its figures are per (a `row` or an
 * `update` of a snapshot, a `command` of Wow's, a `read`), how many units one flush asks for ([flushUnits]), and what
 * it prices ([prices]: today's shape, a candidate, or a reference); and with a [ceiling], that it is the shape Wow's
 * node gives the store (WowShape), whose best candidate step is that ceiling: [COMMITTED], a command answered at its
 * append (Wow's E1), or [SNAPSHOT], once its snapshot was written too (the default wait's).
 */
data class RawCase(
    val name: String,
    val unit: String,
    val flushUnits: Int,
    val prices: String,
    val ceiling: String? = null,
) {
    /** Whether it is a shape Wow's node gives the store. */
    val wowShape: Boolean get() = ceiling != null

    companion object {
        const val COMMITTED = "committed"
        const val SNAPSHOT = "snapshot"
    }
}

/** What one flush did: the [written] units the store took, and the [refused] ones it did not (a guard, a key). */
data class Flushed(val written: Int, val refused: Int = 0)

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

/**
 * The store container's CPU, from its cgroup (`docker exec <container> cat /sys/fs/cgroup/cpu.stat`); null without
 * one.
 */
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

/** One worker of a step: what it does once a flush, and its close. */
interface RawWorker : AutoCloseable {
    fun flush(): Flushed
}

/**
 * A step's result: its case, its connections (and MongoDB's [pool]), and what it did and cost per unit written:
 * [storeCpuMsPerUnit] from the store container's cgroup, [perUnit] the store's own counters' growth per unit,
 * [ratios] those read as a share. A step with any [failures] or [refused] writes is not read ([read]); a paced step
 * counts its flushes that started [late].
 */
data class RawStep(
    val case: RawCase,
    val connections: Int,
    val rate: Double?,
    val seconds: Double,
    val units: Long,
    val flushes: Long,
    val failures: Long,
    val refused: Long,
    val late: Long,
    val p50FlushMs: Double,
    val p99FlushMs: Double,
    val storeCpuMsPerUnit: Double?,
    val perUnit: Map<String, Double>,
    val ratios: Map<String, Double> = emptyMap(),
    val pool: Int? = null,
) {
    val unitsPerSecond: Double get() = if (seconds > 0) units / seconds else 0.0

    /** Whether the step's figures stand: every write it asked for was taken, and none failed. */
    val read: Boolean get() = failures == 0L && refused == 0L && units > 0
}

/** One calibration check: [metric] per command of Wow's own run against the clone's, within its own [bound]. */
data class RawCheck(val metric: String, val wow: Double?, val raw: Double?, val bound: Bound) {
    /** A bound: a ratio within [share] of 1, or a difference within [absolute]. */
    data class Bound(val share: Double? = null, val absolute: Double? = null)

    val passed: Boolean
        get() = when {
            wow == null || raw == null -> false
            bound.absolute != null -> kotlin.math.abs(raw - wow) <= bound.absolute
            else -> wow > 0 && kotlin.math.abs(raw / wow - 1) <= (bound.share ?: 0.0)
        }

    fun toMap(): Map<String, Any?> = mapOf(
        "metric" to metric,
        "wowPerCommand" to wow,
        "rawPerCommand" to raw,
        "share" to bound.share,
        "absolute" to bound.absolute,
        "passed" to passed,
    )
}

/**
 * The check that the clone writes what Wow writes: each of [checks] per command of Wow's own run on this store (the
 * domain's commands in this JVM) against the clone's calibration step, on the same rows in the same state; it passes
 * when every check does and the step was [read] (every write it asked for taken). [apart] are reported beside them,
 * unbounded, and every counter of both ([wowCounters], [rawCounters]), so a gap shows where it is. A run that fails it
 * says so at its head, and none of its cases is read.
 */
data class RawCalibration(
    val checks: List<RawCheck>,
    val apart: List<RawCheck> = emptyList(),
    val wowCounters: Map<String, Double> = emptyMap(),
    val rawCounters: Map<String, Double> = emptyMap(),
    val read: Boolean = true,
) {
    val passed: Boolean get() = read && checks.isNotEmpty() && checks.all { it.passed }

    fun toMap(): Map<String, Any?> = mapOf(
        "passed" to passed,
        "read" to read,
        "checks" to checks.map { it.toMap() },
        "apart" to apart.map { it.toMap() - "passed" },
        "wowPerCommandAll" to wowCounters,
        "rawPerCommandAll" to rawCounters,
    )
}

/** How a step runs: [timing]'s warm-up and measured seconds, and with [rate], that many units a second in all. */
data class Pace(val timing: Pair<Int, Int>, val rate: Double? = null)

/**
 * Runs [connections] workers ([worker] by index, each with its own connection) of [case]: [pace]'s warm-up unmeasured,
 * then its measured seconds, as fast as they go or at its rate. The store container's CPU ([cpu]) and the store's own
 * counters ([counters]) are read at the window's edges with every worker paused between flushes, so the units counted
 * and the counters' growth cover the same writes. With a rate, a flush's latency starts at its scheduled time, and a
 * flush that started more than [LATE_NANOS] after it counts as late.
 */
fun runStep(
    case: RawCase,
    connections: Int,
    pace: Pace,
    cpu: ContainerCpu,
    counters: () -> Map<String, Double>,
    ratios: (Map<String, Double>) -> Map<String, Double> = { emptyMap() },
    worker: (Int) -> RawWorker,
): RawStep {
    val (warmUpSeconds, seconds) = pace.timing
    val tally = Tally(connections, pace.rate?.let { (case.flushUnits * NANOS_PER_SECOND / it).toLong() })
    val workers = ArrayList<RawWorker>(connections)
    val threads = ArrayList<Thread>(connections)
    try {
        repeat(connections) { index -> workers += worker(index) }
        workers.forEachIndexed { index, w -> threads += Thread.ofPlatform().name("raw-$index").start { tally.loop(index, w) } }
        Thread.sleep(warmUpSeconds * MILLIS)
        // The window opens as the pause ends and closes as the next one begins: its reads are outside it.
        val (cpuBefore, before, startedAt) = tally.paused {
            val read = cpu.micros() to counters()
            tally.measuring.set(true)
            Triple(read.first, read.second, System.nanoTime())
        }
        Thread.sleep(seconds * MILLIS)
        val (endedAt, cpuAfter, after) = tally.paused {
            val ended = System.nanoTime()
            tally.measuring.set(false)
            Triple(ended, cpu.micros(), counters())
        }
        val elapsed = (endedAt - startedAt) / NANOS_PER_SECOND
        val units = tally.counted.sum()
        if (units == 0L) {
            tally.failure.get()?.let { throw IllegalStateException("${case.name} at $connections did nothing", it) }
        }
        val growth = after.mapValues { (key, value) -> value - (before[key] ?: 0.0) }
        val latencies = tally.latencies()
        return RawStep(
            case = case,
            connections = connections,
            rate = pace.rate,
            seconds = elapsed,
            units = units,
            flushes = tally.flushed.sum(),
            failures = tally.failed.sum(),
            refused = tally.refused.sum(),
            late = tally.late.sum(),
            p50FlushMs = percentile(latencies.copyOf(), P50) / NANOS_PER_MILLI,
            p99FlushMs = percentile(latencies, P99) / NANOS_PER_MILLI,
            storeCpuMsPerUnit = perUnit(cpuBefore, cpuAfter, units),
            perUnit = growth.mapValues { (_, delta) -> if (units > 0) delta / units else 0.0 },
            ratios = ratios(growth),
        )
    } finally {
        tally.stopping.set(true)
        threads.forEach { it.join() }
        workers.forEach { it.close() }
    }
}

/** The container's CPU per unit in milliseconds, from its micros at the window's edges; null without them. */
private fun perUnit(before: Long?, after: Long?, units: Long): Double? =
    if (before != null && after != null && units > 0) (after - before) / MICROS_PER_MILLI / units else null

/**
 * What a step's workers did, inside the window only: each one's units written and refused, flushes, failures, late
 * starts and flush latencies. With [interval], the flushes are paced so that between them the workers start one every
 * [interval] nanoseconds, each timed from when it was due.
 */
private class Tally(connections: Int, private val interval: Long?) {
    val measuring = AtomicBoolean(false)
    val stopping = AtomicBoolean(false)
    val counted = LongArray(connections)
    val refused = LongArray(connections)
    val flushed = LongArray(connections)
    val failed = LongArray(connections)
    val late = LongArray(connections)
    val failure = AtomicReference<Throwable?>(null)
    private val times = List(connections) { LongList() }
    private val next = AtomicLong(System.nanoTime())
    private val pausedNanos = AtomicLong()
    private val gate = ReentrantReadWriteLock()

    /**
     * Runs [block] with every worker between flushes: no flush is in flight, and none starts until it returns. A paced
     * schedule moves on by the pause, the due times not yet claimed and those a worker already holds alike (it adds
     * the pauses since it claimed one), so the pause makes no flush late and is in no flush's latency.
     */
    fun <T> paused(block: () -> T): T = gate.write {
        val started = System.nanoTime()
        try {
            block()
        } finally {
            val pause = System.nanoTime() - started
            pausedNanos.addAndGet(pause)
            if (interval != null) next.addAndGet(pause)
        }
    }

    /** Worker [index]'s loop: flush until the step stops, counting what lands inside the window. */
    fun loop(index: Int, worker: RawWorker) {
        while (!stopping.get()) {
            val turn = interval?.let { Turn(next.getAndAdd(it), pausedNanos.get()) }
            turn?.await()
            gate.read {
                val due = turn?.due()
                val started = System.nanoTime()
                val done = try {
                    worker.flush()
                } catch (error: Exception) {
                    if (measuring.get()) failed[index]++
                    failure.compareAndSet(null, error)
                    null
                }
                if (measuring.get() && done != null) count(index, done, due, started)
            }
        }
    }

    private fun count(index: Int, done: Flushed, due: Long?, started: Long) {
        counted[index] += done.written.toLong()
        refused[index] += done.refused.toLong()
        flushed[index]++
        if (due != null && started - due > LATE_NANOS) late[index]++
        times[index].add(System.nanoTime() - (due ?: started))
    }

    /** A paced flush's turn: its [claimed] due time, moved on by every pause since ([pausedAt] the pauses then). */
    private inner class Turn(private val claimed: Long, private val pausedAt: Long) {
        fun due(): Long = claimed + pausedNanos.get() - pausedAt

        /** Waits until the turn is due, however far the pauses meanwhile move it. */
        fun await() {
            while (true) {
                val wait = due() - System.nanoTime()
                if (wait <= 0) return
                LockSupport.parkNanos(wait)
            }
        }
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

/**
 * The aggregates one worker of a case writes: every [of]-th of [versions]' aggregates, by [index], taken in turn so a
 * flush holds distinct aggregates. Their versions are the case's ([versions], by aggregate), carried from one step to
 * the next, so a later step goes on from the versions an earlier one wrote.
 */
class Aggregates(private val versions: IntArray, index: Int, of: Int) {
    /** A command of aggregate number [number]: its [id] and the [version] it writes. */
    data class Command(val number: Int, val version: Int) {
        val id: String get() = idOf(number)
    }

    companion object {
        const val ID_PREFIX = "acc-"
        const val ID_DIGITS = 7

        /** Aggregate number [number]'s id, of the load generator's form (`acc-`, seven digits), as Wow's run has. */
        fun idOf(number: Int): String = ID_PREFIX + number.toString().padStart(ID_DIGITS, '0')
    }

    private val owned = (index until versions.size step of).toList()
    private var next = 0

    init {
        require(owned.isNotEmpty()) { "Worker $index of $of owns none of ${versions.size} aggregates." }
    }

    /** The next aggregate, at the version it is at. */
    fun next(): Command = owned[next++ % owned.size].let { Command(it, versions[it]) }

    /** The next [n] aggregates, each moved one version higher. */
    fun take(n: Int): List<Command> = List(n) {
        val number = owned[next++ % owned.size]
        versions[number]++
        Command(number, versions[number])
    }
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
            "wowShape" to step.case.wowShape,
            "ceiling" to step.case.ceiling,
            "connections" to step.connections,
            "pool" to step.pool,
            "rate" to step.rate,
            "seconds" to step.seconds,
            "units" to step.units,
            "flushes" to step.flushes,
            "failures" to step.failures,
            "refused" to step.refused,
            "late" to step.late,
            "read" to step.read,
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
            mapOf(
                "store" to store,
                "ceilings" to ceilingsOf(steps, calibration.passed).mapValues { (_, step) -> ceilingMap(step) },
                "steps" to rows,
            ),
    )
}

/**
 * The ceilings a figure of Wow's is compared with, by what it is compared with ([RawCase.ceiling]): of each, the
 * candidate step that did the most commands a second. A candidate is a step of Wow's own shape, unpaced and read, with
 * no snapshot its writers failed; a committed one only when its window's saves kept up with its appends (at least
 * [WowShape.SAVES_KEPT] of them). None from an uncalibrated run ([calibrated]).
 */
fun ceilingsOf(steps: List<RawStep>, calibrated: Boolean): Map<String, RawStep> {
    if (!calibrated) return emptyMap()
    return steps.filter { step ->
        step.case.ceiling != null && step.read && step.rate == null &&
            (step.perUnit[WowShape.SHAPE_SAVE_FAILED] ?: 0.0) == 0.0 &&
            (step.case.ceiling != RawCase.COMMITTED || (step.ratios[WowShape.SAVES_PER_APPEND] ?: 0.0) >= WowShape.SAVES_KEPT)
    }.groupBy { it.case.ceiling!! }.mapValues { (_, candidates) -> candidates.maxBy { it.unitsPerSecond } }
}

private fun ceilingMap(step: RawStep): Map<String, Any?> = mapOf(
    "case" to step.case.name,
    "connections" to step.connections,
    "unitsPerSecond" to step.unitsPerSecond,
)

/** [value] as JSON text: maps, lists, numbers (a non-finite one null), booleans, null and strings. */
fun jsonOf(value: Any?): String = when (value) {
    null -> "null"
    is Double -> if (value.isFinite()) value.toString() else "null"
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonOf(k.toString())}:${jsonOf(v)}" }
    is List<*> -> value.joinToString(",", "[", "]") { jsonOf(it) }
    else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

/** How late a paced flush may start before it counts as late. */
const val LATE_NANOS: Long = 1_000_000L

private const val PERCENT = 100.0
private const val P50 = 50.0
private const val P99 = 99.0
private const val MILLIS = 1_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val NANOS_PER_MILLI = 1_000_000.0
private const val MICROS_PER_MILLI = 1_000.0
private const val DOCKER_SECONDS = 10L
private const val INITIAL = 1_024
