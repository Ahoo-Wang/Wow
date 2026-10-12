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

package me.ahoo.wow.leases

/** A membership change: the first start of a repetition, a node joining, leaving gracefully, or killed. */
enum class Change { START, JOIN, LEAVE, KILL }

/** The commands and cluster events counted over some seconds; latencies over the commands sent in them. */
data class Tally(
    val offset: Int? = null,
    val sent: Long = 0,
    val succeeded: Long = 0,
    val duplicates: Long = 0,
    val contended: Long = 0,
    val unknown: Long = 0,
    val otherFailed: Long = 0,
    val errors: Long = 0,
    val conflicts: Long = 0,
    val nonOwner: Long = 0,
    val forwards: Long = 0,
    val forwardFailures: Long = 0,
    val lostLeases: Long = 0,
    val p99Millis: Double = Double.NaN,
    val maxMillis: Double = Double.NaN,
) {
    val failed: Long get() = contended + unknown + otherFailed + errors

    companion object {
        /** Sums [seconds] (epoch seconds) of [timeline]. */
        fun of(timeline: Timeline, seconds: LongRange, offset: Int? = null): Tally {
            val present = seconds.mapNotNull { timeline.second(it) }
            val latencies = present.flatMap { it.latencies }.map { it / MICROS_PER_MILLI }
            return Tally(
                offset = offset,
                sent = present.sumOf { it.sent.sum() },
                succeeded = present.sumOf { it.succeeded.sum() },
                duplicates = present.sumOf { it.duplicates.sum() },
                contended = present.sumOf { it.contended.sum() },
                unknown = present.sumOf { it.unknown.sum() },
                otherFailed = present.sumOf { it.otherFailed.sum() },
                errors = present.sumOf { it.errors.sum() },
                conflicts = present.sumOf { it.conflicts.sum() },
                nonOwner = present.sumOf { it.nonOwner.sum() },
                forwards = present.sumOf { it.forwards.sum() },
                forwardFailures = present.sumOf { it.forwardFailures.sum() },
                lostLeases = present.sumOf { it.lostLeases.sum() },
                p99Millis = Stats.quantile(latencies, P99),
                maxMillis = latencies.maxOrNull() ?: Double.NaN,
            )
        }

        private const val MICROS_PER_MILLI = 1_000.0
        private const val P99 = 0.99
    }
}

/**
 * One membership change and what it cost. Times are milliseconds after the change began ([startedAt]); a null time
 * means the condition was not reached within the step timeout.
 *
 * - [convergedMillis]: every partition has a live lease held by its HRW owner among the expected members, every
 *   member holds and accepts exactly its HRW share, and every member's route table lists the expected members and
 *   owners. The other times are the first moment each part held on its own.
 * - [moved]: partitions whose lease holder differs before and after; [predicted]: the share HRW gives the node that
 *   joined or went; [extraMoves]: moved minus predicted (0 when HRW moves only what it must); [movedSet]: which.
 * - [unownedPartitionSeconds]: the integral over the step of the partitions without a live lease (no subscription
 *   reads them meanwhile).
 * - [entries] / [repeats]: log entries the projector handled for the first time / again between this change and the
 *   next; [movedEntries] / [movedRepeats]: the same for the partitions that moved.
 * - [window]: the commands sent from the change until a second after convergence; [baseline]: the three seconds
 *   before the change; [profile]: second by second around it.
 */
data class StepResult(
    val backend: String,
    val config: String,
    val partitions: Int,
    val repetition: Int,
    val change: Change,
    val from: Int,
    val to: Int,
    val node: String,
    val startedAt: Long,
    val convergedMillis: Long?,
    val membershipMillis: Long?,
    val leasesMillis: Long?,
    val heldMillis: Long?,
    val routesMillis: Long?,
    val moved: Int,
    val predicted: Int,
    val extraMoves: Int,
    val maxUnowned: Int,
    val unownedPartitionSeconds: Double,
    val entries: Int = 0,
    val repeats: Int = 0,
    val movedEntries: Int = 0,
    val movedRepeats: Int = 0,
    val window: Tally? = null,
    val baseline: Tally? = null,
    val profile: List<Tally> = emptyList(),
    val movedSet: Set<Int> = emptySet(),
) {
    val transition: String get() = "$from→$to"
}

/** The latency of one lease-store operation over a series. */
data class LeaseOperation(
    val operation: String,
    val calls: Int,
    val perSecond: Double,
    val p50Millis: Double,
    val p99Millis: Double,
    val maxMillis: Double,
)

/** The lease-store calls of one series (backend, configuration, partitions). */
data class LeaseSeries(
    val backend: String,
    val config: String,
    val partitions: Int,
    val seconds: Double,
    val failures: Long,
    val operations: List<LeaseOperation>,
)
