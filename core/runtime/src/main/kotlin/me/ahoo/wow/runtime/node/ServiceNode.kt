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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.cluster.TopicRouter
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.health.StoreWritePressure
import me.ahoo.wow.runtime.ledger.LedgerPublisher
import me.ahoo.wow.runtime.manifest.ManifestCheck
import me.ahoo.wow.runtime.snapshot.SnapshotMaintenance
import me.ahoo.wow.runtime.snapshot.SnapshotStatus
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.subscription.FailureClassification
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.timer.TimerLoop
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClassifier
import java.time.Clock

/**
 * One node of a service, assembled without a host: what a host (Spring Boot, a test, a
 * sample) wires to its ports.
 *
 * **Per bounded context** of the [models] ([contexts]): its log, kernel, executor, built-in subscriptions, lease
 * group ownership, wait coordinator and forward endpoint, which [ContextAssembly] wires.
 *
 * **Per node**: the [waits] registered here, the [signals] sender (a signal to this node goes straight to [waits],
 * one to another node through the host's remote sender), the [router] over every context's lease group, the
 * [gateway] that admits commands, the [registry] of models, the [forwardHandler] that hands a forwarded command
 * to its context's endpoint, and the [parked] entries of every subscription as an operator acts on them; each runtime
 * retries the entries of the partitions it runs over the long horizon and tells the [parkedNotifier] when one parks or
 * spends its retries.
 *
 * **Per topic** read for the subscriptions of other contexts' published streams, and for the receipts of its
 * sagas' commands: one [TopicSubscriptions] each, which [TopicAssembly] builds; a node with such subscriptions and no
 * [topics] is not built.
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
 * **The topic router**: the commands admitted for the aggregate types of
 * [ServiceNodeOptions.topicRouted] are written to their context's command topic ([TopicRouter]) and executed by the
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
 * **Lifecycle** ([state]): [start] joins every context's lease group and the node is then [isReady]; [stop] hands
 * off, waits for the waits, closes the gateway, lets the executions end, closes the executors and the append queues,
 * in that order ([NodeLifecycle]). [listener] hears each [Step] once it is done; a step that fails is logged and the
 * next ones still run, and [stop] then throws the first failure.
 *
 * **The host** does the rest around it. Starting: open the backends ([BackendFactory]) → serve the internal port
 * (forwards to [forwardHandler], signals to [waits]) → [bind] its address, when the node was built without one →
 * [start] → serve the public port → report ready. Stopping:
 * report not ready and shut the public port down gracefully → [stop] → stop the internal port, which serves the
 * forwards and signals of the other nodes until then → close the backends. Its shutdown phase allows at least
 * the waits' maximum timeout plus `drainTimeout`.
 */
// The facade of a node: NodeAssembly builds it, NodeLifecycle runs it.
class ServiceNode @InternalWowApi constructor(
    val service: String,
    val node: String,
    /** This node's internal address, where the cluster channel and the signals reach it; null to [bind] it later. */
    address: String?,
    models: Collection<AggregateModel<*>>,
    val manifest: Manifest,
    backends: BackendFactory,
    channel: ClusterChannel,
    /** Sends signals to the other nodes of the service; a single node needs none. */
    remoteSignals: SignalSender = SignalSender.NONE,
    val options: ServiceNodeOptions = ServiceNodeOptions(),
    admission: AdmissionPlugins = AdmissionPlugins(),
    subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
    private val listener: (Step) -> Unit = {},
    /** The kernel's codec, shared with the snapshot subscriptions. */
    codec: EventCodec = EventCodec(),
    /** The contracts of the [manifest] and its peers ([ContractDirectory.load] reads the peers on the class path). */
    val contracts: ContractDirectory = ContractDirectory(manifest),
    /** Where the subscriptions of other contexts' published streams read them; none by default. */
    topics: SourceTopics? = null,
    /** Hears when an entry parks and when its automatic retries are spent; nobody by default. */
    parkedNotifier: ParkedNotifier = ParkedNotifier.NONE,
    /** The clock the node's timers fire by: the system's, unless a test moves it. */
    clock: Clock = Clock.systemUTC(),
    /**
     * Sees the node's work: admissions, executions, decisions, appends, the handling of every
     * subscription and the firing of timers; nothing by default.
     */
    observer: Observer = Observer.NONE,
    /**
     * The client of the other contexts' command gateways, through which the sagas call them
     * synchronously; none by default, and a call then fails.
     */
    caller: CommandCaller? = null,
    /**
     * The application's classifiers of its handlers' failures, asked before the backends'
     * and the runtime's own ([FailureClassification.of]); none by default.
     */
    failureClassifiers: List<FailureClassifier> = emptyList(),
    /**
     * The queries the subscription handlers read with, by the tenant of the commit they handle: the
     * host's query API, resolved when a handler first reads; none by default, and a read then fails.
     */
    queries: ContextQueries = ContextQueries.NONE,
) {
    private val assembly = NodeAssembly(
        service,
        node,
        address,
        models,
        manifest,
        backends,
        channel,
        remoteSignals,
        options,
        admission,
        subscriptions,
        codec,
        contracts,
        topics,
        parkedNotifier,
        clock,
        observer,
        caller,
        failureClassifiers,
        queries,
    )

    /** This node's internal address: the cluster channel and the signals reach it there. Known once [hasAddress]. */
    val address: String get() = assembly.address

    /** Whether the node knows its [address]: it was built with one, or [bind] gave it. */
    val hasAddress: Boolean get() = assembly.hasAddress

    /**
     * The append latency of the store [store] (`wow.stores.<name>`), as this node's logs on it measure
     * it; null when none of them is on it.
     */
    @InternalWowApi
    fun writePressure(store: String): StoreWritePressure? = assembly.writePressure(store)

    /** The commands in flight in the executors of every context: one bound for the node. */
    internal val inFlight: InFlightLimit get() = assembly.inFlight

    /** The aggregate models this node serves, the built-in job aggregates among them. */
    @InternalWowApi
    val registry: ModelRegistry get() = assembly.registry

    /** The waits started on this node. */
    @InternalWowApi
    val waits: WaitRegistry get() = assembly.waits

    /** Delivers signals: to [waits] when they are this node's, through the remote sender otherwise. */
    @InternalWowApi
    val signals: SignalSender get() = assembly.signals

    /** The node's one firing loop of every context's timers. */
    @InternalWowApi
    val timerLoop: TimerLoop get() = assembly.timerLoop

    /**
     * The aggregate states each context's cache keeps: the configured capacity, or null when it
     * is bounded by [stateCacheBytes].
     */
    @InternalWowApi
    val stateCacheCapacity: Int? get() = assembly.stateCacheCapacity

    /**
     * The estimated bytes each context's cache holds when no capacity is configured: sized from the heap over the
     * contexts the node serves; null when a capacity bounds it.
     */
    @InternalWowApi
    val stateCacheBytes: Long? get() = assembly.stateCacheBytes

    /** Where commands run: [Routing.PARTITIONED] over every context's lease group, or [Routing.LOCAL]. */
    @InternalWowApi
    val router: CommandRouter get() = assembly.router

    /** The parts of each bounded context, by its name. */
    @InternalWowApi
    val contexts: Map<String, ContextNode> get() = assembly.contexts

    /** The names of the bounded contexts the node hosts. */
    val hostedContexts: Set<String> get() = contexts.keys

    /** The node's clock: every wall time the node reads, its timers', leases' and waits' alike. */
    val clock: Clock get() = assembly.clock

    /**
     * Whether the snapshots of an aggregate type are being rebuilt or backfilled on its primary target:
     * its context's [SnapshotMaintenance.rebuilding]; false for a type whose snapshots are not kept here.
     */
    @InternalWowApi
    val snapshotStatus: SnapshotStatus get() = assembly.snapshotStatus

    /** Admits the commands of every context. */
    val gateway: CommandGateway get() = assembly.gateway

    /**
     * What the internal port serves to the cluster channel: a forwarded command goes to its context's endpoint; one
     * of a context this node does not host is refused as of an unknown type, so its sender runs it.
     */
    @InternalWowApi
    val forwardHandler: ClusterChannelHandler get() = assembly.forwardHandler

    /**
     * The subscriptions of other contexts' published streams, by the topic they read: one runtime
     * per topic on this node. Wow's own: health, pauses and tests read it; its type is internal to Wow.
     */
    @InternalWowApi
    val topicSubscriptions: Map<String, TopicSubscriptions> get() = assembly.topicSubscriptions

    /**
     * The parked entries of every subscription of the node, the built-in ones, the intakes' and those of other
     * contexts' topics included, as an operator lists and acts on them.
     */
    @InternalWowApi
    val parked: ParkedOps get() = assembly.parked

    /**
     * Publishes the node's failure ledger to the ops context when [ServiceNodeOptions.ledger] says
     * where: the entries whose partitions the node runs, through that context's command topic.
     */
    val ledgerPublisher: LedgerPublisher? get() = assembly.ledgerPublisher

    /**
     * The topics the node's contexts own and use, which [start] validates or creates as
     * [SourceTopics.ddl] says: the command topic of each context that takes commands from it, with exactly its log's
     * partitions; the receipts topic of each context that reads its rejections; with a producer, the topic of each
     * stream its contexts publish, and, under [Ddl.VALIDATE], the command topic of each peer context whose
     * contract lists commands, where the gateway sends them: that topic is the peer's, so
     * it is checked, never created. None without [topics].
     */
    val topicRequirements: List<TopicRequirement> get() = assembly.topicRequirements

    private val lifecycle = NodeLifecycle(assembly, listener)

    /** Where the node is in its lifecycle. */
    val state: State get() = lifecycle.state

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
        assembly.bind(address)
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
    suspend fun start(standby: Boolean): Unit = lifecycle.start(standby)

    /** Whether the node is on standby: started with `standby` and not [resume]d yet. */
    @InternalWowApi
    val standby: Boolean get() = lifecycle.standby

    /** When and by whom the node was resumed from standby; null while it is on standby or never was. */
    @InternalWowApi
    val resumption: Resumption? get() = lifecycle.resumption

    /**
     * Resumes a node started on standby, at a migration's cut-over: joins every context's lease group and every topic's,
     * starting the subscriptions there (one that starts at the end of its stream starts where the stream ends now),
     * the retries of the parked entries, the jobs and the snapshot maintenance, and then admits commands. It is the
     * operator's act, recorded for the audit: [by] names who resumed it and [reason] why, both logged with the time.
     * A node not on standby is left as it is: the answer says when it was resumed, if it ever was. A resume that fails
     * stops the node and throws.
     */
    @InternalWowApi
    suspend fun resume(by: String, reason: String? = null): Resumption = lifecycle.resume(by, reason)

    /** Leaves the cluster and closes the node, in the order [NodeLifecycle] describes. Stopping twice does nothing. */
    suspend fun stop(): Unit = lifecycle.stop()

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
