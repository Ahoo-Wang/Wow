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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.WaitPlan
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The lease group of one bounded context in one service (decision D72): the nodes of [service] share the partitions
 * of [context]'s log, and command routing and every subscription of the context share their owners. A service that
 * hosts several contexts has one group, with its own partitions and leases, per context.
 *
 * The partitions of another context's published topic that the service's subscriptions read are leased the same way,
 * in the group [topic] names, `(service, "topic:<name>")` (decision D134).
 */
class LeaseGroup @InternalWowApi constructor(val service: String, val context: String) {
    init {
        require(service.isNotBlank()) { "A lease group names its service." }
        require(context.isNotBlank()) { "A lease group names its context." }
    }

    /** The group as the lease store keys it: `service/context`. */
    val name: String get() = "$service/$context"

    override fun toString(): String = name

    override fun equals(other: Any?): Boolean =
        this === other || other is LeaseGroup && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    private fun valueFields(): List<Any?> = listOf(service, context)

    companion object {
        /** A LeaseGroup of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(service: String, context: String): LeaseGroup = LeaseGroup(service, context)

        /** The lease group of the partitions of [topic] that [service]'s subscriptions read (decision D134). */
        @JvmStatic
        fun topic(service: String, topic: String): LeaseGroup = LeaseGroup(service, "topic:$topic")
    }
}

/**
 * The tuning of partition ownership and the cluster channel on one node (design 02 §3; decisions D70–D72, D80, D223).
 *
 * - [leaseTtl], renewed every [renewInterval]: how long a partition stays with a node that stopped renewing. A
 *   renewal round renews the held leases with one call (decision D270), and acquisitions run at most [leaseParallelism]
 *   at a time: a round must stay well inside `leaseTtl − renewInterval` (spike H6). A lease left unrenewed that long is taken as lost (finding A1-8), so
 *   [renewInterval] is less than half of [leaseTtl] (review R2.1-5): otherwise every lease would be lost between two
 *   rounds.
 * - [memberTtl], refreshed every [heartbeatInterval]: how long a node stays a member after its last heartbeat.
 *   [heartbeatInterval] is less than half of [memberTtl] (review X-10), so a heartbeat late by up to another interval
 *   keeps the node a member.
 * - [reconcileInterval]: how often a node compares the partitions it holds with its HRW target; a change of the
 *   members it notices reconciles at once.
 * - [routeRefreshInterval]: how often the route table is read from the leases and members; a failed forward refreshes
 *   it at once.
 * - [forwardTimeout]: how long a forward waits for the owner's reply, capped by the command's own deadline (D80);
 *   a forward sent and not answered by then is reported unknown, not run locally (decision D223). Only a forward the
 *   owner surely did not receive runs locally (D71). Since a timeout no longer runs the command elsewhere, waiting
 *   less than the command would only fail it sooner: the default is the default wait ([WaitPlan.DEFAULT_TIMEOUT],
 *   10 seconds), not D80's 1 second, which covered the owner's tail before falling back.
 * - [maxInFlightPerPeer]: the forwards one peer may have outstanding; one more runs locally instead.
 * - [drainTimeout]: how long a hand-off waits for the partition's commands in flight before it moves on.
 * - [reconcileTimeout]: how long one reconcile may run before the node takes itself as stuck: it stops heartbeating
 *   and leaves the members until the reconcile ends, so the others take the partitions it cannot run (finding C.1
 *   F1). Well above what a hand-off takes: the drain, then the stop of each runtime; longer than [drainTimeout] at least
 *   (review X-10).
 *
 * Fields may be added (decision D216): Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class ClusterOptions @InternalWowApi constructor(
    val leaseTtl: Duration = 10.seconds,
    val renewInterval: Duration = 3.seconds,
    val memberTtl: Duration = 10.seconds,
    val heartbeatInterval: Duration = 3.seconds,
    val reconcileInterval: Duration = 1.seconds,
    val routeRefreshInterval: Duration = 1.seconds,
    val forwardTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT,
    val maxInFlightPerPeer: Int = 1_024,
    val drainTimeout: Duration = 10.seconds,
    val leaseParallelism: Int = 16,
    val reconcileTimeout: Duration = 1.minutes,
) {
    init {
        require(renewInterval.isPositive() && renewInterval < leaseTtl) { "A lease is renewed before it expires." }
        // A lease unrenewed for leaseTtl - renewInterval is taken as lost (finding A1-8): a round must come before that.
        require(renewInterval * 2 < leaseTtl) {
            "The renew interval $renewInterval must be less than half the lease TTL $leaseTtl: a lease unrenewed for " +
                "leaseTtl - renewInterval is taken as lost, so a longer interval loses every lease between two rounds."
        }
        require(heartbeatInterval.isPositive() && heartbeatInterval < memberTtl) {
            "A member heartbeats before its membership expires."
        }
        // Heartbeats follow one another, each after the interval and the call (review X-10): one slow call must not
        // let the membership expire and move the partitions away.
        require(heartbeatInterval * 2 < memberTtl) {
            "The heartbeat interval $heartbeatInterval must be less than half the member TTL $memberTtl: a node " +
                "whose heartbeat is late once would otherwise drop out of the members and lose its partitions."
        }
        require(reconcileInterval.isPositive()) { "The reconcile interval is positive." }
        require(routeRefreshInterval.isPositive()) { "The route refresh interval is positive." }
        require(forwardTimeout.isPositive()) { "The forward timeout is positive." }
        require(maxInFlightPerPeer >= 1) { "A peer takes at least one forward." }
        require(!drainTimeout.isNegative()) { "The drain timeout is never negative." }
        require(leaseParallelism >= 1) { "At least one lease call runs at a time." }
        require(reconcileTimeout.isPositive()) { "The reconcile timeout is positive." }
        // A reconcile hands partitions off, each waiting up to the drain timeout (review X-10).
        require(reconcileTimeout > drainTimeout) {
            "The reconcile timeout $reconcileTimeout must be longer than the drain timeout $drainTimeout: a " +
                "hand-off waits up to the drain timeout, and a node reconciling longer takes itself as stuck and " +
                "leaves the members."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ClusterOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ClusterOptions(leaseTtl=$leaseTtl, renewInterval=$renewInterval, memberTtl=$memberTtl, " +
            "heartbeatInterval=$heartbeatInterval, reconcileInterval=$reconcileInterval, " +
            "routeRefreshInterval=$routeRefreshInterval, forwardTimeout=$forwardTimeout, " +
            "maxInFlightPerPeer=$maxInFlightPerPeer, drainTimeout=$drainTimeout, " +
            "leaseParallelism=$leaseParallelism, reconcileTimeout=$reconcileTimeout)"

    private fun valueFields(): List<Any?> = listOf(
        leaseTtl, renewInterval, memberTtl, heartbeatInterval, reconcileInterval, routeRefreshInterval,
        forwardTimeout, maxInFlightPerPeer, drainTimeout, leaseParallelism, reconcileTimeout
    )
}
