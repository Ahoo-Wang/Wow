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

package me.ahoo.wow.runtime.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.spi.subscription.LeaseStore
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Starting and stopping the partitions one [PartitionOwnership] holds, around their leases: [acquire] takes a
 * partition's lease, makes its gate and starts its runtimes, intake and timers; [handOff] stops them, in order, and
 * releases the lease; [dropLost] stops those whose lease was lost; [stop] stops the runtimes, the intake and the timers
 * whole.
 *
 * It alone puts, removes and clears the entries of [gates], which the ownership builds and passes it; the ownership and
 * its [LeaseRenewals] only read them, and flip a gate's own fields.
 *
 * Each step it finishes for one partition, an acquisition started or given up, a hand-off or a drop, is reported to
 * [progressed]: the ownership takes a reconcile with no step for its reconcile timeout as stuck. A lease it takes while
 * the ownership is [fenced] is marked lost at once, so it is never renewed.
 */
internal class PartitionRuns(
    private val group: LeaseGroup,
    private val node: String,
    private val leases: LeaseStore,
    private val options: ClusterOptions,
    private val timeSource: TimeSource,
    private val unrenewedLimit: Duration,
    private val gates: MutableMap<Int, PartitionGate>,
    /** The runtimes reading the partitions held: the context's (or the topic's), then its other groups'. */
    private val runtimes: List<SubscriptionRuntime>,
    private val intake: SubscriptionRuntime?,
    private val timers: List<TimerRunner>,
    private val partitionChanged: (Int) -> Unit,
    /** Told each step finished for one partition. */
    private val progressed: () -> Unit = {},
    /** Whether the ownership is fenced: a lease taken now is not renewed. */
    private val fenced: () -> Boolean = { false },
) {
    /**
     * Not cancellable: a lease acquired is either tracked by a gate or released. The partition's subscriptions read once
     * [reads] completes, when given.
     */
    suspend fun acquire(partition: Int, reads: Job?) = try {
        start(partition, reads)
    } finally {
        progressed()
    }

    private suspend fun start(partition: Int, reads: Job?) = withContext(NonCancellable) {
        val asked = timeSource.markNow()
        leases.acquire(group.name, partition, node, options.leaseTtl) ?: return@withContext
        // Tracked from now on, so the renewals keep the lease while the partition starts; it takes no command yet.
        val gate = PartitionGate(asked, unrenewedLimit)
        if (fenced()) {
            // Taken by a reconcile the node is fenced against: never renewed, so it expires for the others.
            gate.lost = true
        }
        gates[partition] = gate
        try {
            // Another node may have committed to the partition's aggregates since this one last cached them.
            partitionChanged(partition)
            runtimes.forEach { it.startPartition(partition, reads) }
            intake?.startPartition(partition, reads)
            timers.forEach { it.startPartition(partition) }
        } catch (cancelled: CancellationException) {
            // A start cancelled from within takes the same way out, then goes on up: no gate is left behind.
            failedStart(partition, gate, cancelled)
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            failedStart(partition, gate, failure)
            return@withContext
        }
        gate.started()
        if (gate.lost) {
            // Lost while it started: the next reconcile stops it, as any partition whose lease was lost.
            logger.warn { "$node lost the lease of partition $partition of $group while starting it." }
        } else {
            logger.info { "$node acquired partition $partition of $group." }
        }
    }

    /**
     * A start of [partition] that [failure] ended: logged, its timers, intake and subscriptions stopped as a hand-off
     * stops them, whichever had started, its lease released and its gate removed.
     */
    private suspend fun failedStart(partition: Int, gate: PartitionGate, failure: Exception) {
        logger.warn(failure) { "$node failed to start partition $partition of $group; releasing it." }
        stopTimers(partition)
        stopIntake(partition)
        stopSubscriptions(partition)
        quietly("release partition $partition", LEASE_EXPIRES) { leases.release(group.name, partition, node) }
        gates.remove(partition, gate)
    }

    /** Hands off [handed] concurrently, each in order: stop accepting, drain, save checkpoints, release. */
    suspend fun handOff(handed: List<Int>) = coroutineScope {
        handed.map { partition ->
            async {
                val gate = gates[partition] ?: return@async
                gate.accepting = false
                stopTimers(partition)
                // The intake stops taking the partition's commands, settles those in flight and saves its checkpoint.
                stopIntake(partition)
                withTimeoutOrNull(options.drainTimeout) { gate.inFlight.awaitZero() } ?: logger.warn {
                    "$node handed off partition $partition of $group with commands still in flight."
                }
                stopSubscriptions(partition)
                quietly("release partition $partition", LEASE_EXPIRES) { leases.release(group.name, partition, node) }
                quietly(
                    "drop the cached states of partition $partition",
                    STATES_DROPPED_AGAIN
                ) { partitionChanged(partition) }
                gates.remove(partition, gate)
                logger.info { "$node handed off partition $partition of $group." }
                progressed()
            }
        }.awaitAll()
    }

    /** Stops the partitions whose lease expired before it was renewed; another node may own them now. */
    suspend fun dropLost() {
        gates.filterValues { it.lost }.forEach { (partition, gate) ->
            stopTimers(partition)
            stopIntake(partition)
            stopSubscriptions(partition)
            partitionChanged(partition)
            gates.remove(partition, gate)
            progressed()
        }
    }

    private suspend fun stopTimers(partition: Int) = timers.forEach { runner ->
        quietly("stop the timers of partition $partition", OTHERS_STOPPED) { runner.stopPartition(partition) }
    }

    private suspend fun stopIntake(partition: Int) =
        quietly("stop the command intake of partition $partition", OTHERS_STOPPED) { intake?.stopPartition(partition) }

    private suspend fun stopSubscriptions(partition: Int) = coroutineScope {
        val action = "stop the subscriptions of partition $partition"
        runtimes.forEach { launch { quietly(action, OTHERS_STOPPED) { it.stopPartition(partition) } } }
    }

    /** Stops the timers, the intake and the subscriptions, each failure logged: a stop leaves none of them running. */
    suspend fun stop() {
        timers.forEach { quietly("stop the timers of $group", REST_STOPPED) { it.stop() } }
        quietly("stop the command intake of $group", REST_STOPPED) { intake?.stop() }
        runtimes.forEach { quietly("stop the subscriptions of $group", REST_STOPPED) { it.stop() } }
    }

    /** Forgets every partition at once, as a crash would: the gates are dropped, nothing is stopped or released. */
    fun clear() {
        gates.clear()
    }

    private suspend fun quietly(action: String, then: String, block: suspend () -> Unit) =
        runLogged(node, group, action, then, block)

    private companion object {
        /** What follows a stop of one of a partition's parts that failed. */
        const val OTHERS_STOPPED = "the partition's other parts are stopped all the same"

        /** What follows a stop of the whole runtimes, intake or timers that failed. */
        const val REST_STOPPED = "the others are stopped all the same"

        /** What follows a release that failed. */
        const val LEASE_EXPIRES = "its lease expires on its own"

        /** What follows a failed drop of a partition's cached states. */
        const val STATES_DROPPED_AGAIN = "they are dropped again before the partition is taken back"
    }
}

/** What follows a round of a loop that failed. */
internal const val NEXT_ROUND = "retrying on the next round"

/**
 * Runs [block], [action] of [node] in [group]; a failure, never a cancellation, is logged at WARN with what follows it
 * ([then]) and not thrown: the next round of a loop, or what happens all the same on the way out.
 */
@Suppress("TooGenericExceptionCaught") // Whatever failed, what follows it is the caller's to say.
internal suspend fun runLogged(
    node: String,
    group: LeaseGroup,
    action: String,
    then: String,
    block: suspend () -> Unit
) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(failure) { "$node failed to $action in $group; $then." }
    }
}

// The lines moved from the ownership keep its logger's name.
private val logger = KotlinLogging.logger(PartitionOwnership::class.java.name)
