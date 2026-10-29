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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.schedule.ScheduleJobRuntime
import me.ahoo.wow.spi.store.Store
import kotlin.time.Duration.Companion.milliseconds
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
                join()
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // Whatever failed, what started is stopped; the failure is the start's.
            try {
                shutdown()
            } catch (@Suppress("TooGenericExceptionCaught") stopping: Exception) {
                failure.addSuppressed(stopping)
            }
            throw failure
        }
        state = ServiceNode.State.RUNNING
        if (standby) {
            logger.warn {
                "Node $node of $service started on standby: it serves reads, refuses commands and handles no " +
                    "subscription until it is resumed. Contexts ${contexts.keys}."
            }
        } else {
            work()
            logger.info { "Node $node of $service started: contexts ${contexts.keys}." }
        }
    }

    /** Whether the node is on standby: started with `standby` and not [resume]d yet. */
    val standby: Boolean
        get() = gateway.standby

    /** When and by whom the node was resumed from standby; null while it is on standby or never was. */
    @Volatile
    var resumption: Resumption? = null
        private set

    /** Resumes a node started on standby ([ServiceNode.resume]). */
    suspend fun resume(by: String, reason: String? = null): Resumption = lock.withLock {
        check(state == ServiceNode.State.RUNNING) { "Node $node is $state; only a running node resumes." }
        if (!standby) {
            return@withLock resumption ?: Resumption(node, null, by, reason, resumed = false)
        }
        try {
            join()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            logger.error(failure) { "Node $node of $service failed to resume from standby; stopping." }
            try {
                shutdown()
            } catch (@Suppress("TooGenericExceptionCaught") stopping: Exception) {
                failure.addSuppressed(stopping)
            }
            throw failure
        }
        work()
        gateway.standby = false
        Resumption(node, assembly.clock.millis(), by, reason, resumed = true).also {
            resumption = it
            logger.warn {
                "Node $node of $service resumed from standby by [$by]${reason?.let { r -> " ($r)" }.orEmpty()} at " +
                    "${java.time.Instant.ofEpochMilli(checkNotNull(it.at))}: it handles its subscriptions and admits " +
                    "commands. Contexts ${contexts.keys}."
            }
        }
    }

    /** Joins every context's lease group and every topic's, and starts the ledger's publisher. */
    private suspend fun join() {
        contexts.values.forEach { it.ownership.start() }
        topicSubscriptions.values.forEach { it.start() }
        ledgerPublisher?.start()
    }

    /** Starts the jobs and the snapshot maintenance, in the background. */
    private fun work() {
        startJobs()
        contexts.values.forEach { it.snapshotMaintenance?.start(background) }
    }

    /** Runs the node's background work: the startup of the SYSTEM jobs; cancelled when the node stops. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /** Leaves the cluster and closes the node, in the order the class describes. Stopping twice does nothing. */
    suspend fun stop(): Unit = lock.withLock {
        when (state) {
            ServiceNode.State.NEW -> state = ServiceNode.State.STOPPED
            ServiceNode.State.STOPPED -> Unit
            else -> shutdown()
        }
    }

    private suspend fun shutdown() {
        state = ServiceNode.State.STOPPING
        background.coroutineContext[Job]?.cancelAndJoin()
        val waitsEnd = TimeSource.Monotonic.markNow() + options.wait.maxTimeout
        var failure: Exception? = null
        suspend fun step(step: ServiceNode.Step, block: suspend () -> Unit) {
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
        step(ServiceNode.Step.HANDED_OFF) { handOff() }
        step(ServiceNode.Step.WAITS_ENDED) {
            val ended = withTimeoutOrNull(-waitsEnd.elapsedNow()) {
                while (waits.size > 0) {
                    delay(WAIT_POLL)
                }
            }
            if (ended == null && waits.size > 0) {
                logger.warn { "Node $node of $service stops with ${waits.size} waits still registered." }
            }
        }
        step(ServiceNode.Step.GATEWAY_CLOSED) { gateway.close() }
        step(ServiceNode.Step.EXECUTIONS_ENDED) {
            val limit = maxOf(-waitsEnd.elapsedNow(), options.cluster.drainTimeout)
            withTimeoutOrNull(limit) { contexts.values.forEach { it.coordinator.join() } } ?: logger.warn {
                val executing = contexts.values.sumOf { it.coordinator.executing }
                "Node $node of $service closes its executors with $executing commands still executing."
            }
        }
        step(ServiceNode.Step.EXECUTORS_CLOSED) {
            // What still executes is cancelled first: the executors would otherwise wait for exactly those commands.
            contexts.values.forEach { it.coordinator.close() }
            coroutineScope {
                contexts.values.forEach { launch { it.executor.close(options.cluster.drainTimeout) } }
            }
        }
        step(ServiceNode.Step.LOGS_CLOSED) {
            timerLoop.close()
            contexts.values.forEach { it.log.close() }
        }
        closeStores()
        state = ServiceNode.State.STOPPED
        logger.info { "Node $node of $service stopped." }
        failure?.let { throw it }
    }

    /**
     * Hands off every context's partitions and every topic's, concurrently, and leaves the snapshot state revisions.
     */
    private suspend fun handOff() = coroutineScope {
        ledgerPublisher?.stop()
        contexts.values.forEach { context ->
            launch {
                context.ownership.stop()
                context.snapshotMaintenance?.stop()
            }
        }
        topicSubscriptions.values.forEach { launch { it.stop() } }
    }

    private companion object {
        val WAIT_POLL = 10.milliseconds

        /** The node's own log category, as before its lifecycle had a type of its own. */
        val logger = KotlinLogging.logger(ServiceNode::class.java.name)
    }

    /** Closes every placed store once the logs are: one that fails to close is logged, the others closed all the same. */
    @Suppress("TooGenericExceptionCaught") // Whatever one store throws, the others are closed.
    private fun closeStores() {
        placedStores.forEach { store ->
            try {
                store.close()
            } catch (failure: Exception) {
                logger.warn(failure) { "Node $node of $service failed to close the store $store." }
            }
        }
    }
}

/**
 * The resumption of a node started on standby ([ServiceNode.resume]): the [node], when it was resumed ([at], epoch
 * milliseconds; null for a node that never was), [by] whom and why ([reason]). [resumed] is false when the node was
 * not on standby, so the call changed nothing.
 */
@InternalWowApi
data class Resumption(val node: String, val at: Long?, val by: String, val reason: String?, val resumed: Boolean)
