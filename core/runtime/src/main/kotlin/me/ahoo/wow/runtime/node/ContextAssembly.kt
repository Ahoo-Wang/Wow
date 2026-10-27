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
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.KernelOptions
import me.ahoo.wow.kernel.StateCache
import me.ahoo.wow.kernel.StateCacheOutcome
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.cluster.ForwardEndpoint
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.cluster.ServiceMembership
import me.ahoo.wow.runtime.cluster.TopicRouter
import me.ahoo.wow.runtime.command.MessageEntries
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.runtime.log.LogGroupRouter
import me.ahoo.wow.runtime.log.PartitionAppendQueue
import me.ahoo.wow.runtime.reservation.ReservationRouter
import me.ahoo.wow.runtime.reservation.ReservationSubscription
import me.ahoo.wow.runtime.snapshot.LogProgress
import me.ahoo.wow.runtime.snapshot.SnapshotHops
import me.ahoo.wow.runtime.snapshot.SnapshotMaintenance
import me.ahoo.wow.runtime.snapshot.SnapshotSubscription
import me.ahoo.wow.runtime.state.StateViews
import me.ahoo.wow.runtime.subscription.LogGroupDirectory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.ContextCommandTypes
import me.ahoo.wow.runtime.timer.ContextTimerFirer
import me.ahoo.wow.runtime.timer.TimerFirer
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.runtime.timer.TimersSubscription
import me.ahoo.wow.runtime.wait.ReceiptHops
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStore

/**
 * Wires one bounded context of a [ServiceNode] from its [ContextBackends]: a [PartitionAppendQueue] over the context's
 * log groups (configured by [ServiceNodeOptions.appendQueue]) → a [Kernel] with its state cache, snapshot and
 * reservation stores → an [Executor] recording the state views the context's state consumers share; the built-in
 * [SnapshotSubscription] (for the aggregates whose Manifest enables snapshots) and [ReservationSubscription], in a
 * [SubscriptionRuntime] per log group; a [PartitionOwnership] of the lease group `(service, context)`; with a timer
 * store, the built-in [TimersSubscription] and a [TimerRunner] driven by that ownership; the runtime of the context's
 * command topic when it takes commands from it. [Parts.complete] then adds what needs the node's router: the
 * [WaitCoordinator] and the [ForwardEndpoint].
 */
internal class ContextAssembly(private val wiring: NodeWiring) {
    private val service = wiring.service
    private val node = wiring.node
    private val address: String get() = wiring.address()
    private val options = wiring.options
    private val admission = wiring.admission
    private val observer = wiring.observer
    private val channel = wiring.channel
    private val contracts = wiring.contracts
    private val topics = wiring.topics
    private val registry = wiring.registry
    private val waits = wiring.waits
    private val signals = wiring.signals
    private val origin = wiring.origin
    private val sender = wiring.sender
    private val caller = wiring.caller
    private val queries = wiring.queries
    private val peerEvents = wiring.peerEvents
    private val notifiers = wiring.notifiers
    private val classification = wiring.classification
    private val inFlight = wiring.inFlight
    private val timerLoop = wiring.timerLoop
    private val nodeSnapshots = wiring.nodeSnapshots
    private val stateCacheCapacity = wiring.stateCacheCapacity
    private val stateCacheBytes = wiring.stateCacheBytes
    private val committedHandlers = wiring.committedHandlers

    private fun membershipOf(leases: LeaseStore): ServiceMembership = wiring.membershipOf(leases)

    private fun takesCommands(context: String): Boolean = wiring.takesCommands(context)

    /**
     * The runtime of [context]'s command topic when the node takes commands from topics ([SourceTopics.intake]), or
     * publishes its failure ledger, whose operators' actions arrive there, in its lease [group] and
     * stores: the topic's partitions are the log's, so the same leases drive both.
     */
    private fun intakeOf(context: String, group: LeaseGroup, backends: ContextBackends): SubscriptionRuntime? =
        topics?.takeIf { takesCommands(context) || options.ledger != null }?.let {
            SubscriptionRuntime(
                group.name,
                MessageEntries.commands(it.commandsOf(context), it.source, backends.log.partitions, observer),
                backends.checkpoints,
                backends.ledger,
                options = options.subscription,
                signals = signals,
                notifier = notifiers,
                observer = observer,
                classification = classification,
            )
        }

    /**
     * The state cache of [context]'s kernel, of [stateCacheCapacity] states or [stateCacheBytes] of them weighed by the
     * sizes [codec] measures, each eviction observed.
     */
    private fun stateCacheOf(context: String, codec: EventCodec): StateCache {
        val evicted = { observer.stateCache(context, StateCacheOutcome.EVICTED) }
        return when (val capacity = stateCacheCapacity) {
            null -> LruStateCache(
                maxBytes = checkNotNull(stateCacheBytes),
                evicted = evicted,
                weigh = codec.sizes::bytesOf,
            )
            0 -> StateCache.None
            else -> LruStateCache(capacity = capacity, evicted = evicted)
        }
    }

    fun assemble(
        context: String,
        models: List<AggregateModel<*>>,
        backends: ContextBackends,
        codec: EventCodec,
        application: SubscriptionFactory,
    ): Parts {
        // Each group's own commits reach its tails in memory; its sequences are its own.
        val groups = logGroupsOf(context, models, backends).map { it.withLog(handoffOf(context, it.log)) }
        val routed = routedLog(groups)
        val log = PartitionAppendQueue(routed, options.appendQueue, capacity = options.executor.maxInFlight)
        val names = ContextCommandTypes(models, contracts)
        val states = stateCacheOf(context, codec)
        val reserved = reservationsOf(backends, groups)
        val kernel = kernelOf(log, backends, reserved, codec, names, states)
        val snaps = nodeSnapshots.assembly(context, models, backends, kernel, routed, codec)
        val views = StateViews(codec, stateReaders(context, models, snaps.readers, application), options.stateViews)
        val snapshots = snaps.subscriptions(views, options.snapshots.writeBatch)
        val reservations = reserved?.let { ReservationSubscription(context, it, observer) }
        val firing = LateFirer()
        val group = LeaseGroup(service, context)
        val progress = progressOf(group, groups)
        val maintenance = snaps.maintenance(group.name, node, backends.leases, progress, options.snapshots) { address }
        val builtIns = snapshots.map { it.subscription } +
            listOfNotNull(maintenance?.rebuildSubscription, reservations?.subscription)
        val logGroups = groups.mapIndexed { index, part ->
            logGroupOf(progress[index].group, part, builtIns, timersOf(context, part, firing))
        }
        val subscriptions = logGroups.first().subscriptions
        val intake = intakeOf(context, group, backends)
        val ownership = ownershipOf(group, backends, logGroups, intake, states)
        val executor = Executor(
            kernel,
            options.executor.laneCapacity,
            options.executor.maxBatch,
            views.forExecutor(),
            inFlight,
        )
        return Parts(
            context,
            models,
            group,
            log,
            kernel,
            executor,
            snapshots,
            maintenance,
            reservations,
            subscriptions,
            ownership,
            backends,
            codec,
            views,
            intake,
            logGroups.first().timers,
            firing,
            logGroups,
            names,
        )
    }

    /**
     * This node's share of the partitions of [group], whose log groups [logGroups] read them, the first the context's
     * own, with its command [intake]; [states] drops the states of each partition that changes hands.
     */
    private fun ownershipOf(
        group: LeaseGroup,
        backends: ContextBackends,
        logGroups: List<LogGroupNode>,
        intake: SubscriptionRuntime?,
        states: StateCache,
    ): PartitionOwnership = PartitionOwnership(
        group,
        node,
        { this.address },
        backends.log.partitions,
        backends.leases,
        options.cluster,
        logGroups.first().subscriptions,
        // Connected ahead of the first forward, so its timeout is not spent opening a connection.
        peers = { peer -> if (options.routing == Routing.PARTITIONED) channel.connect(peer.address) },
        intake = intake,
        timers = logGroups.mapNotNull { it.timers?.runner },
        groups = logGroups.drop(1).map { it.subscriptions },
        partitionChanged = { partition -> states.invalidate(partition, backends.log.partitions) },
        membership = membershipOf(backends.leases),
    )

    /**
     * A log group [part] of a context on this node: the runtime [name]d of its subscriptions, the [builtIns] and its
     * [timers]' `timers` subscription among them, over its log, checkpoints and ledger.
     */
    private fun logGroupOf(
        name: String,
        part: GroupParts,
        builtIns: List<Subscription>,
        timers: ContextTimers?,
    ): LogGroupNode {
        val runtime = SubscriptionRuntime(
            name,
            part.log,
            part.checkpoints,
            part.ledger,
            builtIns + listOfNotNull(timers?.subscription?.subscription),
            options.subscription,
            signals = signals,
            notifier = notifiers,
            observer = observer,
            classification = classification,
        )
        return LogGroupNode(part.store, part.types, part.log, runtime, timers)
    }

    /**
     * The log groups of [context]: its default one, of every aggregate type of [models] no other group
     * names, then each of [backends]' other groups, whose types must be the context's.
     */
    private fun logGroupsOf(
        context: String,
        models: List<AggregateModel<*>>,
        backends: ContextBackends,
    ): List<GroupParts> {
        val types = models.mapTo(LinkedHashSet()) { it.type }
        backends.groups.forEach { group ->
            val foreign = group.types - types
            require(foreign.isEmpty()) {
                "The log group ${group.store} of context $context holds $foreign, which are not its aggregate types $types."
            }
        }
        val grouped = backends.groups.flatMapTo(HashSet()) { it.types }
        val default = GroupParts(
            backends.store,
            types - grouped,
            backends.log,
            backends.checkpoints,
            backends.ledger,
            backends.timers,
            backends.reservations,
        )
        return listOf(default) + backends.groups.map {
            GroupParts(it.store, it.types, it.log, it.checkpoints, it.ledger, it.timers, it.reservations)
        }
    }

    /**
     * The context's reservations, each aggregate type's in its log group's store; a group without one
     * uses the default group's.
     */
    private fun reservationsOf(backends: ContextBackends, groups: List<GroupParts>): ReservationStore? =
        ReservationRouter.of(
            backends.reservations,
            groups.drop(1).flatMap { group ->
                group.reservations?.let { store -> group.types.map { it to store } }.orEmpty()
            }.toMap(),
        )

    /** The context's log, each aggregate type's streams read from its log group's. */
    private fun routedLog(groups: List<GroupParts>): EventLog = LogGroupRouter.of(
        groups.first().log,
        groups.drop(1).flatMap { group -> group.types.map { it to group.log } }.toMap(),
    )

    /**
     * Where the subscriptions of each of [groups] keep their checkpoints, under its name in [group]: the default
     * group's checkpoints are keyed as before log groups.
     */
    private fun progressOf(group: LeaseGroup, groups: List<GroupParts>): List<LogProgress> =
        groups.mapIndexed { index, part ->
            LogProgress(if (index == 0) group.name else "${group.name}#${part.store}", part.log, part.checkpoints)
        }

    /** The kernel of a context over its [log], with its state cache, snapshot and reservation stores. */
    private fun kernelOf(
        log: PartitionAppendQueue,
        backends: ContextBackends,
        reservations: ReservationStore?,
        codec: EventCodec,
        names: CommandTypes,
        states: StateCache,
    ) =
        Kernel(
            log,
            codec = codec,
            reservations = reservations,
            states = states,
            snapshots = backends.primarySnapshots,
            commandTypes = names,
            ids = admission.ids,
            observer = observer,
            options = KernelOptions(
                recentRequests = options.stateCache.recentRequests,
                maxEarlyWait = options.timers.maxEarlyWait
            ),
        )

    /**
     * The scheduled commands of one log group of [context], when its [group] has a timer
     * store: the `timers` subscription writes the timers of the group's aggregates there, and the runner sends them
     * through the node's [timerLoop].
     */
    private fun timersOf(context: String, group: GroupParts, firer: TimerFirer): ContextTimers? =
        group.timers?.let { store ->
            val runner = TimerRunner(context, store, group.ledger, firer, timerLoop)
            ContextTimers(TimersSubscription(context, store, group.log.partitions, runner::offer), runner)
        }

    /** The stores of one log group of a context, as [logGroupsOf] finds them. */
    private class GroupParts(
        val store: String,
        val types: Set<AggregateType>,
        val log: EventLog,
        val checkpoints: CheckpointStore,
        val ledger: FailureLedger,
        val timers: TimerStore?,
        val reservations: ReservationStore?,
    ) {
        fun withLog(log: EventLog) = GroupParts(store, types, log, checkpoints, ledger, timers, reservations)
    }

    /** [log] handing this node's commits to its tails in memory, as [ServiceNodeOptions.handoff] says. */
    private fun handoffOf(context: String, log: EventLog): EventLog =
        HandoffLog(log, context, options.handoff, observer)

    /**
     * The state consumers of [context]: the subscription of each snapshot target, of the types it
     * keeps ([snapshots]), and the readers the [application]'s subscriptions name.
     */
    private fun stateReaders(
        context: String,
        models: List<AggregateModel<*>>,
        snapshots: Map<String, Set<AggregateType>>,
        application: SubscriptionFactory,
    ): Map<String, Set<AggregateType>> {
        val readers = LinkedHashMap<String, Set<AggregateType>>(snapshots)
        application.stateReaders(context, models).forEach { (reader, types) ->
            readers.merge(reader, types) { left, right -> left + right }
        }
        return readers
    }

    /** A context's parts before the node's router exists. */
    inner class Parts(
        val context: String,
        val models: List<AggregateModel<*>>,
        val group: LeaseGroup,
        val log: PartitionAppendQueue,
        val kernel: Kernel,
        val executor: Executor,
        val snapshots: List<SnapshotSubscription>,
        val maintenance: SnapshotMaintenance?,
        val reservations: ReservationSubscription?,
        val subscriptions: SubscriptionRuntime,
        val ownership: PartitionOwnership,
        val backends: ContextBackends,
        val codec: EventCodec,
        val views: StateViews,
        val intake: SubscriptionRuntime?,
        val timers: ContextTimers?,
        val firing: LateFirer,
        val logGroups: List<LogGroupNode>,
        val commandTypes: CommandTypes,
    ) {
        fun complete(router: CommandRouter): ContextNode {
            val runtimes = logGroups.drop(1).flatMap { group -> group.types.map { it to group.subscriptions } }.toMap()
            val runtime = if (runtimes.isEmpty()) subscriptions else LogGroupDirectory(subscriptions, runtimes)
            val snapshotHops = maintenance?.let { SnapshotHops(runtime, context, it.targets, it.types) } ?: runtime
            val directory = contracts.over(ReceiptHops(snapshotHops, context, committedHandlers(context)), context)
            val coordinator = WaitCoordinator(
                executor,
                waits,
                directory,
                signals,
                origin,
                options.wait,
                waitIds = admission.ids.global::next,
                router = router,
                topicRouter = topicRouterOf(context, backends),
                observer = observer,
            )
            val endpoint = ForwardEndpoint(coordinator, models, listOf(ownership), observer = observer)
            firing.firer = ContextTimerFirer(models, coordinator, sender, observer)
            return ContextNode(
                context,
                models,
                group,
                log,
                kernel,
                executor,
                snapshots.firstOrNull { it.name == Hop.SNAPSHOT_SUBSCRIPTION } ?: snapshots.firstOrNull(),
                reservations,
                subscriptions,
                ownership,
                coordinator,
                endpoint,
                backends,
                codec,
                views,
                intake,
                sender,
                timers,
                admission.ids,
                logGroups,
                commandTypes,
                snapshots,
                maintenance,
                caller,
                registry.contexts,
                queries,
                peerEvents,
            )
        }
    }

    /** The topic router of [context]'s types in [ServiceNodeOptions.topicRouted], if any. */
    private fun topicRouterOf(context: String, backends: ContextBackends): TopicRouter? {
        val types = options.topicRouted.filterTo(LinkedHashSet()) { it.context == context }
        if (types.isEmpty()) {
            return null
        }
        val transport = checkNotNull(topics)
        return TopicRouter(
            types,
            transport.commandsOf(context),
            backends.log.partitions,
            checkNotNull(transport.producer)
        )
    }

    /** The context's timer firer, known once its coordinator is: the runner is built before it. */
    internal class LateFirer : TimerFirer {
        @Volatile
        lateinit var firer: TimerFirer

        override suspend fun fire(timer: Timer): String = firer.fire(timer)
    }
}

/** The state views an executor records commits in: none when no reader of the context reads any aggregate type. */
private fun StateViews.forExecutor(): StateViews? = takeIf { it.types.isNotEmpty() }
