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

package me.ahoo.wow.routing.stage2

import kotlin.math.ceil

/** The commands due within one window of a run, and how they ended. */
data class WindowStats(
    val seconds: Double,
    val commands: Int,
    /** Commits answered within the window, per second. */
    val throughput: Double,
    val p50Millis: Double,
    val p99Millis: Double,
    val p999Millis: Double,
    /** Unbounded when a command was never answered. */
    val maxMillis: Double,
    /** The median of each second's p99: the tail without isolated stalls. */
    val steadyP99Millis: Double,
    val worstSecondP99Millis: Double,
    val outcomes: Map<Outcome, Int>,
    /** Unanswered commands. */
    val incomplete: Int,
    /** The failures by code (`wow.command.contended`, `ConnectException`, ...). */
    val failureCodes: Map<String, Int>,
)

/** One second of a run, from the start of the measured window. */
data class SecondStats(val second: Int, val sent: Int, val committed: Int, val failures: Int, val p99Millis: Double)

/** One partition of the killed owner, from the moment it was killed. */
data class PartitionWindow(
    val partition: Int,
    /** Commands to the partition due after the kill and received by a live node. */
    val commands: Int,
    val failures: Int,
    /** The first commit answered after the kill. */
    val firstCommitMillis: Double?,
    /**
     * Until when commands to the partition failed: the answer of the last failed one, 0 when none failed. The
     * unavailability window.
     */
    val unavailableMillis: Double,
    /**
     * When the partition was back to normal: the due time of the first command of the first [RECOVERED_RUN]
     * consecutive ones that all committed within the run's steady p999; null when it never was.
     */
    val recoveredMillis: Double?,
    val maxLatencyMillis: Double,
    /** When a surviving node held the partition's lease, as the lease table showed it (polled every 100 ms). */
    val leaseTakeoverMillis: Double?,
    val newOwner: String?,
) {
    companion object {
        const val RECOVERED_RUN = 10
    }
}

/** An owner killed mid-run (`SIGKILL`) and what its partitions saw. */
data class KillStats(
    val victim: String,
    /** The seconds after the start of the measured window. */
    val atSecond: Double,
    val partitions: List<Int>,
    /** Commands the victim had received and not answered when it died: a load balancer's loss, not a partition's. */
    val lostWithVictim: Int,
    /** Commands due before the kill, sent to a live node, that failed after it: forwards in flight to the victim. */
    val inFlightFailures: Int,
    /** Every command due after the kill, all partitions. */
    val afterKill: WindowStats,
    val maxUnavailableMillis: Double,
    val medianUnavailableMillis: Double,
    val maxRecoveredMillis: Double?,
    val medianRecoveredMillis: Double?,
    val maxLeaseTakeoverMillis: Double?,
    val perPartition: List<PartitionWindow>,
)

/** The log against what the client was told: no commit lost, none twice. */
data class Audit(
    val committedByClient: Int,
    val requestsInLog: Int,
    /** Committed per the client but not in the log. */
    val lost: Int,
    /** In the log although the client was told it failed or got no answer: what a client retry would duplicate. */
    val committedButFailed: Int,
    /** Request ids with more than one entry in the log. */
    val duplicates: Int,
)

data class RunStats(
    val distribution: String,
    val router: String,
    val offeredRate: Double,
    val steady: WindowStats,
    val statements: StatementCounts,
    /** Stage 1's reads per command: an aggregate's stream (version, events, request id) and its snapshot. */
    val readsPerCommand: Double,
    val tailReadsPerCommand: Double,
    val statementsPerCommand: Double,
    /** CPU of the three node JVMs per command, in the measured window. */
    val nodeCpuMicrosPerCommand: Double,
    val kill: KillStats?,
    val audit: Audit,
    val timeline: List<SecondStats>,
)

/** Computes the statistics of a [Recording]; times are `System.nanoTime` values. */
object Stage2Analysis {
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val P50 = 0.50
    private const val P99 = 0.99
    private const val P999 = 0.999

    private fun latency(recording: Recording, index: Int): Long =
        if (recording.done[index] < 0) Long.MAX_VALUE else recording.done[index] - recording.due[index]

    /** The [q] quantile of [latencies] in milliseconds, unanswered ones unbounded; NaN when there are none. */
    fun quantile(latencies: LongArray, q: Double): Double {
        if (latencies.isEmpty()) return Double.NaN
        val value = latencies[(ceil(q * latencies.size).toInt() - 1).coerceIn(0, latencies.size - 1)]
        return if (value == Long.MAX_VALUE) Double.POSITIVE_INFINITY else value / NANOS_PER_MILLI
    }

    /** The commands of [recording] due in [from, until) that [include] keeps. */
    fun window(recording: Recording, from: Long, until: Long, include: (Int) -> Boolean = { true }): WindowStats {
        val indices = (0 until recording.size).filter { recording.due[it] in from until until && include(it) }
        val latencies = indices.map { latency(recording, it) }.toLongArray().also { it.sort() }
        val seconds = (until - from).toDouble() / NANOS_PER_SECOND
        val committedInWindow = indices.count {
            recording.outcome[it] == Outcome.COMMITTED && recording.done[it] in from..until
        }
        val perSecond = indices.groupBy { ((recording.due[it] - from) / NANOS_PER_SECOND).toInt() }
            .values.filter { it.size > 1 }
            .map { second -> quantile(second.map { latency(recording, it) }.toLongArray().also { it.sort() }, P99) }
            .sorted()
        return WindowStats(
            seconds = seconds,
            commands = indices.size,
            throughput = committedInWindow / seconds,
            p50Millis = quantile(latencies, P50),
            p99Millis = quantile(latencies, P99),
            p999Millis = quantile(latencies, P999),
            maxMillis = quantile(latencies, 1.0),
            steadyP99Millis = perSecond.getOrElse(perSecond.size / 2) { Double.NaN },
            worstSecondP99Millis = perSecond.lastOrNull() ?: Double.NaN,
            outcomes = indices.mapNotNull { recording.outcome[it] }.groupingBy { it }.eachCount().toSortedMap(),
            incomplete = indices.count { recording.done[it] < 0 },
            failureCodes = indices.filter { recording.outcome[it]?.failure != false }
                .groupingBy { recording.code[it] ?: "unanswered" }.eachCount().toSortedMap(),
        )
    }

    /** Each second from [from] to [until]. */
    fun timeline(recording: Recording, from: Long, until: Long): List<SecondStats> {
        val bySecond = (0 until recording.size).filter { recording.due[it] in from until until }
            .groupBy { ((recording.due[it] - from) / NANOS_PER_SECOND).toInt() }
        return bySecond.toSortedMap().map { (second, indices) ->
            SecondStats(
                second = second,
                sent = indices.size,
                committed = indices.count { recording.outcome[it] == Outcome.COMMITTED },
                failures = indices.count { recording.outcome[it]?.failure != false },
                p99Millis = quantile(indices.map { latency(recording, it) }.toLongArray().also { it.sort() }, P99),
            )
        }
    }

    /**
     * What the partitions of [victim] (its index among the receivers) saw after it was killed at [killedAt]: commands
     * due from then on, received by the live nodes. [takeovers] holds, per partition, when and to whom its lease went.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // One flat computation per partition.
    fun kill(
        recording: Recording,
        victim: Int,
        victimId: String,
        partitions: List<Int>,
        killedAt: Long,
        windowStart: Long,
        until: Long,
        steadyP999Millis: Double,
        takeovers: Map<Int, Pair<Long, String>>,
    ): KillStats {
        val threshold = (steadyP999Millis * NANOS_PER_MILLI).toLong()
        val afterKill = (0 until recording.size).filter { recording.due[it] >= killedAt && recording.receiver[it] != victim }
        val perPartition = partitions.sorted().map { partition ->
            val commands = afterKill.filter { recording.partition[it] == partition }.sortedBy { recording.due[it] }
            val failed = commands.filter { recording.outcome[it]?.failure != false }
            val ok = commands.map { recording.outcome[it] == Outcome.COMMITTED && latency(recording, it) <= threshold }
            val recovered = (0..commands.size - PartitionWindow.RECOVERED_RUN).firstOrNull { start ->
                (start until start + PartitionWindow.RECOVERED_RUN).all { ok[it] }
            }
            PartitionWindow(
                partition = partition,
                commands = commands.size,
                failures = failed.size,
                firstCommitMillis = commands.filter { recording.outcome[it] == Outcome.COMMITTED }
                    .minOfOrNull { recording.done[it] }?.let { (it - killedAt) / NANOS_PER_MILLI },
                unavailableMillis = failed.maxOfOrNull {
                    if (recording.done[it] < 0) until else recording.done[it]
                }?.let { (it - killedAt) / NANOS_PER_MILLI } ?: 0.0,
                recoveredMillis = recovered?.let { (recording.due[commands[it]] - killedAt) / NANOS_PER_MILLI },
                maxLatencyMillis = commands.maxOfOrNull { latency(recording, it) }
                    ?.let { if (it == Long.MAX_VALUE) Double.POSITIVE_INFINITY else it / NANOS_PER_MILLI } ?: 0.0,
                leaseTakeoverMillis = takeovers[partition]?.let { (it.first - killedAt) / NANOS_PER_MILLI },
                newOwner = takeovers[partition]?.second,
            )
        }
        val unavailable = perPartition.map { it.unavailableMillis }.sorted()
        val recovered = perPartition.map { it.recoveredMillis ?: Double.POSITIVE_INFINITY }.sorted()
        return KillStats(
            victim = victimId,
            atSecond = (killedAt - windowStart).toDouble() / NANOS_PER_SECOND,
            partitions = partitions.sorted(),
            lostWithVictim = (0 until recording.size).count {
                recording.receiver[it] == victim && recording.due[it] < killedAt &&
                    (recording.done[it] < 0 || recording.done[it] >= killedAt) && recording.outcome[it]?.failure != false
            },
            inFlightFailures = (0 until recording.size).count {
                recording.receiver[it] != victim && recording.due[it] < killedAt &&
                    (recording.done[it] < 0 || recording.done[it] >= killedAt) && recording.outcome[it]?.failure != false
            },
            afterKill = window(recording, killedAt, until) { recording.receiver[it] != victim },
            maxUnavailableMillis = unavailable.lastOrNull() ?: 0.0,
            medianUnavailableMillis = unavailable.getOrElse(unavailable.size / 2) { 0.0 },
            maxRecoveredMillis = recovered.lastOrNull()?.takeIf { it.isFinite() },
            medianRecoveredMillis = recovered.getOrNull(recovered.size / 2)?.takeIf { it.isFinite() },
            maxLeaseTakeoverMillis = perPartition.map { it.leaseTakeoverMillis }
                .takeIf { all -> all.isNotEmpty() && all.all { it != null } }?.maxOf { it!! },
            perPartition = perPartition,
        )
    }

    /** The log's entries of the run ([inLog], by request id `<run>-<index>`) against the client's outcomes. */
    fun audit(recording: Recording, runId: String, inLog: Map<String, Int>): Audit {
        val committed = (0 until recording.size).filter { recording.outcome[it] == Outcome.COMMITTED }
        val failed = (0 until recording.size).filter { recording.outcome[it] != Outcome.COMMITTED }
        return Audit(
            committedByClient = committed.size,
            requestsInLog = inLog.size,
            lost = committed.count { "$runId-$it" !in inLog },
            committedButFailed = failed.count { "$runId-$it" in inLog },
            duplicates = inLog.values.count { it > 1 },
        )
    }
}
