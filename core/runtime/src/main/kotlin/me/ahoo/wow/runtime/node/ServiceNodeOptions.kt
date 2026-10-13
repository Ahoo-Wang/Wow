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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandRewriter
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.IdGenerator
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.state.StateViewOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.transport.PartitionedSource
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
 * - [maxWaitTimeout], [maxWaitHops] and [defaultWaitTimeout]: the waits ([WaitOptions]); the default timeout is that
 *   of every command whose request names none, whichever edge it came through, capped by [maxWaitTimeout]. [ServiceNode.stop] also waits at most
 *   [maxWaitTimeout] for the waits registered on the node to end: none of them outlives it.
 * - [subscription]: the subscriptions of every context, the built-in ones included, with the starts the host
 *   configures for some of them ([SubscriptionOptions.starts], decision D154): a name no subscription of the node has
 *   fails its assembly.
 * - [appendQueue]: the partition append queue, by the log backend's preset ([AppendQueueOptions.MONGO],
 *   [AppendQueueOptions.POSTGRES]).
 * - [laneCapacity]: the commands one aggregate may have waiting in the executor.
 * - [stateCacheCapacity]: the aggregate states kept per context between commands; 0 keeps none.
 * - [stateViews]: the bounds of each context's state views, shared by its state consumers (decision D133).
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
    val defaultWaitTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT,
    val stateViews: StateViewOptions = StateViewOptions(),
) {
    init {
        require(defaultWaitTimeout.isPositive()) { "The default wait timeout is positive." }
        require(laneCapacity >= 1) { "A lane takes at least one command." }
        require(stateCacheCapacity >= 0) { "The state cache capacity is never negative." }
    }

    companion object {
        const val DEFAULT_LANE_CAPACITY: Int = 1_024
        const val DEFAULT_STATE_CACHE_CAPACITY: Int = 4_096
    }
}

/**
 * The admission plug-ins of a node's [me.ahoo.wow.runtime.admission.CommandGateway] (decisions D92, D114), and the
 * [tenants] of its aggregate types (decision D116).
 */
data class AdmissionPlugins(
    val authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
    val rewriters: List<CommandRewriter> = emptyList(),
    val validators: List<CommandValidator> = emptyList(),
    val ids: IdGenerator = IdGenerator.UUID_V7,
    val tenants: TenantPolicy = TenantPolicy.CALLERS,
)

/**
 * The application's subscriptions of one bounded context (projections, reactors, sagas), beside the built-in
 * `snapshot` and `reservation` ones. Called once per context while the node is assembled, with the context's parts,
 * so a saga can [forward][me.ahoo.wow.runtime.wait.WaitCoordinator.forward] through its coordinator; the
 * subscriptions are registered when the node starts. The annotated processors' come from
 * [me.ahoo.wow.runtime.binding.SubscriptionBindings].
 *
 * [sourced] are the context's subscriptions of other contexts' published streams (decision D134): the node reads
 * each stream's topic ([SourceTopics]) for them instead of the log.
 */
fun interface SubscriptionFactory {
    fun subscriptions(context: ContextNode): List<Subscription>

    /**
     * The subscriptions of [context] that read the published streams of other contexts' aggregates (design 04 §5,
     * decision D134), each with the aggregate types it reads. The node finds each type's topic in its contracts
     * ([me.ahoo.wow.runtime.contract.ContractDirectory.publishedOf]) and reads it ([SourceTopics]). None by default.
     */
    fun sourced(context: ContextNode): List<SourcedSubscription> = emptyList()

    /**
     * The aggregate types among [context]'s [models] whose state after each commit the subscriptions read (design 04
     * §1): the context's executor keeps their state views ([ContextNode.views]), asked before the context is
     * assembled. None by default.
     */
    fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> = emptySet()

    /**
     * The state consumers among the subscriptions of [context] (decision D133), by the reader name each reads the
     * state views with ([ContextNode.stateReader]), with the aggregate types it reads: a view is evicted once every
     * reader of its type passed it, or it ages out. By default one reader named after this factory's class, of the
     * types [viewTypes] names: it passes nothing unless its subscriptions read under that name, so their views only
     * age out. A factory that overrides it also overrides [viewTypes] with the types it names.
     */
    fun stateReaders(context: String, models: List<AggregateModel<*>>): Map<String, Set<AggregateType>> {
        val types = viewTypes(context, models)
        return if (types.isEmpty()) emptyMap() else mapOf(javaClass.name to types)
    }

    /** This factory's subscriptions, then [other]'s, with the state readers either has. */
    operator fun plus(other: SubscriptionFactory): SubscriptionFactory {
        val first = this
        return object : SubscriptionFactory {
            override fun subscriptions(context: ContextNode): List<Subscription> =
                first.subscriptions(context) + other.subscriptions(context)

            override fun sourced(context: ContextNode): List<SourcedSubscription> =
                first.sourced(context) + other.sourced(context)

            override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> =
                first.viewTypes(context, models) + other.viewTypes(context, models)

            override fun stateReaders(
                context: String,
                models: List<AggregateModel<*>>,
            ): Map<String, Set<AggregateType>> {
                val readers = first.stateReaders(context, models).toMutableMap()
                other.stateReaders(context, models).forEach { (reader, types) ->
                    readers.merge(reader, types) { left, right -> left + right }
                }
                return readers
            }
        }
    }

    companion object {
        val NONE: SubscriptionFactory = SubscriptionFactory { emptyList() }

        /** [factories] in their order, as [plus] combines two; [NONE] when there are none. */
        @JvmStatic
        fun of(factories: List<SubscriptionFactory>): SubscriptionFactory =
            factories.reduceOrNull { all, factory -> all + factory } ?: NONE
    }
}

/**
 * A [subscription] that reads the published streams of [types], aggregates of another context (decision D134): its
 * filter is of that context, and it handles the records read from each type's topic.
 */
data class SourcedSubscription(val subscription: Subscription, val types: Set<AggregateType>) {
    init {
        require(types.isNotEmpty()) { "${subscription.name} reads no published stream." }
        require(types.all { it.context == subscription.filter.context }) {
            "${subscription.name} reads $types, not all of its filter's context ${subscription.filter.context}."
        }
    }
}

/**
 * Where a node reads other contexts' published streams (decision D134): the topics of [source], each named as the
 * publishing context's Manifest names it (`PublishedManifest.topic`) after [prefix], the prefix the publisher's sinks
 * write under too.
 */
data class SourceTopics(val source: PartitionedSource, val prefix: String = "") {
    /** The topic of a stream the Manifest publishes on [topic]. */
    fun topicOf(topic: String): String = prefix + topic
}
