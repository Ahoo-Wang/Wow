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

package me.ahoo.wow.spring.boot.node

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.ExecutorOptions
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.StateCacheOptions
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.state.SharedStateOptions
import me.ahoo.wow.runtime.wait.UnknownContexts
import me.ahoo.wow.runtime.wait.WaitOptions
import org.springframework.util.unit.DataSize
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** The runtime's own cluster defaults, which these keys default to. */
private val DEFAULT_CLUSTER = ClusterOptions()

/** How the nodes of a service share the partitions of each context's log, and forward commands to their owners. */
@InternalWowApi
class ClusterProperties {
    /** How long a partition stays with a node that stopped renewing its lease. */
    var leaseTtl: Duration = DEFAULT_CLUSTER.leaseTtl.toJavaDuration()

    /** How often a node renews its leases; less than half of `lease-ttl`, or every lease is lost between rounds. */
    var renewInterval: Duration = DEFAULT_CLUSTER.renewInterval.toJavaDuration()

    /** How long a node stays a member of the cluster after its last heartbeat. */
    var memberTtl: Duration = DEFAULT_CLUSTER.memberTtl.toJavaDuration()

    /** How often a node sends its heartbeat; less than half of `member-ttl`, so one late heartbeat is harmless. */
    var heartbeatInterval: Duration = DEFAULT_CLUSTER.heartbeatInterval.toJavaDuration()

    /**
     * How often a node compares the partitions it holds with those it should hold; a change of the members it
     * notices reconciles at once.
     */
    var reconcileInterval: Duration = DEFAULT_CLUSTER.reconcileInterval.toJavaDuration()

    /** How often the route table is read from the leases and members; a failed forward refreshes it at once. */
    var routeRefreshInterval: Duration = DEFAULT_CLUSTER.routeRefreshInterval.toJavaDuration()

    /**
     * How long a forward waits for the owner's reply, capped by the command's own deadline:
     * `wow.wait.default-timeout` when not set, so a node whose waits are longer forwards as long. A forward the
     * owner received and did not answer by then is reported unknown (`wow.command.unknown`), not run here, so the
     * command never runs twice and a retry with the same request id is safe.
     */
    var forwardTimeout: Duration? = null

    /** The forwards one node may have outstanding with another; one more runs here instead. */
    var maxInFlightPerNode: Int = DEFAULT_CLUSTER.maxInFlightPerPeer

    /** How long a hand-off of a partition waits for its commands in flight before it moves on. */
    var drainTimeout: Duration = DEFAULT_CLUSTER.drainTimeout.toJavaDuration()

    /**
     * The most lease calls a node runs at a time for one lease group: its acquisitions and the releases its hand-offs
     * end with, one bound for both.
     */
    var leaseParallelism: Int = DEFAULT_CLUSTER.leaseParallelism

    /**
     * How long one reconcile may go without finishing a step for a partition before the node takes itself as stuck:
     * it leaves the members and gives up its leases until the reconcile moves again, so the other nodes take the
     * partitions it cannot run. Longer than `drain-timeout` and than `lease-ttl`, or the node does not start. One still
     * running after `wow.health.liveness.stall-timeout` fails the node's liveness.
     */
    var reconcileTimeout: Duration = DEFAULT_CLUSTER.reconcileTimeout.toJavaDuration()

    /** The cluster options, a forward waiting [ClusterOptions]' default when [forwardTimeout] is not set. */
    fun options(): ClusterOptions = options(DEFAULT_CLUSTER.forwardTimeout.toJavaDuration())

    /** The cluster options, a forward waiting [defaultWaitTimeout] when [forwardTimeout] is not set. */
    fun options(defaultWaitTimeout: Duration): ClusterOptions = ClusterOptions(
        leaseTtl = leaseTtl.toKotlinDuration(),
        renewInterval = renewInterval.toKotlinDuration(),
        memberTtl = memberTtl.toKotlinDuration(),
        heartbeatInterval = heartbeatInterval.toKotlinDuration(),
        reconcileInterval = reconcileInterval.toKotlinDuration(),
        routeRefreshInterval = routeRefreshInterval.toKotlinDuration(),
        forwardTimeout = (forwardTimeout ?: defaultWaitTimeout).toKotlinDuration(),
        maxInFlightPerPeer = maxInFlightPerNode,
        drainTimeout = drainTimeout.toKotlinDuration(),
        leaseParallelism = leaseParallelism,
        reconcileTimeout = reconcileTimeout.toKotlinDuration(),
    )
}

/** How long and how far the commands' waits go. */
@InternalWowApi
class WaitProperties {
    /**
     * How long a command whose request names no timeout waits (`Wow-Wait-Timeout`), whichever edge it came
     * through. At most `wow.wait.max-timeout`: a node whose default is above it does not start.
     */
    var defaultTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT.toJavaDuration()

    /** The longest wait; a longer timeout a caller asks for is capped to it, and a node stops within it. */
    var maxTimeout: Duration = WaitOptions.DEFAULT_MAX_TIMEOUT.toJavaDuration()

    /** The most hops of a wait plan, at most [WaitOptions.MAX_HOPS_CAP]; each saga a chain follows adds two. */
    var maxHops: Int = WaitOptions.DEFAULT_MAX_HOPS

    /**
     * Whether a wait plan may name a context the node knows no Manifest of: `allow`, checked where
     * the chain arrives, or `reject`.
     */
    var unknownContexts: UnknownContexts = UnknownContexts.ALLOW

    /**
     * The most waits the node holds at once, which bounds their memory: beyond it a command whose plan waits past
     * ACCEPTED is not run and is answered `wow.command.busy` (429, with `Retry-After`), FAILED at ACCEPTED.
     */
    var maxRegistered: Int = WaitOptions.DEFAULT_MAX_REGISTERED

    fun options(): WaitOptions = WaitOptions(
        maxTimeout.toKotlinDuration(),
        maxHops,
        defaultTimeout.toKotlinDuration(),
        unknownContexts,
        maxRegistered,
    )
}

/** How the node executes commands. */
@InternalWowApi
class ExecutorProperties {
    /** The commands one aggregate may have waiting in the executor. */
    var laneCapacity: Int = me.ahoo.wow.runtime.Executor.DEFAULT_LANE_CAPACITY

    /**
     * The most of one aggregate's queued commands the executor decides in turn and commits with one append; 1
     * runs each alone. It never waits to fill a batch.
     */
    var maxBatch: Int = me.ahoo.wow.runtime.Executor.DEFAULT_MAX_BATCH

    /**
     * The commands the node may have in flight, all contexts together: beyond it a command is
     * refused as `wow.command.busy` (429, with `Retry-After`) instead of queued.
     */
    var maxInFlight: Int = InFlightLimit.DEFAULT_MAX

    fun options(): ExecutorOptions = ExecutorOptions(laneCapacity, maxBatch, maxInFlight)
}

/**
 * The bounds of each context's shared states, the states its consumers share after each commit.
 */
@InternalWowApi
class SharedStatesProperties {
    /** The most shared states a context keeps. */
    var capacity: Int = SharedStateOptions.DEFAULT_CAPACITY

    /** How long a shared state is kept at most. */
    var maxAge: Duration = SharedStateOptions.DEFAULT_MAX_AGE.toJavaDuration()

    /** The most serialized state a context's views hold, counting two bytes a character (`64MB`). */
    var maxBytes: DataSize = DataSize.ofBytes(SharedStateOptions.DEFAULT_MAX_BYTES)

    fun options(): SharedStateOptions = SharedStateOptions(
        capacity,
        maxAge.toKotlinDuration(),
        maxBytes.toBytes()
    )
}

/** The cache of aggregate states each context keeps between commands. */
@InternalWowApi
class StateCacheProperties {
    /**
     * The aggregate states kept per context between commands; 0 keeps none. Not set (the default), each context's
     * cache is bounded by bytes instead: a tenth of the maximum heap (`-Xmx`), split between
     * the node's contexts, each state weighed at an estimated 1 KB plus 12 bytes per recent request it holds (its
     * rings grow with the requests the aggregate commits, up to `recent-requests`), and at least 4096 states with
     * full rings. Most aggregates commit few requests, so the default holds several times as many of them as a
     * count sized for full rings. A miss costs a snapshot and a tail read before the command is decided, so the
     * cache should hold the aggregates written often: set a capacity when states are much larger than 1 KB (a
     * capacity times the average state size should stay well within the heap). The counters
     * `wow.state_cache.lookups` (`hit`, `miss`) and `wow.state_cache.evictions` show the fit.
     */
    var capacity: Int? = null

    /**
     * The requests each cached aggregate keeps of those it committed last, derived from the log, so
     * that most commands skip looking their request id up; 0 looks every request id up.
     */
    var recentRequests: Int = Kernel.DEFAULT_RECENT_REQUESTS

    fun options(): StateCacheOptions = StateCacheOptions(capacity, recentRequests)
}
