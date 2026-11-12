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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The fence of one [PartitionOwnership]'s lease [group] (RT-13): whether the reconcile running now is stuck, and what
 * the node does while it is.
 *
 * A reconcile is stuck when it finishes no step for one partition ([stepFinished]: an acquisition, a hand-off, a drop)
 * for [ClusterOptions.reconcileTimeout]; a long reconcile that keeps finishing steps is not. [check], every heartbeat,
 * then fences the group: the node leaves the members ([ServiceMembership.stuck]); every partition of [gates] stops
 * taking commands at once; each one's commands in flight end, or [ClusterOptions.drainTimeout] passes, and only then is
 * its gate marked lost, so its lease is left unrenewed, without a release, to expire within [ClusterOptions.leaseTtl];
 * and the partitions' [timers] are stopped, each stop bounded by the drain timeout. So there is never a holder here
 * and an acquirer elsewhere at once. A lease taken while [fenced] is the caller's to mark lost ([PartitionRuns]).
 *
 * Once the reconcile finishes a step again, [check] recovers: the node is a member again, and the reconciler is woken
 * ([wake]), whose next round drops the partitions the fence gave up. Each fence and recovery is observed and logged at
 * WARN.
 *
 * It reads [gates], which the ownership builds and its runs alone change, and flips only a gate's own fields.
 */
internal class GroupFence(
    private val group: LeaseGroup,
    private val node: String,
    private val options: ClusterOptions,
    private val timeSource: TimeSource,
    private val gates: Map<Int, PartitionGate>,
    private val membership: ServiceMembership,
    private val timers: List<TimerRunner>,
    private val observer: Observer,
    private val wake: () -> Unit,
) {
    /** When the reconcile running now last finished a step for one partition, or started; null between reconciles. */
    @Volatile
    private var stepped: TimeMark? = null

    /** When the group was fenced; null while it is not. */
    @Volatile
    private var fencedAt: TimeMark? = null

    /** Whether the group is fenced: a lease taken now is never renewed. */
    val fenced: Boolean get() = fencedAt != null

    /** How long the reconcile running now has finished no step; null between reconciles. */
    val stalledFor: Duration? get() = stepped?.elapsedNow()

    /** A reconcile starts: its first step is due within the reconcile timeout. */
    fun reconcileStarted() {
        stepped = timeSource.markNow()
    }

    /** The reconcile running now finished a step for one partition; a step outside a reconcile counts for none. */
    fun stepFinished() {
        if (stepped != null) {
            stepped = timeSource.markNow()
        }
    }

    /** The reconcile ended. */
    fun reconcileEnded() {
        stepped = null
    }

    /**
     * Fences the group while the reconcile running now has finished no step for the reconcile timeout, and recovers
     * once it moves again or has ended. The waits of a fence run in [upkeep]; without one, the gates are lost at once.
     */
    suspend fun check(upkeep: CoroutineScope?) {
        val stalled = stepped?.elapsedNow()
        if (stalled != null && stalled > options.reconcileTimeout) {
            if (!fenced) {
                fence(stalled, upkeep)
            }
            return
        }
        val since = fencedAt ?: return
        membership.stuck(group.name, false)
        fencedAt = null
        val fencedFor = since.elapsedNow()
        logger.warn {
            "$node finished a step of its reconcile of $group again after $fencedFor fenced; it is back among the " +
                "members, and its next reconcile stops the partitions it gave up and takes its share back."
        }
        observer.observed(ObservedEvent.OwnershipRecovered(group.name, fencedFor.inWholeMilliseconds))
        wake()
    }

    private suspend fun fence(stalled: Duration, upkeep: CoroutineScope?) {
        // Set before the walk: a gate put after it sees the fence when its start asks.
        fencedAt = timeSource.markNow()
        logger.warn {
            "$node has finished no step of its reconcile of $group for $stalled; it leaves the members and gives up " +
                "its leases: its partitions take no command from now on, and each lease expires within " +
                "${options.leaseTtl} once its commands in flight end, for the others to take. Its subscriptions keep " +
                "running until the reconcile moves again; one still running after wow.health.liveness.stall-timeout " +
                "fails the node's liveness, so its orchestrator restarts it."
        }
        observer.observed(ObservedEvent.OwnershipFenced(group.name, stalled.inWholeMilliseconds))
        gates.forEach { (partition, gate) ->
            gate.accepting = false
            if (upkeep == null) {
                gate.lost = true
            } else {
                upkeep.launch { giveUp(partition, gate) }
                upkeep.launch { stopTimers(partition) }
            }
        }
        membership.stuck(group.name, true)
    }

    /** Leaves [gate]'s lease unrenewed once its commands in flight end, or the drain timeout passes. */
    private suspend fun giveUp(partition: Int, gate: PartitionGate) {
        withTimeoutOrNull(options.drainTimeout) { gate.inFlight.awaitZero() } ?: logger.warn {
            "$node gives up the lease of partition $partition of $group with commands still in flight."
        }
        gate.lost = true
        // Closed again: a start that ended meanwhile opened it.
        gate.accepting = false
    }

    /** Stops [partition]'s timers, within the drain timeout: past it, the next reconcile stops them. */
    private suspend fun stopTimers(partition: Int) {
        withTimeoutOrNull(options.drainTimeout) {
            timers.forEach { runner ->
                runLogged(node, group, "stop the timers of partition $partition", TIMERS_LATER) {
                    runner.stopPartition(partition)
                }
            }
        } ?: logger.warn { "$node did not stop the timers of partition $partition of $group within the drain timeout." }
    }

    private companion object {
        // The lines moved from the ownership keep its logger's name.
        val logger = KotlinLogging.logger(PartitionOwnership::class.java.name)

        /** What follows a stop of a fenced partition's timers that failed. */
        const val TIMERS_LATER = "the next reconcile stops them"
    }
}
