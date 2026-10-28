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

import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.StateCacheSizing
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.admission.PeerCommands
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.PartitionedRouter
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.cluster.ServiceMembership
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
import me.ahoo.wow.runtime.manifest.ManifestCheck
import me.ahoo.wow.runtime.schedule.ScheduleJobRuntime
import me.ahoo.wow.runtime.snapshot.SnapshotMaintenance
import me.ahoo.wow.runtime.snapshot.SnapshotStatus
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.subscription.FailureClassification
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.timer.TimerLoop
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.wait.Origin
import java.time.Clock

/**
 * The composition root of a [ServiceNode]: it builds every part of the node, in this order, from what the host gives
 * it, and refuses a node that is misconfigured ([NodeChecks]) before the parts it would break. Each bounded context is
 * wired by [ContextAssembly], the topics by [TopicAssembly]; what every part shares is the [NodeWiring].
 */
@Suppress("LongParameterList")
internal class NodeAssembly(
    val service: String,
    val node: String,
    /** This node's internal address, where the cluster channel and the signals reach it; null to [bind] it later. */
    address: String?,
    models: Collection<AggregateModel<*>>,
    val manifest: Manifest,
    backends: BackendFactory,
    val channel: ClusterChannel,
    /** Sends signals to the other nodes of the service; a single node needs none. */
    remoteSignals: SignalSender = SignalSender.NONE,
    val options: ServiceNodeOptions = ServiceNodeOptions(),
    val admission: AdmissionPlugins = AdmissionPlugins(),
    subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
    /** The kernel's codec, shared with the snapshot subscriptions. */
    codec: EventCodec = EventCodec(),
    /** The contracts of the [manifest] and its peers ([ContractDirectory.load] reads the peers on the class path). */
    val contracts: ContractDirectory = ContractDirectory(manifest),
    /** Where the subscriptions of other contexts' published streams read them; none by default. */
    val topics: SourceTopics? = null,
    /** Hears when an entry parks and when its automatic retries are spent; nobody by default. */
    parkedNotifier: ParkedNotifier = ParkedNotifier.NONE,
    /** The clock the node's timers fire by: the system's, unless a test moves it. */
    clock: Clock = Clock.systemUTC(),
    /**
     * Sees the node's work: admissions, executions, decisions, appends, the handling of every
     * subscription and the firing of timers; nothing by default.
     */
    val observer: Observer = Observer.NONE,
    /**
     * The client of the other contexts' command gateways, through which the sagas call them
     * synchronously; none by default, and a call then fails.
     */
    val caller: CommandCaller? = null,
    /**
     * The application's classifiers of its handlers' failures, asked before the backends'
     * and the runtime's own ([FailureClassification.of]); none by default.
     */
    failureClassifiers: List<FailureClassifier> = emptyList(),
    /**
     * The queries the subscription handlers read with, by the tenant of the commit they handle: the
     * host's query API, resolved when a handler first reads; none by default, and a read then fails.
     */
    val queries: ContextQueries = ContextQueries.NONE,
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
    val jobs: Map<String, List<ScheduleJob>> = ScheduleJobRuntime.configured(
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
    val inFlight = InFlightLimit(options.executor.maxInFlight)

    /**
     * The models the node runs: the [models] of the Manifest, the built-in job aggregate of each context's [jobs], and
     * the built-in rebuild aggregate of each context that keeps snapshots.
     */
    private val served: List<AggregateModel<*>> =
        models.toList() + jobs.map { (context, contextJobs) -> ScheduleJobModel.model(context, contextJobs) } +
            nodeSnapshots.rebuildModels(opened, models)

    /** The aggregate models this node serves, the built-in job aggregates among them. */
    val registry: ModelRegistry = ModelRegistry(served)

    /** The waits started on this node. */
    val waits: WaitRegistry = WaitRegistry()

    /** Delivers signals: to [waits] when they are this node's, through the remote sender otherwise. */
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
        NodeChecks.topicRouted(node, options.topicRouted, registry, topics?.producer != null)
    }

    /** The node's one firing loop of every context's timers. */
    val timerLoop: TimerLoop = TimerLoop(options.timers, clock)

    /**
     * The aggregate states each context's cache keeps: the configured capacity, or null when it
     * is bounded by [stateCacheBytes].
     */
    val stateCacheCapacity: Int? = options.stateCache.capacity

    /**
     * The estimated bytes each context's cache holds when no capacity is configured: sized from the heap over the
     * contexts the node serves ([StateCacheSizing]); null when a capacity bounds it.
     */
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
    val router: CommandRouter = when (options.routing) {
        Routing.PARTITIONED -> PartitionedRouter(parts.map { it.ownership }, channel, options.cluster, observer)
        Routing.LOCAL -> CommandRouter.LOCAL
    }

    /** The parts of each bounded context, by its name. */
    val contexts: Map<String, ContextNode> = parts.associate { it.context to it.complete(router) }

    /** The names of the bounded contexts the node hosts. */
    val hostedContexts: Set<String> get() = contexts.keys

    /**
     * Whether the snapshots of an aggregate type are being rebuilt or backfilled on its primary target:
     * its context's [SnapshotMaintenance.rebuilding]; false for a type whose snapshots are not kept here.
     */
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
    val forwardHandler: ClusterChannelHandler = ClusterChannelHandler { request ->
        contexts[request.command.ref.type.context]?.endpoint?.forward(request)
            ?: ForwardReply.refused(Refusal.UNKNOWN_TYPE)
    }

    val applicationSubscriptions = contexts.values.associateWith { subscriptions.subscriptions(it) }

    /**
     * The subscriptions of other contexts' published streams, by the topic they read: one runtime
     * per topic on this node. Wow's own: health, pauses and tests read it; its type is internal to Wow.
     */
    val topicSubscriptions: Map<String, TopicSubscriptions> = topicAssembly.subscriptions(
        contexts.values,
        subscriptions
    )

    /**
     * The parked entries of every subscription of the node, the built-in ones, the intakes' and those of other
     * contexts' topics included, as an operator lists and acts on them.
     */
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
    val intakes: Map<ContextNode, CommandIntake> =
        topicAssembly.intakes(contexts.values, parked) { ledgerPublisher }

    fun takesCommands(context: String): Boolean = wiring.takesCommands(context)

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

    /** Gives a node built without an internal address the [address] it was bound at, once. */
    fun bind(address: String) {
        check(bound == null) { "Node $node already has the address $bound." }
        bound = address
        val origin = Origin(node, address, service)
        contexts.values.forEach { it.coordinator.origin = origin }
    }
}
