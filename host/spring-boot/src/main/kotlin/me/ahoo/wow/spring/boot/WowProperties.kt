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
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.client.PeerMode
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.store.AggregatePlacement
import me.ahoo.wow.runtime.store.StorePlacements
import me.ahoo.wow.runtime.subscription.BackpressureOptions
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.SystemicFailureOptions
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.net.URI
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import me.ahoo.wow.client.RetryPolicy as ClientRetryPolicy

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
    var subscription: Subscription = Subscription()

    /**
     * Per subscription, by its context and name, `<context>.<name>`, bracketed in a property name
     * (`wow.subscriptions.[shop.CartSaga.onOrderCreated]`, `wow.subscriptions.[shop.wow.snapshot]`): a name is unique
     * within its context alone.
     */
    var subscriptions: MutableMap<String, SubscriptionSettings> = LinkedHashMap()
    var timers: Timers = Timers()

    /**
     * Per `@OnSchedule` job, by its name: `wow.schedules.<job>.*` holds all of the job's settings, its rule and the
     * retries of the subscriptions that run it. A job no handler of the node runs fails its start, and so does a job's
     * subscription configured under `wow.subscriptions`.
     */
    var schedules: MutableMap<String, Schedule> = LinkedHashMap()
    var tenant: TenantProperties = TenantProperties()
    var http: Http = Http()

    /** How the node calls its peers, the services of the other contexts it calls over HTTP: `wow.peer.*`. */
    var peer: Peer = Peer()

    /**
     * The peer contexts called over HTTP, by context (`wow.peers.<context>.*`): a saga's synchronous calls to that
     * context and the typed clients of it go to its service, at its `url` when one is set, else where `wow.peer.discovery`
     * finds the service its contract names (DNS by default). A node whose saga calls a context it cannot reach does not
     * start.
     */
    var peers: MutableMap<String, PeerTarget> = LinkedHashMap()

    /**
     * What each service that calls this one may call and read on the internal port, by the name it states
     * (`Wow-Service`, its `spring.application.name`): optional lists that catch a misconfiguration. The internal port
     * is reachable by the platform's own pods alone; a service that names itself is taken on that network's word.
     */
    var callers: MutableMap<String, CallerPermits> = LinkedHashMap()
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

    /** How the node's subscriptions run, retry their failures and park what keeps failing. */
    @InternalWowApi
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
         * `wow.subscriptions.[<context>.<name>].retry.*` and its handler's `@Retry` come before it.
         */
        var retry: Retry = Retry.of(DEFAULT_SUBSCRIPTION.retry)

        /**
         * How a failing read of the log, or write of a store, is retried: until the node stops, the backoff growing
         * for `error-after` (5) attempts, after which each failure is logged as an error and retried at
         * `max-backoff`, so a long outage does not stop the subscription.
         */
        var readRetry: ReadRetry = ReadRetry.of(DEFAULT_SUBSCRIPTION.readRetry)

        /** How the parked entries are retried automatically, over minutes and hours. */
        var parked: Parked = Parked()

        /**
         * When a partition pauses on a systemic failure, such as a database that is down, instead of parking every
         * aggregate that fails.
         */
        var systemic: Systemic = Systemic()

        /** How a handler whose downstream pushes back is retried, and its partition throttled. */
        var backpressure: Backpressure = Backpressure()

        /**
         * The partitions the node reads behind their head at once, all its subscriptions together; 0 for no bound. A
         * partition whose subscriptions start behind the head (a history migrate-data imported, a long stop) waits for
         * one, and the others stand at their checkpoints, so the node keeps its store for its commands while it
         * catches up. More do not catch up sooner on one store, and raise the latency of the commands meanwhile.
         */
        var maxCatchUps: Int = DEFAULT_SUBSCRIPTION.maxCatchUps

        /** These options, with the starts, the parked retry specs and the retry budgets [subscriptions] configure. */
        fun options(subscriptions: Map<String, SubscriptionSettings> = emptyMap()): SubscriptionOptions {
            val spec = parked.spec()
            val configured = subscriptions.mapKeys { (key, _) -> subscriptionOf(key) }
            return SubscriptionOptions(
                maxInFlight = maxInFlight,
                retry = retry.policy(),
                checkpointInterval = checkpointInterval.toKotlinDuration(),
                stopTimeout = stopTimeout.toKotlinDuration(),
                readRetry = readRetry.policy(),
                starts = configured.mapNotNull { (id, settings) ->
                    settings.start?.takeIf { it.isNotBlank() }?.let { id to SubscriptionStart.parse(it) }
                }.toMap(),
                parked = ParkedRetryOptions(
                    spec = spec,
                    specs = configured.mapNotNull { (id, settings) -> settings.parked.specOver(spec)?.let { id to it } }
                        .toMap(),
                    scanInterval = parked.scanInterval.toKotlinDuration(),
                ),
                retries = configured.mapNotNull { (id, settings) ->
                    settings.retry.handlerRetry()?.let { id to it }
                }.toMap(),
                systemic = systemic.options(),
                backpressure = backpressure.options(),
                maxCatchUps = maxCatchUps,
            )
        }

        /**
         * The subscription `wow.subscriptions.[<key>]` configures, refused unless it reads `<context>.<name>`: a
         * [WowConfigurationException] of the key.
         */
        private fun subscriptionOf(key: String): SubscriptionId = configNotNull(
            SubscriptionId.parse(key),
            "wow.subscriptions.[$key]",
            "Name it by its context and name, wow.subscriptions.[<context>.<name>], such as " +
                "wow.subscriptions.[shop.CartSaga.onOrderCreated].",
            key,
        ) { "wow.subscriptions.[$key] names no subscription: a subscription is configured by its context and name." }
    }

    /**
     * How a subscription answers backpressure: a downstream that is up but asks to be called later
     * (`wow.command.busy`, `wow.wait.timeout`, `wow.query.busy`, a 429 with `Retry-After`). The entry is retried, then
     * its aggregate parks; it never pauses the partition. Meanwhile each backpressure halves the handlings the
     * partition runs at once, and each success raises them again.
     */
    @InternalWowApi
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
     * `@Retry(recoverable, unrecoverable)`, then the `FailureClassifier` beans, then the modules' on the classpath,
     * then the runtime's own.
     */
    @InternalWowApi
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
     * The automatic retries of parked entries over minutes and hours; per subscription,
     * `wow.subscriptions.[<context>.<name>].parked.*` overrides them.
     */
    @InternalWowApi
    class Parked {
        /** The most automatic retries of a parked entry; 0 turns them off. */
        var maxRetries: Int = DEFAULT_PARKED.spec.maxRetries

        /**
         * The wait before the first retry after the failure; each next one waits twice as long after the previous one
         * started.
         */
        var initialBackoff: Duration = DEFAULT_PARKED.spec.initialBackoff.toJavaDuration()

        /**
         * How long a retry's claim on the entry holds before another retry may start, such as on the partition's next
         * owner: it cancels nothing.
         */
        var claimTtl: Duration = DEFAULT_PARKED.spec.claimTtl.toJavaDuration()

        /** How often each node scans its subscriptions' parked entries for retries that are due. */
        var scanInterval: Duration = DEFAULT_PARKED.scanInterval.toJavaDuration()

        fun spec(): ParkedRetrySpec =
            ParkedRetrySpec(maxRetries, initialBackoff.toKotlinDuration(), claimTtl.toKotlinDuration())
    }

    /**
     * One subscription's retry spec of its parked entries, each setting not given being `wow.subscription.parked`'s.
     */
    @InternalWowApi
    class ParkedSpec {
        /** How many automatic retries a parked entry of the subscription gets, over `wow.subscription.parked`'s. */
        var maxRetries: Int? = null

        /** The wait before the first automatic retry, over `wow.subscription.parked`'s. */
        var initialBackoff: Duration? = null

        /** How long a retry's claim on the entry holds, over `wow.subscription.parked`'s. */
        var claimTtl: Duration? = null

        /** The spec these settings give over [base], null when none is given. */
        fun specOver(base: ParkedRetrySpec): ParkedRetrySpec? {
            if (maxRetries == null && initialBackoff == null && claimTtl == null) {
                return null
            }
            return ParkedRetrySpec(
                maxRetries ?: base.maxRetries,
                initialBackoff?.toKotlinDuration() ?: base.initialBackoff,
                claimTtl?.toKotlinDuration() ?: base.claimTtl,
            )
        }
    }

    /**
     * One subscription's settings. [start] is where it starts in a partition it has no checkpoint in yet: `beginning`,
     * `end`, or an ISO-8601 instant (`2026-10-01T00:00:00Z`), the first entry committed at or after it; unset, its
     * kind's default (a projection from the beginning, a reactor or a saga from the end). A subscription that has a
     * checkpoint goes on from it. [parked] is how its parked entries are retried, over `wow.subscription.parked`.
     * [retry] is its in-process retry budget before it parks, over its handler's `@Retry` and `wow.subscription.retry`.
     * A name the node has no subscription of is refused at startup, and so is the subscription of an `@OnSchedule` job,
     * whose settings are its job's, `wow.schedules.<job>.*`.
     */
    @InternalWowApi
    class SubscriptionSettings {
        /** Where the subscription starts in a partition it has no checkpoint in: `beginning`, `end` or an instant. */
        var start: String? = null
        var parked: ParkedSpec = ParkedSpec()
        var retry: RetrySpec = RetrySpec()
    }

    /**
     * One subscription's in-process retry budget: each setting given replaces its handler's `@Retry`
     * one, each one not given is the handler's, else `wow.subscription.retry`'s. [timeout] bounds one attempt: one
     * that runs longer is cancelled and counts as failed.
     */
    @InternalWowApi
    class RetrySpec {
        /** The most attempts of the handler in all, before its aggregate parks. */
        var maxAttempts: Int? = null

        /** The wait after the first failure. */
        var initialBackoff: Duration? = null

        /** The longest wait between two attempts. */
        var maxBackoff: Duration? = null

        /** How many times longer each wait is than the one before; at least 1. */
        var multiplier: Double? = null

        /** How long one attempt may run before it is cancelled and counts as failed. */
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
     * The node's firing loop of the timers, which sends the scheduled commands when they are due.
     */
    @InternalWowApi
    class Timers {
        /**
         * How often the loop reads the timers due within `lookahead`, with one query per timer store over every
         * partition the node owns; a timer is sent up to this late.
         */
        var pollInterval: Duration = DEFAULT_TIMERS.pollInterval.toJavaDuration()

        /** How far ahead each read loads the timers that fall due; a longer lookahead reads more timers at once. */
        var lookahead: Duration = DEFAULT_TIMERS.lookahead.toJavaDuration()

        /**
         * The attempts a technical failure to send a timer gets before the timer is parked, under the subscription
         * `wow.timers.sends`.
         */
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
            retry = DEFAULT_TIMERS.retry.copyWith(maxAttempts = maxAttempts),
            expiresAfter = expiresAfter?.toKotlinDuration(),
            parallelism = parallelism,
            maxFireRate = maxFireRate,
            claimTimeout = claimTimeout.toKotlinDuration(),
            maxEarlyWait = maxEarlyWait.toKotlinDuration(),
        )
    }

    /**
     * An `@OnSchedule` job's settings, the only place it is configured: its rule, the [cron], the time [zone] and the
     * [misfire] policy, each unset to keep the annotation's (a tenant's own rule of a TENANT job comes before it);
     * [enabled] false disables a SYSTEM or a SHARDED job's instances at startup; and the [retry] and [parked] retries
     * of the subscriptions that run it, over its handler's `@Retry` and `wow.subscription.retry`, and over
     * `wow.subscription.parked`.
     */
    @InternalWowApi
    class Schedule {
        /** The job's cron, six fields, over its annotation's. */
        var cron: String? = null

        /** The time zone its cron is read in, over its annotation's. */
        var zone: String? = null

        /** What a firing missed while no node ran it does, over its annotation's. */
        var misfire: Misfire? = null

        /** Whether a SYSTEM or a SHARDED job's instances run; false disables them at startup. */
        var enabled: Boolean = true

        /** The in-process retry budget of the subscriptions that run it, before they park. */
        var retry: RetrySpec = RetrySpec()

        /** How their parked entries are retried. */
        var parked: ParkedSpec = ParkedSpec()

        /** These settings, the parked retries over [parkedBase], `wow.subscription.parked`'s. */
        fun options(parkedBase: ParkedRetrySpec): JobOptions =
            JobOptions(cron, zone, misfire, enabled, retry.handlerRetry(), parked.specOver(parkedBase))
    }

    /**
     * A retry policy: the wait grows from `initial-backoff` by `multiplier` at each failure, up to `max-backoff`.
     */
    @InternalWowApi
    class Retry {
        /** The most attempts of a call in all, the first included. */
        var maxAttempts: Int = 1

        /** The wait after the first failure. */
        var initialBackoff: Duration = Duration.ZERO

        /** The longest wait between two attempts. */
        var maxBackoff: Duration = Duration.ZERO

        /** How many times longer each wait is than the one before; at least 1. */
        var multiplier: Double = 1.0

        /**
         * How long one attempt of a handler may run before it is cancelled and counts as failed; not set, an attempt
         * runs as long as it takes.
         */
        var timeout: Duration? = null

        fun policy(): RetryPolicy = RetryPolicy(
            maxAttempts = maxAttempts,
            initialBackoff = initialBackoff.toKotlinDuration(),
            maxBackoff = maxBackoff.toKotlinDuration(),
            multiplier = multiplier,
            timeout = timeout?.toKotlinDuration() ?: kotlin.time.Duration.INFINITE,
        )

        @InternalWowApi
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

    /**
     * How a failing read is retried, never giving up: the wait grows from `initial-backoff` by `multiplier` at each
     * failure, up to `max-backoff`; past `error-after` attempts each failure is logged as an error.
     */
    @InternalWowApi
    class ReadRetry {
        /** The wait after the first failure. */
        var initialBackoff: Duration = Duration.ZERO

        /** The longest wait between two attempts. */
        var maxBackoff: Duration = Duration.ZERO

        /** How many times longer each wait is than the one before; at least 1. */
        var multiplier: Double = 1.0

        /** The attempts after which each failure is logged as an error, and retried at `max-backoff`. */
        var errorAfter: Int = 1

        fun policy(): RetryPolicy = RetryPolicy(
            maxAttempts = errorAfter,
            initialBackoff = initialBackoff.toKotlinDuration(),
            maxBackoff = maxBackoff.toKotlinDuration(),
            multiplier = multiplier,
        )

        @InternalWowApi
        companion object {
            fun of(policy: RetryPolicy): ReadRetry = ReadRetry().apply {
                errorAfter = policy.maxAttempts
                initialBackoff = policy.initialBackoff.toJavaDuration()
                maxBackoff = policy.maxBackoff.toJavaDuration()
                multiplier = policy.multiplier
            }
        }
    }

    /** The HTTP edge: its routes and its error answers. */
    @InternalWowApi
    class Http {
        /**
         * The request headers copied into a command's headers, beside the standard request facts the edge records:
         * `app_id`, the application the caller resolver authenticated, and on the public port
         * `user_agent` and `remote_ip`; none of the three can be listed, and each fact is cut to 512 characters.
         * `remote_ip` is the server's remote address, which the host takes from the gateway's `X-Forwarded-For`
         * (`server.forward-headers-strategy` is `framework` unless the application sets it, `none` included).
         */
        var requestFacts: MutableSet<String> = LinkedHashSet()

        /** The client address and `User-Agent` of each command, recorded as request facts. */
        var clientFacts: ClientFacts = ClientFacts()

        /** The HTTP status of an error code, by code (bracketed in a property name: `wow.http.errors.[a.b].status`). */
        var errors: MutableMap<String, Error> = LinkedHashMap()

        /** The problem+json answers of what fails on the application's own routes. */
        var globalErrors: GlobalErrors = GlobalErrors()

        /** The service's Manifest route. */
        var manifest: Manifest = Manifest()

        /** The OpenAPI document of the routes. */
        var openapi: OpenApi = OpenApi()

        /** The operator's routes of the node: its parked entries and its subscriptions. */
        var ops: HttpOps = HttpOps()

        @InternalWowApi
        class HttpOps {
            /**
             * Serves the operator's routes on the public port: the parked entries under `/wow/ops/parked`, and the read
             * of the node's subscriptions, `GET /wow/ops/subscriptions`.
             */
            var enabled: Boolean = false
        }

        /** The client address and `User-Agent` of each command, recorded as request facts. */
        @InternalWowApi
        class ClientFacts {
            /**
             * Whether the public port records each command's client address and `User-Agent` as the request facts
             * `remote_ip` and `user_agent`, for audit. They are personal data, and written into the event
             * stream, which is never deleted: once recorded, they cannot be erased. On by default; off records
             * neither.
             */
            var enabled: Boolean = true
        }

        /** The problem+json answers of what fails on the application's own routes. */
        @InternalWowApi
        class GlobalErrors {
            /**
             * Whether what fails on the application's own routes is answered as the framework routes' problem+json, so
             * every route answers errors alike: on by default; off leaves those errors to Spring's error handling.
             */
            var enabled: Boolean = true
        }

        @InternalWowApi
        class Error {
            /** The HTTP status the code is answered with. */
            var status: Int = 0
        }

        @InternalWowApi
        class Manifest {
            /** Serves `GET /wow/manifest`. */
            var enabled: Boolean = true
        }

        @InternalWowApi
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
        @InternalWowApi
        class Springdoc {
            /** Merges the Wow routes into springdoc's documents. */
            var enabled: Boolean = true

            /** The springdoc group of the Wow routes alone: `/v3/api-docs/<group>`. */
            var group: String = SpringdocAutoConfiguration.DEFAULT_GROUP
        }
    }

    /**
     * One calling service: the [commands] it may call, names or patterns (`AddItem`, `shop.cart.*`, `wow.lifecycle.*`):
     * without them, the domain commands alone; a system command (`wow.*`: the lifecycle, the snapshot rebuild, the
     * schedules) only when an entry naming `wow.` lists it; an entry naming a domain command narrows the domain
     * commands to those listed. The [reads] narrow the models it may read on the internal port's read routes, by
     * `context.aggregate.model` (`ordering.order.snapshot`, `.events`, `.state`) or a dataset's `context.dataset`,
     * patterns with `*`: without them, every model the node serves.
     */
    @InternalWowApi
    class CallerPermits {
        /** The commands the service may call, names or patterns; the domain commands alone when empty. */
        var commands: MutableList<String> = ArrayList()

        /** The models the service may read on the internal port's read routes, patterns too; every one when empty. */
        var reads: MutableList<String> = ArrayList()
    }

    /** How the node calls its peers over HTTP, whichever peer: one HTTP client, these settings. */
    @InternalWowApi
    class Peer {
        /**
         * How the node finds the internal ports of a peer's nodes: `dns` (the default) looks up the DNS SRV records
         * `wow.peer.dns.name-template` names after the peer's service, as its contract on the class path or its
         * Manifest names it; `static` takes `wow.peers.<context>.url` alone; `spring-cloud`, with wow-spring-cloud on
         * the class path, reads the instances a Spring Cloud service registry has of the service; another value names
         * a community provider's discovery. Each is configured under `wow.peer.<type>` by the options it declares. A
         * peer's `url` wins over any discovery.
         */
        var discovery: String = DnsPeerDiscovery.TYPE

        /**
         * The mode of a peer that names none: `service`, through its internal port as this service, or `user`,
         * through its public routes with the end user's credential.
         */
        var mode: PeerMode = PeerMode.SERVICE

        /**
         * How long a request may take to be answered, a command's longer wait aside; `wow.peers.<context>.timeout`
         * overrides it for one peer.
         */
        var timeout: Duration = WowClient.DEFAULT_TIMEOUT.toJavaDuration()

        /** How long a request may take to connect. */
        var connectTimeout: Duration = HttpCommandCaller.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

        /** The headers of the public request it serves that a user-mode request forwards. */
        var forwardedHeaders: MutableList<String> = mutableListOf("Authorization")

        /**
         * How a typed client's request that a peer answered with a problem a retry may get past is sent again; a
         * saga's call is not retried here, its subscription's retry budget is the one that counts.
         */
        var retry: PeerRetry = PeerRetry()
    }

    /**
     * One peer context: its internal port's base [url] (service mode), `http://host:port` of an address in its
     * internal network, never its public port, which refuses calls; its public routes' base [publicUrl] (user mode);
     * the [mode] it is called in, over `wow.peer.mode`; where its [contract] comes from (`jar`, or `manifest` for a
     * peer called by its Manifest alone); and its [timeout], over `wow.peer.timeout`.
     */
    @InternalWowApi
    class PeerTarget {
        /** The base URL of the peer's internal port (service mode), never its public port; found when not set. */
        var url: URI? = null

        /** The base URL of the peer's public routes (user mode). */
        var publicUrl: URI? = null

        /** The mode the peer is called in: `service` or `user`, over `wow.peer.mode`. */
        var mode: PeerMode? = null

        /** Where the peer's contract comes from: `jar`, its api jar, or `manifest`, its Manifest alone. */
        var contract: Contract = Contract.JAR

        /** How long a call to the peer may take, over `wow.peer.timeout`. */
        var timeout: Duration? = null

        /** Where a peer's contract comes from. */
        @InternalWowApi
        enum class Contract {
            /** Its api jar on the class path: its command objects are sent. */
            JAR,

            /**
             * Its Manifest alone, read from its `GET /wow/manifest`: a console, a gateway or a tool calls it with
             * [me.ahoo.wow.client.NamedCommand]s, without its api jar.
             */
            MANIFEST,
        }
    }

    /**
     * How a problem a retry may get past is retried ([me.ahoo.wow.client.RetryPolicy]): a 429, a command's problem that
     * keeps the request id, or a connection that could not be made.
     */
    @InternalWowApi
    class PeerRetry {
        /** The most attempts of a request in all; 1 never retries. */
        var maxAttempts: Int = ClientRetryPolicy.DEFAULT.maxAttempts

        /** The wait before the first retry when the peer gives no `Retry-After`; it doubles at each next one. */
        var initialBackoff: Duration = ClientRetryPolicy.DEFAULT.backoff.toJavaDuration()

        /** The longest `Retry-After` of a peer that is waited out; a peer that asks for longer is not retried. */
        var maxRetryAfter: Duration = ClientRetryPolicy.DEFAULT.maxRetryAfter.toJavaDuration()
    }

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

        const val DEFAULT_INTERNAL_PORT: Int = 9090
        const val DEFAULT_PARTITIONS: Int = 64
        private val DEFAULT_SUBSCRIPTION = SubscriptionOptions()
        private val DEFAULT_TIMERS = TimerOptions()
        private val DEFAULT_PARKED = ParkedRetryOptions()
        private val DEFAULT_SYSTEMIC = SystemicFailureOptions()
        private val DEFAULT_BACKPRESSURE = BackpressureOptions()
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
