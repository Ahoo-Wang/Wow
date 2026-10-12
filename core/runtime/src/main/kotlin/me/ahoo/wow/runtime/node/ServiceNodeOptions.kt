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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandRewriter
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.IdGenerator
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import kotlin.time.Duration

/**
 * The stores of one bounded context on a node (decision D72: a log, its partitions and leases are per context). The
 * [log]'s partition count is the context's. Without [snapshots] the context has no snapshot subscription, and its
 * default wait is COMMITTED; without [reservations] its commands cannot reserve keys.
 */
data class ContextBackends(
    val log: EventLog,
    val checkpoints: CheckpointStore,
    val leases: LeaseStore,
    val ledger: FailureLedger,
    val snapshots: SnapshotStore? = null,
    val reservations: ReservationStore? = null,
)

/** Opens the [ContextBackends] of each bounded context the node hosts; the host closes what it opened. */
fun interface BackendFactory {
    fun backends(context: String): ContextBackends
}

/** Where a node runs a command (design 02 §2; decision D71). */
enum class Routing {
    /** On the owner of its log partition, forwarded over the cluster channel; the default. */
    PARTITIONED,

    /** On the node it arrived at, with optimistic concurrency: for a single node. */
    LOCAL,
}

/**
 * The tuning of a [ServiceNode] (decision D110 names the host's keys for them).
 *
 * - [cluster]: partition ownership and the cluster channel; [routing]: where commands run.
 * - [maxWaitTimeout] and [maxWaitHops]: the waits ([WaitOptions]). [ServiceNode.stop] also waits at most
 *   [maxWaitTimeout] for the waits registered on the node to end: none of them outlives it.
 * - [subscription]: the subscriptions of every context, the built-in ones included.
 * - [appendQueue]: the partition append queue, by the log backend's preset ([AppendQueueOptions.MONGO],
 *   [AppendQueueOptions.POSTGRES]).
 * - [laneCapacity]: the commands one aggregate may have waiting in the executor.
 * - [stateCacheCapacity]: the aggregate states kept per context between commands; 0 keeps none.
 */
data class ServiceNodeOptions(
    val cluster: ClusterOptions = ClusterOptions(),
    val routing: Routing = Routing.PARTITIONED,
    val maxWaitTimeout: Duration = WaitOptions.DEFAULT_MAX_TIMEOUT,
    val maxWaitHops: Int = WaitOptions.DEFAULT_MAX_HOPS,
    val subscription: SubscriptionOptions = SubscriptionOptions(),
    val appendQueue: AppendQueueOptions = AppendQueueOptions(),
    val laneCapacity: Int = DEFAULT_LANE_CAPACITY,
    val stateCacheCapacity: Int = DEFAULT_STATE_CACHE_CAPACITY,
) {
    init {
        require(laneCapacity >= 1) { "A lane takes at least one command." }
        require(stateCacheCapacity >= 0) { "The state cache capacity is never negative." }
    }

    companion object {
        const val DEFAULT_LANE_CAPACITY: Int = 1_024
        const val DEFAULT_STATE_CACHE_CAPACITY: Int = 4_096
    }
}

/** The admission plug-ins of a node's [me.ahoo.wow.runtime.admission.CommandGateway] (decisions D92, D114). */
data class AdmissionPlugins(
    val authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
    val rewriters: List<CommandRewriter> = emptyList(),
    val validators: List<CommandValidator> = emptyList(),
    val ids: IdGenerator = IdGenerator.UUID_V7,
)

/**
 * The application's subscriptions of one bounded context (projections, reactors, sagas), beside the built-in
 * `snapshot` and `reservation` ones. Called once per context while the node is assembled, with the context's parts,
 * so a saga can [forward][me.ahoo.wow.runtime.wait.WaitCoordinator.forward] through its coordinator; the
 * subscriptions are registered when the node starts.
 */
fun interface SubscriptionFactory {
    fun subscriptions(context: ContextNode): List<Subscription>

    companion object {
        val NONE: SubscriptionFactory = SubscriptionFactory { emptyList() }
    }
}
