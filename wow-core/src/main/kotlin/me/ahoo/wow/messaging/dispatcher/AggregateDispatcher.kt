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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.execution.dispatchKeyed
import me.ahoo.wow.infra.lifecycle.TerminatedSignalCapable
import me.ahoo.wow.infra.sink.terminated
import me.ahoo.wow.messaging.LocalDeliveryTicket
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.messaging.rejectLocalDelivery
import me.ahoo.wow.messaging.takeLocalDeliveryTicket
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.runtime.RuntimeActivity
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.internal.DefaultRuntimeExecutionResources
import me.ahoo.wow.runtime.internal.publishTerminalSignal
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.publisher.SynchronousSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstract dispatcher for the message exchanges of the [aggregates][namedAggregates] of one bounded context, received
 * through one source (design X7: one receiver per role and bounded context), with graceful shutdown support.
 *
 * Exchanges run on the runtime's [KeyedExecutor][me.ahoo.wow.execution.KeyedExecutor]
 * ([RuntimeContext.keyedExecutor]): one mailbox per [mailbox key][mailboxKey] (the aggregate ID), so exchanges of one
 * aggregate run one at a time in arrival order and different aggregates run in parallel on the runtime's shared,
 * CPU-sized workers. A handler that waits (I/O, a retry backoff) delays only its own mailbox. The dispatcher holds at
 * most [KeyedExecutor.maxInFlight][me.ahoo.wow.execution.KeyedExecutor.maxInFlight] unfinished exchanges and requests
 * more from its source as they finish.
 *
 * Key features:
 * - Per-aggregate ordering, cross-aggregate parallelism on shared workers
 * - Metrics collection for monitoring dispatcher performance
 * - Graceful shutdown that waits for active tasks to complete
 * - Error handling through SafeSubscriber integration
 *
 * Example usage:
 * ```kotlin
 * class CustomAggregateDispatcher(
 *     private val receiver: MessageReceiver<CommandExchange>,
 * ) : AggregateDispatcher<CommandExchange>(
 *     messageReadiness = receiver.readiness,
 *     processingAdmission = receiver::openProcessing,
 *     processingQuiescence = receiver::closeProcessing,
 * ) {
 *     override val namedAggregates: Set<NamedAggregate> = setOf(cartAggregate, orderAggregate)
 *     override val messageFlux: Flux<CommandExchange> = receiver.messages
 *
 *     override fun CommandExchange.mailboxKey(): Any = message.aggregateId
 *
 *     override fun handleExchange(exchange: CommandExchange): Mono<Void> {
 *         return commandHandler.handle(exchange)
 *             .doOnSuccess { exchange.acknowledge() }
 *     }
 * }
 *
 * // Usage
 * val dispatcher = CustomAggregateDispatcher(commandBus.receiver(subscription.copy(runtimeOwned = true)))
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
 * @param cleanupDispatcher Bounded dispatcher used for detached physical
 * cancellation.
 * @param messageReadiness Completion signal for asynchronous message-source
 * initialization. The message flux is subscribed before this signal is
 * awaited.
 * @param processingAdmission Explicit transport-processing gate opened by
 * [start] after dispatcher demand opens.
 * @param processingQuiescence Prompt logical transport gate closed before
 * physical source cancellation is detached.
 * @param metrics Instance-scoped metrics recorder for handled exchanges.
 *
 * @see MessageDispatcher for the interface this class implements
 * @see SafeSubscriber for error handling capabilities
 * @see MessageExchange for the exchange type contract
 */
abstract class AggregateDispatcher<T : MessageExchange<*, *>> protected constructor(
    private val cleanupDispatcher: (Runnable) -> Boolean = DefaultRuntimeExecutionResources::dispatchCleanup,
    private val messageReadiness: Mono<Void> = Mono.empty(),
    private val processingAdmission: () -> Unit = {},
    private val processingQuiescence: () -> Unit = {},
    private val metrics: WowMetrics = WowMetrics.NONE,
) :
    SafeSubscriber<Void>(),
    MessageDispatcher,
    TerminatedSignalCapable<Void> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * The flux of message exchanges to be processed.
     *
     * This reactive stream provides the source of messages that the dispatcher
     * will handle. Exchanges are run per [mailbox key][mailboxKey] on the runtime's keyed executor.
     *
     * The flux should emit MessageExchange instances that can be processed
     * by the handleExchange() method implementation.
     */
    abstract val messageFlux: Flux<T>

    /** The aggregates whose exchanges [messageFlux] carries: all of one bounded context. */
    abstract val namedAggregates: Set<NamedAggregate>

    /**
     * The `processor` and `source` metric tag of [namedAggregate]'s exchanges. Defaults to [name]; the framework's
     * dispatchers keep the per-aggregate dispatcher names they reported before one dispatcher served a whole context.
     */
    protected open fun metricProcessorName(namedAggregate: NamedAggregate): String = name

    private val handleMetricDescriptors = ConcurrentHashMap<String, MetricDescriptor>()

    private fun handleMetricDescriptorOf(exchange: T): MetricDescriptor {
        val namedAggregate = exchange.message as? NamedAggregate ?: namedAggregates.first()
        handleMetricDescriptors[namedAggregate.aggregateName]?.let { return it }
        return handleMetricDescriptors.computeIfAbsent(namedAggregate.aggregateName) {
            val processor = metricProcessorName(namedAggregate)
            MetricDescriptor(
                component = "dispatcher",
                operation = "handle",
                context = namedAggregate.contextName,
                aggregate = namedAggregate.aggregateName,
                processor = processor,
                source = processor,
            )
        }
    }

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
    private val processingMonitor = Any()
    private var processingOpened = false
    private var processingClosed = false

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
     * @see mailboxKey for the ordering key
     */
    final override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
        Mono.fromRunnable<Void> {
            val preparedDemandGate = DemandGateFlux(messageFlux) { cancellation ->
                scheduleDetachedCleanup("late source cancellation", cancellation)
            }
            synchronized(lifecycleMonitor) {
                check(state == State.NEW) {
                    "[$name] Dispatcher can only be prepared once. Current state: $state."
                }
                this.runtimeContext = runtimeContext
                demandGate = preparedDemandGate
                state = State.PREPARED
            }
            log.info {
                "[$name] Prepare subscription to $namedAggregates."
            }
            subscribeMessagePipeline(runtimeContext, preparedDemandGate)
        }
            .then(messageReadiness)

    @Suppress("TooGenericExceptionCaught")
    private fun subscribeMessagePipeline(
        runtimeContext: RuntimeContext,
        demandGate: DemandGateFlux<T>,
    ) {
        val terminalFailure = AtomicReference<Throwable?>()
        demandGate
            .takeUntilOther(stopRequestedSink.asMono())
            .handle<TrackedExchange<T>> { exchange, sink ->
                admitExchange(runtimeContext, exchange, sink)
            }
            .doOnNext(TrackedExchange<T>::confirmLocalDelivery)
            .dispatchKeyed(
                executor = runtimeContext.keyedExecutor,
                keyOf = TrackedExchange<T>::mailboxKey,
                handler = ::handleTrackedExchange,
            )
            .doOnDiscard(TrackedExchange::class.java) {
                it.rejectLocalDelivery()
                it.complete()
            }
            .doOnError { error ->
                terminalFailure.compareAndSet(null, error)
                runtimeContext.reportFailure(error)
            }
            .doFinally {
                val processingFailure = synchronized(lifecycleMonitor) {
                    state = State.STOPPED
                    revokeProcessing()
                }
                if (processingFailure != null) {
                    terminalFailure.compareAndSet(null, processingFailure)
                    runtimeContext.reportFailure(processingFailure)
                }
                tryEmitTerminated(terminalFailure.get())
            }
            .subscribe(this)
        terminalFailure.get()?.let { error ->
            throw error
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun admitExchange(
        runtimeContext: RuntimeContext,
        exchange: T,
        sink: SynchronousSink<TrackedExchange<T>>,
    ) {
        val activity = try {
            runtimeContext.tryAcquire()
        } catch (error: Throwable) {
            exchange.rejectLocalDelivery()
            throw error
        }
        if (activity == null) {
            exchange.rejectLocalDelivery()
            log.warn {
                "[$name] Reject an exchange received after runtime admission closed; " +
                    "the exchange remains unacknowledged."
            }
            return
        }
        var trackedExchange: TrackedExchange<T>? = null
        try {
            trackedExchange = TrackedExchange(
                exchange = exchange,
                mailboxKey = exchange.mailboxKey(),
                activity = activity,
                localDeliveryTicket = exchange.takeLocalDeliveryTicket(),
            )
            sink.next(trackedExchange)
        } catch (error: Throwable) {
            activity.close()
            trackedExchange?.rejectLocalDelivery() ?: exchange.rejectLocalDelivery()
            Exceptions.throwIfFatal(error)
            sink.error(error)
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
        openProcessing()
        log.info {
            "[$name] Start processing $namedAggregates."
        }
    }

    final override fun quiesce() {
        requestStop()
    }

    /**
     * The key of this exchange's mailbox: exchanges with equal keys run one at a time in arrival order; exchanges with
     * different keys may run in parallel. Dispatchers key by the aggregate ID.
     */
    abstract fun T.mailboxKey(): Any

    /**
     * Handles one admitted exchange inside its mailbox, measured when metrics are enabled, and releases its runtime
     * activity when the handling terminates (completes, fails or is cancelled).
     */
    private fun handleTrackedExchange(trackedExchange: TrackedExchange<T>): Mono<Void> {
        val handledExchange = Mono.defer { handleExchange(trackedExchange.exchange) }
        val measuredExchange = if (metrics.enabled) {
            metrics.operation(handledExchange, handleMetricDescriptorOf(trackedExchange.exchange))
        } else {
            handledExchange
        }
        return measuredExchange.doFinally { trackedExchange.complete() }
    }

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
     * After [quiesce] closes the source side, this method waits for every
     * already accepted exchange to complete naturally.
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
            "[$name] Stop gracefully."
        }
        return rawTerminatedSignal.doFinally {
            log.info {
                "[$name] [$it] Graceful shutdown complete."
            }
        }
    }

    private fun requestStop() {
        val (stopSignal, sourceCancellation, processingFailure) = synchronized(lifecycleMonitor) {
            val signal = when (state) {
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
            val failure = revokeProcessing()
            val cancellation = if (signal == StopSignal.REQUEST_STOP) {
                demandGate?.detachCancellation()
            } else {
                null
            }
            Triple(signal, cancellation, failure)
        }
        sourceCancellation?.let { cancellation ->
            scheduleDetachedCleanup("source cancellation", cancellation)
        }
        when (stopSignal) {
            StopSignal.NONE -> Unit
            StopSignal.REQUEST_STOP -> stopRequestedSink.tryEmitEmpty()
            StopSignal.TERMINATE -> tryEmitTerminated()
        }
        processingFailure?.let { throw it }
    }

    final override fun forceStop() {
        forceStopDispatcher()
    }

    private fun forceStopDispatcher() {
        val (newlyStopped, sourceCancellation, processingFailure) = synchronized(lifecycleMonitor) {
            val changed = state != State.STOPPED
            state = State.STOPPED
            val failure = revokeProcessing()
            Triple(changed, demandGate?.detachCancellation(), failure)
        }
        if (newlyStopped) {
            tryEmitTerminated()
        }
        sourceCancellation?.let { cancellation ->
            scheduleDetachedCleanup("source cancellation", cancellation)
        }
        if (newlyStopped) {
            scheduleDetachedCleanup("processing pipeline cancellation", ::cancel)
        }
        processingFailure?.let { throw it }
    }

    private fun openProcessing() {
        synchronized(processingMonitor) {
            if (processingOpened || processingClosed) {
                return
            }
            processingOpened = true
            processingAdmission()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun revokeProcessing(): Throwable? =
        synchronized(processingMonitor) {
            if (processingClosed) {
                return@synchronized null
            }
            processingClosed = true
            try {
                processingQuiescence()
                null
            } catch (error: Throwable) {
                Exceptions.throwIfFatal(error)
                error
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleDetachedCleanup(
        cleanupName: String,
        cleanup: () -> Unit,
    ) {
        val accepted = cleanupDispatcher(
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

    private class TrackedExchange<T : MessageExchange<*, *>>(
        val exchange: T,
        val mailboxKey: Any,
        private val activity: RuntimeActivity,
        private val localDeliveryTicket: LocalDeliveryTicket?,
    ) {
        private val completed = AtomicBoolean()

        fun complete() {
            if (completed.compareAndSet(false, true)) {
                activity.close()
            }
        }

        fun confirmLocalDelivery() {
            localDeliveryTicket?.confirm()
        }

        fun rejectLocalDelivery() {
            localDeliveryTicket?.reject()
        }
    }
}
