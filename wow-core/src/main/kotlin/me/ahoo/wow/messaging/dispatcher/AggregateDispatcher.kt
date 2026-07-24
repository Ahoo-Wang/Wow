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
import me.ahoo.wow.infra.sink.terminated
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metrics.Metrics
import reactor.core.publisher.Flux
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import java.util.concurrent.atomic.AtomicLong

/**
 * Abstract dispatcher for handling message exchanges for a specific aggregate with graceful shutdown support.
 *
 * This dispatcher provides a robust framework for processing message exchanges in parallel,
 * with built-in metrics collection, error handling, and graceful shutdown capabilities.
 * Message exchanges are grouped by key for parallel processing, ensuring ordered execution
 * within each group while allowing concurrent processing across different groups.
 *
 * Key features:
 * - Parallel message processing with a configurable ordering-stripe count
 * - Metrics collection for monitoring dispatcher performance
 * - Graceful shutdown that waits for active group cancellation to settle
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
 * dispatcher.start()
 *
 * // Graceful shutdown
 * dispatcher.stopGracefully().block()
 * ```
 *
 * @param T The type of message exchange being handled, must implement MessageExchange
 *
 * @see MessageDispatcher for the interface this class implements
 * @see SafeSubscriber for error handling capabilities
 * @see MessageExchange for the exchange type contract
 */
abstract class AggregateDispatcher<T : MessageExchange<*, *>> :
    SafeSubscriber<Void>(),
    MessageDispatcher,
    ParallelismCapable,
    NamedAggregateDecorator,
    TerminatedSignalCapable<Void> {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val ROOT_TERMINATED_MASK = Long.MIN_VALUE
    }

    /**
     * The number of ordering stripes for grouped exchanges.
     *
     * This value bounds the key space used by [toGroupKey] and the number of
     * groups subscribed by `flatMap`. Each stripe processes exchanges
     * sequentially. It is independent from the worker count of [scheduler];
     * reducing it can increase hash collisions and head-of-line blocking.
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
     * according to the configured ordering-stripe count.
     *
     * The flux should emit MessageExchange instances that can be processed
     * by the handleExchange() method implementation.
     */
    abstract val messageFlux: Flux<T>

    private val terminatedSink = Sinks.empty<Void>()

    override val terminatedSignal: Mono<Void> = terminatedSink.asMono()
    private val terminationState = AtomicLong(0)

    private fun tryEmitTerminated() {
        if (terminatedSink.terminated) {
            return
        }
        log.info {
            "[$name] Emitting terminated signal."
        }
        val result = terminatedSink.tryEmitEmpty()
        if (result != Sinks.EmitResult.OK) {
            log.warn {
                "[$name] Failed to emit terminated signal: $result."
            }
        }
    }

    /**
     * Starts the dispatcher by subscribing to the message flux.
     *
     * This method initiates message processing by subscribing to the messageFlux.
     * Messages are grouped by their grouping key for parallel processing, with
     * each group being handled on the configured scheduler. The dispatcher
     * subscribes to the processing pipeline for error handling.
     *
     * The subscription can be cancelled gracefully using stopGracefully().
     *
     * @throws Exception if subscription fails or initial setup encounters errors
     * @see stopGracefully for graceful shutdown
     * @see toGroupKey for grouping logic
     */
    override fun start() {
        log.info {
            "[$name] Start subscribe to $namedAggregate."
        }
        messageFlux
            .groupBy { it.toGroupKey() }
            .flatMap({ grouped ->
                handleGroupedExchange(grouped)
            }, parallelism, parallelism)
            .doFinally {
                markRootTerminated()
            }
            .subscribe(this)
    }

    /**
     * Converts a message exchange to a grouping key for parallel processing.
     *
     * This extension function determines how message exchanges are grouped
     * for parallel processing. Exchanges with the same key will be processed
     * sequentially within their group, while different groups can be processed
     * concurrently based on the ordering-stripe count.
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
     * the group. Group lifecycle tracking ensures that the terminated signal is
     * emitted only after the root subscription and every subscribed group has
     * observed termination.
     *
     * Metrics are collected for monitoring dispatcher performance, including
     * processing time, error rates, and throughput per group.
     *
     * @param grouped The grouped flux of message exchanges to process
     * @return A Mono that completes when all exchanges in the group are handled
     */
    private fun handleGroupedExchange(grouped: GroupedFlux<Int, T>): Mono<Void> =
        Mono.defer {
            if (!tryRegisterActiveGroup()) {
                return@defer Mono.empty()
            }
            Mono.defer {
                grouped
                    .publishOn(scheduler)
                    .name(Wow.WOW_PREFIX + "dispatcher")
                    .tag("dispatcher", name)
                    .tag(Metrics.AGGREGATE_KEY, namedAggregate.aggregateName)
                    .metrics()
                    .concatMap { exchange ->
                        Mono.defer {
                            handleExchange(exchange)
                        }
                    }.then()
            }.doFinally {
                unregisterActiveGroup()
            }
        }

    /**
     * Registers a group only while the root subscription is active.
     *
     * The root-terminated flag and active-group count share one CAS state so a
     * concurrently subscribing group cannot be missed by shutdown.
     */
    private fun tryRegisterActiveGroup(): Boolean {
        while (true) {
            val currentState = terminationState.get()
            if (currentState and ROOT_TERMINATED_MASK != 0L) {
                return false
            }
            check(currentState < Long.MAX_VALUE) {
                "[$name] Active group count overflow."
            }
            if (terminationState.compareAndSet(currentState, currentState + 1)) {
                return true
            }
        }
    }

    private fun unregisterActiveGroup() {
        while (true) {
            val currentState = terminationState.get()
            val activeGroupCount = currentState and Long.MAX_VALUE
            check(activeGroupCount > 0) {
                "[$name] Active group count underflow."
            }
            val updatedState = currentState - 1
            if (terminationState.compareAndSet(currentState, updatedState)) {
                if (updatedState == ROOT_TERMINATED_MASK) {
                    tryEmitTerminated()
                }
                return
            }
        }
    }

    private fun markRootTerminated() {
        while (true) {
            val currentState = terminationState.get()
            if (currentState and ROOT_TERMINATED_MASK != 0L) {
                return
            }
            val updatedState = currentState or ROOT_TERMINATED_MASK
            if (terminationState.compareAndSet(currentState, updatedState)) {
                if (updatedState == ROOT_TERMINATED_MASK) {
                    tryEmitTerminated()
                }
                return
            }
        }
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
     * This method initiates shutdown by cancelling the subscription to stop
     * accepting new messages and to cancel any active exchange handling.
     *
     * The method returns a Mono that completes after cancellation has propagated
     * through the dispatch pipeline and the subscriber has terminated.
     *
     * @return A Mono that completes when cancellation has settled for the root subscription and active groups
     * @see stop for the blocking version
     * @see cancel for subscription cancellation
     */
    override fun stopGracefully(): Mono<Void> {
        log.info {
            "[$name] Stop gracefully."
        }
        cancel()
        /*
         * BaseSubscriber.cancel() does not invoke hookFinally before subscription.
         * This idempotent mark also closes the concurrent start/stop registration window.
         */
        markRootTerminated()
        return terminatedSignal.doFinally {
            log.info {
                "[$name] [$it] Graceful shutdown complete."
            }
        }
    }
}
