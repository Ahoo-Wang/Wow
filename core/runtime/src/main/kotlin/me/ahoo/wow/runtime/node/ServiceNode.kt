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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.KernelOptions
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.StateCache
import me.ahoo.wow.kernel.StateCacheOutcome
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.runtime.StateCacheSizing
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommittedRequests
import me.ahoo.wow.runtime.admission.CreationIds
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.admission.PeerCommands
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.cluster.ForwardEndpoint
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.cluster.PartitionedRouter
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.cluster.ServiceMembership
import me.ahoo.wow.runtime.cluster.TopicRouter
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.command.MessageEntries
import me.ahoo.wow.runtime.command.Receipts
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.contract.PeerEvents
import me.ahoo.wow.runtime.health.NodeStores
import me.ahoo.wow.runtime.health.StoreWritePressure
import me.ahoo.wow.runtime.health.WritePressures
import me.ahoo.wow.runtime.health.untracked
import me.ahoo.wow.runtime.ledger.LedgerCommands
import me.ahoo.wow.runtime.ledger.LedgerPublisher
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.runtime.log.LogGroupRouter
import me.ahoo.wow.runtime.log.PartitionAppendQueue
import me.ahoo.wow.runtime.manifest.ManifestCheck
import me.ahoo.wow.runtime.reservation.ReservationRouter
import me.ahoo.wow.runtime.reservation.ReservationSubscription
import me.ahoo.wow.runtime.schedule.ScheduleJobRuntime
import me.ahoo.wow.runtime.snapshot.LogProgress
import me.ahoo.wow.runtime.snapshot.SnapshotHops
import me.ahoo.wow.runtime.snapshot.SnapshotMaintenance
import me.ahoo.wow.runtime.snapshot.SnapshotStatus
import me.ahoo.wow.runtime.snapshot.SnapshotSubscription
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.state.StateViewReader
import me.ahoo.wow.runtime.state.StateViews
import me.ahoo.wow.runtime.subscription.FailureClassification
import me.ahoo.wow.runtime.subscription.LogGroupDirectory
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.ContextCommandTypes
import me.ahoo.wow.runtime.timer.ContextTimerFirer
import me.ahoo.wow.runtime.timer.TimerFirer
import me.ahoo.wow.runtime.timer.TimerLoop
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.runtime.timer.TimersSubscription
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.ReceiptHops
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.wait.Origin
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
    val views: StateViews,
    /**
     * The runtime of the context's command topic, driven by [ownership]: null unless the node takes
     * commands from topics ([SourceTopics.intake]).
     */
    val intake: SubscriptionRuntime? = null,
    /** Sends the commands of the context's sagas to other contexts; null without a producer. */
    val sender: CommandSender? = null,
    /**
     * The built-in `timers` subscription and the context's timer runner, when its backends have a
     * timer store: the runner's [TimerRunner.resume] and [TimerRunner.skip] serve an operator.
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
) {
    /**
     * The ids of the context's creation commands that name no aggregate: a saga's derived command, one
     * its command topic brings, each looked up by its request in the context's [log] first.
     */
    val creations: CreationIds = CreationIds(ids, CommittedRequests.of(log))

    /**
     * Reads the state after a commit as the state consumer [reader] of [views] ([StateViewReader]), from the
     * context's snapshots when its views miss: what [me.ahoo.wow.runtime.subscription.EntryDecoder.stateEvents]
     * decodes with.
     */
    fun stateReader(reader: String): StateViewReader =
        StateViewReader(reader, models, kernel, codec, views, backends.primarySnapshots)

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

/**
 * One node of a service, assembled without a host: what a host (Spring Boot, a test, a
 * sample) wires to its ports.
 *
 * **Per bounded context** of the [models] ([contexts]): a [PartitionAppendQueue] over the context's log, configured by
 * [ServiceNodeOptions.appendQueue] → a [Kernel] with its state cache, snapshot and reservation stores → an [Executor]
 * recording the state views the context's state consumers share; the built-in
 * [SnapshotSubscription] (for the aggregates whose Manifest enables snapshots) and [ReservationSubscription], with
 * the application's subscriptions, in a
 * [SubscriptionRuntime]; a [PartitionOwnership] of the lease group `(service, context)`; a [WaitCoordinator]; a
 * [ForwardEndpoint]. With a timer store, the built-in [TimersSubscription] and a [TimerRunner] driven by the
 * context's [PartitionOwnership], which sends the scheduled commands of the partitions the node owns.
 *
 * **Per node**: the [waits] registered here, the [signals] sender (a signal to this node goes straight to [waits],
 * one to another node through the host's remote sender), the [router] over every context's lease group, the
 * [gateway] that admits commands, the [registry] of models, the [forwardHandler] that hands a forwarded command
 * to its context's endpoint, and the [parked] entries of every subscription as an operator acts on them; each runtime
 * retries the entries of the partitions it runs over the long horizon and tells the [parkedNotifier] when one parks or
 * spends its retries.
 *
 * **Per topic** read for the subscriptions of other contexts' published streams ([SubscriptionFactory.sourced]):
 * one [TopicSubscriptions] of every subscription reading it, in the lease group `(service,
 * "topic:<topic>")`, keeping its checkpoints and parked aggregates in the stores of the first context (in the order
 * of the models) that reads it. The topic is the publishing context's ([contracts], [ContractDirectory.publishedOf])
 * in [topics]; a node with such subscriptions and no [topics] is not built.
 *
 * **Commands across contexts** (–), when [topics] has a producer: a saga's command to an
 * aggregate of another context is sent to that context's command topic ([ContextNode.sender]); the receipts of those
 * rejected, and of those committed that the saga asked about, come back on the saga's context's receipts topic, read
 * in the lease group of that topic for the contexts whose sagas have `@OnRejected` or `@OnCommitted` handlers
 * ([SubscriptionFactory.receipts]). With [SourceTopics.intake], each context
 * executes the commands of its own command topic ([ContextNode.intake], [CommandIntake]), driven by its
 * [PartitionOwnership]: the owner of a log partition reads the same partition of the topic, and [start] fails unless
 * the topic has as many partitions as the log.
 *
 * **The failure ledger across services**: with [ServiceNodeOptions.ledger], the node publishes the
 * entries whose partitions it runs to the ops context ([ledgerPublisher]), from [start] until [stop] hands off; each
 * intake runs the ledger's system commands the ops context sends back on the node's [parked] entries.
 *
 * **The `Kafka` router**: the commands admitted for the aggregate types of
 * [ServiceNodeOptions.kafkaRouted] are written to their context's command topic ([TopicRouter]) and executed by the
 * owner of their log partition through the context's intake, which every such context runs; the node is not built
 * when it does not serve one of those types, or has no producer in [topics].
 *
 * **Topics**: [start] first validates or creates the topics the node's contexts own
 * ([topicRequirements]) as [SourceTopics.ddl] says, and fails when one is not as they need it.
 *
 * **Startup checks**: the [manifest] (normally [me.ahoo.wow.runtime.manifest.ManifestLoader]'s) names this
 * [service], and it and the [models] agree ([ManifestCheck]); otherwise the node is not built. Nor is it built
 * without a producer in [topics] when a saga of the [manifest] derives, schedules or handles the rejections of
 * commands of a context the node does not serve ([ContractDirectory.commandsOutside]), nor when a saga calls a context
 * the node serves, or one its [caller] does not reach ([ContractDirectory.contextsCalled]). The
 * [contracts] are
 * those of this [manifest] and its peers, checked when they were built; each context's waits name
 * subscriptions through them, so a chain may name one of another context.
 *
 * **Internal address**: the [address] the other nodes reach this node at, given when the node is built, or learned
 * with [bind] once the host's internal port is bound (a port the system chose, such as `0`), before [start].
 *
 * **Lifecycle** ([state]): [start] joins every context's lease group (heartbeat, reconcile, acquire its partitions,
 * start their subscriptions), and the node is then [isReady]. [stop] leaves in this order (refining
 * to the whole node), each step after the previous one:
 *
 * 1. every context's [PartitionOwnership.stop], concurrently: leave the members, and for each partition stop
 *    accepting, drain its commands within [me.ahoo.wow.runtime.cluster.ClusterOptions.drainTimeout], stop its
 *    subscriptions and save their checkpoints, release its lease;
 * 2. wait until no wait is registered on the node, at most the waits'
 *    [maximum timeout][me.ahoo.wow.runtime.wait.WaitOptions.maxTimeout] after the stop began, which no wait outlives;
 * 3. [CommandGateway.close]: refuse new requests, finish the detached ones;
 * 4. every context's [WaitCoordinator.join]: the commands still executing (a wait that timed out leaves its command
 *    executing) settle normally, at most until the waits' bound of step 2, and at least
 *    [me.ahoo.wow.runtime.cluster.ClusterOptions.drainTimeout];
 * 5. every context's [WaitCoordinator.close], cancelling what still executes, then its [Executor.close], which waits
 *    at most [me.ahoo.wow.runtime.cluster.ClusterOptions.drainTimeout] for its commands to finish;
 * 6. every context's [PartitionAppendQueue.close], committing what is queued.
 *
 * [listener] hears each [Step] once it is done. A step that fails is logged and the next ones still run; [stop] then
 * throws the first failure.
 *
 * **The host** does the rest around it. Starting: open the backends ([BackendFactory]) → serve the internal port
 * (forwards to [forwardHandler], signals to [waits]) → [bind] its address, when the node was built without one →
 * [start] → serve the public port → report ready. Stopping:
 * report not ready and shut the public port down gracefully → [stop] → stop the internal port, which serves the
 * forwards and signals of the other nodes until then → close the backends. Its shutdown phase allows at least
 * the waits' maximum timeout plus `drainTimeout`.
 */
// The node's composition root: it wires every part of every context, which the parts themselves implement.
@Suppress("LargeClass")
class ServiceNode @InternalWowApi constructor(
    val service: String,
    val node: String,
    /** This node's internal address, where the cluster channel and the signals reach it; null to [bind] it later. */
    address: String?,
    models: Collection<AggregateModel<*>>,
    val manifest: Manifest,
    backends: BackendFactory,
    private val channel: ClusterChannel,
    /** Sends signals to the other nodes of the service; a single node needs none. */
    remoteSignals: SignalSender = SignalSender.NONE,
    val options: ServiceNodeOptions = ServiceNodeOptions(),
    private val admission: AdmissionPlugins = AdmissionPlugins(),
    subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
    private val listener: (Step) -> Unit = {},
    /** The kernel's codec, shared with the snapshot subscriptions. */
    codec: EventCodec = EventCodec(),
    /** The contracts of the [manifest] and its peers ([ContractDirectory.load] reads the peers on the class path). */
    val contracts: ContractDirectory = ContractDirectory(manifest),
    /** Where the subscriptions of other contexts' published streams read them; none by default. */
    private val topics: SourceTopics? = null,
    /** Hears when an entry parks and when its automatic retries are spent; nobody by default. */
    parkedNotifier: ParkedNotifier = ParkedNotifier.NONE,
    /** The clock the node's timers fire by: the system's, unless a test moves it. */
    clock: Clock = Clock.systemUTC(),
    /**
     * Sees the node's work: admissions, executions, decisions, appends, the handling of every
     * subscription and the firing of timers; nothing by default.
     */
    private val observer: Observer = Observer.NONE,
    /**
     * The client of the other contexts' command gateways, through which the sagas call them
     * synchronously; none by default, and a call then fails.
     */
    private val caller: CommandCaller? = null,
    /**
     * The application's classifiers of its handlers' failures, asked before the backends'
     * and the runtime's own ([FailureClassification.of]); none by default.
     */
    failureClassifiers: List<FailureClassifier> = emptyList(),
    /**
     * The queries the subscription handlers read with, by the tenant of the commit they handle: the
     * host's query API, resolved when a handler first reads; none by default, and a read then fails.
     */
    private val queries: ContextQueries = ContextQueries.NONE,
) {
    /** How every subscription of the node classifies its handler's failures. */
    private val classification = FailureClassification.of(failureClassifiers)

    init {
        require(service.isNotBlank()) { "A node names its service." }
        require(node.isNotBlank()) { "A node has an id." }
        require(address == null || address.isNotBlank()) { "A node has an internal address." }
        val named = manifest.service?.name
        require(named == null || named == service) { "The Manifest is of service $named, not $service." }
        ManifestCheck.check(manifest, models)
        require(contracts.local == manifest) { "The contracts are not of the node's Manifest." }
    }

    @Volatile
    private var bound: String? = address

    /** This node's internal address: the cluster channel and the signals reach it there. Known once [hasAddress]. */
    val address: String
        get() = checkNotNull(bound) { "Node $node has no internal address yet: bind it before it starts." }

    /** Whether the node knows its [address]: it was built with one, or [bind] gave it. */
    val hasAddress: Boolean get() = bound != null

    /**
     * The jobs of each context's `@OnSchedule` handlers, their defaults under the host's
     * [ServiceNodeOptions.schedules]: each context with jobs gets the built-in job aggregate `<context>.schedule`.
     */
    private val jobs: Map<String, List<ScheduleJob>> = ScheduleJobRuntime.configured(
        models.mapTo(LinkedHashSet()) { it.type.context }.associateWith { subscriptions.jobs(it) },
        options.schedules,
    )

    /** The append latency of each store the node's logs are on. */
    private val pressures = WritePressures()

    /**
     * The backends of each context the [models] are of, opened once, each call recorded and each
     * append's latency measured.
     */
    private val opened: Map<String, ContextBackends> = models.mapTo(LinkedHashSet()) { it.type.context }
        .associateWith { NodeStores.track(it, backends.backends(it), pressures) }

    /**
     * The append latency of the store [store] (`wow.stores.<name>`), as this node's logs on it measure
     * it; null when none of them is on it.
     */
    @InternalWowApi
    fun writePressure(store: String): StoreWritePressure? = pressures.find(store)

    private val nodeSnapshots = NodeSnapshots(manifest, observer)

    /**
     * The node's membership in its service on each lease store its contexts use: one record and one
     * read of the members per store, which every lease group on that store shares.
     */
    private val memberships = HashMap<LeaseStore, ServiceMembership>()

    private fun membershipOf(leases: LeaseStore): ServiceMembership = synchronized(memberships) {
        memberships.getOrPut(untracked(leases)) {
            ServiceMembership(service, node, { address }, leases, options.cluster)
        }
    }

    /** The other contexts' published events, decoded into their api classes by the [contracts]. */
    private val peerEvents = PeerEvents.of(contracts)

    /** The commands in flight in the executors of every context: one bound for the node. */
    internal val inFlight = InFlightLimit(options.executor.maxInFlight)

    /**
     * The models the node runs: the [models] of the Manifest, the built-in job aggregate of each context's [jobs], and
     * the built-in rebuild aggregate of each context that keeps snapshots.
     */
    private val served: List<AggregateModel<*>> =
        models.toList() + jobs.map { (context, contextJobs) -> ScheduleJobModel.model(context, contextJobs) } +
            nodeSnapshots.rebuildModels(opened, models)

    /** The aggregate models this node serves, the built-in job aggregates among them. */
    @InternalWowApi
    val registry: ModelRegistry = ModelRegistry(served)

    /** The waits started on this node. */
    @InternalWowApi
    val waits: WaitRegistry = WaitRegistry()

    /** Delivers signals: to [waits] when they are this node's, through the remote sender otherwise. */
    @InternalWowApi
    val signals: SignalSender = LocalSignalSender(node, service, waits, remoteSignals)

    private val origin = Origin(node, address.orEmpty(), service)

    /** Sends the commands of every context's sagas to the command topics of other contexts; null without a producer. */
    private val sender: CommandSender? = topics?.takeIf { it.producer != null }?.let { CommandSender(contracts, it) }

    /** The `@OnCommitted` handlers of each context's sagas: hops of its waits. */
    private val committedHandlers: (String) -> Set<String> = subscriptions::committedHandlers

    init {
        require(options.ledger == null || sender != null) {
            "Node $node publishes its failure ledger to ${options.ledger?.context}, through that context's command " +
                "topic, but has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers)."
        }
    }

    /** The application's notifier of parked entries, and the [ledgerPublisher] when the node publishes its ledger. */
    private val notifiers: ParkedNotifier = if (options.ledger == null) {
        parkedNotifier
    } else {
        ParkedNotifier.of(listOf(parkedNotifier, ParkedNotifier { ledgerPublisher?.notifier?.notify(it) }))
    }

    init {
        // A saga that sends to another context needs a producer: without one, each of its executions would retry and
        // park at run time while the node looked healthy (DX gap G9).
        if (sender == null) {
            val outside = contracts.commandsOutside(models.mapTo(HashSet()) { it.type.context })
            require(outside.isEmpty()) {
                "Node $node has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers), but " +
                    outside.entries.joinToString("; ") { (subscription, contexts) ->
                        "$subscription sends commands to ${contexts.joinToString()}"
                    } + ": a command across contexts goes through its context's command topic."
            }
        }
    }

    init {
        // A saga's synchronous calls: each to another context, which the node's caller reaches.
        val served = models.mapTo(HashSet()) { it.type.context }
        val problems = contracts.contextsCalled().flatMap { (subscription, contexts) ->
            contexts.mapNotNull { context ->
                when {
                    context == ContractDirectory.UNKNOWN_CONTEXT ->
                        "$subscription calls a command no Manifest of the service or its peers decides: depend on " +
                            "the api module of its context, whose jar carries its contract"

                    context in served -> "$subscription calls $context, a context of this node: derive the command"

                    caller == null || !caller.reaches(context) ->
                        "$subscription calls $context, which the node's caller does not reach (with Spring Boot, " +
                            "set wow.peers.$context.url)"

                    else -> null
                }
            }
        }
        require(problems.isEmpty()) {
            "Node $node cannot make its sagas' calls: ${problems.joinToString("; ")}."
        }
    }

    /** The contexts of the types the `Kafka` router takes: each takes the commands of its topic. */
    private val kafkaRoutedContexts: Set<String> = options.kafkaRouted.mapTo(LinkedHashSet()) { it.context }

    init {
        val unknown = options.kafkaRouted.filter { registry.find(it) == null }
        require(unknown.isEmpty()) {
            "The Kafka router is configured for $unknown, which node $node does not serve; it serves " +
                "${registry.models.map { it.type }}."
        }
        require(options.kafkaRouted.isEmpty() || topics?.producer != null) {
            "The Kafka router of ${options.kafkaRouted} writes their commands to their command topics, but node $node " +
                "has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers)."
        }
    }

    /** The node's one firing loop of every context's timers. */
    @InternalWowApi
    val timerLoop: TimerLoop = TimerLoop(options.timers, clock)

    /**
     * The aggregate states each context's cache keeps: the configured capacity, or null when it
     * is bounded by [stateCacheBytes].
     */
    @InternalWowApi
    val stateCacheCapacity: Int? = options.stateCache.capacity

    /**
     * The estimated bytes each context's cache holds when no capacity is configured: sized from the heap over the
     * contexts the node serves ([StateCacheSizing]); null when a capacity bounds it.
     */
    @InternalWowApi
    val stateCacheBytes: Long? = if (stateCacheCapacity == null) {
        StateCacheSizing.maxBytes(
            served.map { it.type.context }.distinct().size.coerceAtLeast(1),
            options.stateCache.recentRequests,
        )
    } else {
        null
    }

    private val parts: List<Parts> = served.groupBy { it.type.context }.map { (context, contextModels) ->
        assemble(context, contextModels, opened.getValue(context), codec, subscriptions)
    }

    /** Where commands run: [Routing.PARTITIONED] over every context's lease group, or [Routing.LOCAL]. */
    @InternalWowApi
    val router: CommandRouter = when (options.routing) {
        Routing.PARTITIONED -> PartitionedRouter(parts.map { it.ownership }, channel, options.cluster, observer)
        Routing.LOCAL -> CommandRouter.LOCAL
    }

    /** The parts of each bounded context, by its name. */
    @InternalWowApi
    val contexts: Map<String, ContextNode> = parts.associate { it.context to it.complete() }

    /** The names of the bounded contexts the node hosts. */
    val hostedContexts: Set<String> get() = contexts.keys

    /**
     * Whether the snapshots of an aggregate type are being rebuilt or backfilled on its primary target:
     * its context's [SnapshotMaintenance.rebuilding]; false for a type whose snapshots are not kept here.
     */
    @InternalWowApi
    val snapshotStatus: SnapshotStatus = SnapshotStatus { type ->
        contexts[type.context]?.snapshotMaintenance?.rebuilding(type) ?: false
    }

    /** Admits the commands of every context. */
    val gateway: CommandGateway = CommandGateway(
        registry,
        contexts.mapValues { it.value.coordinator },
        authorizer = admission.authorizer,
        transformers = admission.transformers,
        validators = admission.validators,
        ids = admission.ids,
        tenants = admission.tenants,
        committed = { type, tenantId, requestId ->
            contexts[type.context]?.log?.findByRequestId(type, tenantId, requestId)
        },
        observer = observer,
        peers = PeerCommands(contracts, sender),
    )

    /**
     * What the internal port serves to the cluster channel: a forwarded command goes to its context's endpoint; one
     * of a context this node does not host is refused as of an unknown type, so its sender runs it.
     */
    @InternalWowApi
    val forwardHandler: ClusterChannelHandler = ClusterChannelHandler { request ->
        contexts[request.command.ref.type.context]?.endpoint?.forward(request)
            ?: ForwardReply.refused(Refusal.UNKNOWN_TYPE)
    }

    private val applicationSubscriptions = contexts.values.associateWith { subscriptions.subscriptions(it) }

    /**
     * The subscriptions of other contexts' published streams, by the topic they read: one runtime
     * per topic on this node. Wow's own: health, pauses and tests read it; its type is internal to Wow.
     */
    @InternalWowApi
    val topicSubscriptions: Map<String, TopicSubscriptions> = topicSubscriptions(subscriptions) + receipts(subscriptions)

    /**
     * The parked entries of every subscription of the node, the built-in ones, the intakes' and those of other
     * contexts' topics included, as an operator lists and acts on them.
     */
    @InternalWowApi
    val parked: ParkedOps = ParkedOps {
        contexts.values.flatMap { context ->
            (context.logGroups.map { it.subscriptions } + listOfNotNull(context.intake)).map { ParkedOps.Scope.of(it) } +
                context.logGroups.mapNotNull { group ->
                    group.timers?.let { ParkedOps.Scope.of(context.context, it.runner, group.log.partitions) }
                }
        } + topicSubscriptions.values.map { ParkedOps.Scope.of(it, options.subscription.parked) }
    }

    /**
     * Publishes the node's failure ledger to the ops context when [ServiceNodeOptions.ledger] says
     * where: the entries whose partitions the node runs, through that context's command topic. An
     * automatic one ([LedgerPublishing.automatic]) publishes while that topic exists, asked of the
     * topics' admin at start and every partition refresh of the node's source topics.
     */
    val ledgerPublisher: LedgerPublisher? = options.ledger?.let { publishing ->
        val sender = checkNotNull(sender)
        val available: (suspend () -> Boolean)? = if (publishing.automatic) {
            val source = checkNotNull(topics)
            val topic = source.commandsOf(publishing.context)
            val admin = source.admin
            suspend { admin != null && topic in admin.partitions(setOf(topic)) }
        } else {
            null
        }
        LedgerPublisher(
            service,
            publishing,
            parked,
            { wire -> sender.send(wire) },
            ids = admission.ids.global,
            available = available,
            availability = topics?.partitionRefresh ?: publishing.interval,
        )
    }

    /**
     * The command intake of each context that takes commands from its topic, running the ledger's
     * system commands on the node's [parked] entries.
     */
    private val intakes: Map<ContextNode, CommandIntake> = contexts.values.filter { it.intake != null }.associateWith {
        val ledger = LedgerCommands(parked, options.ledger?.context ?: Ledger.OPS_CONTEXT) { ledgerPublisher }
        val ledgerOnly = !takesCommands(it.context)
        CommandIntake(
            it.context,
            it.models,
            it.coordinator,
            topics!!,
            it.creations,
            ledger,
            inFlight,
            ledgerOnly = ledgerOnly
        )
    }

    /**
     * Whether [context]'s command topic is as its ledger's commands need it, [check] says; when it is not, the node runs
     * without them, as it did before it took them, and warns: the operators' actions on its parked entries do not
     * reach it until the topic has its log's partitions.
     */
    private suspend fun ledgerTopicReady(context: String, check: suspend () -> Unit): Boolean = try {
        check()
        true
    } catch (unready: IllegalStateException) {
        logger.warn {
            "Node $node takes no ledger command of $context, so the operators' actions on its parked entries do not " +
                "reach it: ${unready.message}"
        }
        false
    }

    /** Whether [context] takes the commands of its topic: the node takes them from topics, or routes some through Kafka. */
    private fun takesCommands(context: String): Boolean = topics?.intake == true || context in kafkaRoutedContexts

    init {
        // A start configured for a subscription the node does not have is a typo, not a no-op.
        val names = contexts.values.flatMapTo(HashSet()) { context ->
            applicationSubscriptions.getValue(context).map { it.name } +
                context.snapshotSubscriptions.map { it.subscription.name } +
                listOfNotNull(
                    context.snapshotMaintenance?.rebuildSubscription?.name,
                    context.reservations?.subscription?.name,
                    context.timers?.subscription?.subscription?.name,
                ) +
                listOfNotNull(intakes[context]?.subscription?.name)
        }
        topicSubscriptions.values.flatMapTo(names) { topic -> topic.subscriptions.map { it.name } }
        options.subscription.starts.keys.filterNot { it in names }.forEach {
            throw IllegalArgumentException(
                "A start is configured for the subscription $it, which node $node does not have: ${names.sorted()}.",
            )
        }
        options.subscription.parked.specs.keys.filterNot { it in names }.forEach {
            throw IllegalArgumentException(
                "A parked retry spec is configured for the subscription $it, which node $node does not have: " +
                    "${names.sorted()}.",
            )
        }
        options.subscription.retries.keys.filterNot { it in names }.forEach {
            throw IllegalArgumentException(
                "A retry budget is configured for the subscription $it, which node $node does not have: " +
                    "${names.sorted()}.",
            )
        }
    }

    /**
     * The topics the node's contexts own and use, which [start] validates or creates as
     * [SourceTopics.ddl] says: the command topic of each context that takes commands from it, with exactly its log's
     * partitions; the receipts topic of each context that reads its rejections; with a producer, the topic of each
     * stream its contexts publish, and, under [Ddl.VALIDATE], the command topic of each peer context whose
     * contract lists commands, where the gateway sends them: that topic is the peer's, so
     * it is checked, never created. None without [topics].
     */
    val topicRequirements: List<TopicRequirement> = topicRequirements()

    private fun topicRequirements(): List<TopicRequirement> {
        val sources = topics ?: return emptyList()
        val requirements = ArrayList<TopicRequirement>()
        contexts.values.forEach { context ->
            val partitions = context.backends.log.partitions
            if (context.intake != null) {
                // A node that takes the ledger's commands alone creates the topic, and runs without them on another
                // count: it did not need the topic before it published its ledger.
                requirements += TopicRequirement(
                    sources.commandsOf(context.context),
                    partitions,
                    exact = takesCommands(context.context),
                    "the command topic of context ${context.context}, with as many partitions as its log",
                )
            }
            val receipts = sources.receiptsOf(context.context)
            if (receipts in topicSubscriptions) {
                requirements += TopicRequirement(
                    receipts,
                    partitions,
                    exact = false,
                    "the receipts topic of context ${context.context}",
                )
            }
            if (sources.producer != null) {
                manifest.contexts.firstOrNull { it.name == context.context }?.published.orEmpty().forEach {
                    requirements += TopicRequirement(
                        sources.topicOf(it.topic),
                        partitions,
                        exact = false,
                        "the published stream of ${context.context}.${it.aggregate}",
                    )
                }
            }
        }
        if (sources.producer != null && sources.ddl == Ddl.VALIDATE) {
            contracts.peers.flatMap { it.manifest.contexts }
                .filter { context -> context.aggregates.any { aggregate -> aggregate.commands.any { !it.mounted } } }
                .forEach { peer ->
                    requirements += TopicRequirement(
                        sources.commandsOf(peer.name),
                        1,
                        exact = false,
                        "the command topic of peer context ${peer.name}, where this node sends its commands",
                    )
                }
        }
        return requirements.distinctBy { it.topic }
    }

    private val lifecycle = Mutex()

    /** Where the node is in its lifecycle. */
    @Volatile
    var state: State = State.NEW
        private set

    /** Whether the node serves commands: it has started and is not stopping. */
    val isReady: Boolean get() = state == State.RUNNING

    /** The parts of [context]. */
    @InternalWowApi
    fun context(context: String): ContextNode =
        requireNotNull(contexts[context]) { "Node $node hosts no context $context." }

    /**
     * Gives the node built without an internal address the [address] its host's internal port was bound at, such as
     * one whose port the system chose: before [start], once.
     */
    fun bind(address: String) {
        require(address.isNotBlank()) { "A node has an internal address." }
        check(state == State.NEW) { "Node $node is $state; its address is bound before it starts." }
        check(bound == null) { "Node $node already has the address $bound." }
        bound = address
        val origin = Origin(node, address, service)
        contexts.values.forEach { it.coordinator.origin = origin }
    }

    /**
     * Registers the application's subscriptions and joins every context's lease group: heartbeats, reconciles, and
     * acquires its share of the partitions, starting their subscriptions. A start that fails stops what it started
     * and throws.
     *
     * With [standby] (the dual-run window of a migration's cut-over) the node starts serving
     * reads only: it registers the subscriptions but joins no lease group, so no subscription handles anything, no
     * parked entry is retried and no job or schedule runs, and its gateway refuses every command
     * ([FrameworkCodes.NODE_STANDBY]). [resume] then joins the groups, at the cut-over: a subscription that starts at
     * the end of its stream starts where the stream ends then, not where it ended when the node booted.
     */
    suspend fun start(): Unit = start(standby = false)

    /** Starts the node as [start] does, on standby when [standby] says so (see [start]). */
    suspend fun start(standby: Boolean): Unit = lifecycle.withLock {
        check(state == State.NEW) { "Node $node is $state; a node starts once." }
        check(hasAddress) { "Node $node has no internal address: bind it before it starts." }
        state = State.STARTING
        gateway.standby = standby
        try {
            topics?.let { NodeTopics.prepare(node, it, topicRequirements) }
            val running = intakes.keys.filter { context ->
                val check = suspend {
                    CommandIntake.checkPartitions(
                        topics!!.commandsOf(context.context),
                        topics.source,
                        context.context,
                        context.backends.log.partitions,
                    )
                }
                if (takesCommands(context.context)) {
                    check()
                    true
                } else {
                    ledgerTopicReady(context.context, check)
                }
            }.toSet()
            contexts.values.forEach { context ->
                applicationSubscriptions.getValue(context).forEach { subscription ->
                    context.logGroups.forEach { it.subscriptions.add(subscription) }
                }
                context.snapshotMaintenance?.prepare()?.forEach { backfill ->
                    context.logGroups.forEach { it.subscriptions.add(backfill.subscription) }
                }
                intakes[context]?.takeIf { context in running }?.let { context.intake!!.add(it.subscription) }
            }
            if (!standby) {
                join()
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // Whatever failed, what started is stopped; the failure is the start's.
            try {
                shutdown()
            } catch (@Suppress("TooGenericExceptionCaught") stopping: Exception) {
                failure.addSuppressed(stopping)
            }
            throw failure
        }
        state = State.RUNNING
        if (standby) {
            logger.warn {
                "Node $node of $service started on standby: it serves reads, refuses commands and handles no " +
                    "subscription until it is resumed. Contexts ${contexts.keys}."
            }
        } else {
            work()
            logger.info { "Node $node of $service started: contexts ${contexts.keys}." }
        }
    }

    /** Whether the node is on standby: started with `standby` and not [resume]d yet. */
    @InternalWowApi
    val standby: Boolean
        get() = gateway.standby

    /** When and by whom the node was resumed from standby; null while it is on standby or never was. */
    @InternalWowApi
    @Volatile
    var resumption: Resumption? = null
        private set

    /**
     * Resumes a node started on standby, at a migration's cut-over: joins every context's lease group and every topic's,
     * starting the subscriptions there (one that starts at the end of its stream starts where the stream ends now),
     * the retries of the parked entries, the jobs and the snapshot maintenance, and then admits commands. It is the
     * operator's act, recorded for the audit: [by] names who resumed it and [reason] why, both logged with the time.
     * A node not on standby is left as it is: the answer says when it was resumed, if it ever was. A resume that fails
     * stops the node and throws.
     */
    @InternalWowApi
    suspend fun resume(by: String, reason: String? = null): Resumption = lifecycle.withLock {
        check(state == State.RUNNING) { "Node $node is $state; only a running node resumes." }
        if (!standby) {
            return@withLock resumption ?: Resumption(node, null, by, reason, resumed = false)
        }
        try {
            join()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            logger.error(failure) { "Node $node of $service failed to resume from standby; stopping." }
            try {
                shutdown()
            } catch (@Suppress("TooGenericExceptionCaught") stopping: Exception) {
                failure.addSuppressed(stopping)
            }
            throw failure
        }
        work()
        gateway.standby = false
        Resumption(node, System.currentTimeMillis(), by, reason, resumed = true).also {
            resumption = it
            logger.warn {
                "Node $node of $service resumed from standby by [$by]${reason?.let { r -> " ($r)" }.orEmpty()} at " +
                    "${java.time.Instant.ofEpochMilli(checkNotNull(it.at))}: it handles its subscriptions and admits " +
                    "commands. Contexts ${contexts.keys}."
            }
        }
    }

    /** Joins every context's lease group and every topic's, and starts the ledger's publisher. */
    private suspend fun join() {
        contexts.values.forEach { it.ownership.start() }
        topicSubscriptions.values.forEach { it.start() }
        ledgerPublisher?.start()
    }

    /** Starts the jobs and the snapshot maintenance, in the background. */
    private fun work() {
        startJobs()
        contexts.values.forEach { it.snapshotMaintenance?.start(background) }
    }

    /** Runs the node's background work: the startup of the SYSTEM jobs; cancelled when the node stops. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Brings each context's SYSTEM jobs in line with the Manifest and the host's configuration, in the
     * background: enables each instance (a no-op when nothing changed, a reschedule when its default did), or
     * disables it, retrying until each settles.
     */
    private fun startJobs() {
        jobs.forEach { (context, contextJobs) ->
            val node = contexts.getValue(context)
            val commands = ScheduleJobRuntime.startup(
                context,
                contextJobs,
                options.schedules,
                node.backends.log.partitions,
            )
            if (commands.isEmpty()) {
                return@forEach
            }
            val model = registry.models.first { it.type == ScheduleJobs.typeOf(context) }
            background.launch {
                ScheduleJobRuntime.send(model, commands, options.timers.retry, admission.ids.global::next) { m, c ->
                    node.coordinator.forward(m, c, null)
                }
            }
        }
    }

    /** Leaves the cluster and closes the node, in the order the class describes. Stopping twice does nothing. */
    suspend fun stop(): Unit = lifecycle.withLock {
        when (state) {
            State.NEW -> state = State.STOPPED
            State.STOPPED -> Unit
            else -> shutdown()
        }
    }

    private suspend fun shutdown() {
        state = State.STOPPING
        background.coroutineContext[Job]?.cancelAndJoin()
        val waitsEnd = TimeSource.Monotonic.markNow() + options.wait.maxTimeout
        var failure: Exception? = null
        suspend fun step(step: Step, block: suspend () -> Unit) {
            try {
                block()
                logger.info { "Node $node of $service: $step." }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failed: Exception) {
                logger.warn(failed) { "Node $node of $service failed to reach $step; stopping on." }
                failure = failure ?: failed
            }
            listener(step)
        }
        step(Step.HANDED_OFF) { handOff() }
        step(Step.WAITS_ENDED) {
            val ended = withTimeoutOrNull(-waitsEnd.elapsedNow()) {
                while (waits.size > 0) {
                    delay(WAIT_POLL)
                }
            }
            if (ended == null && waits.size > 0) {
                logger.warn { "Node $node of $service stops with ${waits.size} waits still registered." }
            }
        }
        step(Step.GATEWAY_CLOSED) { gateway.close() }
        step(Step.EXECUTIONS_ENDED) {
            val limit = maxOf(-waitsEnd.elapsedNow(), options.cluster.drainTimeout)
            withTimeoutOrNull(limit) { contexts.values.forEach { it.coordinator.join() } } ?: logger.warn {
                val executing = contexts.values.sumOf { it.coordinator.executing }
                "Node $node of $service closes its executors with $executing commands still executing."
            }
        }
        step(Step.EXECUTORS_CLOSED) {
            // What still executes is cancelled first: the executors would otherwise wait for exactly those commands.
            contexts.values.forEach { it.coordinator.close() }
            coroutineScope {
                contexts.values.forEach { launch { it.executor.close(options.cluster.drainTimeout) } }
            }
        }
        step(Step.LOGS_CLOSED) {
            timerLoop.close()
            contexts.values.forEach { it.log.close() }
        }
        state = State.STOPPED
        logger.info { "Node $node of $service stopped." }
        failure?.let { throw it }
    }

    /**
     * Hands off every context's partitions and every topic's, concurrently, and leaves the snapshot state revisions.
     */
    private suspend fun handOff() = coroutineScope {
        ledgerPublisher?.stop()
        contexts.values.forEach { context ->
            launch {
                context.ownership.stop()
                context.snapshotMaintenance?.stop()
            }
        }
        topicSubscriptions.values.forEach { launch { it.stop() } }
    }

    /**
     * The runtimes of the topics the [application]'s sourced subscriptions read: each type's published stream, by its
     * topic in [topics].
     */
    private fun topicSubscriptions(application: SubscriptionFactory): Map<String, TopicSubscriptions> {
        val readers = LinkedHashMap<String, MutableList<Pair<ContextNode, Subscription>>>()
        contexts.values.forEach { context ->
            application.sourced(context).forEach { sourced ->
                sourced.types.forEach { type ->
                    val name = sourced.subscription.name
                    val published = requireNotNull(contracts.publishedOf(type)) {
                        "$name of ${context.context} reads $type, which no Manifest of the service or its peers publishes."
                    }
                    require(sourced.subscription.context == context.context) {
                        "$name reads $type for ${context.context}, but belongs to ${sourced.subscription.context}: " +
                            "a sourced subscription belongs to the context that reads (Subscription.context)."
                    }
                    val sources = requireNotNull(topics) {
                        "$name of ${context.context} reads the topic ${published.topic} of $type, but node $node reads " +
                            "no topics: give it their source (with Spring Boot, set wow.kafka.bootstrap-servers)."
                    }
                    val readersOfTopic = readers.getOrPut(sources.topicOf(published.topic)) { ArrayList() }
                    if (readersOfTopic.none { it.second === sourced.subscription }) {
                        readersOfTopic += context to sourced.subscription
                    }
                }
            }
        }
        return readers.mapValues { (topic, read) ->
            val backends = read.first().first.backends
            TopicSubscriptions(
                service = service,
                topic = topic,
                node = node,
                addressOf = { address },
                source = topics!!.source,
                checkpoints = backends.checkpoints,
                leases = backends.leases,
                membership = membershipOf(backends.leases),
                ledger = backends.ledger,
                subscriptions = read.map { it.second },
                options = options.subscription,
                cluster = options.cluster,
                signals = signals,
                notifier = notifiers,
                partitionRefresh = topics!!.partitionRefresh,
                observer = observer,
                classification = classification,
            )
        }
    }

    /**
     * The runtimes of the receipts topics of the contexts whose sagas the [application] gives `@OnRejected` or
     * `@OnCommitted` handlers ([SubscriptionFactory.receipts]), each in the lease group of its topic and the stores of its
     * context. None without [topics]: no command left for another context then.
     */
    private fun receipts(application: SubscriptionFactory): Map<String, TopicSubscriptions> {
        val sources = topics ?: return emptyMap()
        return contexts.values.mapNotNull { context ->
            val handler = application.receipts(context) ?: return@mapNotNull null
            val topic = sources.receiptsOf(context.context)
            val receipts = Receipts(context.context, contracts, handler)
            topic to TopicSubscriptions(
                service = service,
                topic = topic,
                node = node,
                addressOf = { address },
                source = sources.source,
                checkpoints = context.backends.checkpoints,
                leases = context.backends.leases,
                membership = membershipOf(context.backends.leases),
                ledger = context.backends.ledger,
                subscriptions = listOf(receipts.subscription),
                options = options.subscription,
                cluster = options.cluster,
                clock = Clock.systemUTC(),
                context = Dispatchers.Default,
                signals = signals,
                notifier = notifiers,
                entries = { partitions -> MessageEntries.receipts(topic, sources.source, partitions, observer) },
                partitionRefresh = sources.partitionRefresh,
                observer = observer,
                classification = classification,
            )
        }.toMap()
    }

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

    private fun assemble(
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
    private inner class Parts(
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
        fun complete(): ContextNode {
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

    /** The `Kafka` router of [context]'s types in [ServiceNodeOptions.kafkaRouted], if any. */
    private fun topicRouterOf(context: String, backends: ContextBackends): TopicRouter? {
        val types = options.kafkaRouted.filterTo(LinkedHashSet()) { it.context == context }
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
    private class LateFirer : TimerFirer {
        @Volatile
        lateinit var firer: TimerFirer

        override suspend fun fire(timer: Timer): String = firer.fire(timer)
    }

    /** Where a node is in its lifecycle. */
    enum class State { NEW, STARTING, RUNNING, STOPPING, STOPPED }

    /** The steps of [stop], in their order. */
    enum class Step {
        /** Every context's partitions are handed off, its subscriptions stopped with their checkpoints saved. */
        HANDED_OFF,

        /** No wait is registered on the node any more, or the longest wait's timeout has passed. */
        WAITS_ENDED,

        /** The gateway refuses new requests, and its detached commands have finished. */
        GATEWAY_CLOSED,

        /** No command executes in a coordinator any more, or the bound on waiting for them has passed. */
        EXECUTIONS_ENDED,

        /**
         * Every context's coordinator is closed, cancelling what still executes, and its executor closed: its commands
         * finished, or the bound on waiting for them passed.
         */
        EXECUTORS_CLOSED,

        /** Every context's append queue is closed, what was queued committed. */
        LOGS_CLOSED,
    }

    companion object {
        private val WAIT_POLL = 10.milliseconds
        private val logger = KotlinLogging.logger {}

        /**
         * The aggregate models of [providers] (by default, those registered on the class path), their
         * factories' parameters from [dependencies]: a [me.ahoo.wow.kernel.MissingDependencyException] when one is
         * missing, so the node does not start.
         */
        fun models(
            dependencies: Dependencies,
            providers: List<ModelProvider> = ModelProvider.load(),
        ): List<AggregateModel<*>> = providers.flatMap { provider ->
            provider.models(dependencies).onEach {
                require(it.type.context == provider.context) {
                    "${provider.fragment} provides ${it.type}, which is not of its context ${provider.context}."
                }
            }
        }
    }
}

/**
 * The resumption of a node started on standby ([ServiceNode.resume]): the [node], when it was resumed ([at], epoch
 * milliseconds; null for a node that never was), [by] whom and why ([reason]). [resumed] is false when the node was
 * not on standby, so the call changed nothing.
 */
@InternalWowApi
data class Resumption(val node: String, val at: Long?, val by: String, val reason: String?, val resumed: Boolean)

/** The state views an executor records commits in: none when no reader of the context reads any aggregate type. */
private fun StateViews.forExecutor(): StateViews? = takeIf { it.types.isNotEmpty() }
