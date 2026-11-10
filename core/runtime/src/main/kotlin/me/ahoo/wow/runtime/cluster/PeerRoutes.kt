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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import java.util.concurrent.ConcurrentHashMap

/**
 * Who owns which partition of a lease group, as one node last read it: the live [owners] by partition, each a live
 * member with its address, and the live [members]. A lease whose holder is no longer a member is left out: its owner
 * is unknown, so a command for it runs locally.
 */
@InternalWowApi
data class RouteTable(val owners: Map<Int, Member> = emptyMap(), val members: List<Member> = emptyList()) {
    @InternalWowApi
    companion object {
        val EMPTY = RouteTable()
    }
}

/**
 * What one [PartitionOwnership] knows of the other members of its [group]: the [routes] it read last, the members its
 * reconcile and its reads saw last, and whom it has greeted at which address ([peers], [greetings]). A change of the
 * members wakes the ownership's reconciler ([wake]); what else it needs of the ownership comes with each call.
 */
internal class PeerRoutes(
    private val group: LeaseGroup,
    private val node: String,
    private val membership: ServiceMembership,
    private val leases: LeaseStore,
    /** Called, in its own coroutine, for each other member the routes show at an address not seen before. */
    private val peers: suspend (Member) -> Unit,
    /** The greetings that fail, and since when. */
    private val greetings: PeerGreetings,
    private val wake: () -> Unit,
) {
    /** The routes as last read from the lease store. */
    @Volatile
    var routes: RouteTable = RouteTable.EMPTY
        private set

    @Volatile
    private var lastMembers: Set<String> = emptySet()

    /** The other members' addresses handed to [peers] so far, as `node@address`. */
    private val seenPeers = ConcurrentHashMap.newKeySet<String>()

    /** The members greeted and still members, as `node@address`. */
    val knownPeers: Set<String> get() = seenPeers

    /** The members [PartitionOwnership.reconcile] computed its target from, as the membership read them last. */
    fun membersSeen(nodes: Set<String>) {
        lastMembers = nodes
    }

    /** Forgets every member and greeting, as a start that failed leaves the group: one that comes back is greeted again. */
    fun forget() {
        seenPeers.clear()
        greetings.retain(emptySet())
        lastMembers = emptySet()
    }

    /**
     * Reads the owners into [routes], beside the members the membership read last; a change of the members wakes the
     * reconciler ([wake]). Once the node [steppedOut], the membership reads no members for it, so it reads them here.
     * Greetings run in [upkeep], none without it (the ownership not running).
     */
    suspend fun refresh(steppedOut: Boolean, upkeep: CoroutineScope?) {
        if (steppedOut) {
            membership.refresh()
        }
        val members = membership.membersOf(group.name)
        val byNode = members.associateBy { it.node }
        val leased = leases.owners(group.name)
        val owners = leased.mapNotNull { lease -> byNode[lease.owner]?.let { lease.partition to it } }.toMap()
        routes = RouteTable(owners, members)
        greetNew(members, upkeep)
        val nodes = byNode.keys
        if (nodes != lastMembers) {
            lastMembers = nodes
            wake()
        }
    }

    /**
     * Hands each other member of [members] at an address not seen before to [peers], in its own coroutine. A greeting
     * that fails is tried again on the next read of the routes: logged at INFO, without its trace, while
     * it fails for less than the member TTL, as a new member's address that does not resolve yet does, and at WARN
     * with its trace after.
     */
    private fun greetNew(members: List<Member>, upkeep: CoroutineScope?) {
        upkeep ?: return
        // The members that left are forgotten: one that comes back is greeted again.
        val current = members.mapTo(HashSet()) { "${it.node}@${it.address}" }
        seenPeers.retainAll(current)
        greetings.retain(current)
        members.filter { it.node != node && seenPeers.add("${it.node}@${it.address}") }.forEach { member ->
            upkeep.launch { greet(member) }
        }
    }

    private suspend fun greet(member: Member) {
        val peer = "${member.node}@${member.address}"
        try {
            peers(member)
            greetings.greeted(peer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // Greeted again on the next read of the routes.
            seenPeers.remove(peer)
            if (greetings.failed(peer)) {
                logger.warn(failure) {
                    "$node failed to greet ${member.node} at ${member.address} in $group for " +
                        "${greetings.failingFor(peer)}; retrying on the next round."
                }
            } else {
                logger.info {
                    "$node could not greet ${member.node} at ${member.address} in $group yet ($failure); " +
                        "retrying on the next round."
                }
            }
        }
    }

    /**
     * After each read of the service's members, while the ownership runs: a change of this group's members wakes the
     * reconciler.
     */
    fun membersRead() {
        val nodes = membership.membersOf(group.name).mapTo(HashSet()) { it.node }
        if (nodes != lastMembers) {
            lastMembers = nodes
            wake()
        }
    }

    private companion object {
        // The lines moved from the ownership keep its logger's name.
        val logger = KotlinLogging.logger(PartitionOwnership::class.java.name)
    }
}
