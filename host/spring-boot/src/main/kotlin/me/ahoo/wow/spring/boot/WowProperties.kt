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

import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.http.internal.HttpSignalSender
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.wait.WaitOptions
import org.springframework.boot.context.properties.ConfigurationProperties
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
 *   `wow.internal.host` (every interface): the internal port (D89).
 * - `wow.backend`: `auto` (the default), `memory`, `postgres` or `mongo` ([BackendKind]); `wow.mongo.database`.
 * - `wow.log.partitions` (64) and `wow.log.append-queue.*`, whose defaults are the backend's preset.
 * - `wow.cluster.*` ([ClusterOptions]) and `wow.cluster.router`: `partitioned`, or `local` (the default on the memory
 *   backend, which cannot route across nodes).
 * - `wow.wait.default-timeout` (10s), `max-timeout` (60s), `max-hops` (4): the default timeout is the node's
 *   ([ServiceNodeOptions.defaultWaitTimeout]), for every command whose request names none.
 * - `wow.executor.lane-capacity`, `wow.state-cache.capacity`, `wow.subscription.*`.
 * - `wow.subscriptions.[<subscription>].start`: `beginning`, `end` or an ISO-8601 instant, where the subscription
 *   (`processor.function`, bracketed for its dot) starts in a partition it has no checkpoint in yet, instead of its
 *   kind's default (decision D154), see [SubscriptionSettings].
 * - `wow.timers.poll-interval` (1s), `lookahead` (30s), `max-attempts` (10), `expires-after` (none): the timer runner
 *   of the scheduled commands (decision D166), see [Timers].
 * - `wow.tenant.default`: the tenant of a caller that has none (D97).
 * - `wow.http.request-facts`, `wow.http.errors.[<code>].status`, `wow.http.manifest.enabled`.
 * - `wow.aggregates.[<context>.<aggregate>].tenant`: a fixed tenant per aggregate type (decision D116), see
 *   [Aggregate].
 */
@ConfigurationProperties(prefix = WowProperties.PREFIX)
class WowProperties {
    /** The service's name; `spring.application.name` when not set. */
    var service: String? = null
    var node: Node = Node()
    var internal: Internal = Internal()
    var backend: BackendKind = BackendKind.AUTO
    var mongo: Mongo = Mongo()
    var log: Log = Log()
    var cluster: Cluster = Cluster()
    var wait: Wait = Wait()
    var executor: Executor = Executor()
    var stateCache: StateCache = StateCache()
    var subscription: Subscription = Subscription()

    /** Per subscription, by its name (bracketed in a property name: `wow.subscriptions.[CartSaga.onOrderCreated]`). */
    var subscriptions: MutableMap<String, SubscriptionSettings> = LinkedHashMap()
    var timers: Timers = Timers()
    var tenant: Tenant = Tenant()
    var http: Http = Http()

    /** Per aggregate type, by `<context>.<aggregate>` (bracketed in a property name: `wow.aggregates.[shop.cart]`). */
    var aggregates: MutableMap<String, Aggregate> = LinkedHashMap()

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

        /** The address the internal server binds; every interface when not set. */
        var host: String? = null

        /** How long a peer has to accept a connection of the cluster channel or the signal sender. */
        var connectTimeout: Duration = InternalHttp.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

        /** The signals queued per origin node before they are dropped (decision D109). */
        var signalQueueCapacity: Int = HttpSignalSender.DEFAULT_QUEUE_CAPACITY
    }

    class Mongo {
        /** The database of the log and the stores on the MongoDB backend. */
        var database: String = DEFAULT_MONGO_DATABASE
    }

    class Log {
        /** The partitions of each context's log: fixed once the log holds entries. */
        var partitions: Int = DEFAULT_PARTITIONS
        var appendQueue: AppendQueue = AppendQueue()
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
        var forwardTimeout: Duration = DEFAULT_CLUSTER.forwardTimeout.toJavaDuration()
        var maxInFlightPerPeer: Int = DEFAULT_CLUSTER.maxInFlightPerPeer
        var drainTimeout: Duration = DEFAULT_CLUSTER.drainTimeout.toJavaDuration()
        var leaseParallelism: Int = DEFAULT_CLUSTER.leaseParallelism

        fun options(): ClusterOptions = ClusterOptions(
            leaseTtl = leaseTtl.toKotlinDuration(),
            renewInterval = renewInterval.toKotlinDuration(),
            memberTtl = memberTtl.toKotlinDuration(),
            heartbeatInterval = heartbeatInterval.toKotlinDuration(),
            reconcileInterval = reconcileInterval.toKotlinDuration(),
            routeRefreshInterval = routeRefreshInterval.toKotlinDuration(),
            forwardTimeout = forwardTimeout.toKotlinDuration(),
            maxInFlightPerPeer = maxInFlightPerPeer,
            drainTimeout = drainTimeout.toKotlinDuration(),
            leaseParallelism = leaseParallelism,
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

        /** The most hops of a wait plan. */
        var maxHops: Int = WaitOptions.DEFAULT_MAX_HOPS
    }

    class Executor {
        /** The commands one aggregate may have waiting in the executor. */
        var laneCapacity: Int = ServiceNodeOptions.DEFAULT_LANE_CAPACITY
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

        /** How a failing read of the log is retried. */
        var readRetry: Retry = Retry.of(DEFAULT_SUBSCRIPTION.readRetry)

        /** These options, with the starts [subscriptions] configure. */
        fun options(subscriptions: Map<String, SubscriptionSettings> = emptyMap()): SubscriptionOptions =
            SubscriptionOptions(
                maxInFlight = maxInFlight,
                retry = retry.policy(),
                checkpointInterval = checkpointInterval.toKotlinDuration(),
                stopTimeout = stopTimeout.toKotlinDuration(),
                readRetry = readRetry.policy(),
                starts = subscriptions.mapNotNull { (name, settings) ->
                    settings.start?.takeIf { it.isNotBlank() }?.let { name to SubscriptionStart.parse(it) }
                }.toMap(),
            )
    }

    /**
     * One subscription's settings. [start] is where it starts in a partition it has no checkpoint in yet (decision
     * D154): `beginning`, `end`, or an ISO-8601 instant (`2026-10-01T00:00:00Z`), the first entry committed at or
     * after it; unset, its kind's default (a projection from the beginning, a reactor or a saga from the end). A
     * subscription that has a checkpoint goes on from it. A name the node has no subscription of is refused at
     * startup.
     */
    class SubscriptionSettings {
        var start: String? = null
    }

    /**
     * The timer runner of each context (decision D166): how often each owned partition reads the timers due within
     * [lookahead] ([pollInterval]); how many attempts a technical failure to send one gets before it is parked under
     * `wow.timers` ([maxAttempts]); and, when set, how late a timer may be sent before it expires instead
     * ([expiresAfter]).
     */
    class Timers {
        var pollInterval: Duration = DEFAULT_TIMERS.pollInterval.toJavaDuration()
        var lookahead: Duration = DEFAULT_TIMERS.lookahead.toJavaDuration()
        var maxAttempts: Int = DEFAULT_TIMERS.retry.maxAttempts
        var expiresAfter: Duration? = null

        fun options(): TimerOptions = DEFAULT_TIMERS.copy(
            pollInterval = pollInterval.toKotlinDuration(),
            lookahead = lookahead.toKotlinDuration(),
            retry = DEFAULT_TIMERS.retry.copy(maxAttempts = maxAttempts),
            expiresAfter = expiresAfter?.toKotlinDuration(),
        )
    }

    class Retry {
        var maxAttempts: Int = 1
        var initialBackoff: Duration = Duration.ZERO
        var maxBackoff: Duration = Duration.ZERO
        var multiplier: Double = 1.0

        fun policy(): RetryPolicy = RetryPolicy(
            maxAttempts = maxAttempts,
            initialBackoff = initialBackoff.toKotlinDuration(),
            maxBackoff = maxBackoff.toKotlinDuration(),
            multiplier = multiplier,
        )

        companion object {
            fun of(policy: RetryPolicy): Retry = Retry().apply {
                maxAttempts = policy.maxAttempts
                initialBackoff = policy.initialBackoff.toJavaDuration()
                maxBackoff = policy.maxBackoff.toJavaDuration()
                multiplier = policy.multiplier
            }
        }
    }

    class Tenant {
        /** The tenant of a caller that has none, such as the anonymous one (decision D97). */
        var default: String = Caller.DEFAULT_TENANT
    }

    class Http {
        /** The request headers copied into a command's headers. */
        var requestFacts: MutableSet<String> = LinkedHashSet()

        /** The HTTP status of an error code, by code (bracketed in a property name: `wow.http.errors.[a.b].status`). */
        var errors: MutableMap<String, Error> = LinkedHashMap()
        var manifest: Manifest = Manifest()

        class Error {
            var status: Int = 0
        }

        class Manifest {
            /** Serves `GET /wow/manifest`. */
            var enabled: Boolean = true
        }
    }

    /**
     * An aggregate type's settings. [tenant] fixes the tenant of every aggregate of the type (decision D116, replacing
     * 9.x `@StaticTenantId`): its commands and state reads are in that tenant whoever the caller is
     * ([me.ahoo.wow.runtime.admission.TenantPolicy]). A type the node does not serve is refused at startup.
     */
    class Aggregate {
        var tenant: String? = null
    }

    companion object {
        const val PREFIX: String = "wow"
        const val DEFAULT_INTERNAL_PORT: Int = 9090
        const val DEFAULT_PARTITIONS: Int = 64
        const val DEFAULT_MONGO_DATABASE: String = "wow"
        private val DEFAULT_CLUSTER = ClusterOptions()
        private val DEFAULT_SUBSCRIPTION = SubscriptionOptions()
        private val DEFAULT_TIMERS = TimerOptions()
    }
}

/** The backend of a node's log and stores (`wow.backend`, decision D110). */
enum class BackendKind {
    /** PostgreSQL or MongoDB by what the classpath and the application's beans offer; memory when neither. */
    AUTO,

    /** In memory: for one node, whose data does not outlive it. */
    MEMORY,

    /** PostgreSQL, through the application's `DataSource`. */
    POSTGRES,

    /** MongoDB, through the application's `MongoClient`. */
    MONGO,
}
