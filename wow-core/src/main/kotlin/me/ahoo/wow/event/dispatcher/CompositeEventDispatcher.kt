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

package me.ahoo.wow.event.dispatcher

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.internal.FailureAccumulator
import me.ahoo.wow.runtime.internal.RuntimeComponentGroup
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.scheduler.BorrowedAggregateSchedulerSupplier
import reactor.core.Exceptions
import reactor.core.publisher.Mono

/**
 * A composite event dispatcher that combines event stream and state event dispatchers to handle domain events and state events efficiently.
 *
 * This class implements the [MessageDispatcher] interface and delegates event processing to two specialized dispatchers:
 * - [EventStreamDispatcher] for handling domain event streams.
 * - [StateEventDispatcher] for handling state-related events.
 *
 * It provides a unified way to start and stop both dispatchers, ensuring proper lifecycle management and parallelism control.
 *
 * Example usage:
 * ```
 * val dispatcher = CompositeEventDispatcher(
 *     name = "MyApp.DomainEventDispatcher",
 *     parallelism = 4,
 *     domainEventBus = myDomainEventBus,
 *     stateEventBus = myStateEventBus,
 *     functionRegistrar = myFunctionRegistrar,
 *     eventHandler = myEventHandler,
 *     schedulerSupplier = mySchedulerSupplier
 * )
 * val runtime = WowRuntime(
 *     components = listOf(dispatcher),
 *     shutdownTimeout = Duration.ofSeconds(60),
 *     shutdownQuietPeriod = Duration.ofSeconds(1),
 * )
 * runtime.start().block()
 * // ... application logic ...
 * runtime.stopGracefully().block()
 * ```
 *
 * @param name The name of this dispatcher, typically formatted as `applicationName.DomainEventDispatcher`.
 * @param parallelism The level of parallelism for processing events. Defaults to [MessageParallelism.DEFAULT_PARALLELISM].
 * @param domainEventBus The domain event bus for publishing and subscribing to domain events.
 * @param stateEventBus The state event bus for handling state-related events.
 * @param functionRegistrar The registrar for domain event handler functions.
 * @param eventHandler The event handler for processing domain events.
 * @param schedulerSupplier Supplier for creating schedulers for aggregate processing. Defaults to a default implementation.
 *
 * @see EventStreamDispatcher
 * @see StateEventDispatcher
 * @see MessageDispatcher
 */
open class CompositeEventDispatcher(
    /**
     * The name of this dispatcher, typically formatted as `applicationName.DomainEventDispatcher`.
     */
    override val name: String,
    /**
     * The level of parallelism for processing events.
     * @default MessageParallelism.DEFAULT_PARALLELISM
     */
    private val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    /**
     * The domain event bus for publishing and subscribing to domain events.
     */
    private val domainEventBus: DomainEventBus,
    /**
     * The state event bus for handling state-related events.
     */
    private val stateEventBus: StateEventBus,
    /**
     * The registrar for domain event handler functions.
     */
    private val functionRegistrar: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>,
    /**
     * The event handler for processing domain events.
     */
    private val eventHandler: EventHandler,
    /**
     * Supplier for creating schedulers for aggregate processing.
     * @default DefaultAggregateSchedulerSupplier("EventDispatcher")
     */
    private val schedulerSupplier: AggregateSchedulerSupplier
) : MessageDispatcher {
    private val childSchedulerSupplier = BorrowedAggregateSchedulerSupplier(schedulerSupplier)

    private val eventStreamDispatcher by lazy {
        EventStreamDispatcher(
            name = name,
            parallelism = parallelism,
            messageBus = domainEventBus,
            functionRegistrar = functionRegistrar.filter { it.functionKind == FunctionKind.EVENT },
            eventHandler = eventHandler,
            schedulerSupplier = childSchedulerSupplier,
        )
    }

    private val stateEventDispatcher by lazy {
        StateEventDispatcher(
            name = name,
            parallelism = parallelism,
            messageBus = stateEventBus,
            functionRegistrar = functionRegistrar.filter { it.functionKind == FunctionKind.STATE_EVENT },
            eventHandler = eventHandler,
            schedulerSupplier = childSchedulerSupplier,
        )
    }
    private var componentGroup: RuntimeComponentGroup? = null

    /**
     * Starts the composite event dispatcher by initializing and starting both the event stream dispatcher and state event dispatcher.
     *
     * This method ensures that both underlying dispatchers are started and ready to process events.
     */
    final override fun prepare(runtimeContext: RuntimeContext) {
        check(componentGroup == null) {
            "[$name] Dispatcher can only be prepared once."
        }
        RuntimeComponentGroup(
            listOf(eventStreamDispatcher, stateEventDispatcher)
        ).also { group ->
            componentGroup = group
            group.prepare(runtimeContext)
        }
        prepareManaged(runtimeContext)
    }

    protected open fun prepareManaged(@Suppress("UNUSED_PARAMETER") runtimeContext: RuntimeContext) = Unit

    final override fun start() {
        componentGroup?.start()
        startManaged()
    }

    protected open fun startManaged() = Unit

    /**
     * Stops the composite event dispatcher gracefully by stopping both the event stream dispatcher and state event dispatcher.
     *
     * This method waits for both dispatchers to complete their current processing and shut down cleanly.
     *
     * @return A [Mono] that completes when both dispatchers have stopped gracefully.
     */
    final override fun stopGracefully(): Mono<Void> {
        val failures = FailureAccumulator()
        return (componentGroup?.stopGracefully() ?: Mono.empty())
            .onErrorResume { error ->
                failures.record(error)
                Mono.empty()
            }
            .then(Mono.defer(::stopManagedGracefully))
            .onErrorResume { error ->
                failures.record(error)
                Mono.empty()
            }
            .then(Mono.defer(schedulerSupplier::stopGracefully))
            .onErrorResume { error ->
                failures.record(error)
                Mono.empty()
            }
            .then(
                Mono.defer {
                    failures.current()?.let { Mono.error<Void>(it) } ?: Mono.empty()
                }
            )
    }

    protected open fun stopManagedGracefully(): Mono<Void> = Mono.empty()

    @Suppress("TooGenericExceptionCaught")
    final override fun forceStop() {
        val failures = FailureAccumulator()
        componentGroup?.forceStop()?.let(failures::record)
        try {
            forceStopManaged()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            failures.record(error)
        }
        try {
            schedulerSupplier.forceStop()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            failures.record(error)
        }
        failures.current()?.let { throw it }
    }

    protected open fun forceStopManaged() = Unit
}
