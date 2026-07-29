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

package me.ahoo.wow.messaging.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.infra.lifecycle.TerminatedSignalCapable
import me.ahoo.wow.infra.lifecycle.publishTerminalSignal
import me.ahoo.wow.infra.sink.terminated
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metrics.Metrics
import me.ahoo.wow.runtime.RuntimeActivity
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.internal.RuntimeCleanupExecutor
import me.ahoo.wow.runtime.internal.forceAllReporting
import me.ahoo.wow.runtime.internal.stopAllReporting
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstract dispatcher for handling message exchanges for a specific aggregate with graceful shutdown support.
 *
 * This dispatcher provides a robust framework for processing message exchanges in parallel,
 * with built-in metrics collection, error handling, and graceful shutdown capabilities.
 * Message exchanges are grouped by key for parallel processing, ensuring ordered execution
 * within each group while allowing concurrent processing across different groups.
 *
 * Key features:
 * - Parallel message processing with configurable parallelism level
 * - Metrics collection for monitoring dispatcher performance
 * - Graceful shutdown that waits for active tasks to complete
 * - Error handling through SafeSubscriber integration
 * - Scheduler-based execution for resource management
 *
 * Example usage:
 * ```kotlin
 * class CustomAggregateDispatcher(
 *     override val parallelism: Int = 4,
 *     override val scheduler: Scheduler = Schedulers.boundedElastic(),
 *     override val messageFlux: Flux<CommandExchange> = commandBus.receive(subscription)
 * ) : AggregateDispatcher<CommandExchange>() {
 *
 *     override fun CommandExchange.toGroupKey(): Int {
 *         return command.aggregateId.hashCode() % parallelism
 *     }
 *
 *     override fun handleExchange(exchange: CommandExchange): Mono<Void> {
 *         return commandHandler.handle(exchange)
 *             .doOnSuccess { exchange.acknowledge() }
 *     }
 * }
 *
 * // Usage
 * val dispatcher = CustomAggregateDispatcher()
 * val runtime = WowRuntime(
 *     components = listOf(dispatcher),
 *     shutdownTimeout = Duration.ofSeconds(30),
 *     shutdownQuietPeriod = Duration.ZERO,
 * )
 * runtime.start().block()
 * runtime.stopGracefully().block()
 * ```
 *
 * @param T The type of message exchange being handled, must implement MessageExchange
 * @param forceCleanupDispatcher Bounded dispatcher used for detached physical
 * cancellation during force-stop.
 *
 * @see MessageDispatcher for the interface this class implements
 * @see SafeSubscriber for error handling capabilities
 * @see MessageExchange for the exchange type contract
 */
abstract class AggregateDispatcher<T : MessageExchange<*, *>> protected constructor(
    private val forceCleanupDispatcher: (Runnable) -> Boolean = { action ->
        RuntimeCleanupExecutor.execute(action)
    },
) :
    SafeSubscriber<Void>(),
    MessageDispatcher,
    ParallelismCapable,
    NamedAggregateDecorator,
    TerminatedSignalCapable<Void> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * The level of parallelism for processing grouped exchanges.
     *
     * This value determines how many groups can be processed concurrently.
     * Each group processes exchanges sequentially, but different groups
     * can be processed in parallel. A higher parallelism value allows
     * more concurrent processing but may increase resource consumption.
     *
     * Typical values range from 1 (sequential processing) to the number
     * of available CPU cores or higher for I/O-bound workloads.
     */
    abstract override val parallelism: Int

    /**
     * The scheduler to use for processing message exchanges.
     *
     * The scheduler determines the thread pool and execution context
     * where message processing occurs. Common choices include:
     * - Schedulers.boundedElastic() for I/O-bound operations
     * - Schedulers.parallel() for CPU-bound operations
     * - Custom schedulers for specific resource management needs
     *
     * The scheduler is used via publishOn() to ensure message processing
     * happens on the appropriate threads.
     */
    abstract val scheduler: Scheduler

    /**
     * The flux of message exchanges to be processed.
     *
     * This reactive stream provides the source of messages that the dispatcher
     * will handle. The flux is grouped by key and processed in parallel
     * according to the configured parallelism level.
     *
     * The flux should emit MessageExchange instances that can be processed
     * by the handleExchange() method implementation.
     */
    abstract val messageFlux: Flux<T>

    private val terminatedSink = Sinks.empty<Void>()
    private val stopRequestedSink = Sinks.empty<Void>()
    private val rawTerminatedSignal = terminatedSink.asMono()

    @Volatile
    private var demandGate: DemandGateFlux<T>? = null

    override val terminatedSignal: Mono<Void> =
        rawTerminatedSignal.publishTerminalSignal()

    @Volatile
    private var runtimeContext: RuntimeContext? = null

    private val lifecycleMonitor = Any()

    private enum class State {
        NEW,
        PREPARED,
        RUNNING,
        STOPPING,
        STOPPED,
    }

    private enum class StopSignal {
        NONE,
        REQUEST_STOP,
        TERMINATE,
    }

    @Volatile
    private var state = State.NEW

    private val forceStopRequested = AtomicBoolean()

    private fun tryEmitTerminated(error: Throwable? = null) {
        if (terminatedSink.terminated) {
            return
        }
        log.info {
            "[$name] Emitting terminated signal."
        }
        val result = if (error == null) {
            terminatedSink.tryEmitEmpty()
        } else {
            terminatedSink.tryEmitError(error)
        }
        if (result != Sinks.EmitResult.OK) {
            log.warn {
                "[$name] Failed to emit terminated signal: $result."
            }
        }
    }

    /**
     * Prepares the dispatcher by subscribing without requesting messages.
     *
     * The shared runtime prepares every dispatcher before opening demand. This
     * readiness barrier prevents message loss across cyclic command/event/saga
     * pipelines during startup.
     *
     * [start] opens this dispatcher's demand gate.
     *
     * @throws Exception if subscription fails or initial setup encounters errors
     * @see stopGracefully for graceful shutdown
     * @see toGroupKey for grouping logic
     */
    final override fun prepare(runtimeContext: RuntimeContext) {
        val preparedDemandGate = DemandGateFlux(messageFlux) { cancellation ->
            scheduleForceCleanup("late source cancellation", cancellation)
        }
        synchronized(lifecycleMonitor) {
            check(state == State.NEW) {
                "[$name] Dispatcher can only be prepared once. Current state: $state."
            }
            this.runtimeContext = runtimeContext
            demandGate = preparedDemandGate
            state = State.PREPARED
        }
        runtimeContext.onAdmissionClose(::requestStop)
        log.info {
            "[$name] Prepare subscription to $namedAggregate."
        }
        subscribeMessagePipeline(runtimeContext, preparedDemandGate)
        prepareManaged(runtimeContext)
    }

    /**
     * Adds component-specific preparation after the guarded source subscription
     * has been established.
     */
    protected open fun prepareManaged(@Suppress("UNUSED_PARAMETER") runtimeContext: RuntimeContext) = Unit

    @Suppress("TooGenericExceptionCaught")
    private fun subscribeMessagePipeline(
        runtimeContext: RuntimeContext,
        demandGate: DemandGateFlux<T>,
    ) {
        val terminalFailure = AtomicReference<Throwable?>()
        demandGate
            .takeUntilOther(stopRequestedSink.asMono())
            .handle<TrackedExchange<T>> { exchange, sink ->
                val activity = runtimeContext.tryAcquire()
                if (activity != null) {
                    try {
                        sink.next(
                            TrackedExchange(
                                exchange = exchange,
                                groupKey = exchange.toGroupKey(),
                                activity = activity,
                            ),
                        )
                    } catch (error: Throwable) {
                        activity.close()
                        Exceptions.throwIfFatal(error)
                        sink.error(error)
                    }
                } else {
                    log.warn {
                        "[$name] Reject an exchange received after runtime admission closed; " +
                            "the exchange remains unacknowledged."
                    }
                }
            }
            .groupBy { trackedExchange -> trackedExchange.groupKey }
            .flatMap({ grouped ->
                handleGroupedExchange(grouped)
            }, parallelism, parallelism)
            .doOnDiscard(TrackedExchange::class.java) {
                it.complete()
            }
            .doOnError { error ->
                terminalFailure.compareAndSet(null, error)
                runtimeContext.reportFailure(error)
            }
            .doFinally {
                synchronized(lifecycleMonitor) {
                    state = State.STOPPED
                }
                tryEmitTerminated(terminalFailure.get())
            }
            .subscribe(this)
        terminalFailure.get()?.let { error ->
            throw error
        }
    }

    final override fun start() {
        synchronized(lifecycleMonitor) {
            if (state == State.RUNNING || state == State.STOPPED) {
                return
            }
            check(state == State.PREPARED) {
                "[$name] Dispatcher cannot start from state: $state."
            }
            state = State.RUNNING
        }
        checkNotNull(demandGate).open()
        log.info {
            "[$name] Start processing $namedAggregate."
        }
        startManaged()
    }

    /**
     * Adds component-specific work after guarded source demand is open.
     */
    protected open fun startManaged() = Unit

    /**
     * Converts a message exchange to a grouping key for parallel processing.
     *
     * This extension function determines how message exchanges are grouped
     * for parallel processing. Exchanges with the same key will be processed
     * sequentially within their group, while different groups can be processed
     * concurrently based on the parallelism level.
     *
     * A good grouping strategy distributes load evenly across groups while
     * maintaining ordering requirements. Common approaches include:
     * - Hash-based grouping for even distribution
     * - Aggregate ID-based grouping for per-aggregate ordering
     * - Round-robin assignment for simple load balancing
     *
     * @receiver The message exchange to group
     * @return An integer key for grouping exchanges. Should distribute evenly across available groups.
     */
    abstract fun T.toGroupKey(): Int

    /**
     * Handles a grouped flux of message exchanges.
     *
     * This private method processes a group of message exchanges that share
     * the same grouping key. It applies metrics collection, schedules execution
     * on the configured scheduler, and processes exchanges sequentially within
     * the group. Task counting is managed for graceful shutdown support.
     *
     * Metrics are collected for monitoring dispatcher performance, including
     * processing time, error rates, and throughput per group.
     *
     * @param grouped The grouped flux of message exchanges to process
     * @return A Mono that completes when all exchanges in the group are handled
     */
    private fun handleGroupedExchange(grouped: GroupedFlux<Int, TrackedExchange<T>>): Mono<Void> =
        grouped
            .publishOn(scheduler)
            .name(Wow.WOW_PREFIX + "dispatcher")
            .tag("dispatcher", name)
            .tag(Metrics.AGGREGATE_KEY, namedAggregate.aggregateName)
            .metrics()
            .concatMap { trackedExchange ->
                Mono.defer {
                    handleExchange(trackedExchange.exchange)
                }.doFinally {
                    trackedExchange.complete()
                }
            }.then()

    /**
     * Handles a single message exchange.
     *
     * Implementations should process the message exchange, perform any necessary
     * business logic, and return a Mono that completes when processing is finished.
     * The exchange may be acknowledged or additional processing may occur.
     *
     * This method is called for each message exchange in the processing pipeline.
     * Implementations should be idempotent and handle errors appropriately.
     *
     * @param exchange The message exchange to handle
     * @return A Mono that completes when the exchange is handled. The Mono may emit errors for failed processing.
     */
    abstract fun handleExchange(exchange: T): Mono<Void>

    /**
     * Performs a graceful shutdown of the dispatcher.
     *
     * This method completes the source side to stop accepting new messages while
     * allowing every already accepted exchange to complete naturally.
     *
     * The method returns a Mono that completes when shutdown is fully finished,
     * allowing for reactive shutdown coordination. This ensures no message
     * processing is interrupted mid-flight.
     *
     * @return A Mono that completes when all active tasks have finished and shutdown is complete
     * @see forceStop for deadline-expiry cancellation
     */
    final override fun stopGracefully(): Mono<Void> {
        log.info {
            "[$name] Stop gracefully. Active runtime operations: ${runtimeContext?.activeOperationCount ?: 0}."
        }
        requestStop()
        return stopAllReporting(
            listOf(
                {
                    rawTerminatedSignal.doFinally {
                        log.info {
                            "[$name] [$it] Graceful shutdown complete."
                        }
                    }
                },
                ::stopManagedGracefullyIfAllowed,
            ),
            ::reportRuntimeFailure,
        )
    }

    private fun stopManagedGracefullyIfAllowed(): Mono<Void> =
        if (forceStopRequested.get()) {
            Mono.empty()
        } else {
            stopManagedGracefully()
        }

    /**
     * Adds component-specific graceful cleanup after message processing drains.
     */
    protected open fun stopManagedGracefully(): Mono<Void> = Mono.empty()

    private fun requestStop() {
        val stopSignal = synchronized(lifecycleMonitor) {
            when (state) {
                State.NEW -> {
                    state = State.STOPPED
                    StopSignal.TERMINATE
                }

                State.PREPARED,
                State.RUNNING,
                -> {
                    state = State.STOPPING
                    StopSignal.REQUEST_STOP
                }

                State.STOPPING,
                State.STOPPED,
                -> StopSignal.NONE
            }
        }
        when (stopSignal) {
            StopSignal.NONE -> Unit
            StopSignal.REQUEST_STOP -> stopRequestedSink.tryEmitEmpty()
            StopSignal.TERMINATE -> tryEmitTerminated()
        }
    }

    final override fun forceStop() {
        forceStopRequested.set(true)
        forceAllReporting(
            listOf(
                ::forceStopDispatcher,
                ::forceStopManaged,
            ),
            ::reportRuntimeFailure,
        )?.let { throw it }
    }

    private fun forceStopDispatcher() {
        val (newlyStopped, sourceCancellation) = synchronized(lifecycleMonitor) {
            val changed = state != State.STOPPED
            state = State.STOPPED
            changed to demandGate?.detachCancellation()
        }
        if (newlyStopped) {
            tryEmitTerminated()
        }
        sourceCancellation?.let { cancellation ->
            scheduleForceCleanup("source cancellation", cancellation)
        }
        if (newlyStopped) {
            scheduleForceCleanup("processing pipeline cancellation", ::cancel)
        }
    }

    /**
     * Adds component-specific prompt cleanup after the dispatcher is detached.
     */
    protected open fun forceStopManaged() = Unit

    private fun reportRuntimeFailure(error: Throwable) {
        runtimeContext?.reportFailure(error)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleForceCleanup(
        cleanupName: String,
        cleanup: () -> Unit,
    ) {
        val accepted = forceCleanupDispatcher(
            Runnable {
                Thread.currentThread().interrupt()
                try {
                    cleanup()
                } catch (error: Throwable) {
                    Exceptions.throwIfFatal(error)
                    runtimeContext?.reportFailure(error)
                    log.warn(error) {
                        "[$name] Failed to execute detached $cleanupName."
                    }
                } finally {
                    Thread.interrupted()
                }
            },
        )
        if (!accepted) {
            val rejection = RejectedExecutionException(
                "[$name] Cannot schedule detached $cleanupName because the bounded " +
                    "runtime cleanup executor is saturated.",
            )
            runtimeContext?.reportFailure(rejection)
            log.warn(rejection) {
                "[$name] Skip detached $cleanupName."
            }
        }
    }

    private class TrackedExchange<T : Any>(
        val exchange: T,
        val groupKey: Int,
        private val activity: RuntimeActivity,
    ) {
        private val completed = AtomicBoolean()

        fun complete() {
            if (completed.compareAndSet(false, true)) {
                activity.close()
            }
        }
    }
}
