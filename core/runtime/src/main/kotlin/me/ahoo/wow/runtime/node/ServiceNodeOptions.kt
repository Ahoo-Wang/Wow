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
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.runtime.ExecutorOptions
import me.ahoo.wow.runtime.StateCacheOptions
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.command.ReceiptHandler
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.state.SharedStateOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.wait.WaitContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The stores of one bounded context on a node (a log, its partitions and leases are per context). The
 * [log]'s partition count is the context's. Without [snapshots] the context has no snapshot subscription, and its
 * default wait is COMMITTED; without [reservations] its commands cannot reserve keys; without [timers] its commands and
 * sagas cannot schedule commands: a schedule is committed but never sent.
 *
 * **Log groups**: the aggregates whose log is on one store instance form a group, with its own
 * partition sequences and aggregate tables, and the checkpoints, parked entries and timers of the subscriptions that
 * read it in the same store. [log], [checkpoints], [ledger] and [timers] are the context's default group, on the store
 * named [store]: every aggregate type of the context that no other group names. Each of [groups] is another store's,
 * with the aggregate types it holds. Partitions and leases stay per context: every group has the context's partitions,
 * and the owner of a partition reads it in every group. One store, no [groups]: one group, the whole context in it.
 *
 * Fields may be added: construct it with [of]; the constructor, which also takes the log groups and
 * snapshot targets the host configures, is internal to Wow.
 */
class ContextBackends @InternalWowApi constructor(
    val log: EventLog,
    val checkpoints: CheckpointStore,
    val leases: LeaseStore,
    val ledger: FailureLedger,
    /**
     * The context's snapshot stores: every store its aggregate types' snapshots are written to, with
     * each type's primary one; [SnapshotTargets.single] for one store. None, its snapshots are not kept.
     */
    val snapshots: SnapshotTargets? = null,
    val reservations: ReservationStore? = null,
    val timers: TimerStore? = null,
    /** The store instance of the default log group, as the host names it (`wow.log.store`). */
    val store: String = DEFAULT_STORE,
    /** The context's other log groups, each on a store of its own. */
    val groups: List<LogGroupBackends> = emptyList(),
    /**
     * The store instances these ports were opened on, which the node creates the schemas of when it starts, probes for
     * its health and closes once it stopped; none for backends an application opened itself.
     */
    val stores: PlacedStores = PlacedStores.NONE,
    /**
     * The one-round-trip reads of a snapshot with its tail, by aggregate type: one for each type whose log and
     * primary snapshots are on one store that reads them together ([Store.snapshotTails]).
     */
    val snapshotTails: SnapshotTailReaders = SnapshotTailReaders.NONE,
) {
    /** The snapshots the write side and the state readers read: each type's primary target. */
    val primarySnapshots: SnapshotStore? get() = snapshots?.primary

    init {
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
        snapshots: SnapshotTargets? = this.snapshots,
        reservations: ReservationStore? = this.reservations,
        timers: TimerStore? = this.timers,
        store: String = this.store,
        groups: List<LogGroupBackends> = this.groups,
        stores: PlacedStores = this.stores,
        snapshotTails: SnapshotTailReaders = this.snapshotTails,
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
        stores,
        snapshotTails,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is ContextBackends && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ContextBackends(log=$log, checkpoints=$checkpoints, leases=$leases, ledger=$ledger, snapshots=$snapshots, " +
            "reservations=$reservations, timers=$timers, store=$store, groups=$groups, stores=$stores)"

    private fun valueFields(): List<Any?> =
        listOf(log, checkpoints, leases, ledger, snapshots, reservations, timers, store, groups, stores, snapshotTails)

    companion object {
        /** The name of the store of a context's default log group when the host names none. */
        const val DEFAULT_STORE: String = "default"

        /**
         * The stores of a context with one log group, on the store [DEFAULT_STORE]: its [log], [checkpoints],
         * [leases] and failure [ledger], and optionally its one snapshot store [snapshots] (as
         * [SnapshotTargets.single]), [reservations] and [timers].
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
        ): ContextBackends = ContextBackends(
            log,
            checkpoints,
            leases,
            ledger,
            snapshots?.let { SnapshotTargets.single(it) },
            reservations,
            timers,
        )
    }
}

/**
 * The store instances a context's ports were opened on: those its [writePath] needs (each store holding one of its
 * logs, each type's primary snapshot store), whose outage makes the node not ready, and the [others] (additional
 * snapshot stores, query and search stores), whose outage the node's health shows without taking it out of service.
 */
class PlacedStores @InternalWowApi constructor(val writePath: Set<Store>, val others: Set<Store> = emptySet()) {
    /** Every placed store, the write path's first. */
    val all: Set<Store> get() = writePath + others

    override fun equals(other: Any?): Boolean =
        this === other || other is PlacedStores && writePath == other.writePath && others == other.others

    override fun hashCode(): Int = 31 * writePath.hashCode() + others.hashCode()

    override fun toString(): String = "PlacedStores(writePath=$writePath, others=$others)"

    @InternalWowApi
    companion object {
        /** No store: backends an application opened itself. */
        @JvmField
        val NONE: PlacedStores = PlacedStores(emptySet())
    }
}

/**
 * The one-round-trip reads of a context's snapshots with their tails ([Store.snapshotTails]): the [default] one, of the
 * aggregate types the layout places nowhere in particular, when their log store holds their primary snapshots, and
 * the one of each type [placed] elsewhere, null when its log and primary snapshots are on different stores.
 */
class SnapshotTailReaders @InternalWowApi constructor(
    private val default: SnapshotTailReader?,
    private val placed: Map<AggregateType, SnapshotTailReader?> = emptyMap(),
) {
    /** The reader of [type]'s snapshots with their tails; null when they are read apart. */
    fun of(type: AggregateType): SnapshotTailReader? = if (type in placed) placed[type] else default

    /** These readers, each wrapped by [wrap]. */
    @InternalWowApi
    fun map(wrap: (AggregateType?, SnapshotTailReader) -> SnapshotTailReader): SnapshotTailReaders =
        SnapshotTailReaders(
            default?.let { wrap(null, it) },
            placed.mapValues { (type, it) -> it?.let { wrap(type, it) } }
        )

    override fun toString(): String = "SnapshotTailReaders(default=${default != null}, placed=${placed.keys})"

    @InternalWowApi
    companion object {
        /** No reader: every snapshot is read apart from its tail. */
        @JvmField
        val NONE: SnapshotTailReaders = SnapshotTailReaders(null)
    }
}

/**
 * One log group of a context other than its default one: the aggregate [types] whose log is [log], on
 * the store instance [store]; the checkpoints, parked entries and timers of the subscriptions reading it are in
 * [checkpoints], [ledger] and [timers], on the same store, and so are its types' [reservations] (an
 * aggregate type's reservations follow its log; a group without its own uses the context's).
 *
 * Fields may be added: Wow builds the groups from the host's configuration, so the constructor is
 * internal to Wow.
 */
class LogGroupBackends @InternalWowApi constructor(
    val store: String,
    val types: Set<AggregateType>,
    val log: EventLog,
    val checkpoints: CheckpointStore,
    val ledger: FailureLedger,
    val timers: TimerStore? = null,
    val reservations: ReservationStore? = null,
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
        reservations: ReservationStore? = this.reservations,
    ): LogGroupBackends = LogGroupBackends(store, types, log, checkpoints, ledger, timers, reservations)

    override fun equals(other: Any?): Boolean =
        this === other || other is LogGroupBackends && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "LogGroupBackends(store=$store, types=$types, log=$log, checkpoints=$checkpoints, ledger=$ledger, " +
            "timers=$timers, reservations=$reservations)"

    private fun valueFields(): List<Any?> = listOf(store, types, log, checkpoints, ledger, timers, reservations)
}

/** Opens the [ContextBackends] of each bounded context the node hosts; the host closes what it opened. */
fun interface BackendFactory {
    fun backends(context: String): ContextBackends
}

/** Where a node runs a command. */
enum class Routing {
    /** On the owner of its log partition, forwarded over the cluster channel; the default. */
    PARTITIONED,

    /** On the node it arrived at, with optimistic concurrency: for a single node. */
    LOCAL,
}

/**
 * The tuning of a [ServiceNode] (names the host's keys for them).
 *
 * - [cluster]: partition ownership and the cluster channel; [routing]: where commands run.
 * - [wait]: the waits ([WaitOptions]); its default timeout is that of every command whose request names none,
 *   whichever edge it came through, capped by its maximum. [ServiceNode.stop] also waits at most that maximum for the
 *   waits registered on the node to end: none of them outlives it.
 * - [subscription]: the subscriptions of every context, the built-in ones included, with the starts the host
 *   configures for some of them ([SubscriptionOptions.starts]): a name no subscription of the node has
 *   fails its assembly.
 * - [appendQueue]: the partition append queue, on by default.
 * - [executor]: how the node's executors take commands ([ExecutorOptions]): each aggregate's lane, its batches, and
 *   the commands in flight on the node.
 * - [stateCache]: what each context's state cache keeps between commands ([StateCacheOptions]).
 * - [sharedStates]: the bounds of each context's shared states, shared by its state consumers.
 * - [timers]: the timer runner of each context that has a timer store.
 * - [schedules]: the host's override of each job's default rule, by job name (`wow.schedules.<job>.*`);
 *   a name no `@OnSchedule` handler of the node runs fails its assembly.
 * - [snapshots]: the snapshot maintenance of each context: how often it claims the state revisions,
 *   rebuilds in place and checks the backfills, and the bounds of a rebuild.
 * - [topicRouted]: the aggregate types whose commands the topic router takes: an
 *   admitted command of one of them is written to its context's command topic, accepted once the broker acknowledged
 *   it, and executed by the owner of its log partition from there. Each must be a type the node serves, the node
 *   needs a producer ([SourceTopics.producer]), and each of their contexts takes the commands of its command topic
 *   whether or not [SourceTopics.intake] is set. None by default.
 * - [handoff]: the bounds of the hand-over of the node's own commits to its subscriptions' tails in memory
 * ([HandoffOptions]).
 * - [ledger]: where the node publishes its failure ledger: the ops context whose `parked` aggregate
 *   records each entry, through its command topic, which needs a producer ([SourceTopics.producer]). Not published by
 *   default.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class ServiceNodeOptions @InternalWowApi constructor(
    val cluster: ClusterOptions = ClusterOptions(),
    val routing: Routing = Routing.PARTITIONED,
    val wait: WaitOptions = WaitOptions(),
    val subscription: SubscriptionOptions = SubscriptionOptions(),
    val appendQueue: AppendQueueOptions = AppendQueueOptions(),
    val executor: ExecutorOptions = ExecutorOptions(),
    val stateCache: StateCacheOptions = StateCacheOptions(),
    val sharedStates: SharedStateOptions = SharedStateOptions(),
    val timers: TimerOptions = TimerOptions(),
    val schedules: Map<String, JobOptions> = emptyMap(),
    val topicRouted: Set<AggregateType> = emptySet(),
    val ledger: LedgerPublishing? = null,
    /** The snapshot maintenance of each context: claims, in-place rebuilds, backfills. */
    val snapshots: SnapshotOptions = SnapshotOptions(),
    val handoff: HandoffOptions = HandoffOptions(),
) {
    /** This ServiceNodeOptions with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        cluster: ClusterOptions = this.cluster,
        routing: Routing = this.routing,
        wait: WaitOptions = this.wait,
        subscription: SubscriptionOptions = this.subscription,
        appendQueue: AppendQueueOptions = this.appendQueue,
        executor: ExecutorOptions = this.executor,
        stateCache: StateCacheOptions = this.stateCache,
        sharedStates: SharedStateOptions = this.sharedStates,
        timers: TimerOptions = this.timers,
        schedules: Map<String, JobOptions> = this.schedules,
        topicRouted: Set<AggregateType> = this.topicRouted,
        ledger: LedgerPublishing? = this.ledger,
        snapshots: SnapshotOptions = this.snapshots,
        handoff: HandoffOptions = this.handoff,
    ): ServiceNodeOptions = ServiceNodeOptions(
        cluster,
        routing,
        wait,
        subscription,
        appendQueue,
        executor,
        stateCache,
        sharedStates,
        timers,
        schedules,
        topicRouted,
        ledger,
        snapshots,
        handoff,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is ServiceNodeOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ServiceNodeOptions(cluster=$cluster, routing=$routing, wait=$wait, subscription=$subscription, " +
            "appendQueue=$appendQueue, executor=$executor, stateCache=$stateCache, sharedStates=$sharedStates, " +
            "timers=$timers, schedules=$schedules, topicRouted=$topicRouted, ledger=$ledger, snapshots=$snapshots, " +
            "handoff=$handoff)"

    private fun valueFields(): List<Any?> = listOf(
        cluster, routing, wait, subscription, appendQueue, executor, stateCache, sharedStates, timers, schedules,
        topicRouted, ledger, snapshots, handoff,
    )
}

/**
 * The admission plug-ins of a node's [me.ahoo.wow.runtime.admission.CommandGateway], and the
 * [tenants] of its aggregate types. [ids] are the node's id generators: admission's
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
 * [sourced] are the context's subscriptions of other contexts' published streams: the node reads
 * each stream's topic ([SourceTopics]) for them instead of the log.
 */
@InternalWowApi
fun interface SubscriptionFactory {
    fun subscriptions(context: ContextNode): List<Subscription>

    /**
     * The subscriptions of [context] that read the published streams of other contexts' aggregates,
     * each with the aggregate types it reads. The node finds each type's topic in its contracts
     * ([me.ahoo.wow.runtime.contract.ContractDirectory.publishedOf]) and reads it ([SourceTopics]). None by default.
     */
    fun sourced(context: ContextNode): List<SourcedSubscription> = emptyList()

    /**
     * What handles the receipts of the commands [context]'s sagas sent to other contexts that rejected or committed
     * them: the node reads the context's receipts topic for it ([SourceTopics.receiptsOf]). None
     * by default; a factory whose sagas have `@OnRejected` or `@OnCommitted` handlers gives them.
     */
    fun receipts(context: ContextNode): ReceiptHandler? = null

    /**
     * The `@OnCommitted` handlers of [context]'s sagas, by name (`processor.function`): each is a saga's hop a wait
     * chain may name, though no subscription of the context's log runs it. None by default.
     */
    fun committedHandlers(context: String): Set<String> = emptySet()

    /**
     * The jobs of [context]'s `@OnSchedule` handlers, asked before the context is assembled: the node
     * adds the built-in job aggregate `<context>.schedule` deciding them, and starts the SYSTEM ones. None by default.
     */
    fun jobs(context: String): List<ScheduleJob> = emptyList()

    /**
     * The aggregate types among [context]'s [models] whose state after each commit the subscriptions read:
     * the context's executor keeps their shared states ([ContextNode.views]), asked before the context is
     * assembled. None by default.
     */
    fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> = emptySet()

    /**
     * The state consumers among the subscriptions of [context], by the reader name each reads the
     * shared states with ([ContextNode.stateReader]), with the aggregate types it reads: a view is evicted once every
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
 * A [subscription] that reads the published streams of [types], aggregates of another context: its
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
 * The topics of a node's broker, each named after [prefix], the prefix every service on the broker writes under:
 *
 * - **Published streams**: the node reads other contexts' streams from [source], each topic named as
 *   the publishing context's Manifest names it (`PublishedManifest.topic`, [topicOf]). Partitions added to a topic
 *   it reads are read too, within [partitionRefresh] of the source seeing them.
 * - **Commands across contexts**, with a [producer]: a saga's command to an aggregate of
 *   a context outside its own is sent to that context's command topic, `wow.<context>.commands` ([commandsOf]), and
 *   the receipts of those another context rejects are read from the saga's context's receipts topic,
 *   `wow.<context>.receipts` ([receiptsOf]).
 * - **Command intake**, when [intake] is set: each context the node hosts executes the commands of its
 *   command topic, whose partitions must be its log's, and sends the receipts of those it rejects through
 *   [producer].
 * - **Topic management**: at its start, the node validates or creates the topics its contexts own as
 *   [ddl] says, through [admin], which any [ddl] but [Ddl.NONE] needs.
 */
class SourceTopics @InternalWowApi constructor(
    val source: PartitionedSource,
    val prefix: String = "",
    val producer: TopicProducer? = null,
    val intake: Boolean = false,
    val admin: TopicAdmin? = null,
    val ddl: Ddl = Ddl.NONE,
    val partitionRefresh: Duration = DEFAULT_PARTITION_REFRESH,
) {
    init {
        require(!intake || producer != null) { "A command intake sends receipts: it needs a producer." }
        require(!ddl.checks || admin != null) { "Topics are validated or created through an admin: $ddl." }
        require(partitionRefresh.isPositive()) { "The partitions of a topic are refreshed at a positive interval." }
    }

    /** The topic of a stream the Manifest publishes on [topic]. */
    fun topicOf(topic: String): String = prefix + topic

    /** The command topic of [context]. */
    fun commandsOf(context: String): String = "${prefix}wow.$context.commands"

    /** The receipts topic of [context]. */
    fun receiptsOf(context: String): String = "${prefix}wow.$context.receipts"

    /** This SourceTopics with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        source: PartitionedSource = this.source,
        prefix: String = this.prefix,
        producer: TopicProducer? = this.producer,
        intake: Boolean = this.intake,
        admin: TopicAdmin? = this.admin,
        ddl: Ddl = this.ddl,
        partitionRefresh: Duration = this.partitionRefresh,
    ): SourceTopics = SourceTopics(source, prefix, producer, intake, admin, ddl, partitionRefresh)

    override fun equals(other: Any?): Boolean =
        this === other || other is SourceTopics && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SourceTopics(" +
        "source=$source, prefix=$prefix, producer=$producer, intake=$intake, admin=$admin, ddl=$ddl, partitionRefresh=$partitionRefresh)"

    private fun valueFields(): List<Any?> = listOf(source, prefix, producer, intake, admin, ddl, partitionRefresh)

    companion object {
        /** A SourceTopics of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            source: PartitionedSource,
            prefix: String = "",
            producer: TopicProducer? = null,
            intake: Boolean = false,
            admin: TopicAdmin? = null,
            ddl: Ddl = Ddl.NONE,
            partitionRefresh: Duration = DEFAULT_PARTITION_REFRESH
        ): SourceTopics = SourceTopics(
            source,
            prefix,
            producer,
            intake,
            admin,
            ddl,
            partitionRefresh
        )

        val DEFAULT_PARTITION_REFRESH: Duration = 30.seconds
    }
}
