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
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.serialization.toJsonString
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Abstract base class for message dispatchers that manage multiple aggregate dispatchers.
 *
 * This class coordinates the dispatching of messages to multiple named aggregates by
 * creating individual dispatchers for each aggregate and managing their lifecycle.
 * It provides a framework for implementing dispatchers that need to handle messages
 * across different aggregates, ensuring proper initialization, starting, and graceful shutdown.
 *
 * Subclasses must implement the abstract methods to define how messages are received
 * for each aggregate and how individual aggregate dispatchers are created.
 *
 * Example usage:
 * ```
 * class MyMainDispatcher : MainDispatcher<MyMessage>() {
 *     override val namedAggregates = setOf(myAggregate1, myAggregate2)
 *
 *     override fun receiveMessage(namedAggregate: NamedAggregate): Flux<MyMessage> {
 *         // Implementation to receive messages for the aggregate
 *         return myMessageFlux
 *     }
 *
 *     override fun newAggregateDispatcher(
 *         namedAggregate: NamedAggregate,
 *         messageFlux: Flux<MyMessage>
 *     ): MessageDispatcher {
 *         // Implementation to create dispatcher for the aggregate
 *         return MyAggregateDispatcher(namedAggregate, messageFlux)
 *     }
 * }
 *
 * val dispatcher = MyMainDispatcher()
 * dispatcher.start()
 * // ... application logic ...
 * dispatcher.stopGracefully().block()
 * ```
 *
 * @param T The type of message being dispatched, must be a non-null type.
 *
 * @see MessageDispatcher
 */
abstract class MainDispatcher<T : Any> : MessageDispatcher {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * The set of named aggregates that this dispatcher will manage.
     *
     * Each aggregate in this set will have its own dedicated dispatcher created.
     * Must be instances of [me.ahoo.wow.modeling.MaterializedNamedAggregate].
     */
    abstract val namedAggregates: Set<NamedAggregate>

    /**
     * Creates a flux of messages for the specified named aggregate.
     *
     * This method should return a reactive stream of messages that are destined for the given aggregate.
     * The implementation should handle message sourcing, filtering, and any necessary transformations.
     *
     * @param namedAggregate The named aggregate to receive messages for. Must not be null.
     * @return A [Flux] of messages for the aggregate. May be empty if no messages are available.
     *
     * @throws IllegalArgumentException if the namedAggregate is invalid or unsupported.
     * @throws RuntimeException if there are issues with message sourcing or connectivity.
     */
    abstract fun receiveMessage(namedAggregate: NamedAggregate): Flux<T>

    /**
     * Creates a new message dispatcher for a specific named aggregate.
     *
     * This method is responsible for instantiating a dispatcher that will handle messages
     * for a single aggregate. The dispatcher should process messages from the provided flux
     * and manage the aggregate's state or behavior accordingly.
     *
     * @param namedAggregate The named aggregate for which the dispatcher is created. Must not be null.
     * @param messageFlux The flux of messages for the aggregate. May be empty.
     * @return A new [MessageDispatcher] instance configured for the specified aggregate.
     *
     * @throws IllegalArgumentException if the namedAggregate is invalid or if messageFlux is null.
     * @throws RuntimeException if dispatcher creation fails due to configuration issues.
     */
    abstract fun newAggregateDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<T>
    ): MessageDispatcher

    /**
     * Lazily initialized list of aggregate dispatchers, one for each named aggregate.
     *
     * Each dispatcher is created with a message flux that includes receiver group
     * and metrics context. This property is initialized on first access to avoid
     * unnecessary resource allocation.
     */
    protected val aggregateDispatchers by lazy {
        namedAggregates
            .map {
                val messageFlux =
                    receiveMessage(it)
                        .writeReceiverGroup(name)
                        .writeMetricsSubscriber(name)
                newAggregateDispatcher(it, messageFlux)
            }
    }

    /**
     * Starts the dispatcher by running all aggregate dispatchers.
     *
     * Logs the named aggregates being subscribed to and starts each individual
     * aggregate dispatcher. If no aggregates are configured, logs a warning and returns.
     * This method should be called once during the application's startup phase.
     *
     * @throws RuntimeException if starting any aggregate dispatcher fails.
     */
    override fun start() {
        log.info {
            "[$name][${this.javaClass.simpleName}] Start subscribe to namedAggregates:${namedAggregates.toJsonString()}."
        }
        if (namedAggregates.isEmpty()) {
            log.warn {
                "[$name][${this.javaClass.simpleName}] Ignore start because namedAggregates is empty."
            }
            return
        }
        aggregateDispatchers.forEach { it.start() }
    }

    /**
     * Stops the dispatcher gracefully by shutting down all aggregate dispatchers.
     *
     * Logs the closure and calls [MessageDispatcher.stopGracefully] on each aggregate dispatcher.
     * This method waits for all dispatchers to complete their current operations before shutting down.
     * It should be called during the application's shutdown phase.
     *
     * @return A [Mono] that completes when all aggregate dispatchers have stopped gracefully.
     *         Completes with an error if any dispatcher fails to stop.
     */
    override fun stopGracefully(): Mono<Void> {
        log.info {
            "[$name][${this.javaClass.simpleName}] Stop Gracefully."
        }
        return Flux
            .fromIterable(aggregateDispatchers)
            .flatMap { it.stopGracefully() }
            .then()
    }
}
