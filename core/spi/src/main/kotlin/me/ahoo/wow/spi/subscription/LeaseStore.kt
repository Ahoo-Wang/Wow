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

package me.ahoo.wow.spi.subscription

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import kotlin.time.Duration

/**
 * [owner] holds [partition] for subscription group [group] until [expiresAt] (epoch milliseconds, exclusive).
 *
 * [epoch] counts the acquisitions of the partition: each time it changes hands, or its owner takes it back after it
 * expired or was released, the epoch is one more. Wow does not fence on it (decision D45); it shows handovers in
 * metrics and tests.
 *
 * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
 */
class Lease @InternalWowApi constructor(
    val group: String,
    val partition: Int,
    val owner: String,
    val epoch: Long,
    val expiresAt: Long,
) {
    /** This Lease with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        group: String = this.group,
        partition: Int = this.partition,
        owner: String = this.owner,
        epoch: Long = this.epoch,
        expiresAt: Long = this.expiresAt,
    ): Lease = Lease(group, partition, owner, epoch, expiresAt)

    override fun equals(other: Any?): Boolean =
        this === other || other is Lease && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Lease(group=$group, partition=$partition, owner=$owner, epoch=$epoch, expiresAt=$expiresAt)"

    private fun valueFields(): List<Any?> = listOf(group, partition, owner, epoch, expiresAt)

    companion object {
        /** A Lease of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(group: String, partition: Int, owner: String, epoch: Long, expiresAt: Long): Lease =
            Lease(group, partition, owner, epoch, expiresAt)
    }
}

/**
 * [node] is a live member of subscription group [group] until [expiresAt] (epoch milliseconds, exclusive). [address]
 * is the node's internal address (`host:port` of its internal port, never the public one; decision D72): where the
 * cluster channel reaches it, so commands can be routed to the owners of partitions.
 *
 * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
 */
class Member @InternalWowApi constructor(
    val group: String,
    val node: String,
    val address: String,
    val expiresAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Member && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "Member(group=$group, node=$node, address=$address, expiresAt=$expiresAt)"

    private fun valueFields(): List<Any?> = listOf(group, node, address, expiresAt)

    companion object {
        /** A Member of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(group: String, node: String, address: String, expiresAt: Long): Member =
            Member(group, node, address, expiresAt)
    }
}

/**
 * Partition ownership within a subscription group, the deployment unit whose nodes share the partitions of a log
 * (documentation/designs/04-subscriptions.md §1, 02-write-path.md §3, decision D24). Leases live in storage: no
 * ZooKeeper, consumer group or registry.
 *
 * A lease is live while the store's clock is before its expiry. At most one node holds a live lease of a partition;
 * an expired or released one can be acquired by any node. Each operation is atomic, so concurrent acquisitions of a
 * free partition have exactly one winner.
 *
 * Membership is separate from ownership because rebalancing needs it: a node that just joined holds no lease, so the
 * owners would never learn they must give partitions up. Each node [heartbeat]s its membership; the runtime derives a
 * node's fair share from the live [members], releases surplus leases and acquires free ones.
 *
 * The store's clock decides liveness. A backend that takes the time from each node, not from the database, relies on
 * the nodes' clock skew being small against the TTL. Since Wow does not fence (D45), a short overlap of two owners
 * during a handover is only duplicate work, never a wrong result.
 */
@WowSpi
interface LeaseStore {
    /**
     * Acquires [partition] of [group] for [owner] for [ttl]: the lease is free, expired or released, or [owner]
     * already holds it (then it is extended and keeps its epoch, so a retried acquisition is harmless). Returns the
     * lease, or null when another node holds it.
     */
    suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease?

    /**
     * Extends to [ttl] from now each live lease [owner] holds on one of [partitions], and returns them; a partition
     * [owner] no longer holds is left out. A node renews all its leases with one call each round (decision D270), so a
     * backend renews them in one round trip where it can; a failure fails the whole call.
     */
    suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration): List<Lease>

    /** Releases the live lease [owner] holds on [partition], so another node can acquire it at once. */
    suspend fun release(group: String, partition: Int, owner: String): Boolean

    /** The live leases of [group], in partition order. */
    suspend fun owners(group: String): List<Lease>

    /**
     * Registers [node] as a member of [group] reachable at [address], or keeps it one, for [ttl] from now. A later
     * heartbeat replaces the address, so a node restarted on another address is found there.
     */
    suspend fun heartbeat(group: String, node: String, address: String, ttl: Duration): Member

    /** The live members of [group], in node order. */
    suspend fun members(group: String): List<Member>

    /** Removes [node] from [group] at once, for a graceful shutdown; it keeps the leases it has not released. */
    suspend fun leave(group: String, node: String)
}
