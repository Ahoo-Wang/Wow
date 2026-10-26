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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.client.PeerMode
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.http.internal.HttpSignalSender
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.http.ops.WebhookNotifier
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.health.HealthOptions
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.state.StateViewOptions
import me.ahoo.wow.runtime.store.AggregatePlacement
import me.ahoo.wow.runtime.store.StorePlacements
import me.ahoo.wow.runtime.subscription.BackpressureOptions
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.SystemicFailureOptions
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.wait.UnknownContexts
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.TailPolling
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import me.ahoo.wow.client.RetryPolicy as ClientRetryPolicy

/**
 * The host's configuration, the `wow.*` namespace: the node, its internal port, its stores and logs, the cluster, the
 * commands' waits and execution, the subscriptions, the timers and schedules, the HTTP edge and the calls to other
 * contexts. No key keeps a 9.x meaning; the defaults are the runtime's own. The query plane, Kafka, ClickHouse and
 * OpenTelemetry have their own classes ([QueryProperties], [KafkaProperties], [ClickHouseProperties],
 * [ObservabilityProperties]).
 *
 * Every key is in the host's configuration metadata (`META-INF/spring-configuration-metadata.json`), and a `wow.*` key
 * that no property of the host or of a module names is reported at startup ([ConfigurationKeys]).
 */
@ConfigurationProperties(prefix = WowProperties.PREFIX)
class WowProperties {
    /** The service's name; `spring.application.name` when not set. */
    var service: String? = null

    /** This node's identity among the nodes of the service, and where they reach it. */
    var node: Node = Node()

    /** The internal port: the cluster channel, the wait signals and the calls of other services. */
    var internal: Internal = Internal()

    /** The bounded contexts the node serves. */
    var contexts: Contexts = Contexts()

    /**
     * The backend of a node that names no `wow.stores`: `auto` (the default) takes PostgreSQL or MongoDB by the
     * application's `DataSource` or `MongoClient`, and refuses to start without either, so that data is not lost by
     * mistake, unless a profile of `wow.internal.dev-profiles` is active or the internal port binds the loopback
     * interface alone; `memory` keeps the data in memory on purpose, for one node whose data does not outlive it.
     * `postgres`, `mongo` or the type of a community backend's store provider (`me.ahoo.wow.spi.store.StoreProvider`)
     * name one.
     */
    var backend: String = "auto"

    /**
     * How the stores manage the framework's tables, collections and indexes: `auto` creates what is
     * missing (the default), `validate` creates nothing and refuses a store whose schema is missing or in another
     * format, `none` creates and checks nothing; `wow.stores.<name>.ddl` overrides it for one store.
     */
    var ddl: StoreDdl = StoreDdl.AUTO

    /** The defaults of the MongoDB stores. */
    var mongo: Mongo = Mongo()

    /** The defaults of the PostgreSQL stores. */
    var postgres: Postgres = Postgres()

    /** The aggregates' logs: their partitions, their store, and how their appends and tails run. */
    var log: Log = Log()
    var cluster: Cluster = Cluster()
    var wait: Wait = Wait()
    var executor: Executor = Executor()
    var stateCache: StateCache = StateCache()

    /** The bounds of each context's state views: `wow.state-views.*`. */
    var stateViews: StateViews = StateViews()
    var subscription: Subscription = Subscription()

    /** Per subscription, by its name (bracketed in a property name: `wow.subscriptions.[CartSaga.onOrderCreated]`). */
    var subscriptions: MutableMap<String, SubscriptionSettings> = LinkedHashMap()
    var timers: Timers = Timers()

    /**
     * Per `@OnSchedule` job, by its name: `wow.schedules.<job>.*` overrides the job's rule. A job no handler of the
     * node runs fails its start.
     */
    var schedules: MutableMap<String, Schedule> = LinkedHashMap()
    var tenant: Tenant = Tenant()
    var http: Http = Http()
    var calls: Calls = Calls()
    var clients: Clients = Clients()
    var ops: Ops = Ops()

    /** Per aggregate type, by `<context>.<aggregate>` (bracketed in a property name: `wow.aggregates.[shop.cart]`). */
    var aggregates: MutableMap<String, Aggregate> = LinkedHashMap()

    /** The keys wow-cosec reads (`wow.cosec.*`), bound here so they are checked and documented as the others are. */
    var cosec: CoSec = CoSec()

    /** The keys wow-cosid reads (`wow.cosid.*`), bound here as [cosec] is. */
    var cosid: CosId = CosId()

    /** Where `wow.log`, `wow.snapshot` and `wow.aggregates` place the logs and snapshots. */
    internal fun storePlacements(): StorePlacements = StorePlacements(
        log = log.store,
        snapshot = snapshot.store,
        snapshotTargets = snapshot.targets,
        snapshotPrimary = snapshot.primary,
        aggregates = aggregates.mapValues { (_, aggregate) ->
            AggregatePlacement(aggregate.log.store, aggregate.snapshot.store, aggregate.snapshot.primary)
        },
    )

    /**
     * The named store instances, by name: `wow.stores.<name>.type` and its settings. The aggregates whose logs are on
     * one store form a log group, with its own partition sequences, checkpoints, parked entries and timers.
     */
    var stores: MutableMap<String, Store> = LinkedHashMap()

    /** Where the snapshots are by default, and how the snapshot maintenance runs: `wow.snapshot.*`. */
    var snapshot: Snapshot = Snapshot()

    /** The node's health and its probes, when the application has Spring Boot Actuator: `wow.health.*`. */
    var health: Health = Health()

    /** The node's topics without Kafka, for local development, see [Topics]. */
    var topics: Topics = Topics()

    /**
     * Whether the node starts on standby: through the dual-run window of a migration's
     * cut-over it serves reads of what `migrate-data` imports, refuses every command (`wow.node.standby`, 503), and
     * handles no subscription, retries no parked entry and runs no job, until its operator resumes it on the internal
     * port (`POST /wow/node/resume` with the cluster token, naming who resumes it). A subscription that starts at the
     * end of its stream starts where the stream ends at the resume.
     */
    var standby: Boolean = false

    /**
     * Topics for trying a service out without Kafka. With `wow.topics.in-memory` (false), the node keeps
     * the topics a Kafka cluster would hold in its JVM: the command topics of its contexts, which it executes, and
     * their published streams, each with `wow.log.partitions` partitions. A saga's command to another context the
     * node serves then needs no Kafka, and services in one JVM that share the same in-memory topics bean reach each
     * other's topics; separate JVMs do not. Nothing survives a restart. The node warns at startup, and does not start
     * with `wow.kafka.bootstrap-servers` too: for local development and tests only.
     */
    class Topics {
        /** Whether the node's topics are in its JVM instead of Kafka. */
        var inMemory: Boolean = false
    }

    class Node {
        /** This node's id among the nodes of the service; a new one each start when not set. */
        var id: String? = null

        /** The `host:port` of this node's internal port, as the other nodes reach it. */
        var address: String? = null
    }

    class Internal {
        /**
         * The port of the internal server, which carries the cluster channel and the wait signals; 0 for one the
         * system chooses, which the node advertises once the server is bound.
         */
        var port: Int = DEFAULT_INTERNAL_PORT

        /**
         * The address the internal server binds, `0.0.0.0` for every interface; when not set,
         * the host of the member address the node advertises (`wow.node.address`, else this host's address).
         */
        var host: String? = null

        /** How long a peer has to accept a connection of the cluster channel or the signal sender. */
        var connectTimeout: Duration = InternalHttp.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

        /**
         * The wait signals queued per origin node before they are dropped, so a slow or unreachable node cannot
         * hold the sender's memory.
         */
        var signalQueueCapacity: Int = HttpSignalSender.DEFAULT_QUEUE_CAPACITY

        /**
         * The service's cluster tokens, at least 32 characters each: the secret its nodes
         * share, which alone opens the cluster channel (`/wow/internal/forward`) and the wait signals of the internal
         * port. A node sends the first and takes any, so a token is rotated by adding the new one second, then moving
         * it first on every node, then removing the old one; a comma-separated value (`WOW_INTERNAL_TOKEN`) is a list.
         * A service token of `wow.http.services` opens the call routes, not these, and this token no call route.
         *
         * A node without one starts only in a profile of [devProfiles], or with its internal port bound to the
         * loopback interface alone, and warns: its cluster routes then take every request.
         */
        var token: MutableList<String> = ArrayList()

        /**
         * The development profiles, `dev`, `local`, `test` and `demo`: in them the internal port may run without
         * [token], and `wow.backend=auto` falls back to memory when no durable backend is available.
         */
        var devProfiles: MutableSet<String> = linkedSetOf("dev", "local", "test", "demo")
    }

    /**
     * The bounded contexts the node serves among the domain modules on the class path ([ContextSelection]), all of
     * them by default. A name that is no context on the class path is refused at startup.
     */
    class Contexts {
        /** The contexts the node serves; every context on the class path when empty. */
        var include: MutableSet<String> = LinkedHashSet()

        /** The contexts the node does not serve, taken out of those [include] gives. */
        var exclude: MutableSet<String> = LinkedHashSet()

        @InternalWowApi
        fun selection(): ContextSelection = ContextSelection(include.toSet(), exclude.toSet())
    }

    class Postgres {
        /**
         * How long each call of a PostgreSQL store may wait for a reply besides the schema's statements (5 minutes)
         * and the queries (their own `statement_timeout`): a reply lost on the way fails the call, and
         * its connection, instead of blocking it forever. A store's `options.operation-timeout` overrides it.
         */
        var operationTimeout: Duration = 30.seconds.toJavaDuration()
    }

    class Mongo {
        /** The database of the log and the stores on the MongoDB backend, for a store that names none. */
        var database: String = DEFAULT_MONGO_DATABASE

        /**
         * How long an append's insert may take on a store that sets none in its `options.append-timeout`: below the
         * default wait timeout, so a command appending during an outage is answered before its wait
         * ends.
         */
        var appendTimeout: Duration = 5.seconds.toJavaDuration()

        /**
         * Whether a store on a sharded cluster shards its log and snapshot collections when it creates them: `auto`
         * when connected to a mongos with `ddl: auto`, `off` for a cluster its operators shard; a store's
         * `options.sharding` overrides it.
         */
        var sharding: String = "auto"

        /**
         * How long each call of a MongoDB store may take besides appends (`append-timeout`) and the schema's commands
         * (5 minutes): a reply lost on the way fails the call instead of blocking it forever. A store's
         * `options.operation-timeout` overrides it.
         */
        var operationTimeout: Duration = 30.seconds.toJavaDuration()
    }

    class Log {
        /**
         * The partitions of each context's log: fixed once the log holds entries, a power of two that divides 4096,
         * refused at start otherwise.
         */
        var partitions: Int = DEFAULT_PARTITIONS
            set(value) {
                field = Partitioner.requirePartitions(value)
            }

        /** How the appends of one partition are queued and grouped into one call: `wow.log.append-queue.*`. */
        var appendQueue: AppendQueue = AppendQueue()

        /** How often the tail of a PostgreSQL or MongoDB log polls while it finds entries. */
        var pollInterval: Duration = TailPolling.DEFAULT.interval.toJavaDuration()

        /** The longest a tail waits between polls that find nothing: an idle tail backs off up to it. */
        var maxPollInterval: Duration = TailPolling.DEFAULT.maxInterval.toJavaDuration()

        /** The tails' polling these settings give. */
        internal fun tailPolling(): TailPolling =
            TailPolling(pollInterval.toKotlinDuration(), maxPollInterval.toKotlinDuration())

        /**
         * The hand-over of the node's own commits to its tails in memory: a tail waits its gap wait
         * for a missing sequence and [maxPollInterval] without a commit before it reads the store.
         */
        var handoff: Handoff = Handoff()

        /** The hand-over these settings give. */
        internal fun handoffOptions(): HandoffOptions = HandoffOptions(
            enabled = handoff.enabled,
            capacity = handoff.capacity,
            gapWait = handoff.gapWait.toKotlinDuration(),
            probeInterval = maxPollInterval.toKotlinDuration(),
        )

        /**
         * The store of every aggregate's log that `wow.aggregates.[<context>.<aggregate>].log.store` does not name:
         * one of `wow.stores`, the only one when not set.
         */
        var store: String? = null
    }

    /**
     * A named store instance (`wow.stores.<name>`), as 9.x's `storage-routing` binds one: its [type], `memory`,
     * `postgres`, `mongo` or a community backend's (the type of its store provider); on PostgreSQL, the `DataSource`
     * bean [dataSource] names (the only one when not set); on MongoDB, the `MongoClient` bean [client] names (the only
     * one when not set) and its [database] (`wow.mongo.database` when not set); any other setting of its provider in
     * [options] (`wow.stores.<name>.options.<key>`). Two stores of one type may serve different databases; two log
     * stores may not share one. On PostgreSQL, [queryDataSource] names the `DataSource` bean its queries read through,
     * so they do not take the appends' connections, [dataSource]'s when not set; [query] bounds and places its queries.
     */
    class Store {
        var type: String? = null
        var dataSource: String? = null
        var queryDataSource: String? = null
        var client: String? = null
        var database: String? = null

        /** The settings of the store's provider beyond the ones above, by key. */
        var options: MutableMap<String, String> = LinkedHashMap()

        /** How this store manages the framework's schema, over `wow.ddl`. */
        var ddl: StoreDdl? = null

        /** How the store's snapshot and `events` queries run. */
        var query: StoreQuery = StoreQuery()

        /**
         * The settings its provider reads ([me.ahoo.wow.spi.store.StoreConfig.options]): [options] and the named ones.
         */
        internal fun options(): Map<String, String> = options + listOfNotNull(
            dataSource?.let { "data-source" to it },
            queryDataSource?.let { "query-data-source" to it },
            client?.let { "client" to it },
            database?.let { "database" to it },
        )
    }

    /**
     * How one store's queries run (`wow.stores.<name>.query.*`): [maxConcurrent], how many run at once, over
     * `wow.query.max-concurrent` (beyond it a query is `wow.query.busy`, 429; `0` for no bound); [maxWait] and
     * [maxQueued], how long and how many queries beyond it wait for a permit, over `wow.query.max-wait` and
     * `wow.query.max-queued`; [readPreference], the MongoDB read preference of its snapshot and `events` queries
     * (`secondaryPreferred`, say), over `wow.query.events.mongo-read-preference` for the events; [events], its `events`
     * queries; [adaptive], how its bound adapts to its write pressure, over `wow.query.adaptive.*`.
     */
    class StoreQuery {
        var maxConcurrent: Int? = null
        var maxWait: Duration? = null
        var maxQueued: Int? = null
        var readPreference: String? = null
        var events: QueryEvents = QueryEvents()
        var adaptive: StoreAdaptive = StoreAdaptive()
    }

    /**
     * How one store's query bound adapts to its write pressure (`wow.stores.<name>.query.adaptive.*`):
     * [enabled], [floor] and [ceiling] over `wow.query.adaptive.*`.
     */
    class StoreAdaptive {
        var enabled: Boolean? = null
        var floor: Int? = null
        var ceiling: Int? = null
    }

    /**
     * The `events` queries of a store or an aggregate type: [unpinned], whether one that names no
     * aggregate runs, over the store's and `wow.query.events.unpinned`.
     */
    class QueryEvents {
        var unpinned: UnpinnedEvents? = null
    }

    /** An aggregate type's queries (`wow.aggregates.[<context>.<aggregate>].query.*`). */
    class AggregateQuery {
        var events: AggregateQueryEvents = AggregateQueryEvents()
    }

    /**
     * An aggregate type's `events` queries: [unpinned], whether one that names no aggregate runs,
     * over its events store's and `wow.query.events.unpinned`; [store], the store they read (one of `wow.stores`), its
     * log store when not set: an Elasticsearch store whose event search Sink writes the type's streams, say.
     */
    class AggregateQueryEvents {
        var unpinned: UnpinnedEvents? = null
        var store: String? = null
    }

    /**
     * Where the snapshots are, and how the snapshot maintenance runs. Snapshots may have several target stores; a
     * store added to the targets is backfilled from the log: switch the primary to it once it caught up, then drop the
     * old one.
     */
    class Snapshot {
        /**
         * The context's default snapshot store, one of `wow.stores`, whose built-in subscription is `snapshot`;
         * `wow.log.store` when not set.
         */
        var store: String? = null

        /**
         * The stores every aggregate's snapshots are written to, unless its
         * `wow.aggregates.[<context>.<aggregate>].snapshot.store` names others; only `wow.snapshot.store` when empty.
         */
        var targets: MutableList<String> = ArrayList()

        /** The one of the targets the write side and the queries read; the first when not set. */
        var primary: String? = null

        /** How often the snapshot maintenance runs: state revision claims, in-place rebuilds and backfill checks. */
        var interval: Duration = SnapshotOptions.DEFAULT_INTERVAL.toJavaDuration()

        /** How many snapshots a rebuild rewrites per page. */
        var batch: Int = SnapshotOptions.DEFAULT_BATCH

        /** The most pages a rebuild rewrites in one maintenance run. */
        var pages: Int = SnapshotOptions.DEFAULT_PAGES

        /** How many snapshots of a partition the snapshot subscriptions write together at most. */
        var writeBatch: Int = SnapshotOptions.DEFAULT_WRITE_BATCH

        /** The node's snapshot maintenance and write options. */
        fun options(): SnapshotOptions {
            val every = interval.toKotlinDuration()
            val ttl = maxOf(SnapshotOptions.DEFAULT_TTL, every * TTL_INTERVALS)
            return SnapshotOptions(every, batch, pages, ttl, ttl, writeBatch)
        }

        private companion object {
            /** A lease or membership outlives this many runs. */
            const val TTL_INTERVALS = 3
        }
    }

    /**
     * One aggregate type's snapshot targets: [store] lists the stores of `wow.stores` its
     * snapshots are written to, over `wow.snapshot.targets`; [primary] is the one its write side and queries read, the
     * first when not set.
     */
    class AggregateSnapshot {
        var store: MutableList<String> = ArrayList()
        var primary: String? = null
    }

    /** One aggregate type's store of its log or of its snapshots: one of `wow.stores`. */
    class AggregateStore {
        var store: String? = null
    }

    /**
     * The hand-over of the node's own commits to the tails of its subscriptions in memory: the tails read the store
     * only to catch up, when no append of the node can fill a missing sequence any more, or when no commit comes for
     * `wow.log.max-poll-interval`.
     */
    class Handoff {
        /** Whether the node hands its own commits to its subscriptions' tails in memory. */
        var enabled: Boolean = true

        /** The most entries one tail holds that it has not taken; a tail further behind reads the store instead. */
        var capacity: Int = DEFAULT_HANDOFF.capacity

        /**
         * The longest a tail waits for a missing sequence that an append of the node still in flight may hold; once
         * every such append returned without it, the tail reads the store at once.
         */
        var gapWait: Duration = DEFAULT_HANDOFF.gapWait.toJavaDuration()
    }

    /** The partition append queue; each setting not given is the default ([AppendQueueOptions]). */
    class AppendQueue {
        /**
         * Whether the appends of one partition are queued and committed in groups, one call a group; on when not set.
         * A group carries more under load than single appends and costs a few per cent at low concurrency.
         */
        var enabled: Boolean? = null

        /** The most appends one group of a partition carries; 64 when not set. */
        var maxBatch: Int? = null

        /**
         * Whether the groups of several partitions are merged into one call, on a log that takes several partitions
         * a call: on by default, for MongoDB and PostgreSQL; a log that does not take them is called per partition.
         */
        var merge: Boolean? = null

        /** The committers of the merged groups, each with one call in flight; 2 when not set. */
        var mergers: Int? = null

        /** The most streams one merged call takes; 512 when not set. */
        var mergeMax: Int? = null

        fun options(): AppendQueueOptions = AppendQueueOptions().let { default ->
            AppendQueueOptions(
                enabled = enabled ?: default.enabled,
                maxBatch = maxBatch ?: default.maxBatch,
                merge = merge ?: default.merge,
                mergers = mergers ?: default.mergers,
                mergeMax = mergeMax ?: default.mergeMax,
            )
        }
    }

    /** How the nodes of a service share the partitions of each context's log, and forward commands to their owners. */
    class Cluster {
        /**
         * Where commands run: `partitioned`, on the owner of the aggregate's partition, or `local`, here. `local` on
         * the memory backend, which cannot route across nodes, and `partitioned` on the others when not set.
         */
        var router: Routing? = null

        /** How long a partition stays with a node that stopped renewing its lease. */
        var leaseTtl: Duration = DEFAULT_CLUSTER.leaseTtl.toJavaDuration()

        /** How often a node renews its leases; less than half of `lease-ttl`, or every lease is lost between rounds. */
        var renewInterval: Duration = DEFAULT_CLUSTER.renewInterval.toJavaDuration()

        /** How long a node stays a member of the cluster after its last heartbeat. */
        var memberTtl: Duration = DEFAULT_CLUSTER.memberTtl.toJavaDuration()

        /** How often a node sends its heartbeat; less than half of `member-ttl`, so one late heartbeat is harmless. */
        var heartbeatInterval: Duration = DEFAULT_CLUSTER.heartbeatInterval.toJavaDuration()

        /**
         * How often a node compares the partitions it holds with those it should hold; a change of the members it
         * notices reconciles at once.
         */
        var reconcileInterval: Duration = DEFAULT_CLUSTER.reconcileInterval.toJavaDuration()

        /** How often the route table is read from the leases and members; a failed forward refreshes it at once. */
        var routeRefreshInterval: Duration = DEFAULT_CLUSTER.routeRefreshInterval.toJavaDuration()

        /**
         * How long a forward waits for the owner's reply, capped by the command's own deadline:
         * `wow.wait.default-timeout` when not set, so a node whose waits are longer forwards as long. A forward the
         * owner received and did not answer by then is reported unknown (`wow.command.unknown`), not run here, so the
         * command never runs twice and a retry with the same request id is safe.
         */
        var forwardTimeout: Duration? = null

        /** The forwards one peer may have outstanding; one more runs here instead. */
        var maxInFlightPerPeer: Int = DEFAULT_CLUSTER.maxInFlightPerPeer

        /** How long a hand-off of a partition waits for its commands in flight before it moves on. */
        var drainTimeout: Duration = DEFAULT_CLUSTER.drainTimeout.toJavaDuration()

        /** The most lease acquisitions a node runs at a time. */
        var leaseParallelism: Int = DEFAULT_CLUSTER.leaseParallelism

        /**
         * How long one reconcile may run before the node takes itself as stuck and leaves the members until it ends,
         * so the other nodes take the partitions it cannot run; longer than `drain-timeout`.
         */
        var reconcileTimeout: Duration = DEFAULT_CLUSTER.reconcileTimeout.toJavaDuration()

        /** The cluster options, a forward waiting [ClusterOptions]' default when [forwardTimeout] is not set. */
        fun options(): ClusterOptions = options(DEFAULT_CLUSTER.forwardTimeout.toJavaDuration())

        /** The cluster options, a forward waiting [defaultWaitTimeout] when [forwardTimeout] is not set. */
        fun options(defaultWaitTimeout: Duration): ClusterOptions = ClusterOptions(
            leaseTtl = leaseTtl.toKotlinDuration(),
            renewInterval = renewInterval.toKotlinDuration(),
            memberTtl = memberTtl.toKotlinDuration(),
            heartbeatInterval = heartbeatInterval.toKotlinDuration(),
            reconcileInterval = reconcileInterval.toKotlinDuration(),
            routeRefreshInterval = routeRefreshInterval.toKotlinDuration(),
            forwardTimeout = (forwardTimeout ?: defaultWaitTimeout).toKotlinDuration(),
            maxInFlightPerPeer = maxInFlightPerPeer,
            drainTimeout = drainTimeout.toKotlinDuration(),
            leaseParallelism = leaseParallelism,
            reconcileTimeout = reconcileTimeout.toKotlinDuration(),
        )
    }

    /** How long and how far the commands' waits go. */
    class Wait {
        /**
         * How long a command whose request names no timeout waits (`Wow-Wait-Timeout`), whichever edge it came
         * through: at most [maxTimeout].
         */
        var defaultTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT.toJavaDuration()

        /** The longest wait; a longer timeout is capped to it, and a node stops within it. */
        var maxTimeout: Duration = WaitOptions.DEFAULT_MAX_TIMEOUT.toJavaDuration()

        /** The most hops of a wait plan, at most [WaitOptions.MAX_HOPS_CAP]; each saga a chain follows adds two. */
        var maxHops: Int = WaitOptions.DEFAULT_MAX_HOPS

        /**
         * Whether a wait plan may name a context the node knows no Manifest of: `allow`, checked where
         * the chain arrives, or `reject`.
         */
        var unknownContexts: UnknownContexts = UnknownContexts.ALLOW

        /**
         * The most waits the node holds at once, which bounds their memory: beyond it a command whose plan waits past
         * ACCEPTED is not run and is answered `wow.command.busy` (429, with `Retry-After`), FAILED at ACCEPTED.
         */
        var maxRegistered: Int = WaitOptions.DEFAULT_MAX_REGISTERED
    }

    /** How the node executes commands. */
    class Executor {
        /** The commands one aggregate may have waiting in the executor. */
        var laneCapacity: Int = ServiceNodeOptions.DEFAULT_LANE_CAPACITY

        /**
         * The most of one aggregate's queued commands the executor decides in turn and commits with one append; 1
         * runs each alone. It never waits to fill a batch.
         */
        var maxBatch: Int = ServiceNodeOptions.DEFAULT_MAX_BATCH

        /**
         * The commands the node may have in flight, all contexts together: beyond it a command is
         * refused as `wow.command.busy` (429, with `Retry-After`) instead of queued.
         */
        var maxInFlight: Int = InFlightLimit.DEFAULT_MAX
    }

    /**
     * The bounds of each context's state views, the states its consumers share after each commit.
     */
    class StateViews {
        /** The most state views a context keeps. */
        var capacity: Int = StateViewOptions.DEFAULT_CAPACITY

        /** How long a state view is kept at most. */
        var maxAge: Duration = StateViewOptions.DEFAULT_MAX_AGE.toJavaDuration()

        /** The most bytes of serialized state a context's views hold, counting two bytes a character. */
        var maxBytes: Long = StateViewOptions.DEFAULT_MAX_BYTES

        fun options(): StateViewOptions = StateViewOptions(capacity, maxAge.toKotlinDuration(), maxBytes)
    }

    /** The cache of aggregate states each context keeps between commands. */
    class StateCache {
        /**
         * The aggregate states kept per context between commands; 0 keeps none. Not set (the default), each context's
         * cache is bounded by bytes instead: a tenth of the maximum heap (`-Xmx`), split between
         * the node's contexts, each state weighed at an estimated 1 KB plus 12 bytes per recent request it holds (its
         * rings grow with the requests the aggregate commits, up to `recent-requests`), and at least 4096 states with
         * full rings. Most aggregates commit few requests, so the default holds several times as many of them as a
         * count sized for full rings. A miss costs a snapshot and a tail read before the command is decided, so the
         * cache should hold the aggregates written often: set a capacity when states are much larger than 1 KB (a
         * capacity times the average state size should stay well within the heap). The `wow.state-cache` counter
         * (`hit`, `miss`, `evicted`) shows the fit.
         */
        var capacity: Int? = null

        /**
         * The requests each cached aggregate keeps of those it committed last, derived from the log, so
         * that most commands skip looking their request id up; 0 looks every request id up.
         */
        var recentRequests: Int = ServiceNodeOptions.DEFAULT_RECENT_REQUESTS
    }

    /** How the node's subscriptions run, retry their failures and park what keeps failing. */
    class Subscription {
        /**
         * The entries one subscription may hold in one partition between its checkpoint and the last entry it took:
         * it bounds both memory and what a crash replays.
         */
        var maxInFlight: Int = DEFAULT_SUBSCRIPTION.maxInFlight

        /** How often each subscription saves its checkpoint; a stop saves it too. */
        var checkpointInterval: Duration = DEFAULT_SUBSCRIPTION.checkpointInterval.toJavaDuration()

        /**
         * How long a stop waits for the handlers in flight before abandoning them; an abandoned entry is handled
         * again after the restart.
         */
        var stopTimeout: Duration = DEFAULT_SUBSCRIPTION.stopTimeout.toJavaDuration()

        /**
         * How a failing handler is retried in process before its aggregate is parked; a subscription's
         * `wow.subscriptions.[<subscription>].retry.*` and its handler's `@Retry` come before it.
         */
        var retry: Retry = Retry.of(DEFAULT_SUBSCRIPTION.retry)

        /**
         * How a failing read of the log, or write of a store, is retried: until the node stops, the backoff growing
         * for `max-attempts` (5) attempts, after which each failure is logged as an error and retried at
         * `max-backoff`, so a long outage does not stop the subscription.
         */
        var readRetry: Retry = Retry.of(DEFAULT_SUBSCRIPTION.readRetry)

        /** How the parked entries are retried automatically, over minutes and hours. */
        var parked: Parked = Parked()

        /**
         * When a partition pauses on a systemic failure, such as a database that is down, instead of parking every
         * aggregate that fails.
         */
        var systemic: Systemic = Systemic()

        /** How a handler whose downstream pushes back is retried, and its partition throttled. */
        var backpressure: Backpressure = Backpressure()

        /** These options, with the starts, the parked retry specs and the retry budgets [subscriptions] configure. */
        fun options(subscriptions: Map<String, SubscriptionSettings> = emptyMap()): SubscriptionOptions {
            val spec = parked.spec()
            return SubscriptionOptions(
                maxInFlight = maxInFlight,
                retry = retry.policy(),
                checkpointInterval = checkpointInterval.toKotlinDuration(),
                stopTimeout = stopTimeout.toKotlinDuration(),
                readRetry = readRetry.policy(),
                starts = subscriptions.mapNotNull { (name, settings) ->
                    settings.start?.takeIf { it.isNotBlank() }?.let { name to SubscriptionStart.parse(it) }
                }.toMap(),
                parked = ParkedRetryOptions(
                    spec = spec,
                    specs = subscriptions.mapNotNull { (name, settings) ->
                        settings.parked.specOver(
                            spec
                        )?.let { name to it }
                    }
                        .toMap(),
                    scanInterval = parked.scanInterval.toKotlinDuration(),
                ),
                retries = subscriptions.mapNotNull { (name, settings) ->
                    settings.retry.handlerRetry()?.let { name to it }
                }.toMap(),
                systemic = systemic.options(),
                backpressure = backpressure.options(),
            )
        }
    }

    /**
     * How a subscription answers backpressure: a downstream that is up but asks to be called later
     * (`wow.command.busy`, `wow.wait.timeout`, `wow.query.busy`, a 429 with `Retry-After`). The entry is retried, then
     * its aggregate parks; it never pauses the partition. Meanwhile each backpressure halves the handlings the
     * partition runs at once, and each success raises them again.
     */
    class Backpressure {
        /** The most attempts of an entry that meets backpressure before its aggregate parks. */
        var maxAttempts: Int = DEFAULT_BACKPRESSURE.maxAttempts

        /** The wait before the first retry; it doubles at each next one. */
        var initialBackoff: Duration = DEFAULT_BACKPRESSURE.initialBackoff.toJavaDuration()

        /** The longest wait between two retries; a downstream's longer `Retry-After` still counts. */
        var maxBackoff: Duration = DEFAULT_BACKPRESSURE.maxBackoff.toJavaDuration()

        /**
         * The share of each wait, 0 to 1, by which it is shortened at random, so that retries do not come in step.
         */
        var jitter: Double = DEFAULT_BACKPRESSURE.jitter

        /** The fewest handlings a throttled partition runs at once. */
        var minConcurrency: Int = DEFAULT_BACKPRESSURE.minConcurrency

        fun options(): BackpressureOptions = BackpressureOptions(
            maxAttempts = maxAttempts,
            initialBackoff = initialBackoff.toKotlinDuration(),
            maxBackoff = maxBackoff.toKotlinDuration(),
            jitter = jitter,
            minConcurrency = minConcurrency,
        )
    }

    /**
     * When a subscription pauses its partition on a systemic failure: at once on a failure classified systemic, or
     * when enough different aggregates failed within a window with no handling succeeding since. While paused, one
     * attempt at a time probes until a handling succeeds. A handler's failures are classified by its
     * `@Retry(recoverable, unrecoverable)`, then the `FailureClassifier` beans, the backends' and the runtime's own.
     */
    class Systemic {
        /** How many different aggregates failing within `window` pause the partition; 0 turns that off. */
        var failingAggregates: Int = DEFAULT_SYSTEMIC.failingAggregates

        /** The window in which `failing-aggregates` failures pause the partition. */
        var window: Duration = DEFAULT_SYSTEMIC.window.toJavaDuration()

        /** The wait before the first probe of a paused partition; it doubles at each next one. */
        var initialBackoff: Duration = DEFAULT_SYSTEMIC.initialBackoff.toJavaDuration()

        /** The longest wait between two probes of a paused partition. */
        var maxBackoff: Duration = DEFAULT_SYSTEMIC.maxBackoff.toJavaDuration()

        fun options(): SystemicFailureOptions = SystemicFailureOptions(
            failingAggregates = failingAggregates,
            window = window.toKotlinDuration(),
            initialBackoff = initialBackoff.toKotlinDuration(),
            maxBackoff = maxBackoff.toKotlinDuration(),
        )
    }

    /**
     * The automatic retries of parked entries over minutes and hours, as 9.x's `@Retry` and `CompensationScheduler`
     * did; per subscription, `wow.subscriptions.[<subscription>].parked.*` overrides them.
     */
    class Parked {
        /** The most automatic retries of a parked entry; 0 turns them off. */
        var maxRetries: Int = DEFAULT_PARKED.spec.maxRetries

        /**
         * The wait before the first retry after the failure; each next one waits twice as long after the previous one
         * started.
         */
        var minBackoff: Duration = DEFAULT_PARKED.spec.minBackoff.toJavaDuration()

        /** How long a retry may run before the partition's next owner may start another. */
        var executionTimeout: Duration = DEFAULT_PARKED.spec.executionTimeout.toJavaDuration()

        /** How often each node scans its subscriptions' parked entries for retries that are due. */
        var scanInterval: Duration = DEFAULT_PARKED.scanInterval.toJavaDuration()

        fun spec(): ParkedRetrySpec =
            ParkedRetrySpec(maxRetries, minBackoff.toKotlinDuration(), executionTimeout.toKotlinDuration())
    }

    /**
     * One subscription's retry spec of its parked entries, each setting not given being `wow.subscription.parked`'s.
     */
    class ParkedSpec {
        var maxRetries: Int? = null
        var minBackoff: Duration? = null
        var executionTimeout: Duration? = null

        /** The spec these settings give over [base], null when none is given. */
        fun specOver(base: ParkedRetrySpec): ParkedRetrySpec? {
            if (maxRetries == null && minBackoff == null && executionTimeout == null) {
                return null
            }
            return ParkedRetrySpec(
                maxRetries ?: base.maxRetries,
                minBackoff?.toKotlinDuration() ?: base.minBackoff,
                executionTimeout?.toKotlinDuration() ?: base.executionTimeout,
            )
        }
    }

    /**
     * One subscription's settings. [start] is where it starts in a partition it has no checkpoint in yet: `beginning`,
     * `end`, or an ISO-8601 instant (`2026-10-01T00:00:00Z`), the first entry committed at or after it; unset, its
     * kind's default (a projection from the beginning, a reactor or a saga from the end). A subscription that has a
     * checkpoint goes on from it. [parked] is how its parked entries are retried, over `wow.subscription.parked`.
     * [retry] is its in-process retry budget before it parks, over its handler's `@Retry` and `wow.subscription.retry`.
     * A name the node has no subscription of is refused at startup.
     */
    class SubscriptionSettings {
        var start: String? = null
        var parked: ParkedSpec = ParkedSpec()
        var retry: RetrySpec = RetrySpec()
    }

    /**
     * One subscription's in-process retry budget: each setting given replaces its handler's `@Retry`
     * one, each one not given is the handler's, else `wow.subscription.retry`'s. [timeout] bounds one attempt: one
     * that runs longer is cancelled and counts as failed.
     */
    class RetrySpec {
        var maxAttempts: Int? = null
        var initialBackoff: Duration? = null
        var maxBackoff: Duration? = null
        var multiplier: Double? = null
        var timeout: Duration? = null

        /** The budget these settings give, null when none is given. */
        fun handlerRetry(): HandlerRetry? = HandlerRetry(
            maxAttempts = maxAttempts,
            initialBackoff = initialBackoff?.toKotlinDuration(),
            maxBackoff = maxBackoff?.toKotlinDuration(),
            multiplier = multiplier,
            timeout = timeout?.toKotlinDuration(),
        ).takeUnless { it.isEmpty }
    }

    /**
     * The operator's side of the parked entries: their routes, the webhook told when an entry parks, and the failure
     * ledger published to the ops context.
     */
    class Ops {
        /**
         * Whether the operator's routes of the parked entries are served under `/wow/ops/parked` on the public port.
         */
        var enabled: Boolean = false

        /** The webhook told when an entry parks or spends its retries. */
        var webhook: Webhook = Webhook()

        /** Publishing the failure ledger to the ops context. */
        var publish: Publish = Publish()
    }

    /**
     * Publishing the failure ledger to the ops context, so one console sees the parked entries of every service: each
     * entry goes to the `parked` aggregate of the ops context through its command topic. The node needs the Kafka
     * transport.
     */
    class Publish {
        /** Whether the node publishes its failure ledger to the ops context. */
        var enabled: Boolean = false

        /** The ops context the ledger is published to. */
        var context: String = Ledger.OPS_CONTEXT

        /** How often the node scans its ledger and publishes it; an entry is also published at once when it parks. */
        var interval: Duration = LedgerPublishing.DEFAULT_INTERVAL.toJavaDuration()

        /** Where the node publishes its ledger, null when it does not. */
        fun options(): LedgerPublishing? =
            if (enabled) LedgerPublishing(context, interval.toKotlinDuration()) else null
    }

    /**
     * The webhook of the parked entries, told when an entry parks or spends its retries.
     */
    class Webhook {
        /** The URL the webhook posts to; none is posted when not set. */
        var url: URI? = null

        /** The body's format: `generic`, Wow's JSON, or `wecom`, a WeCom robot's Markdown message. */
        var format: WebhookNotifier.Format = WebhookNotifier.Format.GENERIC

        /**
         * The events posted: `parked`, an entry parked, and `exhausted`, an entry spent its retries; both by default.
         */
        var events: MutableSet<ParkedNotice.Kind> = ParkedNotice.Kind.entries.toCollection(LinkedHashSet())

        /** How long one post may take. */
        var timeout: Duration = WebhookNotifier.DEFAULT_TIMEOUT.toJavaDuration()

        /** The headers added to each post, such as a token the receiver checks. */
        var headers: MutableMap<String, String> = LinkedHashMap()
    }

    /**
     * The node's firing loop of the timers, which sends the scheduled commands when they are due.
     */
    class Timers {
        /**
         * How often the loop reads the timers due within `lookahead`, with one query per timer store over every
         * partition the node owns; a timer is sent up to this late.
         */
        var pollInterval: Duration = DEFAULT_TIMERS.pollInterval.toJavaDuration()

        /** How far ahead each read loads the timers that fall due; a longer lookahead reads more timers at once. */
        var lookahead: Duration = DEFAULT_TIMERS.lookahead.toJavaDuration()

        /** The attempts a technical failure to send a timer gets before the timer is parked under `wow.timers`. */
        var maxAttempts: Int = DEFAULT_TIMERS.retry.maxAttempts

        /**
         * How late a timer may be sent, after an outage, before it is cancelled with the outcome `EXPIRED` instead;
         * not set, every timer is sent however late.
         */
        var expiresAfter: Duration? = null

        /** The most commands of timers a partition sends at once. */
        var parallelism: Int = DEFAULT_TIMERS.parallelism

        /**
         * The most commands of timers a partition sends a second, so a backlog of overdue timers drains at that rate;
         * not set, as fast as `parallelism` allows.
         */
        var maxFireRate: Double? = null

        /**
         * How long the claim on a timer being sent holds: when its node dies, the next owner of the partition sends
         * the timer again after it. Longer than a send takes.
         */
        var claimTimeout: Duration = DEFAULT_TIMERS.claimTimeout.toJavaDuration()

        /**
         * How long a scheduled command waits in its target for its due time on the target node's clock; one that
         * arrives earlier than that fails and is sent again later.
         */
        var maxEarlyWait: Duration = DEFAULT_TIMERS.maxEarlyWait.toJavaDuration()

        fun options(): TimerOptions = DEFAULT_TIMERS.copyWith(
            pollInterval = pollInterval.toKotlinDuration(),
            lookahead = lookahead.toKotlinDuration(),
            retry = DEFAULT_TIMERS.retry.copy(maxAttempts = maxAttempts),
            expiresAfter = expiresAfter?.toKotlinDuration(),
            parallelism = parallelism,
            maxFireRate = maxFireRate,
            claimTimeout = claimTimeout.toKotlinDuration(),
            maxEarlyWait = maxEarlyWait.toKotlinDuration(),
        )
    }

    /**
     * The host's override of an `@OnSchedule` job's default rule: its cron [expression], its time
     * [zone] and its [misfire] policy, each unset to keep the annotation's; [enabled] false disables a SYSTEM job's
     * instances at startup. A tenant's own rule of a TENANT job comes before it.
     */
    class Schedule {
        var expression: String? = null
        var zone: String? = null
        var misfire: Misfire? = null
        var enabled: Boolean = true

        fun options(): JobOptions = JobOptions(expression, zone, misfire, enabled)
    }

    /**
     * A retry policy: the wait grows from `initial-backoff` by `multiplier` at each failure, up to `max-backoff`.
     */
    class Retry {
        /**
         * The most attempts of a call in all, the first included; for `read-retry`, the attempts after which each
         * failure is logged as an error and retried at `max-backoff`.
         */
        var maxAttempts: Int = 1

        /** The wait after the first failure. */
        var initialBackoff: Duration = Duration.ZERO

        /** The longest wait between two attempts. */
        var maxBackoff: Duration = Duration.ZERO

        /** How many times longer each wait is than the one before; at least 1. */
        var multiplier: Double = 1.0

        /**
         * How long one attempt of a handler may run before it is cancelled and counts as failed; not set, an attempt
         * runs as long as it takes. A read retry does not use it.
         */
        var timeout: Duration? = null

        fun policy(): RetryPolicy = RetryPolicy(
            maxAttempts = maxAttempts,
            initialBackoff = initialBackoff.toKotlinDuration(),
            maxBackoff = maxBackoff.toKotlinDuration(),
            multiplier = multiplier,
            timeout = timeout?.toKotlinDuration() ?: kotlin.time.Duration.INFINITE,
        )

        companion object {
            fun of(policy: RetryPolicy): Retry = Retry().apply {
                maxAttempts = policy.maxAttempts
                initialBackoff = policy.initialBackoff.toJavaDuration()
                maxBackoff = policy.maxBackoff.toJavaDuration()
                multiplier = policy.multiplier
                timeout = policy.timeout.takeUnless { it.isInfinite() }?.toJavaDuration()
            }
        }
    }

    /** The tenants of the callers. */
    class Tenant {
        /** The tenant of a caller that has none, such as the anonymous one. */
        var default: String = Caller.DEFAULT_TENANT
    }

    /** The HTTP edge: its routes, its error answers and the services the internal port trusts. */
    class Http {
        /**
         * The request headers copied into a command's headers, beside the standard request facts the edge records:
         * `app_id`, the application the caller resolver authenticated, and on the public port
         * `user_agent` and `remote_ip`; none of the three can be listed. `remote_ip` is the server's
         * remote address: behind a proxy, set `server.forward-headers-strategy` to have the server take it from the
         * proxy's forwarded headers.
         */
        var requestFacts: MutableSet<String> = LinkedHashSet()

        /**
         * Whether the public port records each command's client address and `User-Agent` as the request facts
         * `remote_ip` and `user_agent`, as 9.x did, for audit. They are personal data, and written into the event
         * stream, which is never deleted: once recorded, they cannot be erased. On by default; off records neither.
         */
        var clientFacts: Boolean = true

        /** The HTTP status of an error code, by code (bracketed in a property name: `wow.http.errors.[a.b].status`). */
        var errors: MutableMap<String, Error> = LinkedHashMap()

        /**
         * Answers what fails on the application's own routes as the framework routes' problem+json, so every route
         * answers errors alike: on by default; off leaves those errors to Spring's error handling.
         */
        var globalErrors: Boolean = true

        /** The service's Manifest route. */
        var manifest: Manifest = Manifest()

        /** The OpenAPI document of the routes. */
        var openapi: OpenApi = OpenApi()

        /**
         * The service identities the internal port trusts by their static tokens, by service name: a call whose
         * `Authorization: Bearer` token is one of them is that service's, which alone may send `Wow-Call`, and only to
         * the internal port. For a deployment without CoSec; with CoSec, `wow.cosec.service.roles` names its service
         * principals.
         */
        var services: MutableMap<String, Service> = LinkedHashMap()

        /**
         * One trusted service: the [tokens] it may present, at least 32 characters each, several while one is rotated
         * (add the new one here, switch the caller's `wow.calls.token`, then remove the old one), and the [commands]
         * it may call, names or patterns (`AddItem`, `shop.cart.*`, `wow.lifecycle.*`):
         * without them, the domain commands alone; a system command (`wow.*`: the lifecycle, the snapshot rebuild, the
         * schedules) only when an entry naming `wow.` lists it; an entry naming a domain command narrows the domain
         * commands to those listed. A CoSec service principal is configured here by its id, with [commands] alone.
         * The [reads] narrow the models it may read on the internal port's read routes, by
         * `context.aggregate.model` (`ordering.order.snapshot`, `.events`, `.state`) or a dataset's `context.dataset`,
         * patterns with `*`: without them, every model the node serves.
         */
        class Service {
            var tokens: MutableList<String> = ArrayList()
            var commands: MutableList<String> = ArrayList()
            var reads: MutableList<String> = ArrayList()
        }

        class Error {
            var status: Int = 0
        }

        class Manifest {
            /** Serves `GET /wow/manifest`. */
            var enabled: Boolean = true
        }

        class OpenApi {
            /** Serves `GET /wow/openapi`, the OpenAPI 3.1 document of the routes. */
            var enabled: Boolean = true

            /** The springdoc bridge, when the application has springdoc. */
            var springdoc: Springdoc = Springdoc()
        }

        /**
         * The springdoc bridge (SpringdocAutoConfiguration): with springdoc on the classpath, the
         * document of the public routes joins springdoc's default document (`/v3/api-docs` and Swagger UI) beside the
         * application's own controllers, and the [group] lists the Wow routes alone.
         */
        class Springdoc {
            /** Merges the Wow routes into springdoc's documents. */
            var enabled: Boolean = true

            /** The springdoc group of the Wow routes alone: `/v3/api-docs/<group>`. */
            var group: String = SpringdocAutoConfiguration.DEFAULT_GROUP
        }
    }

    /**
     * The synchronous calls of the node's sagas to the command gateways of other contexts. A saga that calls a context
     * without a URL fails, then parks.
     */
    class Calls {
        /**
         * Each context's base URL, `wow.calls.contexts.<context>.url`: the target's internal port, `http://host:port`
         * of an address in its internal network, never its public port, which refuses calls.
         */
        var contexts: MutableMap<String, CallTarget> = LinkedHashMap()

        /**
         * The service token the node presents as `Authorization: Bearer`; an application's
         * [me.ahoo.wow.api.client.ServiceCredential] bean, such as one that renews a CoSec token, replaces it.
         */
        var token: String? = null

        /** How long one call may take. */
        var timeout: Duration = HttpCommandCaller.DEFAULT_TIMEOUT.toJavaDuration()

        /** How long one call may take to connect. */
        var connectTimeout: Duration = HttpCommandCaller.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

        /** Where one context's command gateway is: its public port's base [url]. */
        class CallTarget {
            var url: URI? = null
        }
    }

    /**
     * The typed clients of the peer contexts over HTTP, `wow-client`'s: a peer context listed in [contexts] is reached
     * over HTTP by the service's [me.ahoo.wow.api.client.CommandClient] and [me.ahoo.wow.api.read.QueryApi] beans; any
     * other context as without these clients (its commands through its topic). An application's
     * [me.ahoo.wow.client.EndpointResolver] bean may resolve where each peer is instead. The service mode presents the
     * [me.ahoo.wow.api.client.ServiceCredential] bean (`wow.calls.token`).
     */
    class Clients {
        /**
         * The peer contexts reached over HTTP, by context: one the api jars on the class path declare, or one
         * described by its Manifest alone (`contract: manifest`), called with [me.ahoo.wow.client.NamedCommand]s.
         */
        var contexts: MutableMap<String, ClientTarget> = LinkedHashMap()

        /**
         * The mode of a peer that names none: `service`, through its internal port as this service, or `user`,
         * through its public routes with the end user's credential.
         */
        var mode: PeerMode = PeerMode.SERVICE

        /** How long a request may take to be answered, a command's longer wait aside. */
        var timeout: Duration = WowClient.DEFAULT_TIMEOUT.toJavaDuration()

        /** How long a request may take to connect. */
        var connectTimeout: Duration = HttpCommandCaller.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

        /** The headers of the public request it serves that a user-mode request forwards. */
        var forwardedHeaders: MutableList<String> = mutableListOf("Authorization")

        /** How a request a peer answered with a problem a retry may get past is sent again. */
        var retry: Retry = Retry()

        /**
         * One peer context: its public routes' base [url] (user mode), its internal port's [internalUrl] (service
         * mode; `wow.calls.contexts.<context>.url` when not set), the [mode] it is called in and where its [contract]
         * comes from (`jar`, or `manifest` for a peer called by its Manifest alone).
         */
        class ClientTarget {
            var url: URI? = null
            var internalUrl: URI? = null
            var mode: PeerMode? = null
            var contract: Contract = Contract.JAR
        }

        /** Where a peer's contract comes from. */
        enum class Contract {
            /** Its api jar on the class path: its command objects are sent. */
            JAR,

            /**
             * Its Manifest alone, read from its `GET /wow/manifest`: a console, a gateway or a tool calls it with
             * [me.ahoo.wow.client.NamedCommand]s, without its api jar.
             */
            MANIFEST,
        }

        /**
         * How a problem a retry may get past is retried ([me.ahoo.wow.client.RetryPolicy]): a 429, a command's problem
         * that keeps the request id, or a connection that could not be made.
         */
        class Retry {
            /** The most attempts of a request in all; 1 never retries. */
            var maxAttempts: Int = ClientRetryPolicy.DEFAULT.maxAttempts

            /** The longest `Retry-After` of a peer that is waited out; a peer that asks for longer is not retried. */
            var maxRetryAfter: Duration = ClientRetryPolicy.DEFAULT.maxRetryAfter.toJavaDuration()

            /** The wait before the first retry when the peer gives no `Retry-After`; it doubles at each next one. */
            var backoff: Duration = ClientRetryPolicy.DEFAULT.backoff.toJavaDuration()
        }
    }

    /**
     * An aggregate type's settings. [tenant] fixes the tenant of every aggregate of the type (replacing 9.x
     * `@StaticTenantId`): its commands and state reads are in that tenant whoever the caller is
     * ([me.ahoo.wow.spi.tenant.TenantPolicy]). [idGenerator] names the
     * [me.ahoo.wow.spi.id.AggregateIdGenerator] bean that gives the type's new ids, or a generator an
     * [me.ahoo.wow.spi.id.AggregateIdGeneratorSource] bean finds by that name (CosId's, with wow-cosid); the type's ids
     * come from the `wowAggregateIdGenerator` bean otherwise. A type the node does not serve, or a name nothing gives
     * a generator of, is refused at startup.
     */
    class Aggregate {
        var tenant: String? = null
        var idGenerator: String? = null

        /**
         * How the commands of the type are routed: `cluster`, by the node's router, or `kafka`, through its context's
         * command topic, accepted once the broker acknowledged them, which needs Kafka.
         */
        var routing: AggregateRouting = AggregateRouting.CLUSTER

        /** The store of the type's log, over `wow.log.store`: the type's log group. */
        var log: AggregateStore = AggregateStore()

        /**
         * The stores of the type's snapshots, over `wow.snapshot.targets`; its queries read the primary one.
         */
        var snapshot: AggregateSnapshot = AggregateSnapshot()

        /**
         * How the ClickHouse Sink keeps the type's commits, in the same style as the other per-aggregate keys: over
         * `wow.clickhouse.sink.aggregates.<context>.<aggregate>`, which is still read.
         */
        var clickhouse: AggregateClickHouse = AggregateClickHouse()

        /** The type's queries: whether its `events` queries that name no aggregate run, and the store they read. */
        var query: AggregateQuery = AggregateQuery()
    }

    /** An aggregate type's ClickHouse settings: `wow.aggregates.[<context>.<aggregate>].clickhouse.*`. */
    class AggregateClickHouse {
        var history: AggregateHistory = AggregateHistory()
    }

    /**
     * Whether every commit of the type is kept ([enabled], on by default), and how long ([ttl], forever by default).
     */
    class AggregateHistory {
        var enabled: Boolean? = null
        var ttl: Duration? = null
    }

    /**
     * CoSec's settings (wow-cosec), which that module reads from the environment: bound
     * here, they have metadata, and a key of `wow.cosec` that none of these is refuses the startup
     * ([ConfigurationKeys]) instead of being ignored, so a mistyped [Authorizer.enabled] cannot leave the authorizer
     * that allows all in place.
     */
    class CoSec {
        /** CoSec's authorization of the commands. */
        var authorizer: Authorizer = Authorizer()

        /** The callers that may act across scopes: by CoSec role or policy. */
        var crossScope: Principals = Principals()

        /** The tenant-level callers, who read and write every aggregate of their tenant. */
        var tenantLevel: Principals = Principals()

        /** The service identities, whose synchronous calls the internal port takes. */
        var service: Principals = Principals()

        /**
         * Whether a service in CoSec's inject mode, behind a CoSec gateway (`cosec.inject.enabled=true` with
         * `cosec.authorization.enabled=false`), records the application of the requests the gateway forwards, their
         * `CoSec-App-Id`, as the caller's app and the request fact `app_id`: it trusts the gateway, so it is off by
         * default; in any other mode it fails the startup.
         */
        var trustInjectedApp: Boolean = false

        class Authorizer {
            /**
             * Whether CoSec authorizes every command as its caller (`CoSecCommandAuthorizer`), not only the public
             * routes by path; the services' calls are authorized by CoSec whatever this says.
             */
            var enabled: Boolean = false
        }

        /** Callers by their CoSec roles or policies, comma-separated or as a list. */
        class Principals {
            /** The CoSec roles of these callers. */
            var roles: MutableSet<String> = LinkedHashSet()

            /** The CoSec policies of these callers. */
            var policies: MutableSet<String> = LinkedHashSet()
        }
    }

    /**
     * The node's health (me.ahoo.wow.runtime.health.NodeHealth): with Spring Boot Actuator on the class
     * path, the contributor `wow` (every component), and `wowReadiness` and `wowLiveness`, which the host adds to
     * Actuator's `readiness` and `liveness` groups (`/actuator/health/readiness`, `/actuator/health/liveness`) unless
     * the application configures their members itself. The thresholds are conservative: a probe that fails too soon
     * takes nodes out of service, or restarts them, for what a retry would have absorbed.
     */
    class Health {
        /** Whether the host contributes the node's health to Actuator and its probe groups. */
        var enabled: Boolean = true

        /**
         * How often the node samples what a probe must not wait for (the logs' heads, the subscriptions' lag and
         * parked entries) and probes its stores.
         */
        var sampleInterval: Duration = DEFAULT_HEALTH.sampleInterval.toJavaDuration()
        var stores: HealthStores = HealthStores()
        var ownership: HealthOwnership = HealthOwnership()
        var topics: HealthTopics = HealthTopics()
        var executor: HealthExecutor = HealthExecutor()
        var subscriptions: HealthSubscriptions = HealthSubscriptions()
        var liveness: HealthLiveness = HealthLiveness()

        @InternalWowApi
        fun options(): HealthOptions = HealthOptions(
            sampleInterval = sampleInterval.toKotlinDuration(),
            storeDownAfter = stores.downAfter.toKotlinDuration(),
            storeWindow = stores.window.toKotlinDuration(),
            handoverGrace = ownership.handoverGrace.toKotlinDuration(),
            topicsRequired = topics.required,
            executorSaturation = executor.saturation,
            lagDegradedAt = subscriptions.lagDegradedAt,
            parkedDegradedAt = subscriptions.parkedDegradedAt,
            stallTimeout = liveness.stallTimeout.toKotlinDuration(),
        )
    }

    /** The node's event logs, as its own calls and a probe of their heads see them. */
    class HealthStores {
        /**
         * How long a log must keep failing, with no success since, before it is down and the node not ready: a failed
         * call or probe, or a probe that timed out (after this long, at least a second); a probe still under way is no
         * failure. `0s` is down at the first failure.
         */
        var downAfter: Duration = DEFAULT_HEALTH.storeDownAfter.toJavaDuration()

        /** A log with a failure this recent is degraded. */
        var window: Duration = DEFAULT_HEALTH.storeWindow.toJavaDuration()
    }

    /** The node's share of each lease group's partitions. */
    class HealthOwnership {
        /**
         * How long the owned partitions may differ from the node's share (a hand-over) before the group is degraded,
         * and down (the node not ready) when it owns none of a share that is not empty.
         */
        var handoverGrace: Duration = DEFAULT_HEALTH.handoverGrace.toJavaDuration()
    }

    /** The topics of other contexts the node reads. */
    class HealthTopics {
        /** Whether a topic the node is still joining makes it not ready; degraded only when false. */
        var required: Boolean = DEFAULT_HEALTH.topicsRequired
    }

    /** The node's command executors. */
    class HealthExecutor {
        /** The share of `wow.executor.max-in-flight` in flight at which the executor is degraded. */
        var saturation: Double = DEFAULT_HEALTH.executorSaturation
    }

    /** The node's subscriptions, never a reason to take it out of service. */
    class HealthSubscriptions {
        /** The entries a subscription may be behind the log's head before it is degraded. */
        var lagDegradedAt: Long = DEFAULT_HEALTH.lagDegradedAt

        /** The parked entries of a subscription, counted up to this, at which it is degraded (as when they grow). */
        var parkedDegradedAt: Int = DEFAULT_HEALTH.parkedDegradedAt
    }

    /** Whether the node is stuck, so a restart helps; a storage outage never fails it. */
    class HealthLiveness {
        /**
         * How long the node's dispatcher may not tick, or commands in flight not finish while every log answers,
         * before the node is not live.
         */
        var stallTimeout: Duration = DEFAULT_HEALTH.stallTimeout.toJavaDuration()
    }

    /** CosId's settings (wow-cosid), which that module reads from the environment. */
    class CosId {
        /** The CosId generator of the node's global ids (command ids, wait ids); CosId's own `cosid` when not set. */
        var globalGenerator: String? = null
    }

    /** How the commands of an aggregate type are routed (`wow.aggregates.[<context>.<aggregate>].routing`). */
    enum class AggregateRouting {
        /** By the node's router, `wow.cluster.router`: on the owner of the aggregate's partition, or here. */
        CLUSTER,

        /**
         * The `Kafka` router: written to the context's command topic `wow.<context>.commands`, and
         * executed from there by the owner of the aggregate's log partition. ACCEPTED means durable: a plan that
         * waits only for it is answered 202 once the broker acknowledged the command.
         */
        KAFKA,
    }

    companion object {
        const val PREFIX: String = "wow"
        const val DEFAULT_INTERNAL_PORT: Int = 9090
        const val DEFAULT_PARTITIONS: Int = 64
        const val DEFAULT_MONGO_DATABASE: String = "wow"
        private val DEFAULT_CLUSTER = ClusterOptions()
        private val DEFAULT_SUBSCRIPTION = SubscriptionOptions()
        private val DEFAULT_TIMERS = TimerOptions()
        private val DEFAULT_PARKED = ParkedRetryOptions()
        private val DEFAULT_HEALTH = HealthOptions()
        private val DEFAULT_SYSTEMIC = SystemicFailureOptions()
        private val DEFAULT_BACKPRESSURE = BackpressureOptions()
        private val DEFAULT_HANDOFF = HandoffOptions()
    }
}
