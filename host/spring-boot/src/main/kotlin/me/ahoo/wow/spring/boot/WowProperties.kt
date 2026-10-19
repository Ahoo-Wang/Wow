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
import me.ahoo.wow.http.call.HttpCommandCaller
import me.ahoo.wow.http.internal.HttpSignalSender
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.http.ops.WebhookNotifier
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.state.StateViewOptions
import me.ahoo.wow.runtime.store.AggregatePlacement
import me.ahoo.wow.runtime.store.StorePlacements
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
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * The host's configuration, the `wow.*` namespace (decision D110). No key keeps a 9.x meaning; the defaults are the
 * runtime's own.
 *
 * - `wow.service`: the service's name (the lease groups', the wait origins' and the Manifest's), by default
 *   `spring.application.name`.
 * - `wow.node.id`, `wow.node.address`: this node's id (by default the host name and a random suffix) and the
 *   `host:port` the other nodes reach its internal port at (by default this host's address and `wow.internal.port`).
 * - `wow.internal.port` (9090; 0 for a port the system chooses, which the node learns once bound) and
 *   `wow.internal.host` (the host of the node's member address; `0.0.0.0` for every interface, D195): the internal
 *   port (D89); `wow.internal.token`, the service's cluster tokens, and `wow.internal.dev-profiles`, the profiles
 *   that may go without one (decision D226), see [Internal].
 * - `wow.contexts.include`, `wow.contexts.exclude`: the bounded contexts the node serves among the domain modules on
 *   the class path, by default all of them (decision D169), see [Contexts].
 * - `wow.backend`: `auto` (the default), `memory`, `postgres`, `mongo`, or the type of a community backend's store
 *   provider (`me.ahoo.wow.spi.store.StoreProvider`, review R3.3-1); `wow.mongo.database`: the database of a MongoDB
 *   store that names none; `wow.ddl` (`auto`, `validate` or `none`), how the stores manage the framework's
 *   tables (review A5-10), `wow.stores.<name>.ddl` per store.
 * - `wow.stores.<name>.*`: the named store instances (decision D177, [Store]); `wow.log.store` and
 *   `wow.snapshot.store`, the stores of the aggregates' logs and snapshots by default; per aggregate type,
 *   `wow.aggregates.[<context>.<aggregate>].log.store` and `.snapshot.store`. The aggregates whose logs are on one store
 *   form a log group, with its own partition sequences, checkpoints, parked entries and timers. Snapshots may have
 *   several targets (decision D172, [Snapshot]): `wow.snapshot.targets` and `wow.snapshot.primary`, per aggregate type
 *   `.snapshot.store` as a list and `.snapshot.primary`; `wow.snapshot.interval`, `batch` and `pages` tune the
 *   snapshot maintenance.
 * - `wow.log.partitions` (64; a power of two that divides 4096, decision D240) and `wow.log.append-queue.*`, whose
 *   defaults are the backend's preset; `wow.log.poll-interval` (50ms) and `wow.log.max-poll-interval` (1s), how often
 *   the tails of a PostgreSQL or MongoDB log poll: at the first while they find entries, backing off to the second
 *   while they find none (finding A2-14).
 * - `wow.cluster.*` ([ClusterOptions]) and `wow.cluster.router`: `partitioned`, or `local` (the default on the memory
 *   backend, which cannot route across nodes).
 * - `wow.wait.default-timeout` (10s), `max-timeout` (60s), `max-hops` (16, at most 32), `unknown-contexts` (`allow`
 *   or `reject`, decision D190): the default timeout is the node's ([ServiceNodeOptions.defaultWaitTimeout]), for every
 *   command whose request names none.
 * - `wow.state-views.capacity` (4096), `max-age` (1m), `max-bytes` (64 MiB): the bounds of each context's state views
 *   (decision D133), see [StateViews].
 * - `wow.executor.lane-capacity` (1024), `wow.executor.max-in-flight` (4096, the node's commands in flight, beyond
 *   which a command is `wow.command.busy`, decision D228 ②), `wow.state-cache.capacity`, `wow.subscription.*`.
 * - `wow.subscriptions.[<subscription>].start`: `beginning`, `end` or an ISO-8601 instant, where the subscription
 *   (`processor.function`, bracketed for its dot) starts in a partition it has no checkpoint in yet, instead of its
 *   kind's default (decision D154), see [SubscriptionSettings].
 * - `wow.subscription.parked.max-retries` (10), `min-backoff` (3m), `execution-timeout` (2m), `scan-interval` (10s):
 *   the long-horizon retries of parked entries (roadmap P.1), see [Parked]; per subscription,
 *   `wow.subscriptions.[<subscription>].parked.max-retries`, `min-backoff` and `execution-timeout`, see [ParkedSpec].
 * - `wow.subscription.retry.max-attempts` (5), `initial-backoff` (100ms), `max-backoff` (5s), `multiplier` (2.0),
 *   `timeout` (none): the in-process retries of a failing handler before its aggregate parks; per subscription,
 *   `wow.subscriptions.[<subscription>].retry.*` (roadmap P.3), over the handler's `@Retry`, see [RetrySpec].
 * - `wow.subscription.systemic.failing-aggregates` (3, 0 for none), `window` (10s), `initial-backoff` (1s),
 *   `max-backoff` (30s): a subscription pauses its partition on a systemic failure (decision D243), or when that many
 *   different aggregates failed within the window, and probes it at that backoff until a handling succeeds, see
 *   [Systemic]. A handler's failures are classified by its `@Retry(recoverable, unrecoverable)`, then the
 *   `FailureClassifier` beans, the backends' and the runtime's own (decision D229 G-6).
 * - `wow.ops.enabled` (false): the operator's routes under `/wow/ops/parked`; `wow.ops.webhook.url`, `format`
 *   (`generic` or `wecom`), `events` (`parked`, `exhausted`), `timeout` (5s), `headers`: the webhook told when an
 *   entry parks or spends its retries, see [Ops] and [Webhook]; `wow.ops.publish.enabled` (false), `context` (`ops`),
 *   `interval` (30s): the failure ledger published to the ops context (decision D141), see [Publish].
 * - `wow.timers.poll-interval` (1s), `lookahead` (30s), `max-attempts` (10), `expires-after` (none), `parallelism`
 *   (64), `max-fire-rate` (none, per partition and second), `claim-timeout` (30s), `max-early-wait` (5s): the
 *   node's firing loop of the scheduled commands (decision D166), see [Timers].
 * - `wow.schedules.<job>.expression`, `zone`, `misfire` (`coalesce` or `catch-up`), `enabled` (true): the host's
 *   override of an `@OnSchedule` job's rule (decision D198), see [Schedule]; a job no handler of the node runs fails
 *   its start.
 * - `wow.tenant.default`: the tenant of a caller that has none (D97).
 * - `wow.http.request-facts`, `wow.http.errors.[<code>].status`, `wow.http.manifest.enabled`,
 *   `wow.http.openapi.enabled` (`GET /wow/openapi`, the OpenAPI document of the routes),
 *   `wow.http.openapi.springdoc.enabled` and `group` (the springdoc bridge, decision D233);
 *   `wow.http.services.<service>.tokens`: the service identities the internal port trusts without CoSec (decision
 *   D193), and `wow.http.services.<service>.commands`, the commands each may call there (decision D226), see
 *   [Http.Service].
 * - `wow.calls.contexts.<context>.url`, `wow.calls.token`, `wow.calls.timeout` (10s), `wow.calls.connect-timeout`
 *   (2s): the synchronous calls of the sagas to other contexts' command gateways (decision D193), see [Calls].
 * - `wow.aggregates.[<context>.<aggregate>].tenant`: a fixed tenant per aggregate type (decision D116);
 *   `wow.aggregates.[<context>.<aggregate>].id-generator`: the generator of its new ids (decision D171);
 *   `wow.aggregates.[<context>.<aggregate>].routing`: `cluster` (the default, the node's `wow.cluster.router`)
 *   or `kafka`, the `Kafka` router (decision D143), see [Aggregate].
 * - `wow.cosec.*` and `wow.cosid.*`: the keys wow-cosec and wow-cosid read, see [CoSec] and [CosId].
 *
 * Every key is in the host's configuration metadata (`META-INF/spring-configuration-metadata.json`, review A5-9), and
 * a `wow.*` key that no property of the host or of a module names is reported at startup ([ConfigurationKeys]).
 */
@ConfigurationProperties(prefix = WowProperties.PREFIX)
class WowProperties {
    /** The service's name; `spring.application.name` when not set. */
    var service: String? = null
    var node: Node = Node()
    var internal: Internal = Internal()
    var contexts: Contexts = Contexts()

    /**
     * The backend of a node that names no `wow.stores` (decision D110): `auto` (the default) takes PostgreSQL or
     * MongoDB by the application's `DataSource` or `MongoClient`, and refuses to start without either (review R3.5-1)
     * unless a profile of `wow.internal.dev-profiles` is active or the internal port binds the loopback interface
     * alone; `memory` keeps the data in memory on purpose, for one node whose data does not outlive it.
     */
    var backend: String = "auto"

    /**
     * How the stores manage the framework's tables, collections and indexes (review A5-10): `auto` creates what is
     * missing (the default), `validate` creates nothing and refuses a store whose schema is missing or in another
     * format, `none` creates and checks nothing; `wow.stores.<name>.ddl` overrides it for one store.
     */
    var ddl: StoreDdl = StoreDdl.AUTO
    var mongo: Mongo = Mongo()
    var log: Log = Log()
    var cluster: Cluster = Cluster()
    var wait: Wait = Wait()
    var executor: Executor = Executor()
    var stateCache: StateCache = StateCache()

    /** The bounds of each context's state views (decision D133, review A5-10): `wow.state-views.*`. */
    var stateViews: StateViews = StateViews()
    var subscription: Subscription = Subscription()

    /** Per subscription, by its name (bracketed in a property name: `wow.subscriptions.[CartSaga.onOrderCreated]`). */
    var subscriptions: MutableMap<String, SubscriptionSettings> = LinkedHashMap()
    var timers: Timers = Timers()

    /** Per `@OnSchedule` job, by its name: `wow.schedules.<job>.*` (decision D198). */
    var schedules: MutableMap<String, Schedule> = LinkedHashMap()
    var tenant: Tenant = Tenant()
    var http: Http = Http()
    var calls: Calls = Calls()
    var ops: Ops = Ops()

    /** Per aggregate type, by `<context>.<aggregate>` (bracketed in a property name: `wow.aggregates.[shop.cart]`). */
    var aggregates: MutableMap<String, Aggregate> = LinkedHashMap()

    /** The keys wow-cosec reads (`wow.cosec.*`), bound here so they are checked and documented as the others are. */
    var cosec: CoSec = CoSec()

    /** The keys wow-cosid reads (`wow.cosid.*`), bound here as [cosec] is. */
    var cosid: CosId = CosId()

    /** Where `wow.log`, `wow.snapshot` and `wow.aggregates` place the logs and snapshots (decisions D172, D177). */
    internal fun storePlacements(): StorePlacements = StorePlacements(
        log = log.store,
        snapshot = snapshot.store,
        snapshotTargets = snapshot.targets,
        snapshotPrimary = snapshot.primary,
        aggregates = aggregates.mapValues { (_, aggregate) ->
            AggregatePlacement(aggregate.log.store, aggregate.snapshot.store, aggregate.snapshot.primary)
        },
    )

    /** The named store instances (decision D177), by name: `wow.stores.<name>.type` and its settings. */
    var stores: MutableMap<String, Store> = LinkedHashMap()

    /** Where the snapshots are by default (decision D177): `wow.snapshot.store`. */
    var snapshot: Snapshot = Snapshot()

    class Node {
        /** This node's id among the nodes of the service; a new one each start when not set. */
        var id: String? = null

        /** The `host:port` of this node's internal port, as the other nodes reach it. */
        var address: String? = null
    }

    class Internal {
        /**
         * The port of the internal server: the cluster channel and the wait signals (decision D89); 0 for one the
         * system chooses, which the node advertises once the server is bound.
         */
        var port: Int = DEFAULT_INTERNAL_PORT

        /**
         * The address the internal server binds (decisions D173, D195), `0.0.0.0` for every interface; when not set,
         * the host of the member address the node advertises (`wow.node.address`, else this host's address).
         */
        var host: String? = null

        /** How long a peer has to accept a connection of the cluster channel or the signal sender. */
        var connectTimeout: Duration = InternalHttp.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

        /** The signals queued per origin node before they are dropped (decision D109). */
        var signalQueueCapacity: Int = HttpSignalSender.DEFAULT_QUEUE_CAPACITY

        /**
         * The service's cluster tokens (decision D226, review A3-1), at least 32 characters each: the secret its nodes
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
         * [token], and `wow.backend=auto` falls back to memory when no durable backend is available (review R3.5-1).
         */
        var devProfiles: MutableSet<String> = linkedSetOf("dev", "local", "test", "demo")
    }

    /**
     * The bounded contexts the node serves (decision D169, [ContextSelection]): every context of the domain modules
     * on the class path by default; only those [include] names when set; less those [exclude] names. A name that is
     * no context on the class path is refused at startup.
     */
    class Contexts {
        var include: MutableSet<String> = LinkedHashSet()
        var exclude: MutableSet<String> = LinkedHashSet()

        @InternalWowApi
        fun selection(): ContextSelection = ContextSelection(include.toSet(), exclude.toSet())
    }

    class Mongo {
        /** The database of the log and the stores on the MongoDB backend. */
        var database: String = DEFAULT_MONGO_DATABASE
    }

    class Log {
        /**
         * The partitions of each context's log: fixed once the log holds entries, a power of two that divides 4096
         * (decision D240), refused at start otherwise.
         */
        var partitions: Int = DEFAULT_PARTITIONS
            set(value) {
                field = Partitioner.requirePartitions(value)
            }
        var appendQueue: AppendQueue = AppendQueue()

        /** How often a tail polls while it finds entries (finding A2-14). */
        var pollInterval: Duration = TailPolling.DEFAULT.interval.toJavaDuration()

        /** The longest a tail waits between polls that find nothing: an idle tail backs off up to it. */
        var maxPollInterval: Duration = TailPolling.DEFAULT.maxInterval.toJavaDuration()

        /** The tails' polling these settings give. */
        internal fun tailPolling(): TailPolling =
            TailPolling(pollInterval.toKotlinDuration(), maxPollInterval.toKotlinDuration())

        /**
         * The store of every aggregate's log that `wow.aggregates.[<context>.<aggregate>].log.store` does not name
         * (decision D177): one of `wow.stores`, the only one when not set.
         */
        var store: String? = null
    }

    /**
     * A named store instance (decision D177, `wow.stores.<name>`), as 9.x's `storage-routing` binds one: its [type],
     * `memory`, `postgres`, `mongo` or a community backend's (the type of its store provider, review R3.3-1); on
     * PostgreSQL, the `DataSource` bean [dataSource] names (the only one when not set); on MongoDB, the `MongoClient`
     * bean [client] names (the only one when not set) and its [database] (`wow.mongo.database` when not set); any
     * other setting of its provider in [options] (`wow.stores.<name>.options.<key>`). Two stores of one type may serve
     * different databases; two log stores may not share one.
     */
    class Store {
        var type: String? = null
        var dataSource: String? = null
        var client: String? = null
        var database: String? = null

        /** The settings of the store's provider beyond the ones above, by key. */
        var options: MutableMap<String, String> = LinkedHashMap()

        /** How this store manages the framework's schema, over `wow.ddl`. */
        var ddl: StoreDdl? = null

        /** The settings its provider reads ([me.ahoo.wow.spi.store.StoreConfig.options]): [options] and the named ones. */
        internal fun options(): Map<String, String> = options + listOfNotNull(
            dataSource?.let { "data-source" to it },
            client?.let { "client" to it },
            database?.let { "database" to it },
        )
    }

    /**
     * Where the snapshots are (decisions D172, D177): [store] is the context's default snapshot store, `wow.log.store`
     * when not set, whose built-in subscription is `snapshot`; [targets] are the stores every aggregate's snapshots
     * are written to when `wow.aggregates.[<context>.<aggregate>].snapshot.store` names none, `[store]` when empty;
     * [primary] is the one of them the write side and the queries read, the first when not set. A store added to the
     * targets is backfilled from the log; switch the primary to it once it caught up, then drop the old one.
     *
     * The snapshot maintenance (state revision claims, in-place rebuilds, backfill checks) runs every [interval]; a
     * rebuild rewrites [batch] snapshots a page, at most [pages] pages a run.
     */
    class Snapshot {
        var store: String? = null
        var targets: MutableList<String> = ArrayList()
        var primary: String? = null
        var interval: Duration = SnapshotOptions.DEFAULT_INTERVAL.toJavaDuration()
        var batch: Int = SnapshotOptions.DEFAULT_BATCH
        var pages: Int = SnapshotOptions.DEFAULT_PAGES

        /** The node's snapshot maintenance options. */
        fun options(): SnapshotOptions {
            val every = interval.toKotlinDuration()
            val ttl = maxOf(SnapshotOptions.DEFAULT_TTL, every * TTL_INTERVALS)
            return SnapshotOptions(every, batch, pages, ttl, ttl)
        }

        private companion object {
            /** A lease or membership outlives this many runs. */
            const val TTL_INTERVALS = 3
        }
    }

    /**
     * One aggregate type's snapshot targets (decisions D172, D177): [store] lists the stores of `wow.stores` its
     * snapshots are written to, over `wow.snapshot.targets`; [primary] is the one its write side and queries read, the
     * first when not set.
     */
    class AggregateSnapshot {
        var store: MutableList<String> = ArrayList()
        var primary: String? = null
    }

    /** One aggregate type's store of its log or of its snapshots (decision D177): one of `wow.stores`. */
    class AggregateStore {
        var store: String? = null
    }

    /** The partition append queue; each setting not given is the backend's preset ([AppendQueueOptions]). */
    class AppendQueue {
        var enabled: Boolean? = null
        var maxBatch: Int? = null

        fun options(preset: AppendQueueOptions): AppendQueueOptions =
            AppendQueueOptions(enabled = enabled ?: preset.enabled, maxBatch = maxBatch ?: preset.maxBatch)
    }

    class Cluster {
        /** Where commands run; `local` on the memory backend and `partitioned` on the others when not set. */
        var router: Routing? = null
        var leaseTtl: Duration = DEFAULT_CLUSTER.leaseTtl.toJavaDuration()
        var renewInterval: Duration = DEFAULT_CLUSTER.renewInterval.toJavaDuration()
        var memberTtl: Duration = DEFAULT_CLUSTER.memberTtl.toJavaDuration()
        var heartbeatInterval: Duration = DEFAULT_CLUSTER.heartbeatInterval.toJavaDuration()
        var reconcileInterval: Duration = DEFAULT_CLUSTER.reconcileInterval.toJavaDuration()
        var routeRefreshInterval: Duration = DEFAULT_CLUSTER.routeRefreshInterval.toJavaDuration()

        /**
         * How long a forward waits for the owner's reply, capped by the command's own deadline: `wow.wait.default-timeout`
         * when not set (review A5-10), so a node whose waits are longer forwards as long. A forward the owner received
         * and did not answer by then is reported unknown (`wow.command.unknown`), not run here, so a retry with the
         * same request id is safe (decision D223).
         */
        var forwardTimeout: Duration? = null
        var maxInFlightPerPeer: Int = DEFAULT_CLUSTER.maxInFlightPerPeer
        var drainTimeout: Duration = DEFAULT_CLUSTER.drainTimeout.toJavaDuration()
        var leaseParallelism: Int = DEFAULT_CLUSTER.leaseParallelism

        /**
         * How long one reconcile may run before the node takes itself as stuck and leaves the members until it ends
         * (finding C.1 F1).
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
         * Whether a wait plan may name a context the node knows no Manifest of (decision D190): `allow`, checked where
         * the chain arrives, or `reject`.
         */
        var unknownContexts: UnknownContexts = UnknownContexts.ALLOW
    }

    class Executor {
        /** The commands one aggregate may have waiting in the executor. */
        var laneCapacity: Int = ServiceNodeOptions.DEFAULT_LANE_CAPACITY

        /**
         * The commands the node may have in flight, all contexts together (decision D228 ②): beyond it a command is
         * refused as `wow.command.busy` (429, with `Retry-After`) instead of queued.
         */
        var maxInFlight: Int = InFlightLimit.DEFAULT_MAX
    }

    /**
     * The bounds of each context's state views, the states its consumers share after each commit (decision D133): at
     * most [capacity] views and [maxBytes] of serialized state (two bytes a character), none older than [maxAge].
     */
    class StateViews {
        var capacity: Int = StateViewOptions.DEFAULT_CAPACITY
        var maxAge: Duration = StateViewOptions.DEFAULT_MAX_AGE.toJavaDuration()
        var maxBytes: Long = StateViewOptions.DEFAULT_MAX_BYTES

        fun options(): StateViewOptions = StateViewOptions(capacity, maxAge.toKotlinDuration(), maxBytes)
    }

    class StateCache {
        /** The aggregate states kept per context between commands; 0 keeps none. */
        var capacity: Int = ServiceNodeOptions.DEFAULT_STATE_CACHE_CAPACITY
    }

    class Subscription {
        var maxInFlight: Int = DEFAULT_SUBSCRIPTION.maxInFlight
        var checkpointInterval: Duration = DEFAULT_SUBSCRIPTION.checkpointInterval.toJavaDuration()
        var stopTimeout: Duration = DEFAULT_SUBSCRIPTION.stopTimeout.toJavaDuration()

        /** How a failing handler is retried before its entry is parked. */
        var retry: Retry = Retry.of(DEFAULT_SUBSCRIPTION.retry)

        /**
         * How a failing read of the log, or write of a store, is retried: until the node stops, the backoff growing
         * for `max-attempts` (5) attempts, after which each failure is logged as an error and retried at
         * `max-backoff` (decision D195).
         */
        var readRetry: Retry = Retry.of(DEFAULT_SUBSCRIPTION.readRetry)

        /** How the parked entries are retried over the long horizon (roadmap P.1). */
        var parked: Parked = Parked()

        /** When a partition pauses on a systemic failure (decision D243). */
        var systemic: Systemic = Systemic()

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
            )
        }
    }

    /**
     * When a subscription pauses its partition on a systemic failure (decision D243): at once on a failure classified
     * systemic, and when [failingAggregates] different aggregates failed within [window] with no handling succeeding
     * since (0 turns that off). While paused, one attempt at a time probes, the first [initialBackoff] after the pause,
     * doubling to at most [maxBackoff].
     */
    class Systemic {
        var failingAggregates: Int = DEFAULT_SYSTEMIC.failingAggregates
        var window: Duration = DEFAULT_SYSTEMIC.window.toJavaDuration()
        var initialBackoff: Duration = DEFAULT_SYSTEMIC.initialBackoff.toJavaDuration()
        var maxBackoff: Duration = DEFAULT_SYSTEMIC.maxBackoff.toJavaDuration()

        fun options(): SystemicFailureOptions = SystemicFailureOptions(
            failingAggregates = failingAggregates,
            window = window.toKotlinDuration(),
            initialBackoff = initialBackoff.toKotlinDuration(),
            maxBackoff = maxBackoff.toKotlinDuration(),
        )
    }

    /**
     * The long-horizon retries of parked entries (roadmap P.1, 9.x `@Retry` and `CompensationScheduler`): at most
     * [maxRetries] automatic retries (0 turns them off), the first [minBackoff] after the failure and each next one
     * twice as long after the previous one started; [executionTimeout] is how long a retry may run before the
     * partition's next owner may start another. Each node scans its subscriptions' entries every [scanInterval].
     */
    class Parked {
        var maxRetries: Int = DEFAULT_PARKED.spec.maxRetries
        var minBackoff: Duration = DEFAULT_PARKED.spec.minBackoff.toJavaDuration()
        var executionTimeout: Duration = DEFAULT_PARKED.spec.executionTimeout.toJavaDuration()
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
     * One subscription's settings. [start] is where it starts in a partition it has no checkpoint in yet (decision
     * D154): `beginning`, `end`, or an ISO-8601 instant (`2026-10-01T00:00:00Z`), the first entry committed at or
     * after it; unset, its kind's default (a projection from the beginning, a reactor or a saga from the end). A
     * subscription that has a checkpoint goes on from it. [parked] is how its parked entries are retried, over
     * `wow.subscription.parked` (roadmap P.1). [retry] is its in-process retry budget before it parks (roadmap P.3),
     * over its handler's `@Retry` and `wow.subscription.retry`. A name the node has no subscription of is refused at
     * startup.
     */
    class SubscriptionSettings {
        var start: String? = null
        var parked: ParkedSpec = ParkedSpec()
        var retry: RetrySpec = RetrySpec()
    }

    /**
     * One subscription's in-process retry budget (roadmap P.3): each setting given replaces its handler's `@Retry`
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
     * The operator's side of the parked entries (roadmap P.1): [enabled] serves their routes under `/wow/ops/parked`
     * on the public port, off by default; [webhook] tells a URL when an entry parks or spends its retries.
     */
    class Ops {
        var enabled: Boolean = false
        var webhook: Webhook = Webhook()
        var publish: Publish = Publish()
    }

    /**
     * Publishing the failure ledger to the ops context (decision D141): when [enabled], each entry goes to the `parked`
     * aggregate of [context] (`ops`) through its command topic, at once when it parks and on a scan every [interval]
     * (30s); the node needs the Kafka transport.
     */
    class Publish {
        var enabled: Boolean = false
        var context: String = Ledger.OPS_CONTEXT
        var interval: Duration = LedgerPublishing.DEFAULT_INTERVAL.toJavaDuration()

        /** Where the node publishes its ledger, null when it does not. */
        fun options(): LedgerPublishing? =
            if (enabled) LedgerPublishing(context, interval.toKotlinDuration()) else null
    }

    /**
     * The webhook of the parked entries, posted to [url] when set: in Wow's JSON (`generic`) or as a WeCom robot's
     * Markdown message (`wecom`) ([format]), for the [events] listed (`parked`, `exhausted`; both by default), within
     * [timeout], with [headers] added to each request.
     */
    class Webhook {
        var url: URI? = null
        var format: WebhookNotifier.Format = WebhookNotifier.Format.GENERIC
        var events: MutableSet<ParkedNotice.Kind> = ParkedNotice.Kind.entries.toCollection(LinkedHashSet())
        var timeout: Duration = WebhookNotifier.DEFAULT_TIMEOUT.toJavaDuration()
        var headers: MutableMap<String, String> = LinkedHashMap()
    }

    /**
     * The node's firing loop of the timers (decisions D166, D198): how often it reads, with one query per timer store
     * over every partition the node owns, the timers due within [lookahead] ([pollInterval]); how many attempts a
     * technical failure to send one gets before it is parked under `wow.timers` ([maxAttempts]); when set, how late a
     * timer may be sent before it expires instead ([expiresAfter]); how many commands a partition sends at once
     * ([parallelism]) and, when set, at most a second ([maxFireRate]), the rate a backlog of overdue timers drains at;
     * how long the claim on a timer being sent holds before another owner may send it ([claimTimeout]); how long a
     * scheduled command waits in its target for its due time on the target node's clock before it fails instead, to
     * be sent again later ([maxEarlyWait]).
     */
    class Timers {
        var pollInterval: Duration = DEFAULT_TIMERS.pollInterval.toJavaDuration()
        var lookahead: Duration = DEFAULT_TIMERS.lookahead.toJavaDuration()
        var maxAttempts: Int = DEFAULT_TIMERS.retry.maxAttempts
        var expiresAfter: Duration? = null
        var parallelism: Int = DEFAULT_TIMERS.parallelism
        var maxFireRate: Double? = null
        var claimTimeout: Duration = DEFAULT_TIMERS.claimTimeout.toJavaDuration()
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
     * The host's override of an `@OnSchedule` job's default rule (decision D198): its cron [expression], its time
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
     * A retry policy. [timeout], when set, bounds one attempt of a handler (roadmap P.3): one that runs longer is
     * cancelled and counts as failed; unset, an attempt runs as long as it takes. A read retry does not use it.
     */
    class Retry {
        var maxAttempts: Int = 1
        var initialBackoff: Duration = Duration.ZERO
        var maxBackoff: Duration = Duration.ZERO
        var multiplier: Double = 1.0
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

    class Tenant {
        /** The tenant of a caller that has none, such as the anonymous one (decision D97). */
        var default: String = Caller.DEFAULT_TENANT
    }

    class Http {
        /**
         * The request headers copied into a command's headers, beside the standard request facts the edge records:
         * `app_id`, the application the caller resolver authenticated (decision D232), and on the public port
         * `user_agent` and `remote_ip` (09 §17 G-10); none of the three can be listed. `remote_ip` is the server's
         * remote address: behind a proxy, set `server.forward-headers-strategy` to have the server take it from the
         * proxy's forwarded headers.
         */
        var requestFacts: MutableSet<String> = LinkedHashSet()

        /** The HTTP status of an error code, by code (bracketed in a property name: `wow.http.errors.[a.b].status`). */
        var errors: MutableMap<String, Error> = LinkedHashMap()
        var manifest: Manifest = Manifest()
        var openapi: OpenApi = OpenApi()

        /**
         * The service identities the internal port trusts by their static tokens, by service name (decision D193): a
         * call whose `Authorization: Bearer` token is one of them is that service's, which alone may send `Wow-Call`,
         * and only to the internal port. For a deployment without CoSec; with CoSec, `wow.cosec.service.roles` names its
         * service principals.
         */
        var services: MutableMap<String, Service> = LinkedHashMap()

        /**
         * One trusted service: the [tokens] it may present, at least 32 characters each, several while one is rotated
         * (add the new one here, switch the caller's `wow.calls.token`, then remove the old one), and the [commands]
         * it may call (decision D226, review A3-2), names or patterns (`AddItem`, `shop.cart.*`, `wow.lifecycle.*`):
         * without them, the domain commands alone; a system command (`wow.*`: the lifecycle, the snapshot rebuild, the
         * schedules) only when an entry naming `wow.` lists it; an entry naming a domain command narrows the domain
         * commands to those listed. A CoSec service principal is configured here by its id, with [commands] alone.
         */
        class Service {
            var tokens: MutableList<String> = ArrayList()
            var commands: MutableList<String> = ArrayList()
        }

        class Error {
            var status: Int = 0
        }

        class Manifest {
            /** Serves `GET /wow/manifest`. */
            var enabled: Boolean = true
        }

        class OpenApi {
            /** Serves `GET /wow/openapi`, the OpenAPI 3.1 document of the routes (roadmap 5a.2). */
            var enabled: Boolean = true

            /** The springdoc bridge (decision D233), when the application has springdoc. */
            var springdoc: Springdoc = Springdoc()
        }

        /**
         * The springdoc bridge (decision D233, SpringdocAutoConfiguration): with springdoc on the classpath, the
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
     * The synchronous calls of the node's sagas to the command gateways of other contexts (decision D193): each
     * context's base URL ([contexts], `wow.calls.contexts.<context>.url`: the target's internal port, `http://host:port`
     * of an address in its internal network, never its public port, which refuses calls), the service token the node
     * presents as `Authorization: Bearer` ([token]; an application's
     * [me.ahoo.wow.http.call.ServiceCredential] bean, such as one that renews a CoSec token, replaces it), how long one
     * call may take ([timeout]) and connect ([connectTimeout]). A saga that calls a context without a URL fails, then
     * parks.
     */
    class Calls {
        var contexts: MutableMap<String, CallTarget> = LinkedHashMap()
        var token: String? = null
        var timeout: Duration = HttpCommandCaller.DEFAULT_TIMEOUT.toJavaDuration()
        var connectTimeout: Duration = HttpCommandCaller.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

        /** Where one context's command gateway is: its public port's base [url]. */
        class CallTarget {
            var url: URI? = null
        }
    }

    /**
     * An aggregate type's settings. [tenant] fixes the tenant of every aggregate of the type (decision D116, replacing
     * 9.x `@StaticTenantId`): its commands and state reads are in that tenant whoever the caller is
     * ([me.ahoo.wow.runtime.admission.TenantPolicy]). [idGenerator] names the
     * [me.ahoo.wow.spi.id.AggregateIdGenerator] bean that gives the type's new ids (decision D171), or a generator an
     * [me.ahoo.wow.spi.id.AggregateIdGeneratorSource] bean finds by that name (CosId's, with wow-cosid); the type's ids
     * come from the `wowAggregateIdGenerator` bean otherwise. A type the node does not serve, or a name nothing gives
     * a generator of, is refused at startup.
     */
    class Aggregate {
        var tenant: String? = null
        var idGenerator: String? = null

        /**
         * How the commands of the type are routed (design 02 §2): by the node's router, or through its context's
         * command topic, accepted once the broker acknowledged them (decision D143), which needs Kafka.
         */
        var routing: AggregateRouting = AggregateRouting.CLUSTER

        /** The store of the type's log (decision D177), over `wow.log.store`: the type's log group. */
        var log: AggregateStore = AggregateStore()

        /**
         * The stores of the type's snapshots (decisions D172, D177), over `wow.snapshot.targets`; its queries read the
         * primary one.
         */
        var snapshot: AggregateSnapshot = AggregateSnapshot()

        /**
         * How the ClickHouse Sink keeps the type's commits (decision D164), in the one style of per-aggregate keys
         * (review A5-10): over `wow.clickhouse.sink.aggregates.<context>.<aggregate>`, which is still read.
         */
        var clickhouse: AggregateClickHouse = AggregateClickHouse()
    }

    /** An aggregate type's ClickHouse settings: `wow.aggregates.[<context>.<aggregate>].clickhouse.*`. */
    class AggregateClickHouse {
        var history: AggregateHistory = AggregateHistory()
    }

    /** Whether every commit of the type is kept ([enabled], on by default), and how long ([ttl], forever by default). */
    class AggregateHistory {
        var enabled: Boolean? = null
        var ttl: Duration? = null
    }

    /**
     * CoSec's settings (wow-cosec, decisions D189, D193, D226), which that module reads from the environment: bound
     * here, they have metadata, and a key of `wow.cosec` that none of these is refuses the startup
     * ([ConfigurationKeys]) instead of being ignored, so a mistyped [Authorizer.enabled] cannot leave the authorizer
     * that allows all in place.
     */
    class CoSec {
        var authorizer: Authorizer = Authorizer()

        /** The callers that may act across scopes: by CoSec role or policy. */
        var crossScope: Principals = Principals()

        /** The tenant-level callers (decision D189), who read and write every aggregate of their tenant. */
        var tenantLevel: Principals = Principals()

        /** The service identities (decision D193), whose synchronous calls the internal port takes. */
        var service: Principals = Principals()

        /**
         * Whether a service in CoSec's inject mode, behind a CoSec gateway (`cosec.inject.enabled=true` with
         * `cosec.authorization.enabled=false`), records the application of the requests the gateway forwards, their
         * `CoSec-App-Id`, as the caller's app and the request fact `app_id` (decision D236): a trust decision, off by
         * default; in any other mode it fails the startup.
         */
        var trustInjectedApp: Boolean = false

        class Authorizer {
            /**
             * Whether CoSec authorizes every command as its caller (`CoSecCommandAuthorizer`), not only the public
             * routes by path; the services' calls are authorized by CoSec whatever this says (decision D226).
             */
            var enabled: Boolean = false
        }

        /** Callers by their CoSec roles or policies, comma-separated or as a list. */
        class Principals {
            var roles: MutableSet<String> = LinkedHashSet()
            var policies: MutableSet<String> = LinkedHashSet()
        }
    }

    /** CosId's settings (wow-cosid, decision D171), which that module reads from the environment. */
    class CosId {
        /** The CosId generator of the node's global ids (command ids, wait ids); CosId's own `cosid` when not set. */
        var globalGenerator: String? = null
    }

    /** How the commands of an aggregate type are routed (`wow.aggregates.[<context>.<aggregate>].routing`). */
    enum class AggregateRouting {
        /** By the node's router, `wow.cluster.router`: on the owner of the aggregate's partition, or here. */
        CLUSTER,

        /**
         * The `Kafka` router (decision D143): written to the context's command topic `wow.<context>.commands`, and
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
        private val DEFAULT_SYSTEMIC = SystemicFailureOptions()
    }
}
