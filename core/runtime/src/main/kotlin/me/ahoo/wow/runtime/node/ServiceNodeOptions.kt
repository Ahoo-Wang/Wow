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
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.command.ReceiptHandler
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.state.StateViewOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.wait.UnknownContexts
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.wait.WaitContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The stores of one bounded context on a node (decision D72: a log, its partitions and leases are per context). The
 * [log]'s partition count is the context's. Without [snapshots] the context has no snapshot subscription, and its
 * default wait is COMMITTED; without [reservations] its commands cannot reserve keys; without [timers] its commands and
 * sagas cannot schedule commands (decision D166): a schedule is committed but never sent.
 *
 * **Log groups** (decision D177): the aggregates whose log is on one store instance form a group, with its own
 * partition sequences and aggregate tables, and the checkpoints, parked entries and timers of the subscriptions that
 * read it in the same store. [log], [checkpoints], [ledger] and [timers] are the context's default group, on the store
 * named [store]: every aggregate type of the context that no other group names. Each of [groups] is another store's,
 * with the aggregate types it holds. Partitions and leases stay per context: every group has the context's partitions,
 * and the owner of a partition reads it in every group. One store, no [groups]: one group, as before D177.
 *
 * Fields may be added (decision D216): construct it with [of]; the constructor, which also takes the log groups and
 * snapshot targets the host configures, is internal to Wow.
 */
class ContextBackends @InternalWowApi constructor(
    val log: EventLog,
    val checkpoints: CheckpointStore,
    val leases: LeaseStore,
    val ledger: FailureLedger,
    val snapshots: SnapshotStore? = null,
    val reservations: ReservationStore? = null,
    val timers: TimerStore? = null,
    /** The store instance of the default log group, as the host names it (`wow.log.store`). */
    val store: String = DEFAULT_STORE,
    /** The context's other log groups, each on a store of its own (decision D177). */
    val groups: List<LogGroupBackends> = emptyList(),
    /**
     * The context's snapshot targets (decision D172): every store its aggregate types' snapshots are written to, with
     * each type's primary one. Without them, [snapshots] is every type's only target. With them, [snapshots] is null
     * or their [primary][SnapshotTargets.primary] snapshots.
     */
    val snapshotTargets: SnapshotTargets? = null,
) {
    /** The snapshots the write side and the state readers read: each type's primary target. */
    val primarySnapshots: SnapshotStore? get() = snapshotTargets?.primary ?: snapshots

    init {
        require(snapshotTargets == null || snapshots == null || snapshots === snapshotTargets.primary) {
            "The snapshots of a context with snapshot targets are their primary ones."
        }
        require(store.isNotBlank()) { "A log group names its store." }
        val stores = groups.map { it.store }
        require(store !in stores && stores.toSet().size == stores.size) {
            "Each log group is on a store of its own: $store and $stores."
        }
        val types = groups.flatMap { it.types }
        require(types.toSet().size == types.size) { "An aggregate type is in one log group: $types." }
        groups.forEach { group ->
            require(group.log.partitions == log.partitions) {
                "The log group ${group.store} has ${group.log.partitions} partitions, not the context's ${log.partitions}."
            }
            require((group.timers == null) == (timers == null)) {
                "Either every log group has a timer store or none has: ${group.store} differs from $store."
            }
        }
    }

    /** These ContextBackends with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        log: EventLog = this.log,
        checkpoints: CheckpointStore = this.checkpoints,
        leases: LeaseStore = this.leases,
        ledger: FailureLedger = this.ledger,
        snapshots: SnapshotStore? = this.snapshots,
        reservations: ReservationStore? = this.reservations,
        timers: TimerStore? = this.timers,
        store: String = this.store,
        groups: List<LogGroupBackends> = this.groups,
        snapshotTargets: SnapshotTargets? = this.snapshotTargets,
    ): ContextBackends = ContextBackends(
        log,
        checkpoints,
        leases,
        ledger,
        snapshots,
        reservations,
        timers,
        store,
        groups,
        snapshotTargets,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is ContextBackends && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ContextBackends(log=$log, checkpoints=$checkpoints, leases=$leases, ledger=$ledger, snapshots=$snapshots, " +
            "reservations=$reservations, timers=$timers, store=$store, groups=$groups, " +
            "snapshotTargets=$snapshotTargets)"

    private fun valueFields(): List<Any?> =
        listOf(log, checkpoints, leases, ledger, snapshots, reservations, timers, store, groups, snapshotTargets)

    companion object {
        /** The name of the store of a context's default log group when the host names none. */
        const val DEFAULT_STORE: String = "default"

        /**
         * The stores of a context with one log group, on the store [DEFAULT_STORE]: its [log], [checkpoints],
         * [leases] and failure [ledger], and optionally its [snapshots], [reservations] and [timers].
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            log: EventLog,
            checkpoints: CheckpointStore,
            leases: LeaseStore,
            ledger: FailureLedger,
            snapshots: SnapshotStore? = null,
            reservations: ReservationStore? = null,
            timers: TimerStore? = null,
        ): ContextBackends = ContextBackends(log, checkpoints, leases, ledger, snapshots, reservations, timers)
    }
}

/**
 * One log group of a context other than its default one (decision D177): the aggregate [types] whose log is [log], on
 * the store instance [store]; the checkpoints, parked entries and timers of the subscriptions reading it are in
 * [checkpoints], [ledger] and [timers], on the same store.
 *
 * Fields may be added (decision D216): Wow builds the groups from the host's configuration, so the constructor is
 * internal to Wow.
 */
class LogGroupBackends @InternalWowApi constructor(
    val store: String,
    val types: Set<AggregateType>,
    val log: EventLog,
    val checkpoints: CheckpointStore,
    val ledger: FailureLedger,
    val timers: TimerStore? = null,
) {
    init {
        require(store.isNotBlank()) { "A log group names its store." }
        require(types.isNotEmpty()) { "A log group holds at least one aggregate type." }
    }

    /** This LogGroupBackends with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        store: String = this.store,
        types: Set<AggregateType> = this.types,
        log: EventLog = this.log,
        checkpoints: CheckpointStore = this.checkpoints,
        ledger: FailureLedger = this.ledger,
        timers: TimerStore? = this.timers,
    ): LogGroupBackends = LogGroupBackends(store, types, log, checkpoints, ledger, timers)

    override fun equals(other: Any?): Boolean =
        this === other || other is LogGroupBackends && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "LogGroupBackends(store=$store, types=$types, log=$log, checkpoints=$checkpoints, ledger=$ledger, " +
            "timers=$timers)"

    private fun valueFields(): List<Any?> = listOf(store, types, log, checkpoints, ledger, timers)
}

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
 * - [maxWaitTimeout], [maxWaitHops], [unknownWaitContexts] and [defaultWaitTimeout]: the waits ([WaitOptions]); the default timeout is that
 *   of every command whose request names none, whichever edge it came through, capped by [maxWaitTimeout]. [ServiceNode.stop] also waits at most
 *   [maxWaitTimeout] for the waits registered on the node to end: none of them outlives it.
 * - [subscription]: the subscriptions of every context, the built-in ones included, with the starts the host
 *   configures for some of them ([SubscriptionOptions.starts], decision D154): a name no subscription of the node has
 *   fails its assembly.
 * - [appendQueue]: the partition append queue, by the log store's preset ([AppendQueueOptions.OFF] on PostgreSQL and
 *   MongoDB).
 * - [laneCapacity]: the commands one aggregate may have waiting in the executor.
 * - [maxInFlight]: the commands the node may have in flight in its executors, all contexts together (decision D228 ②,
 *   [me.ahoo.wow.runtime.InFlightLimit]); beyond it a command is refused as busy. Each partition append queue holds at
 *   most as many.
 * - [maxRegisteredWaits]: the waits the node holds at once (review R3.4-3, [WaitOptions.maxRegistered]); beyond it a
 *   command that would register one is refused as busy, before it runs.
 * - [stateCacheCapacity]: the aggregate states kept per context between commands; 0 keeps none, null (the default)
 *   sizes each context's cache from the heap ([me.ahoo.wow.runtime.StateCacheSizing]).
 * - [recentRequests]: the requests each cached aggregate keeps of those it committed last (decision D261), derived
 *   from the log, so that most commands skip looking their request id up; 0 looks every request id up (D46).
 * - [stateViews]: the bounds of each context's state views, shared by its state consumers (decision D133).
 * - [timers]: the timer runner of each context that has a timer store (decision D166).
 * - [schedules]: the host's override of each job's default rule, by job name (`wow.schedules.<job>.*`, decision D198);
 *   a name no `@OnSchedule` handler of the node runs fails its assembly.
 * - [snapshots]: the snapshot maintenance of each context (decision D172): how often it claims the state revisions,
 *   rebuilds in place and checks the backfills, and the bounds of a rebuild.
 * - [kafkaRouted]: the aggregate types whose commands the `Kafka` router takes (design 02 §2, decision D143): an
 *   admitted command of one of them is written to its context's command topic, accepted once the broker acknowledged
 *   it, and executed by the owner of its log partition from there. Each must be a type the node serves, the node
 *   needs a producer ([SourceTopics.producer]), and each of their contexts takes the commands of its command topic
 *   whether or not [SourceTopics.intake] is set. None by default.
 * - [handoff]: whether the node hands its own commits to its subscriptions' tails in memory (decision D249), and the
 *   bounds of each tail's hand-overs ([HandoffOptions]); on by default.
 * - [ledger]: where the node publishes its failure ledger (decision D141): the ops context whose `parked` aggregate
 *   records each entry, through its command topic, which needs a producer ([SourceTopics.producer]). Not published by
 *   default.
 *
 * Fields may be added (decision D216): Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class ServiceNodeOptions @InternalWowApi constructor(
    val cluster: ClusterOptions = ClusterOptions(),
    val routing: Routing = Routing.PARTITIONED,
    val maxWaitTimeout: Duration = WaitOptions.DEFAULT_MAX_TIMEOUT,
    val maxWaitHops: Int = WaitOptions.DEFAULT_MAX_HOPS,
    val subscription: SubscriptionOptions = SubscriptionOptions(),
    val appendQueue: AppendQueueOptions = AppendQueueOptions(),
    val laneCapacity: Int = DEFAULT_LANE_CAPACITY,
    val stateCacheCapacity: Int? = null,
    val defaultWaitTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT,
    val stateViews: StateViewOptions = StateViewOptions(),
    val timers: TimerOptions = TimerOptions(),
    val schedules: Map<String, JobOptions> = emptyMap(),
    val kafkaRouted: Set<AggregateType> = emptySet(),
    val unknownWaitContexts: UnknownContexts = UnknownContexts.ALLOW,
    val ledger: LedgerPublishing? = null,
    /** The snapshot maintenance of each context (decision D172): claims, in-place rebuilds, backfills. */
    val snapshots: SnapshotOptions = SnapshotOptions(),
    val maxInFlight: Int = InFlightLimit.DEFAULT_MAX,
    val maxRegisteredWaits: Int = WaitOptions.DEFAULT_MAX_REGISTERED,
    val handoff: HandoffOptions = HandoffOptions(),
    val recentRequests: Int = DEFAULT_RECENT_REQUESTS,
) {
    init {
        require(maxInFlight >= 1) { "A node takes at least one command in flight." }
        require(defaultWaitTimeout.isPositive()) { "The default wait timeout is positive." }
        require(laneCapacity >= 1) { "A lane takes at least one command." }
        require(stateCacheCapacity == null || stateCacheCapacity >= 0) { "The state cache capacity is never negative." }
        require(recentRequests >= 0) { "The recent requests kept per aggregate are never negative." }
    }

    /** This ServiceNodeOptions with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        cluster: ClusterOptions = this.cluster,
        routing: Routing = this.routing,
        maxWaitTimeout: Duration = this.maxWaitTimeout,
        maxWaitHops: Int = this.maxWaitHops,
        subscription: SubscriptionOptions = this.subscription,
        appendQueue: AppendQueueOptions = this.appendQueue,
        laneCapacity: Int = this.laneCapacity,
        stateCacheCapacity: Int? = this.stateCacheCapacity,
        defaultWaitTimeout: Duration = this.defaultWaitTimeout,
        stateViews: StateViewOptions = this.stateViews,
        timers: TimerOptions = this.timers,
        schedules: Map<String, JobOptions> = this.schedules,
        kafkaRouted: Set<AggregateType> = this.kafkaRouted,
        unknownWaitContexts: UnknownContexts = this.unknownWaitContexts,
        ledger: LedgerPublishing? = this.ledger,
        snapshots: SnapshotOptions = this.snapshots,
        maxInFlight: Int = this.maxInFlight,
        maxRegisteredWaits: Int = this.maxRegisteredWaits,
        handoff: HandoffOptions = this.handoff,
        recentRequests: Int = this.recentRequests,
    ): ServiceNodeOptions = ServiceNodeOptions(
        cluster,
        routing,
        maxWaitTimeout,
        maxWaitHops,
        subscription,
        appendQueue,
        laneCapacity,
        stateCacheCapacity,
        defaultWaitTimeout,
        stateViews,
        timers,
        schedules,
        kafkaRouted,
        unknownWaitContexts,
        ledger,
        snapshots,
        maxInFlight,
        maxRegisteredWaits,
        handoff,
        recentRequests,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is ServiceNodeOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ServiceNodeOptions(cluster=$cluster, routing=$routing, maxWaitTimeout=$maxWaitTimeout, " +
            "maxWaitHops=$maxWaitHops, subscription=$subscription, appendQueue=$appendQueue, " +
            "laneCapacity=$laneCapacity, stateCacheCapacity=$stateCacheCapacity, " +
            "defaultWaitTimeout=$defaultWaitTimeout, stateViews=$stateViews, timers=$timers, " +
            "schedules=$schedules, kafkaRouted=$kafkaRouted, unknownWaitContexts=$unknownWaitContexts, " +
            "ledger=$ledger, snapshots=$snapshots, maxInFlight=$maxInFlight, maxRegisteredWaits=$maxRegisteredWaits, " +
            "handoff=$handoff, recentRequests=$recentRequests)"

    private fun valueFields(): List<Any?> = listOf(
        cluster, routing, maxWaitTimeout, maxWaitHops, subscription, appendQueue, laneCapacity, stateCacheCapacity,
        defaultWaitTimeout, stateViews, timers, schedules, kafkaRouted, unknownWaitContexts, ledger, snapshots,
        maxInFlight, maxRegisteredWaits, handoff, recentRequests,
    )

    companion object {
        const val DEFAULT_LANE_CAPACITY: Int = 1_024

        /** About 3 KB per cached aggregate, 12 MB for a full default state cache (decision D261). */
        const val DEFAULT_RECENT_REQUESTS: Int = 256
    }
}

/**
 * The admission plug-ins of a node's [me.ahoo.wow.runtime.admission.CommandGateway] (decisions D92, D114, D182), and the
 * [tenants] of its aggregate types (decision D116). [ids] are the node's id generators (decision D171): admission's
 * command and aggregate ids, the wait ids, and the ids its decide functions and sagas take.
 */
@InternalWowApi
data class AdmissionPlugins(
    val authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
    val transformers: List<CommandTransformer> = emptyList(),
    val validators: List<CommandValidator> = emptyList(),
    val ids: IdGenerators = IdGenerators.DEFAULT,
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
@InternalWowApi
fun interface SubscriptionFactory {
    fun subscriptions(context: ContextNode): List<Subscription>

    /**
     * The subscriptions of [context] that read the published streams of other contexts' aggregates (design 04 §5,
     * decision D134), each with the aggregate types it reads. The node finds each type's topic in its contracts
     * ([me.ahoo.wow.runtime.contract.ContractDirectory.publishedOf]) and reads it ([SourceTopics]). None by default.
     */
    fun sourced(context: ContextNode): List<SourcedSubscription> = emptyList()

    /**
     * What handles the receipts of the commands [context]'s sagas sent to other contexts that rejected or committed
     * them (decisions D136, D192): the node reads the context's receipts topic for it ([SourceTopics.receiptsOf]). None
     * by default; a factory whose sagas have `@OnRejected` or `@OnCommitted` handlers gives them.
     */
    fun receipts(context: ContextNode): ReceiptHandler? = null

    /**
     * The `@OnCommitted` handlers of [context]'s sagas, by name (`processor.function`): each is a saga's hop a wait
     * chain may name (decision D192), though no subscription of the context's log runs it. None by default.
     */
    fun committedHandlers(context: String): Set<String> = emptySet()

    /**
     * The jobs of [context]'s `@OnSchedule` handlers (decision D198), asked before the context is assembled: the node
     * adds the built-in job aggregate `<context>.schedule` deciding them, and starts the SYSTEM ones. None by default.
     */
    fun jobs(context: String): List<ScheduleJob> = emptyList()

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

            override fun committedHandlers(context: String): Set<String> =
                first.committedHandlers(context) + other.committedHandlers(context)

            override fun receipts(context: ContextNode): ReceiptHandler? {
                val mine = first.receipts(context) ?: return other.receipts(context)
                val theirs = other.receipts(context) ?: return mine
                return object : ReceiptHandler {
                    override suspend fun rejected(rejected: CommandRejected<*>, command: Class<*>): List<String>? =
                        mine.rejected(rejected, command) ?: theirs.rejected(rejected, command)

                    override suspend fun committed(
                        committed: CommandCommitted<*>,
                        command: Class<*>,
                        wait: WaitContext?,
                    ): List<String>? =
                        mine.committed(committed, command, wait) ?: theirs.committed(committed, command, wait)
                }
            }

            override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> =
                first.viewTypes(context, models) + other.viewTypes(context, models)

            override fun jobs(context: String): List<ScheduleJob> = first.jobs(context) + other.jobs(context)

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

    @InternalWowApi
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
@InternalWowApi
data class SourcedSubscription(val subscription: Subscription, val types: Set<AggregateType>) {
    init {
        require(types.isNotEmpty()) { "${subscription.name} reads no published stream." }
        require(types.all { it.context == subscription.filter.context }) {
            "${subscription.name} reads $types, not all of its filter's context ${subscription.filter.context}."
        }
    }
}

/**
 * How a node manages the topics its contexts own at its start (decision D168), as DDL is managed for the stores:
 * the command topic of each context that takes commands from it, `wow.<context>.commands`, with exactly its log's
 * partitions; the receipts topic of each context whose sagas handle rejections, `wow.<context>.receipts`; and the
 * topic of each stream its contexts publish, `wow.<context>.<aggregate>.state`. Those two have the log's partitions
 * when created, and any number otherwise.
 */
enum class TopicDdl {
    /** Creates the topics that are missing, with their partitions, then validates them as [VALIDATE] does. */
    AUTO,

    /** Fails the start when a topic is missing, or a command topic does not have exactly its log's partitions. */
    VALIDATE,

    /**
     * Checks nothing but the command topics a node reads, whose partitions must still be its log's: a command's
     * partition among them is its log partition, so any other count reaches owners that do not own its aggregate.
     */
    NONE,
}

/**
 * The topics of a node's broker, each named after [prefix], the prefix every service on the broker writes under:
 *
 * - **Published streams** (decision D134): the node reads other contexts' streams from [source], each topic named as
 *   the publishing context's Manifest names it (`PublishedManifest.topic`, [topicOf]). Partitions added to a topic
 *   it reads are read too, within [partitionRefresh] of the source seeing them (decision D168).
 * - **Commands across contexts** (decisions D33, D135, D137), with a [producer]: a saga's command to an aggregate of
 *   a context outside its own is sent to that context's command topic, `wow.<context>.commands` ([commandsOf]), and
 *   the receipts of those another context rejects are read from the saga's context's receipts topic,
 *   `wow.<context>.receipts` ([receiptsOf], decision D136).
 * - **Command intake** (decision D137), when [intake] is set: each context the node hosts executes the commands of its
 *   command topic, whose partitions must be its log's, and sends the receipts of those it rejects through
 *   [producer].
 * - **Topic management** (decision D168): at its start, the node validates or creates the topics its contexts own as
 *   [ddl] says, through [admin], which any [ddl] but [TopicDdl.NONE] needs.
 */
data class SourceTopics(
    val source: PartitionedSource,
    val prefix: String = "",
    val producer: TopicProducer? = null,
    val intake: Boolean = false,
    val admin: TopicAdmin? = null,
    val ddl: TopicDdl = TopicDdl.NONE,
    val partitionRefresh: Duration = DEFAULT_PARTITION_REFRESH,
) {
    init {
        require(!intake || producer != null) { "A command intake sends receipts: it needs a producer." }
        require(ddl == TopicDdl.NONE || admin != null) { "Topics are validated or created through an admin: $ddl." }
        require(partitionRefresh.isPositive()) { "The partitions of a topic are refreshed at a positive interval." }
    }

    /** The topic of a stream the Manifest publishes on [topic]. */
    fun topicOf(topic: String): String = prefix + topic

    /** The command topic of [context] (decision D135). */
    fun commandsOf(context: String): String = "${prefix}wow.$context.commands"

    /** The receipts topic of [context] (decision D136). */
    fun receiptsOf(context: String): String = "${prefix}wow.$context.receipts"

    companion object {
        val DEFAULT_PARTITION_REFRESH: Duration = 30.seconds
    }
}
