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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The lease group of one bounded context in one service (decision D72): the nodes of [service] share the partitions
 * of [context]'s log, and command routing and every subscription of the context share their owners. A service that
 * hosts several contexts has one group, with its own partitions and leases, per context.
 */
data class LeaseGroup(val service: String, val context: String) {
    init {
        require(service.isNotBlank()) { "A lease group names its service." }
        require(context.isNotBlank()) { "A lease group names its context." }
    }

    /** The group as the lease store keys it: `service/context`. */
    val name: String get() = "$service/$context"

    override fun toString(): String = name
}

/**
 * The tuning of partition ownership and the cluster channel on one node (design 02 §3; decisions D70–D72, D80).
 *
 * - [leaseTtl], renewed every [renewInterval]: how long a partition stays with a node that stopped renewing. A
 *   renewal round renews the held leases concurrently, at most [leaseParallelism] at a time, and acquisitions are
 *   bounded the same way: a round then takes about `owned / leaseParallelism × store latency`, which must stay well
 *   inside `leaseTtl − renewInterval` (spike H6).
 * - [memberTtl], refreshed every [heartbeatInterval]: how long a node stays a member after its last heartbeat.
 * - [reconcileInterval]: how often a node compares the partitions it holds with its HRW target; a change of the
 *   members it notices reconciles at once.
 * - [routeRefreshInterval]: how often the route table is read from the leases and members; a failed forward refreshes
 *   it at once.
 * - [forwardTimeout]: how long a forward waits for the owner's reply, capped by the command's own deadline (D80);
 *   then the command runs locally (D71).
 * - [maxInFlightPerPeer]: the forwards one peer may have outstanding; one more runs locally instead.
 * - [drainTimeout]: how long a hand-off waits for the partition's commands in flight before it moves on.
 */
data class ClusterOptions(
    val leaseTtl: Duration = 10.seconds,
    val renewInterval: Duration = 3.seconds,
    val memberTtl: Duration = 10.seconds,
    val heartbeatInterval: Duration = 3.seconds,
    val reconcileInterval: Duration = 1.seconds,
    val routeRefreshInterval: Duration = 1.seconds,
    val forwardTimeout: Duration = 1.seconds,
    val maxInFlightPerPeer: Int = 1_024,
    val drainTimeout: Duration = 10.seconds,
    val leaseParallelism: Int = 16,
) {
    init {
        require(renewInterval.isPositive() && renewInterval < leaseTtl) { "A lease is renewed before it expires." }
        require(heartbeatInterval.isPositive() && heartbeatInterval < memberTtl) {
            "A member heartbeats before its membership expires."
        }
        require(reconcileInterval.isPositive()) { "The reconcile interval is positive." }
        require(routeRefreshInterval.isPositive()) { "The route refresh interval is positive." }
        require(forwardTimeout.isPositive()) { "The forward timeout is positive." }
        require(maxInFlightPerPeer >= 1) { "A peer takes at least one forward." }
        require(!drainTimeout.isNegative()) { "The drain timeout is never negative." }
        require(leaseParallelism >= 1) { "At least one lease call runs at a time." }
    }
}
