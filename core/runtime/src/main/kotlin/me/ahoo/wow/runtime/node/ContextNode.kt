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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.CommittedRequests
import me.ahoo.wow.runtime.admission.CreationIds
import me.ahoo.wow.runtime.cluster.ForwardEndpoint
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.contract.PeerEvents
import me.ahoo.wow.runtime.log.PartitionAppendQueue
import me.ahoo.wow.runtime.reservation.ReservationSubscription
import me.ahoo.wow.runtime.snapshot.SnapshotMaintenance
import me.ahoo.wow.runtime.snapshot.SnapshotSubscription
import me.ahoo.wow.runtime.state.SharedStateReader
import me.ahoo.wow.runtime.state.SharedStates
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerLoop
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.runtime.timer.TimersSubscription
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.EventLog

/**
 * The parts of one bounded context on a node: the log it appends through ([log], the partition
 * append queue over the backend's log), its [kernel] and [executor], the built-in [snapshots] and [reservations]
 * subscriptions when the context has their stores, the [subscriptions] runtime of its lease [group], this node's
 * [ownership] of the group's partitions, its wait [coordinator], and the [endpoint] that executes the commands other
 * nodes forward to it.
 *
 * The application's subscriptions read the context's [backends], the kernel's [codec], and the state [views] the
 * executor takes of the aggregate types its state consumers read: the snapshot subscription and the readers the
 * application's subscriptions name ([SubscriptionFactory.stateReaders]), each reading through its [stateReader].
 */
@InternalWowApi
class ContextNode internal constructor(
    val context: String,
    val models: List<AggregateModel<*>>,
    val group: LeaseGroup,
    val log: PartitionAppendQueue,
    val kernel: Kernel,
    val executor: Executor,
    /** The subscription of the default snapshot store when it is a target, else the first target's, if any. */
    val snapshots: SnapshotSubscription?,
    val reservations: ReservationSubscription?,
    val subscriptions: SubscriptionRuntime,
    val ownership: PartitionOwnership,
    val coordinator: WaitCoordinator,
    val endpoint: ForwardEndpoint,
    val backends: ContextBackends,
    val codec: EventCodec,
    val views: SharedStates,
    /**
     * The runtime of the context's command topic, driven by [ownership]: null unless the node takes
     * commands from topics ([SourceTopics.intake]).
     */
    val intake: SubscriptionRuntime? = null,
    /** Sends the commands of the context's sagas to other contexts; null without a producer. */
    val sender: CommandSender? = null,
    /**
     * The built-in `timers` subscription and the context's timer runner, when its backends have a
     * timer store: the runner's [TimerRunner.retry] and [TimerRunner.skip] serve an operator.
     */
    val timers: ContextTimers? = null,
    /** The node's id generators, the ids its sagas take. */
    val ids: IdGenerators = IdGenerators.DEFAULT,
    /**
     * The context's log groups, its default one first: each with the runtime of its subscriptions
     * and its timers. [subscriptions] and [timers] are the default group's.
     */
    val logGroups: List<LogGroupNode> = emptyList(),
    /** Names the commands the context's sagas schedule: by its models and the service's Manifests. */
    val commandTypes: CommandTypes = CommandTypes.SIMPLE_NAMES,
    /** The subscription of each snapshot target store, the default store's first. */
    val snapshotSubscriptions: List<SnapshotSubscription> = listOfNotNull(snapshots),
    /** The context's snapshot maintenance, when it keeps snapshots. */
    val snapshotMaintenance: SnapshotMaintenance? = null,
    /**
     * The client of the other contexts' command gateways the context's sagas call synchronously; null
     * when the host gives none, and a call then fails.
     */
    val caller: CommandCaller? = null,
    /** The contexts of the node: a saga derives the commands of these, and calls only the others. */
    val served: Set<String> = setOf(context),
    /** The queries the context's handlers read with, by the commit's tenant; none by default. */
    val queries: ContextQueries = ContextQueries.NONE,
    /** The events of other contexts' published streams, decoded into their api classes; none by default. */
    val peerEvents: PeerEvents = PeerEvents.NONE,
    /**
     * The node's command validators, the gateway's: the commands the context's sagas derive, and those its intake
     * takes from its topic, are checked as admitted ones are.
     */
    val validators: List<CommandValidator> = emptyList(),
) {
    /**
     * The ids of the context's creation commands that name no aggregate: a saga's derived command, one
     * its command topic brings, each looked up by its request in the context's [log] first.
     */
    val creations: CreationIds = CreationIds(ids, CommittedRequests.of(log))

    /**
     * Reads the state after a commit as the state consumer [reader] of [views] ([SharedStateReader]), from the
     * context's snapshots when its views miss: what [me.ahoo.wow.runtime.subscription.EntryDecoder.stateEvents]
     * decodes with.
     */
    fun stateReader(reader: String): SharedStateReader =
        SharedStateReader(reader, models, kernel, codec, views, backends.primarySnapshots)

    /**
     * The snapshot write checkpoint of [type]: the progress of the subscription of [type]'s primary
     * snapshot target over the partitions of [type]'s log group ([SubscriptionRuntime.progress]). It
     * does not move while no snapshot of the group's aggregate types is written, so a count of [type]'s snapshots
     * taken at one value is still the count at that value. Null when [type] has no snapshots here.
     */
    suspend fun snapshotCheckpoint(type: AggregateType): Long? {
        val primary = snapshotMaintenance?.targets?.primarySubscriptionOf(type) ?: snapshots?.name
        val subscription = snapshotSubscriptions.firstOrNull { it.name == primary }?.subscription ?: return null
        if (type.context != context || type.name !in subscription.filter.aggregates) {
            return null
        }
        val group = logGroups.first { type in it.types }
        return group.subscriptions.progress(subscription.name, group.log.partitions)
    }
}

/**
 * The scheduled commands of one log group of a context on a node: the built-in `timers`
 * [subscription] writes the timers its aggregates schedule, and the [runner] sends them, through the node's
 * [TimerLoop], from the partitions the node owns.
 */
@InternalWowApi
class ContextTimers internal constructor(
    val subscription: TimersSubscription,
    val runner: TimerRunner,
)

/**
 * One log group of a context on a node: the aggregate [types] whose log is [log], on the store named
 * [store]; the runtime of the context's [subscriptions] over that log, its checkpoints and parked entries in the
 * group's store; and the group's [timers], when its store has a timer store. Every group runs the same
 * subscriptions, in the partitions the node owns.
 */
@InternalWowApi
class LogGroupNode internal constructor(
    val store: String,
    val types: Set<AggregateType>,
    val log: EventLog,
    val subscriptions: SubscriptionRuntime,
    val timers: ContextTimers?,
)
