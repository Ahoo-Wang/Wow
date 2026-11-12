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
import java.util.concurrent.atomic.AtomicReference

/**
 * The write shapes a raw run measures, each the store alone doing what Wow asks of it for a command, or a variant of it
 * that prices a candidate (RawStoreBench):
 *
 * - [TODAY]: the log's append and the snapshots' save as separate autocommitted statements, the snapshots one statement
 *   per partition's group, as Wow writes them;
 * - [MERGED]: the snapshots of a flush in one statement;
 * - [IN_TRANSACTION] and [IN_TRANSACTION_MERGED]: the append and the saves in one transaction (one commit);
 * - [LOG_ONLY]: the append alone, the floor with no snapshot written;
 * - [POINT_READ]: one snapshot read by its key.
 */
enum class Shape(val key: String) {
    TODAY("today"),
    MERGED("merged"),
    IN_TRANSACTION("in-transaction"),
    IN_TRANSACTION_MERGED("in-transaction-merged"),
    LOG_ONLY("log-only"),
    POINT_READ("point-read"),
    ;

    val writesSnapshots: Boolean get() = this != LOG_ONLY && this != POINT_READ
    val mergesSnapshots: Boolean get() = this == MERGED || this == IN_TRANSACTION_MERGED
    val oneTransaction: Boolean get() = this == IN_TRANSACTION || this == IN_TRANSACTION_MERGED

    companion object {
        fun of(key: String): Shape =
            entries.firstOrNull { it.key == key } ?: error("Unknown shape $key: ${entries.joinToString { it.key }}.")
    }
}

/**
 * The snapshot statements of a flush of [commands]: one of all of them when [merged], else groups of at most
 * [perStatement], as Wow's saves by partition give about [perStatement] snapshots a statement.
 */
fun snapshotGroups(commands: Int, perStatement: Int, merged: Boolean): List<Int> {
    require(commands >= 1 && perStatement >= 1) { "A flush holds a command, a statement a snapshot." }
    if (merged) return listOf(commands)
    return List((commands + perStatement - 1) / perStatement) { minOf(perStatement, commands - it * perStatement) }
}

/** The [percentile] (0 to 100) of [values], sorted in place; 0 when there are none. */
fun percentile(values: LongArray, percentile: Double): Long {
    if (values.isEmpty()) return 0
    values.sort()
    val rank = (percentile / PERCENT * values.size).toInt().coerceIn(0, values.size - 1)
    return values[rank]
}

/** `usage_usec` of a cgroup v2 `cpu.stat`; null when the text has none. */
fun cgroupUsageMicros(cpuStat: String): Long? =
    cpuStat.lineSequence().map { it.trim().split(' ') }.firstOrNull { it.firstOrNull() == "usage_usec" }
        ?.getOrNull(1)?.toLongOrNull()

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

/** One worker of a step: what it does once a flush (and returns how many commands or reads it was), and its close. */
interface RawWorker : AutoCloseable {
    fun flush(): Int
}

/** A step's result: its shape and variant, its connections, and what it did and cost per command. */
data class RawStep(
    val shape: String,
    val variant: String,
    val connections: Int,
    val seconds: Double,
    val commands: Long,
    val flushes: Long,
    val failures: Long,
    val p50FlushMs: Double,
    val p99FlushMs: Double,
    val storeCpuMsPerCommand: Double?,
    val store: Map<String, Double>,
) {
    val commandsPerSecond: Double get() = if (seconds > 0) commands / seconds else 0.0
}

/**
 * Runs [connections] workers ([worker] by index, each with its own connection) for [warmUpSeconds] unmeasured, then
 * [seconds] measured: the commands and flushes done inside the window, their flush latencies, the store container's
 * CPU ([cpu]) and the store's own counters ([counters], cumulative, read at the window's edges) per command.
 */
fun runStep(
    shape: String,
    variant: String,
    connections: Int,
    warmUpSeconds: Int,
    seconds: Int,
    cpu: ContainerCpu,
    counters: () -> Map<String, Double>,
    worker: (Int) -> RawWorker,
): RawStep {
    val tally = Tally(connections)
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
    val commands = tally.counted.sum()
    if (commands == 0L) {
        tally.failure.get()?.let { throw IllegalStateException("$shape $variant at $connections did nothing", it) }
    }
    val latencies = tally.latencies()
    return RawStep(
        shape = shape,
        variant = variant,
        connections = connections,
        seconds = elapsed,
        commands = commands,
        flushes = tally.flushed.sum(),
        failures = tally.failed.sum(),
        p50FlushMs = percentile(latencies.copyOf(), P50) / NANOS_PER_MILLI,
        p99FlushMs = percentile(latencies, P99) / NANOS_PER_MILLI,
        storeCpuMsPerCommand = perCommand(cpuBefore, cpuAfter, commands),
        store = after.mapValues { (key, value) -> if (commands > 0) (value - (before[key] ?: 0.0)) / commands else 0.0 },
    )
}

/** The container's CPU per command in milliseconds, from its micros at the window's edges; null without them. */
private fun perCommand(before: Long?, after: Long?, commands: Long): Double? =
    if (before != null && after != null && commands > 0) (after - before) / MICROS_PER_MILLI / commands else null

/** What a step's workers did: each one's commands, flushes, failures and flush latencies, inside the window only. */
private class Tally(connections: Int) {
    val measuring = AtomicBoolean(false)
    val stopping = AtomicBoolean(false)
    val counted = LongArray(connections)
    val flushed = LongArray(connections)
    val failed = LongArray(connections)
    val failure = AtomicReference<Throwable?>(null)
    private val times = List(connections) { LongList() }

    /** Worker [index]'s loop: flush until the step stops, counting what lands inside the window. */
    fun loop(index: Int, worker: RawWorker) {
        while (!stopping.get()) {
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

/** [steps] as raw.json's text: the run's settings and each step's result. */
fun rawJson(settings: Map<String, Any?>, steps: List<RawStep>): String {
    fun value(it: Any?): String = when (it) {
        null -> "null"
        is Number -> if (it is Double && !it.isFinite()) "null" else it.toString()
        is Boolean -> it.toString()
        is Map<*, *> -> it.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${value(v)}" }
        is List<*> -> it.joinToString(",", "[", "]") { item -> value(item) }
        else -> "\"" + it.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
    val rows = steps.map { step ->
        mapOf(
            "shape" to step.shape,
            "variant" to step.variant,
            "connections" to step.connections,
            "seconds" to step.seconds,
            "commands" to step.commands,
            "flushes" to step.flushes,
            "failures" to step.failures,
            "commandsPerSecond" to step.commandsPerSecond,
            "p50FlushMs" to step.p50FlushMs,
            "p99FlushMs" to step.p99FlushMs,
            "storeCpuMsPerCommand" to step.storeCpuMsPerCommand,
            "perCommand" to step.store,
        )
    }
    return value(mapOf("schema" to "wow-e2e-bench/raw/1") + settings + mapOf("steps" to rows))
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
