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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.subscription.LeaseStore
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One renewal round of [node] in [group]: the [leases] it held, of which [lost] were found already expired or
 * taken, or went unrenewed for `leaseTtl − renewInterval`, and [failed] threw, and how long the round
 * took. A round longer than `leaseTtl − renewInterval` lets the node's own leases expire between renewals.
 */
@InternalWowApi
data class RenewalRound(
    val group: LeaseGroup,
    val node: String,
    val leases: Int,
    val lost: Int,
    val failed: Int,
    val duration: Duration,
)

/**
 * The renewals of the leases one [PartitionOwnership] holds, and their fencing: every round renews all of them with one
 * [LeaseStore.renew], and a lease the store did not give back, or one left unrenewed for [unrenewedLimit]
 * (`leaseTtl − renewInterval`), is taken as lost: its partition stops taking commands at once, and the reconciler is
 * woken ([wake]) to stop it. Each round is reported to [renewalRounds].
 *
 * It reads [gates], which the ownership builds and alone changes, and flips only a gate's own fields
 * ([PartitionGate.renewedAt], [PartitionGate.lost], [PartitionGate.accepting]): it never puts, removes or clears an entry.
 */
internal class LeaseRenewals(
    private val group: LeaseGroup,
    private val node: String,
    private val leases: LeaseStore,
    private val options: ClusterOptions,
    private val timeSource: TimeSource,
    private val gates: Map<Int, PartitionGate>,
    private val unrenewedLimit: Duration,
    private val renewalRounds: (RenewalRound) -> Unit,
    private val wake: () -> Unit,
) {
    /**
     * Renews every held lease once, all with one [LeaseStore.renew]. A partition the call does not
     * give back is lost, unless it was handed off meanwhile; a call that throws leaves every lease to the next round,
     * each lost only once it is overdue.
     */
    @Suppress("TooGenericExceptionCaught") // A failed renewal is retried on the next round.
    suspend fun renew() {
        val started = timeSource.markNow()
        // A lease left unrenewed too long is taken as lost here, not renewed.
        val overdue = loseOverdue()
        val held = gates.entries.filter { !it.value.lost }.map { it.key to it.value }
        var lost = overdue
        var failed = 0
        if (held.isNotEmpty()) {
            val asked = timeSource.markNow()
            val renewed = try {
                leases.renew(
                    group.name,
                    held.map { it.first },
                    node,
                    options.leaseTtl
                ).mapTo(HashSet()) { it.partition }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(
                    failure
                ) { "$node failed to renew ${held.size} leases of $group; retrying on the next round." }
                null
            }
            held.forEach { (partition, gate) ->
                when (renewalOf(partition, gate, asked, renewed)) {
                    Renewal.LOST -> lost++
                    Renewal.FAILED -> failed++
                    Renewal.RENEWED -> Unit
                }
            }
        }
        report(RenewalRound(group, node, held.size + overdue, lost, failed, started.elapsedNow()))
    }

    /** How [partition]'s lease fared in a round asked at [asked]: [renewed] the partitions renewed, null on a failure. */
    private fun renewalOf(partition: Int, gate: PartitionGate, asked: TimeMark, renewed: Set<Int>?): Renewal {
        if (renewed == null) {
            return if (loseIfOverdue(partition, gate)) Renewal.LOST else Renewal.FAILED
        }
        if (gate.lost) {
            // Taken as lost while the round ran, overdue: the round reports it.
            return Renewal.LOST
        }
        if (partition in renewed) {
            gate.renewedAt = asked
            return Renewal.RENEWED
        }
        if (gates[partition] !== gate) {
            // Handed off and released meanwhile: nothing was lost.
            return Renewal.RENEWED
        }
        logger.warn { "$node lost the lease of partition $partition of $group." }
        gate.lost = true
        gate.accepting = false
        wake()
        return Renewal.LOST
    }

    private fun report(round: RenewalRound) {
        val margin = options.leaseTtl - options.renewInterval
        if (round.duration > margin / 2) {
            logger.warn {
                "$node took ${round.duration} to renew ${round.leases} leases of $group, more than half the margin " +
                    "$margin before they expire; lost ${round.lost}, failed ${round.failed}."
            }
        } else {
            logger.debug { "$node renewed ${round.leases} leases of $group in ${round.duration}." }
        }
        try {
            renewalRounds(round)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            logger.warn(failure) { "The renewal round hook of $node in $group failed." }
        }
    }

    /**
     * Takes the leases left unrenewed for `leaseTtl − renewInterval` as lost here: the
     * store may be unreachable while the log is not, and the lease may expire before the next round renews it. The
     * partition stops as if its lease were lost, without a release the store would not take either; once renewals
     * work again, the next reconcile acquires it anew.
     */
    fun loseOverdue(): Int = gates.count { (partition, gate) -> loseIfOverdue(partition, gate) }

    /** Takes [gate]'s lease as lost when it is overdue (see [loseOverdue]); whether it did now. */
    private fun loseIfOverdue(partition: Int, gate: PartitionGate): Boolean {
        if (gate.lost || !gate.accepting || !gate.overdue) {
            return false
        }
        logger.error {
            "$node has not renewed the lease of partition $partition of $group for $unrenewedLimit; it stops the " +
                "partition before the lease may expire."
        }
        gate.lost = true
        gate.accepting = false
        wake()
        return true
    }

    private enum class Renewal { RENEWED, LOST, FAILED }

    private companion object {
        // The lines moved from the ownership keep its logger's name.
        val logger = KotlinLogging.logger(PartitionOwnership::class.java.name)
    }
}
