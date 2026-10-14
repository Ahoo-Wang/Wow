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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.StateCache
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.cluster.ForwardEndpoint
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.cluster.PartitionedRouter
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.command.MessageEntries
import me.ahoo.wow.runtime.command.Receipts
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.log.PartitionAppendQueue
import me.ahoo.wow.runtime.manifest.ManifestCheck
import me.ahoo.wow.runtime.reservation.ReservationSubscription
import me.ahoo.wow.runtime.snapshot.SnapshotSubscription
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.state.StateViewReader
import me.ahoo.wow.runtime.state.StateViews
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.ContextCommandTypes
import me.ahoo.wow.runtime.timer.ContextTimerFirer
import me.ahoo.wow.runtime.timer.SagaTimers
import me.ahoo.wow.runtime.timer.TimerFirer
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.runtime.timer.TimersSubscription
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.wait.Origin
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The parts of one bounded context on a node (decisions D72, D108): the log it appends through ([log], the partition
 * append queue over the backend's log), its [kernel] and [executor], the built-in [snapshots] and [reservations]
 * subscriptions when the context has their stores, the [subscriptions] runtime of its lease [group], this node's
 * [ownership] of the group's partitions, its wait [coordinator], and the [endpoint] that executes the commands other
 * nodes forward to it.
 *
 * The application's subscriptions read the context's [backends], the kernel's [codec], and the state [views] the
 * executor takes of the aggregate types its state consumers read: the snapshot subscription and the readers the
 * application's subscriptions name ([SubscriptionFactory.stateReaders]), each reading through its [stateReader]
 * (decision D133).
 */
class ContextNode internal constructor(
    val context: String,
    val models: List<AggregateModel<*>>,
    val group: LeaseGroup,
    val log: PartitionAppendQueue,
    val kernel: Kernel,
    val executor: Executor,
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
     * The runtime of the context's command topic (decision D137), driven by [ownership]: null unless the node takes
     * commands from topics ([SourceTopics.intake]).
     */
    val intake: SubscriptionRuntime? = null,
    /** Sends the commands of the context's sagas to other contexts (decision D33); null without a producer. */
    val sender: CommandSender? = null,
    /**
     * The built-in `timers` subscription and the context's timer runner (decision D166), when its backends have a
     * timer store: the runner's [TimerRunner.resume] and [TimerRunner.skip] serve an operator.
     */
    val timers: ContextTimers? = null,
    /** The node's id generators (decision D171), the ids its sagas take. */
    val ids: IdGenerators = IdGenerators.DEFAULT,
) {
    /**
     * Reads the state after a commit as the state consumer [reader] of [views] ([StateViewReader]), from the
     * context's snapshots when its views miss: what [me.ahoo.wow.runtime.subscription.EntryDecoder.stateEvents]
     * decodes with.
     */
    fun stateReader(reader: String): StateViewReader =
        StateViewReader(reader, models, kernel, codec, views, backends.snapshots)
}

/**
 * The scheduled commands of one context on a node (decision D166): the built-in `timers` [subscription] writes the
 * timers its aggregates schedule, [sagas] those its sagas schedule, and the [runner] sends them from the partitions
 * the node owns.
 */
class ContextTimers internal constructor(
    val subscription: TimersSubscription,
    val runner: TimerRunner,
    val sagas: SagaTimers,
)

/**
 * One node of a service, assembled without a host (decision D108; roadmap 4.8): what a host (Spring Boot, a test, a
 * sample) wires to its ports.
 *
 * **Per bounded context** of the [models] ([contexts]): a [PartitionAppendQueue] over the context's log, configured by
 * [ServiceNodeOptions.appendQueue] → a [Kernel] with its state cache, snapshot and reservation stores → an [Executor]
 * recording the state views the context's state consumers share (decision D133); the built-in
 * [SnapshotSubscription] (for the aggregates whose Manifest enables snapshots) and [ReservationSubscription], with
 * the application's subscriptions, in a
 * [SubscriptionRuntime]; a [PartitionOwnership] of the lease group `(service, context)`; a [WaitCoordinator]; a
 * [ForwardEndpoint]. With a timer store, the built-in [TimersSubscription] and a [TimerRunner] driven by the
 * context's [PartitionOwnership], which sends the scheduled commands of the partitions the node owns (decision D166).
 *
 * **Per node**: the [waits] registered here, the [signals] sender (a signal to this node goes straight to [waits],
 * one to another node through the host's remote sender), the [router] over every context's lease group, the
 * [gateway] that admits commands, the [registry] of models, the [forwardHandler] that hands a forwarded command
 * to its context's endpoint, and the [parked] entries of every subscription as an operator acts on them; each runtime
 * retries the entries of the partitions it runs over the long horizon and tells the [parkedNotifier] when one parks or
 * spends its retries (roadmap P.1).
 *
 * **Per topic** read for the subscriptions of other contexts' published streams ([SubscriptionFactory.sourced],
 * decision D134): one [TopicSubscriptions] of every subscription reading it, in the lease group `(service,
 * "topic:<topic>")`, keeping its checkpoints and parked aggregates in the stores of the first context (in the order
 * of the models) that reads it. The topic is the publishing context's ([contracts], [ContractDirectory.publishedOf])
 * in [topics]; a node with such subscriptions and no [topics] is not built.
 *
 * **Commands across contexts** (decisions D33, D135–D137), when [topics] has a producer: a saga's command to an
 * aggregate of another context is sent to that context's command topic ([ContextNode.sender]); the receipts of those
 * rejected come back on the saga's context's receipts topic, read in the lease group of that topic for the contexts
 * whose sagas have `@OnRejected` handlers ([SubscriptionFactory.rejections]). With [SourceTopics.intake], each context
 * executes the commands of its own command topic ([ContextNode.intake], [CommandIntake]), driven by its
 * [PartitionOwnership]: the owner of a log partition reads the same partition of the topic, and [start] fails unless
 * the topic has as many partitions as the log.
 *
 * **Startup checks**: the [manifest] (normally [me.ahoo.wow.runtime.manifest.ManifestLoader]'s) names this
 * [service], and it and the [models] agree ([ManifestCheck]); otherwise the node is not built. Nor is it built
 * without a producer in [topics] when a saga of the [manifest] derives, schedules or handles the rejections of
 * commands of a context the node does not serve ([ContractDirectory.commandsOutside]). The [contracts] are
 * those of this [manifest] and its peers (decision D139), checked when they were built; each context's waits name
 * subscriptions through them, so a chain may name one of another context.
 *
 * **Internal address**: the [address] the other nodes reach this node at, given when the node is built, or learned
 * with [bind] once the host's internal port is bound (a port the system chose, such as `0`), before [start].
 *
 * **Lifecycle** ([state]): [start] joins every context's lease group (heartbeat, reconcile, acquire its partitions,
 * start their subscriptions), and the node is then [isReady]. [stop] leaves in this order (decision D108, refining
 * D71 to the whole node), each step after the previous one:
 *
 * 1. every context's [PartitionOwnership.stop], concurrently: leave the members, and for each partition stop
 *    accepting, drain its commands within [me.ahoo.wow.runtime.cluster.ClusterOptions.drainTimeout], stop its
 *    subscriptions and save their checkpoints, release its lease;
 * 2. wait until no wait is registered on the node, at most [ServiceNodeOptions.maxWaitTimeout] after the stop began,
 *    which no wait outlives;
 * 3. [CommandGateway.close]: refuse new requests, finish the detached ones;
 * 4. every context's [WaitCoordinator.join]: the commands still executing (a wait that timed out leaves its command
 *    executing) settle normally, at most until the waits' bound of step 2, and at least
 *    [me.ahoo.wow.runtime.cluster.ClusterOptions.drainTimeout];
 * 5. every context's [Executor.close], then its [WaitCoordinator.close];
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
 * `maxWaitTimeout + drainTimeout`.
 */
class ServiceNode(
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
    /** Where the subscriptions of other contexts' published streams read them (decision D134); none by default. */
    private val topics: SourceTopics? = null,
    /** Hears when an entry parks and when its automatic retries are spent (roadmap P.1); nobody by default. */
    private val parkedNotifier: ParkedNotifier = ParkedNotifier.NONE,
) {
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

    /** The aggregate models this node serves. */
    val registry: ModelRegistry = ModelRegistry(models)

    /** The waits started on this node. */
    val waits: WaitRegistry = WaitRegistry()

    /** Delivers signals: to [waits] when they are this node's, through the remote sender otherwise. */
    val signals: SignalSender = LocalSignalSender(node, waits, remoteSignals)

    private val waitOptions = WaitOptions(
        Origin(node, address.orEmpty(), service),
        options.maxWaitTimeout,
        options.maxWaitHops,
        options.defaultWaitTimeout,
    )

    /** Sends the commands of every context's sagas to the command topics of other contexts; null without a producer. */
    private val sender: CommandSender? = topics?.takeIf { it.producer != null }?.let { CommandSender(contracts, it) }

    init {
        // A saga that sends to another context needs a producer: without one, each of its executions would retry and
        // park at run time while the node looked healthy (DX gap G9).
        if (sender == null) {
            val outside = contracts.commandsOutside(models.mapTo(HashSet()) { it.type.context })
            require(outside.isEmpty()) {
                "Node $node has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers), but " +
                    outside.entries.joinToString("; ") { (subscription, contexts) ->
                        "$subscription sends commands to ${contexts.joinToString()}"
                    } + ": a command across contexts goes through its context's command topic (decision D33)."
            }
        }
    }

    private val parts: List<Parts> = models.groupBy { it.type.context }.map { (context, contextModels) ->
        assemble(context, contextModels, backends.backends(context), codec, subscriptions)
    }

    /** Where commands run: [Routing.PARTITIONED] over every context's lease group, or [Routing.LOCAL]. */
    val router: CommandRouter = when (options.routing) {
        Routing.PARTITIONED -> PartitionedRouter(parts.map { it.ownership }, channel, options.cluster)
        Routing.LOCAL -> CommandRouter.LOCAL
    }

    /** The parts of each bounded context, by its name. */
    val contexts: Map<String, ContextNode> = parts.associate { it.context to it.complete() }

    /** Admits the commands of every context. */
    val gateway: CommandGateway = CommandGateway(
        registry,
        contexts.mapValues { it.value.coordinator },
        authorizer = admission.authorizer,
        transformers = admission.transformers,
        validators = admission.validators,
        ids = admission.ids,
        tenants = admission.tenants,
    )

    /**
     * What the internal port serves to the cluster channel: a forwarded command goes to its context's endpoint; one
     * of a context this node does not host is refused as of an unknown type, so its sender runs it (decision D71).
     */
    val forwardHandler: ClusterChannelHandler = ClusterChannelHandler { request ->
        contexts[request.command.ref.type.context]?.endpoint?.forward(request)
            ?: ForwardReply.refused(Refusal.UNKNOWN_TYPE)
    }

    private val applicationSubscriptions = contexts.values.associateWith { subscriptions.subscriptions(it) }

    /**
     * The subscriptions of other contexts' published streams (decision D134), by the topic they read: one runtime
     * per topic on this node.
     */
    val topicSubscriptions: Map<String, TopicSubscriptions> = topicSubscriptions(subscriptions) + receipts(subscriptions)

    /**
     * The parked entries of every subscription of the node, the built-in ones, the intakes' and those of other
     * contexts' topics included, as an operator lists and acts on them (roadmap P.1, decision D141).
     */
    val parked: ParkedOps = ParkedOps {
        contexts.values.flatMap { context ->
            listOfNotNull(context.subscriptions, context.intake).map { ParkedOps.Scope.of(it) }
        } + topicSubscriptions.values.map { ParkedOps.Scope.of(it, options.subscription.parked) }
    }

    /** The command intake of each context that takes commands from its topic (decision D137). */
    private val intakes: Map<ContextNode, CommandIntake> = contexts.values.filter { it.intake != null }.associateWith {
        CommandIntake(it.context, it.models, it.coordinator, topics!!)
    }

    init {
        // A start configured for a subscription the node does not have is a typo, not a no-op (decision D154).
        val names = contexts.values.flatMapTo(HashSet()) { context ->
            applicationSubscriptions.getValue(context).map { it.name } +
                listOfNotNull(
                    context.snapshots?.subscription?.name,
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

    private val lifecycle = Mutex()

    /** Where the node is in its lifecycle. */
    @Volatile
    var state: State = State.NEW
        private set

    /** Whether the node serves commands: it has started and is not stopping. */
    val isReady: Boolean get() = state == State.RUNNING

    /** The parts of [context]. */
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
        contexts.values.forEach { it.coordinator.options = it.coordinator.options.copy(origin = origin) }
    }

    /**
     * Registers the application's subscriptions and joins every context's lease group: heartbeats, reconciles, and
     * acquires its share of the partitions, starting their subscriptions. A start that fails stops what it started
     * and throws.
     */
    suspend fun start(): Unit = lifecycle.withLock {
        check(state == State.NEW) { "Node $node is $state; a node starts once." }
        check(hasAddress) { "Node $node has no internal address: bind it before it starts." }
        state = State.STARTING
        try {
            intakes.keys.forEach { context ->
                CommandIntake.checkPartitions(
                    topics!!.commandsOf(context.context),
                    topics.source,
                    context.context,
                    context.backends.log.partitions,
                )
            }
            contexts.values.forEach { context ->
                applicationSubscriptions.getValue(context).forEach { context.subscriptions.add(it) }
                intakes[context]?.let { context.intake!!.add(it.subscription) }
                context.ownership.start()
            }
            topicSubscriptions.values.forEach { it.start() }
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
        logger.info { "Node $node of $service started: contexts ${contexts.keys}." }
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
        val waitsEnd = TimeSource.Monotonic.markNow() + options.maxWaitTimeout
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
            contexts.values.forEach {
                it.executor.close()
                it.coordinator.close()
            }
        }
        step(Step.LOGS_CLOSED) { contexts.values.forEach { it.log.close() } }
        state = State.STOPPED
        logger.info { "Node $node of $service stopped." }
        failure?.let { throw it }
    }

    /** Hands off every context's partitions and every topic's, concurrently. */
    private suspend fun handOff() = coroutineScope {
        contexts.values.forEach { launch { it.ownership.stop() } }
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
                ledger = backends.ledger,
                subscriptions = read.map { it.second },
                options = options.subscription,
                cluster = options.cluster,
                signals = signals,
                notifier = parkedNotifier,
            )
        }
    }

    /**
     * The runtimes of the receipts topics of the contexts whose sagas the [application] gives `@OnRejected` handlers
     * ([SubscriptionFactory.rejections], decision D136), each in the lease group of its topic and the stores of its
     * context. None without [topics]: no command left for another context then.
     */
    private fun receipts(application: SubscriptionFactory): Map<String, TopicSubscriptions> {
        val sources = topics ?: return emptyMap()
        return contexts.values.mapNotNull { context ->
            val handler = application.rejections(context) ?: return@mapNotNull null
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
                ledger = context.backends.ledger,
                subscriptions = listOf(receipts.subscription),
                options = options.subscription,
                cluster = options.cluster,
                clock = Clock.systemUTC(),
                context = Dispatchers.Default,
                signals = signals,
                notifier = parkedNotifier,
                entries = { partitions -> MessageEntries.receipts(topic, sources.source, partitions) },
            )
        }.toMap()
    }

    /**
     * The runtime of [context]'s command topic when the node takes commands from topics ([SourceTopics.intake]), in
     * its lease [group] and stores: the topic's partitions are the log's (decision D135), so the same leases drive both.
     */
    private fun intakeOf(context: String, group: LeaseGroup, backends: ContextBackends): SubscriptionRuntime? =
        topics?.takeIf { it.intake }?.let {
            SubscriptionRuntime(
                group.name,
                MessageEntries.commands(it.commandsOf(context), it.source, backends.log.partitions),
                backends.checkpoints,
                backends.ledger,
                options = options.subscription,
                signals = signals,
                notifier = parkedNotifier,
            )
        }

    private fun assemble(
        context: String,
        models: List<AggregateModel<*>>,
        backends: ContextBackends,
        codec: EventCodec,
        application: SubscriptionFactory,
    ): Parts {
        val log = PartitionAppendQueue(backends.log, options.appendQueue)
        val names = ContextCommandTypes(models, contracts)
        val kernel = kernelOf(log, backends, codec, names)
        val snapshotted = if (backends.snapshots == null) emptyList() else models.filter { snapshotEnabled(it) }
        val views = StateViews(codec, stateReaders(context, models, snapshotted, application), options.stateViews)
        val snapshots = backends.snapshots?.takeIf { snapshotted.isNotEmpty() }?.let {
            SnapshotSubscription(context, snapshotted, it, kernel, backends.log, codec, views)
        }
        val reservations = backends.reservations?.let { ReservationSubscription(context, it) }
        val firing = LateFirer()
        val timers = timersOf(context, backends, names, firing, codec)
        val group = LeaseGroup(service, context)
        val subscriptions = SubscriptionRuntime(
            group.name,
            backends.log,
            backends.checkpoints,
            backends.ledger,
            listOfNotNull(snapshots?.subscription, reservations?.subscription, timers?.subscription?.subscription),
            options.subscription,
            signals = signals,
            notifier = parkedNotifier,
        )
        val intake = intakeOf(context, group, backends)
        val ownership = PartitionOwnership(
            group,
            node,
            { this.address },
            backends.log.partitions,
            backends.leases,
            options.cluster,
            subscriptions,
            // Connected ahead of the first forward, so its timeout is not spent opening a connection (D80).
            peers = { peer -> if (options.routing == Routing.PARTITIONED) channel.connect(peer.address) },
            intake = intake,
            timers = timers?.runner,
        )
        val executor = Executor(kernel, options.laneCapacity, views.takeIf { it.types.isNotEmpty() })
        return Parts(
            context,
            models,
            group,
            log,
            kernel,
            executor,
            snapshots,
            reservations,
            subscriptions,
            ownership,
            backends,
            codec,
            views,
            intake,
            timers,
            firing,
        )
    }

    /** The kernel of a context over its [log], with its state cache, snapshot and reservation stores. */
    private fun kernelOf(log: PartitionAppendQueue, backends: ContextBackends, codec: EventCodec, names: CommandTypes) =
        Kernel(
            log,
            codec = codec,
            reservations = backends.reservations,
            states = if (options.stateCacheCapacity > 0) LruStateCache(options.stateCacheCapacity) else StateCache.None,
            snapshots = backends.snapshots,
            commandTypes = names,
            ids = admission.ids,
        )

    /** The scheduled commands of [context] (decision D166), when its [backends] have a timer store. */
    private fun timersOf(
        context: String,
        backends: ContextBackends,
        names: ContextCommandTypes,
        firer: TimerFirer,
        codec: EventCodec,
    ): ContextTimers? = backends.timers?.let { store ->
        ContextTimers(
            TimersSubscription(context, store, backends.log.partitions),
            TimerRunner(context, store, backends.ledger, firer, options.timers),
            SagaTimers(store, names, backends.log.partitions, codec),
        )
    }

    /**
     * The state consumers of [context] (decision D133): the snapshot subscription of the [snapshotted] types, and the
     * readers the [application]'s subscriptions name.
     */
    private fun stateReaders(
        context: String,
        models: List<AggregateModel<*>>,
        snapshotted: List<AggregateModel<*>>,
        application: SubscriptionFactory,
    ): Map<String, Set<AggregateType>> {
        val readers = LinkedHashMap<String, Set<AggregateType>>()
        if (snapshotted.isNotEmpty()) {
            readers[Hop.SNAPSHOT_SUBSCRIPTION] = snapshotted.mapTo(LinkedHashSet()) { it.type }
        }
        application.stateReaders(context, models).forEach { (reader, types) ->
            readers.merge(reader, types) { left, right -> left + right }
        }
        return readers
    }

    private fun snapshotEnabled(model: AggregateModel<*>): Boolean = manifest.contexts
        .first { it.name == model.type.context }.aggregates
        .first { it.name == model.type.name }.snapshot.enabled

    /** A context's parts before the node's router exists. */
    private inner class Parts(
        val context: String,
        val models: List<AggregateModel<*>>,
        val group: LeaseGroup,
        val log: PartitionAppendQueue,
        val kernel: Kernel,
        val executor: Executor,
        val snapshots: SnapshotSubscription?,
        val reservations: ReservationSubscription?,
        val subscriptions: SubscriptionRuntime,
        val ownership: PartitionOwnership,
        val backends: ContextBackends,
        val codec: EventCodec,
        val views: StateViews,
        val intake: SubscriptionRuntime?,
        val timers: ContextTimers?,
        val firing: LateFirer,
    ) {
        fun complete(): ContextNode {
            val directory = contracts.over(subscriptions, context)
            val coordinator = WaitCoordinator(
                executor,
                waits,
                directory,
                signals,
                waitOptions,
                waitIds = admission.ids.global::next,
                router = router,
            )
            val endpoint = ForwardEndpoint(coordinator, models, listOf(ownership))
            firing.firer = ContextTimerFirer(models, coordinator, sender)
            return ContextNode(
                context,
                models,
                group,
                log,
                kernel,
                executor,
                snapshots,
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
            )
        }
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

        /** Every context's executor is closed, its commands finished, and its coordinator closed. */
        EXECUTORS_CLOSED,

        /** Every context's append queue is closed, what was queued committed. */
        LOGS_CLOSED,
    }

    companion object {
        private val WAIT_POLL = 10.milliseconds
        private val logger = KotlinLogging.logger {}

        /**
         * The aggregate models of [providers] (by default, those registered on the class path, decision D107), their
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
