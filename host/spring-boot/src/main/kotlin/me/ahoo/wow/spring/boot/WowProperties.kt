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
import me.ahoo.wow.runtime.store.AggregatePlacement
import me.ahoo.wow.runtime.store.StorePlacements
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spring.boot.config.ConfigurationKeys
import me.ahoo.wow.spring.boot.http.CallerPermitsProperties
import me.ahoo.wow.spring.boot.http.HttpProperties
import me.ahoo.wow.spring.boot.http.PeerProperties
import me.ahoo.wow.spring.boot.http.PeerTargetProperties
import me.ahoo.wow.spring.boot.observability.HealthProperties
import me.ahoo.wow.spring.boot.observability.ObservabilityProperties
import me.ahoo.wow.spring.boot.security.CoSecProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * The host's configuration, the `wow.*` namespace: the node, its internal port, its stores and logs, the cluster, the
 * commands' waits and execution, the subscriptions, the timers and schedules, the HTTP edge and the calls to other
 * contexts. The defaults are the runtime's own. The query plane, Kafka, ClickHouse and
 * OpenTelemetry have their own classes ([QueryProperties], [KafkaProperties], [ClickHouseProperties],
 * [ObservabilityProperties]).
 *
 * Every key is in the host's configuration metadata (`META-INF/spring-configuration-metadata.json`), and a `wow.*` key
 * that no property of the host or of a module names is reported at startup ([ConfigurationKeys]).
 */
@InternalWowApi
@ConfigurationProperties(prefix = WowProperties.PREFIX)
class WowProperties {
    /** The service's name; `spring.application.name` when not set. */
    var service: String? = null

    /** This node's identity among the nodes of the service, and where they reach it. */
    var node: NodeProperties = NodeProperties()

    /** The internal port: the cluster channel, the wait signals and the calls of other services. */
    var internal: InternalProperties = InternalProperties()

    /** The bounded contexts the node serves. */
    var contexts: ContextsProperties = ContextsProperties()

    /**
     * The profiles in which the node runs for development, as it does when its internal port binds a loopback address
     * alone: `wow.backend=auto` falls back to memory when no durable backend is available, the public port serves
     * without a `CallerResolver` bean, a single Kafka broker gives no durability warning, and a platform service may
     * take its callers from request headers. None of them is allowed in a deployment.
     */
    var devProfiles: MutableSet<String> = linkedSetOf("dev", "local", "test", "demo")

    /**
     * The backend of a node that names no `wow.stores`: `auto` (the default) takes PostgreSQL or MongoDB by the
     * application's `DataSource` or `MongoClient`, and refuses to start without either, so that data is not lost by
     * mistake, unless a profile of `wow.dev-profiles` is active or the internal port binds the loopback
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
    var ddl: Ddl = Ddl.AUTO

    /**
     * Where commands run ([CommandRouting]): `auto`, on the owner of the aggregate's partition, or here on the memory
     * backend, which cannot route across nodes; `partitioned`; `local`; or `topic`, through each context's command
     * topic. An aggregate type's `wow.aggregates.[<context>.<aggregate>].routing` comes before it.
     */
    var routing: CommandRouting = CommandRouting.AUTO

    /** The aggregates' logs: their partitions, their store, and how their appends and tails run. */
    var log: LogProperties = LogProperties()
    var cluster: ClusterProperties = ClusterProperties()
    var wait: WaitProperties = WaitProperties()
    var executor: ExecutorProperties = ExecutorProperties()
    var stateCache: StateCacheProperties = StateCacheProperties()

    /** The bounds of each context's shared states: `wow.shared-states.*`. */
    var sharedStates: SharedStatesProperties = SharedStatesProperties()
    var subscription: SubscriptionProperties = SubscriptionProperties()

    /**
     * Per subscription, by its context and name, `<context>.<name>`, bracketed in a property name
     * (`wow.subscriptions.[shop.CartSaga.onOrderCreated]`, `wow.subscriptions.[shop.wow.snapshot]`): a name is unique
     * within its context alone.
     */
    var subscriptions: MutableMap<String, SubscriptionSettingsProperties> = LinkedHashMap()
    var timers: TimersProperties = TimersProperties()

    /**
     * Per `@OnSchedule` job, by its name: `wow.schedules.<job>.*` holds all of the job's settings, its rule and the
     * retries of the subscriptions that run it. A job no handler of the node runs fails its start, and so does a job's
     * subscription configured under `wow.subscriptions`.
     */
    var schedules: MutableMap<String, ScheduleProperties> = LinkedHashMap()
    var tenant: TenantProperties = TenantProperties()
    var http: HttpProperties = HttpProperties()

    /** How the node calls its peers, the services of the other contexts it calls over HTTP: `wow.peer.*`. */
    var peer: PeerProperties = PeerProperties()

    /**
     * The peer contexts called over HTTP, by context (`wow.peers.<context>.*`): a saga's synchronous calls to that
     * context and the typed clients of it go to its service, at its `url` when one is set, else where `wow.peer.discovery`
     * finds the service its contract names (DNS by default). A node whose saga calls a context it cannot reach does not
     * start.
     */
    var peers: MutableMap<String, PeerTargetProperties> = LinkedHashMap()

    /**
     * What each service that calls this one may call and read on the internal port, by the name it states
     * (`Wow-Service`, its `spring.application.name`): optional lists that catch a misconfiguration. The internal port
     * is reachable by the platform's own pods alone; a service that names itself is taken on that network's word.
     */
    var callers: MutableMap<String, CallerPermitsProperties> = LinkedHashMap()
    var ops: OpsProperties = OpsProperties()

    /** Per aggregate type, by `<context>.<aggregate>` (bracketed in a property name: `wow.aggregates.[shop.cart]`). */
    var aggregates: MutableMap<String, AggregateProperties> = LinkedHashMap()

    /**
     * The keys wow-cosec reads (`wow.cosec.*`), bound here so they are checked and documented as the others are.
     * Experimental in 10.0 with wow-cosec, which follows CoSec 8's pre-release: they may change within 10.x.
     */
    var cosec: CoSecProperties = CoSecProperties()

    /** The timeout of each peer that sets its own, `wow.peers.<context>.timeout`. */
    internal fun peerTimeouts(): Map<String, kotlin.time.Duration> =
        peers.mapNotNull { (context, target) -> target.timeout?.let { context to it.toKotlinDuration() } }.toMap()

    /** Where `wow.log`, `wow.snapshot` and `wow.aggregates` place the logs and snapshots. */
    internal fun storePlacements(): StorePlacements = StorePlacements(
        log = log.store,
        snapshot = snapshot.stores.firstOrNull { it.isNotBlank() },
        snapshotTargets = snapshot.stores,
        snapshotPrimary = snapshot.primary,
        aggregates = aggregates.mapValues { (_, aggregate) ->
            AggregatePlacement(aggregate.log.store, aggregate.snapshot.stores, aggregate.snapshot.primary)
        },
    )

    /**
     * The named store instances, by name: `wow.stores.<name>.type` and its settings. The aggregates whose logs are on
     * one store form a log group, with its own partition sequences, checkpoints, parked entries and timers.
     */
    var stores: MutableMap<String, StoreProperties> = LinkedHashMap()

    /** Where the snapshots are by default, and how the snapshot maintenance runs: `wow.snapshot.*`. */
    var snapshot: SnapshotProperties = SnapshotProperties()

    /**
     * How each context's built-in subscription `wow.access` stamps an aggregate's access attributes over its history
     * when a commit changes them: `wow.access.*`.
     */
    var access: AccessProperties = AccessProperties()

    /** The node's health and its probes, when the application has Spring Boot Actuator: `wow.health.*`. */
    var health: HealthProperties = HealthProperties()

    /**
     * The node's topic transport: `auto` (the default) takes the transport a provider finds configured (Kafka with
     * `wow.kafka.bootstrap-servers`); without one, the topics are in the node's JVM when it runs for development (a
     * profile of `wow.dev-profiles`, or a loopback internal port), and otherwise it has none: the node then publishes no
     * stream, and refuses to start when it needs topics (topic routing, command intake, ledger publishing set to true).
     * `memory` keeps the topics in the JVM on purpose, for development alone; `none` gives the node no topics in any
     * profile, whatever is configured, with the same refusal; `kafka`, or a community provider's type, names the
     * transport. A blank `wow.kafka.bootstrap-servers` is no way to turn the topics off: in development it falls back
     * to the topics in the JVM.
     */
    var transport: String = TransportSelection.AUTO

    /** The in-memory provider's settings: the topics it keeps in the JVM. */
    var memory: MemoryProperties = MemoryProperties()

    /** Where commands run (`wow.routing`, and an aggregate type's `wow.aggregates.[<context>.<aggregate>].routing`). */
    @InternalWowApi
    enum class CommandRouting {
        /** `partitioned`, or `local` on the memory backend; for an aggregate type, `wow.routing`'s. */
        AUTO,

        /** On the owner of the aggregate's log partition, forwarded over the cluster channel. */
        PARTITIONED,

        /** On the node the command arrived at, with optimistic concurrency: for a single node. */
        LOCAL,

        /**
         * Written to the context's command topic `wow.<context>.commands` on the topic transport, and executed from
         * there by the owner of the aggregate's log partition. ACCEPTED means durable: a plan that waits only for it is
         * answered 202 once the broker acknowledged the command.
         */
        TOPIC,
    }

    @InternalWowApi
    companion object {
        const val PREFIX: String = "wow"

        /**
         * The `wow.*` settings [environment] gives, bound before the beans are, for a condition or an environment
         * post-processor: the properties' own defaults are the only ones, as the bean's.
         */
        fun of(environment: Environment): WowProperties =
            Binder.get(environment).bindOrCreate(PREFIX, WowProperties::class.java)

        /**
         * [of], for an environment post-processor, which runs before Spring Boot configures the application's logging:
         * null when a `wow.*` setting does not bind. The post-processor then adds nothing, and the properties' bean
         * refuses the setting when it is bound, with Spring Boot's failure analysis on the application's own log.
         */
        fun ofOrNull(environment: Environment): WowProperties? = try {
            of(environment)
        } catch (refused: BindException) {
            null
        }

        const val DEFAULT_INTERNAL_PORT: Int = 9090
        const val DEFAULT_PARTITIONS: Int = 64
    }

    /** A switch the node may also decide for itself: every tri-state key's values, `auto`, `true` and `false`. */
    @InternalWowApi
    enum class Switch {
        /** The node decides from what it finds, or the feature's default: see the key. */
        AUTO,

        /** On, whatever the node finds. */
        TRUE,

        /** Off. */
        FALSE,
    }
}
