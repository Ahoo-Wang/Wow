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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.health.NodeFatal
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.schedule.ScheduleJobRuntime
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import me.ahoo.wow.spi.store.Store
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The lifecycle of a [ServiceNode] ([state]): [start] joins every context's lease group (heartbeat, reconcile, acquire
 * its partitions, start their subscriptions), and the node is then running. [stop] leaves in this order, each step
 * after the previous one:
 *
 * 1. every context's [me.ahoo.wow.runtime.cluster.PartitionOwnership.stop], concurrently: leave the members, and for
 *    each partition stop accepting, drain its commands within
 *    [me.ahoo.wow.runtime.cluster.ClusterOptions.drainTimeout], stop its subscriptions and save their checkpoints,
 *    release its lease;
 * 2. wait until no wait is registered on the node, at most the waits'
 *    [maximum timeout][me.ahoo.wow.runtime.wait.WaitOptions.maxTimeout] after the stop began, which no wait outlives;
 * 3. [CommandGateway.close]: refuse new requests, finish the detached ones;
 * 4. every context's [me.ahoo.wow.runtime.wait.WaitCoordinator.join]: the commands still executing (a wait that timed
 *    out leaves its command executing) settle normally, at most until the waits' bound of step 2, and at least
 *    [me.ahoo.wow.runtime.cluster.ClusterOptions.drainTimeout];
 * 5. every context's [me.ahoo.wow.runtime.wait.WaitCoordinator.close], cancelling what still executes, then its
 *    [me.ahoo.wow.runtime.Executor.close], which waits at most
 *    [me.ahoo.wow.runtime.cluster.ClusterOptions.drainTimeout] for its commands to finish;
 * 6. every context's [me.ahoo.wow.runtime.log.PartitionAppendQueue.close], committing what is queued.
 *
 * [listener] hears each [ServiceNode.Step] once it is done. A step that fails is logged and the next ones still run;
 * [stop] then throws the first failure. It logs as the [ServiceNode].
 *
 * **A start or an activation that fails**, a cancellation of it included, stops what it had started as [stop] does,
 * in a scope its cancellation does not reach, and waits for that at most the node's stop bound before it throws: the
 * node ends STOPPED either way, and past the bound an ERROR names the steps and the lease groups not stopped yet.
 *
 * **A fatal error** ([NodeFatals]: an out of memory or another error of the JVM that the node's own work met) makes the
 * node step out of every lease group at once, in the background: the heap is the whole process's, so nothing in it is
 * to be trusted after one, and the node holds nothing it would no longer do. Step 1 of [stop] runs, each group's leave
 * being [PartitionOwnership.stepOut][me.ahoo.wow.runtime.cluster.PartitionOwnership.stepOut]: the node leaves the
 * members and hands off every partition, so the other nodes take them over from their checkpoints even where nothing
 * restarts this one, within [ClusterOptions.reconcileTimeout][me.ahoo.wow.runtime.cluster.ClusterOptions.reconcileTimeout];
 * a hand-off that takes longer or fails (a hand-off needs memory too) renews no lease from then on, so the others take
 * the partitions once the leases expire. The gateway stays open: a command the node is still given goes to its
 * partition's new owner. Its liveness and readiness stay `DOWN` until the process ends
 * ([me.ahoo.wow.runtime.health.NodeHealth]), and the host's stop ends the rest. A node that is starting steps out once
 * its start ends, and one that is stopping hands off as it stops; a node on standby holds nothing, and is not
 * activated any more; a node that has not started does not start.
 */
internal class NodeLifecycle(private val assembly: NodeAssembly, private val listener: (ServiceNode.Step) -> Unit) {
    private val service = assembly.service
    private val node = assembly.node
    private val options = assembly.options
    private val admission = assembly.admission
    private val topics = assembly.topics
    private val contexts = assembly.contexts
    private val registry = assembly.registry
    private val waits = assembly.waits
    private val gateway = assembly.gateway
    private val timerLoop = assembly.timerLoop
    private val topicSubscriptions = assembly.topicSubscriptions
    private val topicRequirements = assembly.topicRequirements
    private val ledgerPublisher = assembly.ledgerPublisher
    private val intakes = assembly.intakes
    private val jobs = assembly.jobs
    private val applicationSubscriptions = assembly.applicationSubscriptions
    private val hasAddress: Boolean get() = assembly.hasAddress

    private fun takesCommands(context: String): Boolean = assembly.takesCommands(context)

    /** The store instances the contexts' ports were opened on, each once. */
    private val placedStores: List<Store> by lazy {
        contexts.values.flatMap { it.backends.stores.all }.distinct()
    }

    /**
     * Whether [context]'s command topic is as its ledger's commands need it, [check] says; when it is not, the node runs
     * without them, as it did before it took them, and warns: the operators' actions on its parked entries do not
     * reach it until the topic has its log's partitions.
     */
    private suspend fun ledgerTopicReady(context: String, check: suspend () -> Unit): Boolean = try {
        check()
        true
    } catch (unready: IllegalStateException) {
        logger.warn {
            "Node $node takes no ledger command of $context, so the operators' actions on its parked entries do not " +
                "reach it: ${unready.message}"
        }
        false
    }

    private val lock = Mutex()

    /** Where the node is in its lifecycle. */
    @Volatile
    var state: ServiceNode.State = ServiceNode.State.NEW
        private set

    /** Starts the node ([ServiceNode.start]), on standby when [standby] says so. */
    suspend fun start(standby: Boolean): Unit = lock.withLock {
        check(state == ServiceNode.State.NEW) { "Node $node is $state; a node starts once." }
        check(!assembly.fatals.isFatal) { "Node $node met a fatal error; it does not start." }
        check(hasAddress) { "Node $node has no internal address: bind it before it starts." }
        JdkCheck.PROCESS.warnOnce()
        state = ServiceNode.State.STARTING
        gateway.standby = standby
        try {
            // The schemas of what the stores opened, before anything reads or writes them.
            withContext(Dispatchers.IO) { placedStores.forEach(Store::createSchemas) }
            topics?.let { NodeTopics.prepare(node, it, topicRequirements) }
            val running = intakes.keys.filter { context ->
                val check = suspend {
                    CommandIntake.checkPartitions(
                        topics!!.commandsOf(context.context),
                        topics.source,
                        context.context,
                        context.backends.log.partitions,
                    )
                }
                if (takesCommands(context.context)) {
                    check()
                    true
                } else {
                    ledgerTopicReady(context.context, check)
                }
            }.toSet()
            contexts.values.forEach { context ->
                applicationSubscriptions.getValue(context).forEach { subscription ->
                    context.logGroups.forEach { it.subscriptions.add(subscription) }
                }
                context.snapshotMaintenance?.prepare()?.forEach { backfill ->
                    context.logGroups.forEach { it.subscriptions.add(backfill.subscription) }
                }
                intakes[context]?.takeIf { context in running }?.let { context.intake!!.add(it.subscription) }
            }
            if (!standby) {
                enterWalks()
                join()
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // Whatever failed, a cancellation too, what started is stopped; the failure is the start's.
            undo(failure, "start")
            throw failure
        }
        state = ServiceNode.State.RUNNING
        if (standby) {
            logger.warn {
                "Node $node of $service started on standby: it serves reads, refuses commands and handles no " +
                    "subscription until it is activated. Contexts ${contexts.keys}."
            }
        } else {
            work()
            logger.info { "Node $node of $service started: contexts ${contexts.keys}." }
        }
    }

    /** Whether the node is on standby: started with `standby` and not [activate]d yet. */
    val standby: Boolean
        get() = gateway.standby

    /** When and by whom the node was activated from standby; null while it is on standby or never was. */
    @Volatile
    var activation: Activation? = null
        private set

    /** Activates a node started on standby ([ServiceNode.activate]). */
    suspend fun activate(by: String, reason: String? = null): Activation = lock.withLock {
        check(state == ServiceNode.State.RUNNING) { "Node $node is $state; only a running node is activated." }
        check(!assembly.fatals.isFatal) { "Node $node met a fatal error; it is not activated." }
        if (!standby) {
            return@withLock activation ?: Activation(node, null, by, reason, activated = false)
        }
        try {
            enterWalks()
            join()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            logger.error(failure) { "Node $node of $service failed to activate from standby; stopping." }
            undo(failure, "activation")
            throw failure
        }
        work()
        gateway.standby = false
        Activation(node, assembly.clock.millis(), by, reason, activated = true).also {
            activation = it
            logger.warn {
                "Node $node of $service activated from standby by [$by]${reason?.let { r -> " ($r)" }.orEmpty()} at " +
                    "${java.time.Instant.ofEpochMilli(checkNotNull(it.at))}: it handles its subscriptions and admits " +
                    "commands. Contexts ${contexts.keys}."
            }
        }
    }

    /**
     * Joins every context's lease group and every topic's, and starts the ledger's publisher. The partitions the
     * contexts' first reconciles start read nothing until every context has started its own
     * ([me.ahoo.wow.runtime.cluster.PartitionOwnership.start]): the backlog of a log, a history migrate-data imported
     * say, is read once the node runs all of them, not while it starts the others. A topic's group, which joins in the
     * background, holds back its own first reconcile.
     *
     * The hold lasts at most [ClusterOptions.reconcileTimeout][me.ahoo.wow.runtime.cluster.ClusterOptions.reconcileTimeout]:
     * a context whose join hangs, its store down or a lease it cannot take, is warned of by name, and the partitions
     * the others started read from then on.
     */
    private suspend fun join(): Unit = coroutineScope {
        val reads = Job()
        val joining = AtomicReference<String>()
        val bound = options.cluster.reconcileTimeout
        val release = launch {
            delay(bound)
            logger.warn {
                "Node $node of $service has joined its contexts for more than $bound and context ${joining.get()} " +
                    "is still joining: the partitions the node started read their logs from now on, without waiting " +
                    "for it."
            }
            reads.complete()
        }
        try {
            contexts.forEach { (name, context) ->
                joining.set(name)
                context.ownership.start(reads)
            }
        } finally {
            release.cancel()
            reads.complete()
        }
        topicSubscriptions.values.forEach { it.start() }
        ledgerPublisher?.start()
    }

    /**
     * Makes the node a member of each context's access walks before it joins the lease groups and so accepts commands:
     * a node of another schema reads it among them before it appends.
     */
    private suspend fun enterWalks() = contexts.values.forEach { it.accessWalking?.enter() }

    /** Starts the jobs, the snapshot maintenance and the access walks, in the background. */
    private fun work() {
        startJobs()
        contexts.values.forEach {
            it.snapshotMaintenance?.start(background)
            it.accessWalking?.start(background)
        }
    }

    /** Runs the node's background work: the startup of the SYSTEM jobs; cancelled when the node stops. */
    private val background =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + assembly.fatals.contextOf(Component.NODE, "background"))

    /** Runs the step-out a fatal error starts, apart from [background], which a stop cancels. */
    private val stepping =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + assembly.fatals.contextOf(Component.NODE, "step-out"))

    init {
        assembly.fatals.listen { fatal -> stepping.launch { stepOut(fatal) } }
    }

    /**
     * Steps out of every lease group after the node's first fatal error [fatal] (see the class description): each
     * context's and topic's group in parallel, each on its own, so one that fails again leaves the others to go on; an
     * error that escapes one is the handler's of [stepping].
     */
    private suspend fun stepOut(fatal: NodeFatal) {
        val bound = options.cluster.reconcileTimeout
        // A start or a stop stuck on a store holds the lock: past the bound, the step-out goes on without it.
        val locked = withTimeoutOrNull(bound) { lock.lock() } != null
        try {
            // A stop under way hands off itself, and a start that failed holds nothing.
            if (!locked || state == ServiceNode.State.RUNNING) {
                stepOut(fatal, bound)
            }
        } finally {
            if (locked) {
                lock.unlock()
            }
        }
    }

    private suspend fun stepOut(fatal: NodeFatal, bound: Duration) {
        logger.error {
            "Node $node of $service steps out of its lease groups after its fatal error in ${fatal.component.value} " +
                "${fatal.name}: it hands off its partitions within $bound, so the other nodes take them over from their " +
                "checkpoints, and forwards the commands it is still given. Its liveness is DOWN until its process ends."
        }
        supervisorScope {
            ledgerPublisher?.let { publisher ->
                launch {
                    withTimeoutOrNull(bound) { quietly("stop publishing its ledger") { publisher.stop() } }
                }
            }
            contexts.values.forEach { context ->
                launch {
                    context.ownership.stepOut(bound)
                    withTimeoutOrNull(bound) {
                        quietly("leave the snapshot maintenance of ${context.context}") {
                            context.snapshotMaintenance?.stop()
                        }
                        quietly("leave the access walks of ${context.context}") { context.accessWalking?.stop() }
                    }
                }
            }
            topicSubscriptions.values.forEach { launch { it.stepOut(bound) } }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A step of the step-out that fails leaves the next ones to run.
    private suspend fun quietly(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Node $node of $service failed to $action after its fatal error; stepping out on." }
        }
    }

    /**
     * Brings each context's SYSTEM jobs in line with the Manifest and the host's configuration, in the
     * background: enables each instance (a no-op when nothing changed, a reschedule when its default did), or
     * disables it, retrying until each settles.
     */
    private fun startJobs() {
        jobs.forEach { (context, contextJobs) ->
            val node = contexts.getValue(context)
            val commands = ScheduleJobRuntime.startup(
                context,
                contextJobs,
                options.schedules,
                node.backends.log.partitions,
            )
            if (commands.isEmpty()) {
                return@forEach
            }
            val model = registry.models.first { it.type == ScheduleJobs.typeOf(context) }
            background.launch {
                ScheduleJobRuntime.send(model, commands, options.timers.retry, admission.ids.global::next) { m, c ->
                    node.coordinator.forward(m, c, null)
                }
            }
        }
    }

    /**
     * Stops what a [failure]d [what] (the start, the activation) had started, as a stop does, and whatever cancelled it:
     * the stop runs in a scope of its own, which a cancellation of the caller does not reach, and the caller waits for
     * it at most the node's stop bound ([ServiceNodeOptions.stopBound]). A failure of the stop is suppressed onto
     * [failure]. Past the bound, the stop goes on in the background, and the ERROR logged names what it had not
     * stopped yet. Either way the node ends [STOPPED][ServiceNode.State.STOPPED], so a later [stop] does nothing.
     */
    private suspend fun undo(failure: Throwable, what: String) {
        val stopping = undoing.async { shutdown(joined = true) }
        val ended = withContext(NonCancellable) {
            withTimeoutOrNull(options.stopBound) {
                try {
                    stopping.await()
                } catch (@Suppress("TooGenericExceptionCaught") stopFailed: Exception) {
                    failure.addSuppressed(stopFailed)
                }
            }
        } != null
        if (!ended) {
            state = ServiceNode.State.STOPPED
            logger.error {
                "Node $node of $service did not finish stopping what its failed $what started within " +
                    "${options.stopBound}; it goes on in the background. Not stopped yet: ${notStopped()}."
            }
        }
    }

    /** What a stop under way has not stopped yet: the steps it has not reached, and the contexts still in their groups. */
    private fun notStopped(): String {
        val pending = ServiceNode.Step.entries - reached
        val joined = contexts.filterValues { it.ownership.isRunning }.keys
        return "the steps $pending, the lease groups of the contexts $joined"
    }

    /** The steps the stop under way has reached. */
    private val reached: MutableSet<ServiceNode.Step> = ConcurrentHashMap.newKeySet()

    /** Runs the stop of a failed start or activation, apart from the caller, whose cancellation it outlives. */
    private val undoing =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + assembly.fatals.contextOf(Component.NODE, "undo"))

    /**
     * Leaves the cluster and closes the node, in the order the class describes. A node that never started joined
     * nothing, so it skips the hand-off, but closes what it was built with from the next step on: its gateway refuses
     * commands from then on. Stopping twice does nothing.
     */
    suspend fun stop(): Unit = lock.withLock {
        if (state != ServiceNode.State.STOPPED) {
            shutdown(joined = state != ServiceNode.State.NEW)
        }
    }

    private suspend fun shutdown(joined: Boolean) {
        state = ServiceNode.State.STOPPING
        background.coroutineContext[Job]?.cancelAndJoin()
        val waitsEnd = TimeSource.Monotonic.markNow() + options.wait.maxTimeout
        val steps = Steps()
        if (joined) {
            steps.run(ServiceNode.Step.HANDED_OFF) { handOff() }
        }
        close(steps, waitsEnd)
        state = ServiceNode.State.STOPPED
        logger.info { "Node $node of $service stopped." }
        steps.failure?.let { throw it }
    }

    /**
     * Steps 2 to 6 of a stop, which a node that never started runs too: waits for the waits until [waitsEnd], and
     * closes the gateway, the executions, the executors and the logs.
     */
    private suspend fun close(steps: Steps, waitsEnd: TimeMark) {
        steps.run(ServiceNode.Step.WAITS_ENDED) {
            val ended = withTimeoutOrNull(-waitsEnd.elapsedNow()) {
                while (waits.size > 0) {
                    delay(WAIT_POLL)
                }
            }
            if (ended == null && waits.size > 0) {
                logger.warn { "Node $node of $service stops with ${waits.size} waits still registered." }
            }
        }
        steps.run(ServiceNode.Step.GATEWAY_CLOSED) { gateway.close(options.cluster.drainTimeout) }
        steps.run(ServiceNode.Step.EXECUTIONS_ENDED) {
            val limit = maxOf(-waitsEnd.elapsedNow(), options.cluster.drainTimeout)
            withTimeoutOrNull(limit) { contexts.values.forEach { it.coordinator.join() } } ?: logger.warn {
                val executing = contexts.values.sumOf { it.coordinator.executing }
                "Node $node of $service closes its executors with $executing commands still executing."
            }
        }
        steps.run(ServiceNode.Step.EXECUTORS_CLOSED) {
            // What still executes is cancelled first: the executors would otherwise wait for exactly those commands.
            contexts.values.forEach { it.coordinator.close() }
            coroutineScope {
                contexts.values.forEach { launch { it.executor.close(options.cluster.drainTimeout) } }
            }
        }
        steps.run(ServiceNode.Step.LOGS_CLOSED) {
            timerLoop.close()
            contexts.values.forEach { it.log.close() }
        }
    }

    /**
     * The steps of one stop, run in turn: each logged once done and told to the listener, done or not; the first that
     * failed is kept for the stop to throw once every step ran.
     */
    private inner class Steps {
        var failure: Exception? = null
            private set

        suspend fun run(step: ServiceNode.Step, block: suspend () -> Unit) {
            try {
                block()
                reached += step
                logger.info { "Node $node of $service: $step." }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failed: Exception) {
                logger.warn(failed) { "Node $node of $service failed to reach $step; stopping on." }
                failure = failure ?: failed
            }
            listener(step)
        }
    }

    /**
     * Hands off every context's partitions and every topic's, concurrently, and leaves the snapshot state revisions and
     * the access schemas.
     */
    private suspend fun handOff() = coroutineScope {
        ledgerPublisher?.stop()
        contexts.values.forEach { context ->
            launch {
                context.ownership.stop()
                context.snapshotMaintenance?.stop()
                context.accessWalking?.stop()
            }
        }
        topicSubscriptions.values.forEach { launch { it.stop() } }
    }

    private companion object {
        val WAIT_POLL = 10.milliseconds

        /** The node's own log category, as before its lifecycle had a type of its own. */
        val logger = KotlinLogging.logger(ServiceNode::class.java.name)
    }
}

/**
 * The activation of a node started on standby ([ServiceNode.activate]): the [node], when it was activated ([at], epoch
 * milliseconds; null for a node that never was), [by] whom and why ([reason]). [activated] is false when the node was
 * not on standby, so the call changed nothing.
 */
@InternalWowApi
data class Activation(val node: String, val at: Long?, val by: String, val reason: String?, val activated: Boolean)
