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
 * [node] is a live member of [service] until [expiresAt] (epoch milliseconds, exclusive), serving the lease [groups]
 * (finding R5.B-4): a node records its membership once per service, not once per lease group, and the groups it takes
 * part in are a field of that one record. [address] is the node's internal address (`host:port` of its internal port,
 * never the public one; decision D72): where the cluster channel reaches it, so commands can be routed to the owners
 * of partitions.
 *
 * [service] keys the record: a service's node membership, or a narrower set keyed the same way, such as the nodes of
 * one state revision (decision D172), whose records list no groups.
 *
 * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
 */
class Member @InternalWowApi constructor(
    val service: String,
    val node: String,
    val address: String,
    val expiresAt: Long,
    val groups: Set<String> = emptySet(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Member && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Member(service=$service, node=$node, address=$address, expiresAt=$expiresAt, groups=$groups)"

    private fun valueFields(): List<Any?> = listOf(service, node, address, expiresAt, groups)

    companion object {
        /** A Member of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            service: String,
            node: String,
            address: String,
            expiresAt: Long,
            groups: Set<String> = emptySet()
        ): Member =
            Member(service, node, address, expiresAt, groups)
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
 * owners would never learn they must give partitions up. Membership is recorded per service and ownership per lease
 * group (finding R5.B-4): each node [heartbeat]s one record for its service, listing the lease groups it takes part
 * in, and reads the service's live [members] once for all its groups; the runtime derives a node's fair share of a
 * group from the members that list it, releases surplus leases and acquires free ones. The store operations of the
 * control plane so grow with the groups only for the leases (renewals and owner reads), not for the membership.
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
     * Registers [node] as a member of [service] reachable at [address], serving the lease [groups], or keeps it one,
     * for [ttl] from now. A later heartbeat replaces the address and the groups, so a node restarted on another address
     * is found there, and one that stops taking part in a group drops it at once.
     */
    suspend fun heartbeat(
        service: String,
        node: String,
        address: String,
        ttl: Duration,
        groups: Set<String> = emptySet(),
    ): Member

    /** The live members of [service], in node order, each with the groups it serves. */
    suspend fun members(service: String): List<Member>

    /** Removes [node] from [service] at once, for a graceful shutdown; it keeps the leases it has not released. */
    suspend fun leave(service: String, node: String)
}
