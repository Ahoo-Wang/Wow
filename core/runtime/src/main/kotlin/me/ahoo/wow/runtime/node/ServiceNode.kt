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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.StateCacheSizing
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.admission.PeerCommands
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.cluster.ForwardEndpoint
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.cluster.PartitionedRouter
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.cluster.ServiceMembership
import me.ahoo.wow.runtime.cluster.TopicRouter
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.contract.PeerEvents
import me.ahoo.wow.runtime.health.NodeStores
import me.ahoo.wow.runtime.health.StoreWritePressure
import me.ahoo.wow.runtime.health.WritePressures
import me.ahoo.wow.runtime.health.untracked
import me.ahoo.wow.runtime.ledger.LedgerPublisher
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.log.PartitionAppendQueue
import me.ahoo.wow.runtime.manifest.ManifestCheck
import me.ahoo.wow.runtime.reservation.ReservationSubscription
import me.ahoo.wow.runtime.schedule.ScheduleJobRuntime
import me.ahoo.wow.runtime.snapshot.SnapshotMaintenance
import me.ahoo.wow.runtime.snapshot.SnapshotStatus
import me.ahoo.wow.runtime.snapshot.SnapshotSubscription
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.subscription.FailureClassification
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerLoop
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.runtime.timer.TimersSubscription
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.wait.Origin
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
        NodeChecks.ledgerTransport(node, options.ledger, sender)
    }

    /** The application's notifier of parked entries, and the [ledgerPublisher] when the node publishes its ledger. */
    private val notifiers: ParkedNotifier = if (options.ledger == null) {
        parkedNotifier
    } else {
        ParkedNotifier.of(listOf(parkedNotifier, ParkedNotifier { ledgerPublisher?.notifier?.notify(it) }))
    }

    init {
        val served = models.mapTo(HashSet()) { it.type.context }
        NodeChecks.commandsAcross(node, contracts, served, sender)
        NodeChecks.calls(node, contracts, served, caller)
    }

    init {
        NodeChecks.kafkaRouted(node, options.kafkaRouted, registry, topics?.producer != null)
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

    /** What every part of the node shares. */
    private val wiring = NodeWiring(
        service,
        node,
        { this.address },
        options,
        admission,
        observer,
        channel,
        contracts,
        topics,
        registry,
        waits,
        signals,
        origin,
        sender,
        caller,
        queries,
        peerEvents,
        notifiers,
        classification,
        inFlight,
        timerLoop,
        nodeSnapshots,
        stateCacheCapacity,
        stateCacheBytes,
        ::membershipOf,
        committedHandlers,
    )

    private val contextAssembly = ContextAssembly(wiring)

    private val topicAssembly = TopicAssembly(wiring, manifest)

    private val parts: List<ContextAssembly.Parts> = served.groupBy { it.type.context }.map { (context, contextModels) ->
        contextAssembly.assemble(context, contextModels, opened.getValue(context), codec, subscriptions)
    }

    /** Where commands run: [Routing.PARTITIONED] over every context's lease group, or [Routing.LOCAL]. */
    @InternalWowApi
    val router: CommandRouter = when (options.routing) {
        Routing.PARTITIONED -> PartitionedRouter(parts.map { it.ownership }, channel, options.cluster, observer)
        Routing.LOCAL -> CommandRouter.LOCAL
    }

    /** The parts of each bounded context, by its name. */
    @InternalWowApi
    val contexts: Map<String, ContextNode> = parts.associate { it.context to it.complete(router) }

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
    val topicSubscriptions: Map<String, TopicSubscriptions> = topicAssembly.subscriptions(
        contexts.values,
        subscriptions
    )

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
    private val intakes: Map<ContextNode, CommandIntake> =
        topicAssembly.intakes(contexts.values, parked) { ledgerPublisher }

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
    private fun takesCommands(context: String): Boolean = wiring.takesCommands(context)

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
        NodeChecks.subscriptionsNamed(node, names, options.subscription)
    }

    /**
     * The topics the node's contexts own and use, which [start] validates or creates as
     * [SourceTopics.ddl] says: the command topic of each context that takes commands from it, with exactly its log's
     * partitions; the receipts topic of each context that reads its rejections; with a producer, the topic of each
     * stream its contexts publish, and, under [Ddl.VALIDATE], the command topic of each peer context whose
     * contract lists commands, where the gateway sends them: that topic is the peer's, so
     * it is checked, never created. None without [topics].
     */
    val topicRequirements: List<TopicRequirement> = topicAssembly.requirements(contexts.values, topicSubscriptions)

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
