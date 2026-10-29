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

package me.ahoo.wow.runtime.health

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.node.NodeSample
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ParkedSubscription
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.subscription.PausedPartition
import me.ahoo.wow.runtime.subscription.SubscriptionPauses
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.spi.VirtualThreads
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The health of a [node], host-agnostic: a host maps it to its probes (Spring Boot's Actuator, say).
 *
 * **Components** ([health]), each `UP`, `DEGRADED` or `DOWN` by the [options]:
 *
 * - `node`: the node's lifecycle state; `DOWN` unless it is `RUNNING`.
 * - `ownership`: each lease group the node takes part in (each context's; each topic's it reads):
 * the partitions it owns against its share by HRW, the members, whether a hand-over is in progress,
 *   and the context's lease store. `DOWN` when the lease store is down, when the ownership does not run, or when the
 *   node has owned none of a share that is not empty for [HealthOptions.handoverGrace]; `DEGRADED` when it owns
 *   another set than its share for that long, or the lease store failed lately. On a node on standby
 *   ([ServiceNode.standby]), which joins no lease group until it is resumed, a group that does not run
 *   is `UP`, marked `standby`: the node serves reads.
 * - `stores`: each event log (one per log group), from the node's own calls and a probe of its head
 *   every [HealthOptions.sampleInterval], and each store instance the write path needs (one holding a log, a type's
 *   primary snapshot store) from its probe ([me.ahoo.wow.spi.store.Store.probe]): the last success, the failures
 *   lately and since when they fail. `DOWN` when its calls have failed (or its probe has not answered) for
 *   [HealthOptions.storeDownAfter] with no success since.
 * - `secondaryStores`: each other placed store instance (an additional snapshot store, a query or search store), from
 *   its probe, as `stores` shows one; never part of readiness: its outage degrades the reads it serves, not the node.
 * - `topics`: each topic the node reads, by where it is in joining its lease group:
 *   `DOWN` while it is joining when [HealthOptions.topicsRequired], `DEGRADED` otherwise; `UP`, marked `standby`, while
 *   a node on standby has not joined it.
 * - `subscriptions`: each subscription of each runtime, by its group and name, from the node's last sample
 *   ([NodeSampler]): its lag behind the log's head in the partitions it runs here, its parked entries (the service's,
 *   from the ledgers: each context's `snapshot` its own), and the partitions it has paused
 *   ([SubscriptionPauses], each runtime's systemic pauses). Never `DOWN`: a subscription that falls behind
 *   does not stop the node from serving.
 * - `executor`: the commands in flight against `wow.executor.max-in-flight`, and per context the
 *   executor's commands and the appends queued; `DEGRADED` at [HealthOptions.executorSaturation].
 * - `liveness`: see [liveness].
 *
 * **Readiness** ([readiness], whether the node can serve traffic): `node`, `stores`, `ownership` and `topics`; `DOWN`
 * when one of them is, `UP` otherwise (a degraded node still serves).
 *
 * **Liveness** ([liveness], whether the process is stuck, so a restart helps): `DOWN` only when the node runs and,
 * for [HealthOptions.stallTimeout], one of these holds:
 *
 * - `dispatcher`: its dispatcher has not ticked;
 * - `drivers`: the dispatcher of the backends' blocking driver calls ([drivers]) has not run a tick of its own. Every
 *   store and topic call of the node runs there, so a wedged one (every carrier of the virtual threads pinned, say)
 *   stops the node's leases, heartbeats and reads, with no call failing;
 * - `reconcile`: a reconcile of one of its lease groups has not ended. The node left the group's members after the
 *   group's `reconcileTimeout`, which is shorter, so the others run its partitions meanwhile;
 * - `kernel`: commands are in flight, every log answered within that time, and none finished in it.
 *
 * Each is measured on its own (the drivers tick on the drivers alone), so more than one may hold at once: the details
 * list every one under `stalls`, in this order, and name the first as `stalled`.
 *
 * A storage outage never fails it: a store call that fails or times out returns, and a reconcile waiting on a slow
 * store ends once it answers, while a restart would not bring the stores back, and a restart storm would only add
 * load to them. So the bound is minutes, well above what a slow store under load takes. A node that is not running
 * (starting, stopping) is live: the startup probe covers the start, and a draining node is not restarted.
 *
 * A probe reads what was recorded and sampled: it never calls a store. [sampler], the node's one sampler, which its
 * owner starts and closes, samples the node; each of its samples probes the stores the node has not called and checks
 * the hand-overs. [start] starts the dispatcher's tick, once the node has started; [close] stops it.
 */
@InternalWowApi
class NodeHealth(
    val node: ServiceNode,
    /** The node's one sampler, which its gauges read too; its owner starts and closes it. */
    private val sampler: NodeSampler,
    val options: HealthOptions = HealthOptions(),
    private val context: CoroutineContext = Dispatchers.Default,
    /** Where the backends run their blocking driver calls: [VirtualThreads.dispatcher], which every backend shares. */
    private val drivers: CoroutineContext = VirtualThreads.dispatcher,
    private val nanos: () -> Long = System::nanoTime,
    /**
     * The partitions a runtime has paused: by default what it tells as [SubscriptionPauses]; a host or
     * a test may give another source.
     */
    private val pausedOf: (SubscriptionRuntime) -> List<PausedPartition> = SubscriptionRuntime::paused,
    /** The wall clock the pauses' `since` is of (epoch milliseconds): the node's. */
    private val clock: Clock = node.clock,
) : AutoCloseable {

    @Volatile
    private var running: Job? = null

    @Volatile
    private var lastTick: Long = StoreActivity.NONE

    /** When [drivers] last ran a tick. */
    @Volatile
    private var lastDriverTick: Long = StoreActivity.NONE

    /** The node's logs and lease stores, with what was seen of their calls. */
    private val stores: NodeStores = NodeStores.of(node)

    /** Since when each lease group's owned partitions differ from its share, by group name. */
    private val mismatches = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastSample: NodeSample = NodeSample.EMPTY

    /** How long a probe or a sampled read may take: [HealthOptions.storeDownAfter], at least a second. */
    private val probeTimeout: Duration = options.storeDownAfter.coerceAtLeast(MIN_PROBE_TIMEOUT)

    init {
        sampler.listen(::sampled)
    }

    /** How often the dispatcher ticks: every second, or four times within a shorter stall timeout. */
    private val tick: Duration = minOf(TICK, options.stallTimeout / TICKS_PER_STALL)

    /**
     * Starts the dispatcher's tick, and the drivers' tick run from it, every second (more often within a short stall
     * timeout). Starting twice does nothing.
     */
    @Synchronized
    fun start() {
        if (running != null) {
            return
        }
        lastTick = nanos()
        lastDriverTick = lastTick
        val root = SupervisorJob()
        val scope = CoroutineScope(context + root)
        scope.launch {
            while (true) {
                lastTick = nanos()
                delay(tick)
            }
        }
        // On the drivers alone, so a stalled dispatcher does not stop it, nor stalled drivers the dispatcher's.
        scope.launch(drivers) {
            while (true) {
                lastDriverTick = nanos()
                delay(tick)
            }
        }
        running = root
    }

    /** Stops the tick; [start] starts it again. */
    @Synchronized
    override fun close() {
        running?.cancel()
        running = null
    }

    /** Whether [start]ed and not [close]d. */
    val isRunning: Boolean get() = running != null

    /** Takes one sample of the node ([NodeSampler.sample]), which every reader of the sampler is told of. */
    suspend fun sample() {
        sampler.sample()
    }

    /**
     * Each sample, while the node runs: probes each log's head and each context's lease store, so a store the node is
     * not calling (an idle node, one that recovered) still shows how it is, keeps the sample for the subscriptions,
     * and checks the ownerships' hand-overs.
     */
    private suspend fun sampled(sample: NodeSample) {
        if (node.state != ServiceNode.State.RUNNING) {
            return
        }
        stores.logs.forEach { store -> probe(store.activity) { store.log.head(0) } }
        stores.leases.forEach { store ->
            val group = node.contexts[store.context]?.group?.name ?: return@forEach
            probe(store.activity) { store.leases.members(group) }
        }
        stores.placed.forEach { placed -> probe(placed.activity) { placed.activity.recorded { placed.store.probe() } } }
        lastSample = sample
        ownership()
    }

    /** The node's health, every component. */
    fun health(): ComponentHealth = ComponentHealth.of(
        WOW,
        listOf(
            nodeState(),
            ownership(),
            stores(),
            secondaryStores(),
            topics(),
            subscriptions(),
            executor(),
            liveness()
        ),
    )

    /** Whether the node can serve traffic: `DOWN` when `node`, `stores`, `ownership` or `topics` is. */
    fun readiness(): ComponentHealth {
        val parts = listOf(nodeState(), stores(), ownership(), topics())
        val down = parts.any { it.status == HealthStatus.DOWN }
        return ComponentHealth(READINESS, if (down) HealthStatus.DOWN else HealthStatus.UP, components = parts)
    }

    /** Whether the process is stuck (see the class description): `DOWN` only then. */
    fun liveness(): ComponentHealth {
        val now = nanos()
        val limit = node.inFlight
        val inFlight = limit.inFlight
        val tickAge = ago(now, lastTick)
        val driverTickAge = ago(now, lastDriverTick)
        val progressAge = (now - limit.lastProgress).nanoseconds
        val details = linkedMapOf<String, Any?>(
            "state" to node.state.name,
            "commandsInFlight" to inFlight,
            "lastTickMillis" to tickAge?.inWholeMilliseconds,
            "lastDriverTickMillis" to driverTickAge?.inWholeMilliseconds,
            "lastProgressMillis" to progressAge.inWholeMilliseconds,
        )
        if (node.state != ServiceNode.State.RUNNING || !isRunning) {
            return ComponentHealth(LIVENESS, HealthStatus.UP, details)
        }
        val stall = options.stallTimeout
        val stalls = mutableListOf<String>()
        if (tickAge != null && tickAge >= stall) {
            stalls += STALLED_DISPATCHER
        }
        if (driverTickAge != null && driverTickAge >= stall) {
            stalls += STALLED_DRIVERS
        }
        val reconciling = ownerships().mapNotNull { ownership ->
            ownership.reconcilingFor?.takeIf { it >= stall }?.let { ownership.group.name to it }
        }.maxByOrNull { it.second }
        if (reconciling != null) {
            stalls += STALLED_RECONCILE
            details["group"] = reconciling.first
            details["reconcilingMillis"] = reconciling.second.inWholeMilliseconds
        }
        val storesAnswer = stores.logs.all { store -> ago(now, store.activity.lastSuccess)?.let { it < stall } == true }
        if (inFlight > 0 && progressAge >= stall && storesAnswer) {
            stalls += STALLED_KERNEL
        }
        if (stalls.isEmpty()) {
            return ComponentHealth(LIVENESS, HealthStatus.UP, details)
        }
        details["stalled"] = stalls.first()
        details["stalls"] = stalls
        return ComponentHealth(LIVENESS, HealthStatus.DOWN, details)
    }

    private fun nodeState(): ComponentHealth = ComponentHealth(
        NODE,
        if (node.state == ServiceNode.State.RUNNING) HealthStatus.UP else HealthStatus.DOWN,
        linkedMapOf("service" to node.service, "node" to node.node, "state" to node.state.name),
    )

    private fun stores(): ComponentHealth = ComponentHealth.of(
        STORES,
        stores.logs.map { store ->
            storeHealth(store.activity.name, store.activity, mapOf("context" to store.context, "store" to store.store))
        } + placedHealth(writePath = true),
    )

    private fun secondaryStores(): ComponentHealth = ComponentHealth.of(
        SECONDARY_STORES,
        placedHealth(writePath = false)
    )

    private fun placedHealth(writePath: Boolean): List<ComponentHealth> =
        stores.placed.filter { it.writePath == writePath }.map { placed ->
            storeHealth(
                "${StoreActivity.STORE}:${placed.store.name}",
                placed.activity,
                mapOf("store" to placed.store.name, "type" to placed.store.type),
            )
        }

    private fun storeHealth(name: String, activity: StoreActivity, extra: Map<String, Any?>): ComponentHealth {
        val now = nanos()
        val failingFor = ago(now, activity.failingSince)
        val probingFor = ago(now, activity.probing)
        val failures = activity.failuresWithin(options.storeWindow)
        val down = options.storeDownAfter
        val status = when {
            // A probe under way has not failed: it counts only once it outlives its own timeout (a call that ignores
            // the timeout's cancellation); one that times out has failed, from then on.
            probingFor != null && probingFor >= probeTimeout -> HealthStatus.DOWN
            failingFor != null && failingFor >= down -> HealthStatus.DOWN
            failingFor != null || probingFor != null && probingFor >= options.sampleInterval -> HealthStatus.DEGRADED
            failures > 0 -> HealthStatus.DEGRADED
            else -> HealthStatus.UP
        }
        val details = LinkedHashMap<String, Any?>(extra)
        details["kind"] = activity.kind
        details["lastSuccessMillis"] = ago(now, activity.lastSuccess)?.inWholeMilliseconds
        details["failures"] = failures
        failingFor?.let { details["failingMillis"] = it.inWholeMilliseconds }
        probingFor?.let { details["probingMillis"] = it.inWholeMilliseconds }
        activity.lastError?.takeIf { failures > 0 || failingFor != null }?.let { details["lastError"] = it }
        return ComponentHealth(name, status, details)
    }

    /** Every lease group the node takes part in: each context's, and each topic's it reads. */
    private fun ownerships(): List<PartitionOwnership> =
        node.contexts.values.map { it.ownership } + node.topicSubscriptions.values.mapNotNull { it.ownership }

    private fun ownership(): ComponentHealth {
        val leases = stores.leases.associateBy { it.context }
        val groups = node.contexts.values.map { context ->
            ownershipHealth(context.ownership, leases[context.context]?.activity)
        } + node.topicSubscriptions.values.mapNotNull { topic -> topic.ownership?.let { ownershipHealth(it, null) } }
        val live = groups.mapTo(HashSet()) { it.name }
        mismatches.keys.retainAll(live)
        return ComponentHealth.of(OWNERSHIP, groups)
    }

    private fun ownershipHealth(ownership: PartitionOwnership, leases: StoreActivity?): ComponentHealth {
        val name = ownership.group.name
        val target = ownership.target
        val owned = ownership.owned
        val leaseStore = leases?.let { storeHealth(LEASE_STORE, it, emptyMap()) }
        val details = linkedMapOf<String, Any?>(
            "partitions" to ownership.partitions,
            "share" to target.size,
            "owned" to owned.size,
            "members" to ownership.routes.members.size,
            "handover" to (owned != target),
        )
        leaseStore?.let { details[LEASE_STORE] = mapOf("status" to it.status.name) + it.details }
        val handover = handoverStatus(name, owned, target)
        val standby = !ownership.isRunning && node.standby
        val status = when {
            standby -> HealthStatus.UP
            !ownership.isRunning -> HealthStatus.DOWN
            else -> HealthStatus.worst(listOfNotNull(leaseStore?.status, handover))
        }
        if (!ownership.isRunning) {
            details["running"] = false
        }
        if (standby) {
            details[STANDBY] = true
        }
        return ComponentHealth(name, status, details)
    }

    /** `UP` while [owned] is the [target] or a hand-over is within its grace; see the class description after it. */
    private fun handoverStatus(group: String, owned: Set<Int>, target: Set<Int>): HealthStatus {
        if (owned == target) {
            mismatches.remove(group)
            return HealthStatus.UP
        }
        val now = nanos()
        val since = mismatches.putIfAbsent(group, now) ?: now
        return when {
            (now - since).nanoseconds < options.handoverGrace -> HealthStatus.UP
            owned.isEmpty() && target.isNotEmpty() -> HealthStatus.DOWN
            else -> HealthStatus.DEGRADED
        }
    }

    private fun topics(): ComponentHealth = ComponentHealth.of(
        TOPICS,
        node.topicSubscriptions.map { (topic, subscriptions) ->
            val state = subscriptions.joinState
            val standby = state == TopicSubscriptions.JoinState.STOPPED && node.standby
            val status = when (state) {
                TopicSubscriptions.JoinState.READING -> HealthStatus.UP
                TopicSubscriptions.JoinState.JOINING ->
                    if (options.topicsRequired) HealthStatus.DOWN else HealthStatus.DEGRADED

                TopicSubscriptions.JoinState.STOPPED -> if (standby) HealthStatus.UP else HealthStatus.DOWN
            }
            val details = linkedMapOf<String, Any?>(
                "state" to state.name,
                "subscriptions" to subscriptions.subscriptions.map { it.name },
                "joinFailures" to subscriptions.joinFailures,
            )
            if (standby) {
                details[STANDBY] = true
            }
            subscriptions.lastJoinFailure?.let { details["lastJoinFailure"] = it::class.java.simpleName }
            ComponentHealth(topic, status, details)
        },
    )

    private fun subscriptions(): ComponentHealth {
        val sample = lastSample
        val runtimes = runtimes()
        return ComponentHealth.of(
            SUBSCRIPTIONS,
            runtimes.map { runtime ->
                val paused = pausedOf(runtime).groupBy { it.subscription }
                ComponentHealth.of(
                    runtime.group,
                    runtime.subscriptionNames.sorted().map { subscription ->
                        subscriptionHealth(runtime, subscription, sample, paused[subscription].orEmpty())
                    },
                )
            },
            if (sample.at == StoreActivity.NONE) {
                emptyMap()
            } else {
                mapOf("sampledMillis" to (nanos() - sample.at).nanoseconds.inWholeMilliseconds)
            },
        )
    }

    private fun subscriptionHealth(
        runtime: SubscriptionRuntime,
        subscription: String,
        sample: NodeSample,
        paused: List<PausedPartition>,
    ): ComponentHealth {
        val lag = lagOf(runtime, subscription, sample)
        val key = ParkedSubscription(runtime.group, subscription)
        val parked = sample.parked[key]
        val growing = parked != null && parked > (sample.previousParked[key] ?: parked)
        val status = when {
            paused.isNotEmpty() -> HealthStatus.DEGRADED
            lag != null && lag >= options.lagDegradedAt -> HealthStatus.DEGRADED
            parked != null && (parked >= options.parkedDegradedAt || growing) -> HealthStatus.DEGRADED
            else -> HealthStatus.UP
        }
        val details = linkedMapOf<String, Any?>("partitions" to runtime.partitions.size)
        lag?.let { details["lag"] = it }
        parked?.let {
            details["parked"] = it
            details["parkedGrowing"] = growing
        }
        if (paused.isNotEmpty()) {
            details["paused"] = paused.map {
                mapOf(
                    "partition" to it.partition,
                    "pausedMillis" to (clock.millis() - it.since).coerceAtLeast(0),
                    "reason" to it.reason,
                )
            }
        }
        return ComponentHealth(subscription, status, details)
    }

    /**
     * The entries of the log after [subscription]'s watermark in the partitions [runtime] runs here, to the heads
     * [sample] read; null for a runtime whose heads it did not read (an intake, another context's topic).
     */
    private fun lagOf(runtime: SubscriptionRuntime, subscription: String, sample: NodeSample): Long? {
        val behind = runtime.positions().filter { it.subscription == subscription }.mapNotNull { position ->
            sample.heads[runtime.group to position.partition]?.let { (it - position.watermark).coerceAtLeast(0) }
        }
        return if (behind.isEmpty()) null else behind.sum()
    }

    private fun executor(): ComponentHealth {
        val limit = node.inFlight
        val saturated = limit.inFlight >= limit.max * options.executorSaturation
        return ComponentHealth(
            EXECUTOR,
            if (saturated) HealthStatus.DEGRADED else HealthStatus.UP,
            linkedMapOf(
                "commandsInFlight" to limit.inFlight,
                "maxInFlight" to limit.max,
                "contexts" to node.contexts.mapValues { (_, context) ->
                    mapOf(
                        "commandsInFlight" to context.executor.commandsInFlight,
                        "appendsQueued" to context.log.queued,
                    )
                },
            ),
        )
    }

    /** Every subscription runtime of the node: each context's log groups' and intake's, each topic's once joined. */
    private fun runtimes(): List<SubscriptionRuntime> = node.contexts.values.flatMap { context ->
        context.logGroups.map { it.subscriptions } + listOfNotNull(context.intake)
    } + node.topicSubscriptions.values.mapNotNull { it.runtime }

    /** Calls a store with [call] as a probe, recorded in its [activity]: a probe without an answer is a failure. */
    @Suppress("TooGenericExceptionCaught") // A failed probe is recorded by the store's record; the next one follows.
    private suspend fun probe(activity: StoreActivity, call: suspend () -> Unit) {
        activity.probeStarted()
        try {
            val answered = withTimeoutOrNull(probeTimeout) {
                call()
                true
            }
            if (answered == null) {
                activity.failed(TimeoutException("The probe of $activity did not answer."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.debug(failure) { "The health probe of $activity failed." }
        } finally {
            activity.probeEnded()
        }
    }

    private fun ago(now: Long, at: Long): Duration? = if (at == StoreActivity.NONE) null else (now - at).nanoseconds

    @InternalWowApi
    companion object {
        const val WOW: String = "wow"
        const val READINESS: String = "readiness"
        const val LIVENESS: String = "liveness"
        const val NODE: String = "node"
        const val OWNERSHIP: String = "ownership"
        const val STORES: String = "stores"
        const val SECONDARY_STORES: String = "secondaryStores"
        const val TOPICS: String = "topics"
        const val SUBSCRIPTIONS: String = "subscriptions"
        const val EXECUTOR: String = "executor"
        const val LEASE_STORE: String = "leaseStore"

        /** The liveness's stalls, in the order it lists them. */
        const val STALLED_DISPATCHER: String = "dispatcher"
        const val STALLED_DRIVERS: String = "drivers"
        const val STALLED_RECONCILE: String = "reconcile"
        const val STALLED_KERNEL: String = "kernel"

        /** The detail that marks a lease group or a topic a node on standby has not joined. */
        private const val STANDBY = "standby"
        private val TICK: Duration = 1.seconds
        private const val TICKS_PER_STALL = 4
        private val MIN_PROBE_TIMEOUT: Duration = 1.seconds
        private val logger = KotlinLogging.logger {}
    }
}
