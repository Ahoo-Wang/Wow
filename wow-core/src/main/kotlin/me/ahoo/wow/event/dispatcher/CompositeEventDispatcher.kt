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
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.internal.RuntimeComponentGroup
import me.ahoo.wow.runtime.internal.forceAllReporting
import me.ahoo.wow.runtime.internal.stopAllReporting
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.scheduler.BorrowedAggregateSchedulerSupplier
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
 *     shutdownTimeout = Duration.ofSeconds(30),
 *     shutdownQuietPeriod = Duration.ZERO,
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
 * @param metrics Instance-scoped metrics recorder propagated to both child dispatchers.
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
    private val schedulerSupplier: AggregateSchedulerSupplier,
    private val metrics: WowMetrics = WowMetrics.NONE,
) : MessageDispatcher {
    private val childSchedulerSupplier =
        BorrowedAggregateSchedulerSupplier(schedulerSupplier)

    private val eventStreamDispatcherLazy = lazy {
        EventStreamDispatcher(
            name = name,
            parallelism = parallelism,
            messageBus = domainEventBus,
            functionRegistrar = functionRegistrar.filter { it.functionKind == FunctionKind.EVENT },
            eventHandler = eventHandler,
            schedulerSupplier = childSchedulerSupplier,
            metrics = metrics,
        )
    }
    private val eventStreamDispatcher by eventStreamDispatcherLazy

    private val stateEventDispatcherLazy = lazy {
        StateEventDispatcher(
            name = name,
            parallelism = parallelism,
            messageBus = stateEventBus,
            functionRegistrar = functionRegistrar.filter { it.functionKind == FunctionKind.STATE_EVENT },
            eventHandler = eventHandler,
            schedulerSupplier = childSchedulerSupplier,
            metrics = metrics,
        )
    }
    private val stateEventDispatcher by stateEventDispatcherLazy

    private val childLifecycleMonitor = Any()
    private var eventComponentGroup: RuntimeComponentGroup? = null
    private val forceStopRequested = AtomicBoolean()
    private val childFailure = AtomicReference<Throwable?>()

    @Volatile
    private var runtimeContext: RuntimeContext? = null

    /**
     * Starts the composite event dispatcher by initializing and starting both the event stream dispatcher and state event dispatcher.
     *
     * This method ensures that both underlying dispatchers are started and ready to process events.
     */
    final override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
        Mono.defer {
            if (forceStopRequested.get()) {
                return@defer Mono.empty()
            }
            this.runtimeContext = runtimeContext
            val childRuntimeContext = object : RuntimeContext by runtimeContext {
                override fun reportFailure(error: Throwable) {
                    childFailure.compareAndSet(null, error)
                    runtimeContext.reportFailure(error)
                }
            }
            val group = RuntimeComponentGroup(
                listOf(eventStreamDispatcher, stateEventDispatcher),
                childRuntimeContext::reportFailure,
            )
            val accepted = synchronized(childLifecycleMonitor) {
                if (forceStopRequested.get()) {
                    false
                } else {
                    check(eventComponentGroup == null) {
                        "[$name] Event dispatcher group can only be installed once."
                    }
                    eventComponentGroup = group
                    true
                }
            }
            if (!accepted) {
                return@defer group.forceStop()?.let { Mono.error(it) } ?: Mono.empty()
            }
            group.prepare(
                runtimeContext = childRuntimeContext,
                admissionGate = ::admitChildLifecycleAction,
                afterEach = ::throwIfChildFailed,
            ).then(
                Mono.defer {
                    childFailure.get()
                        ?.let { Mono.error<Void>(it) }
                        ?: Mono.empty()
                },
            )
        }

    final override fun start() {
        if (forceStopRequested.get()) {
            return
        }
        eventComponentGroupSnapshot()?.start(
            admissionGate = ::admitChildLifecycleAction,
            afterEach = ::throwIfChildFailed,
        )
    }

    final override fun quiesce() {
        if (forceStopRequested.get()) {
            return
        }
        eventComponentGroupSnapshot()?.quiesce {
            !forceStopRequested.get()
        }
    }

    /**
     * Stops the composite event dispatcher gracefully by stopping both the event stream dispatcher and state event dispatcher.
     *
     * This method waits for both dispatchers to complete their current processing and shut down cleanly.
     *
     * @return A [Mono] that completes when both dispatchers have stopped gracefully.
     */
    final override fun stopGracefully(): Mono<Void> =
        stopAllReporting(
            buildList {
                eventComponentGroupSnapshot()?.let { group ->
                    add {
                        group.stopGracefully(
                            shouldStop = { !forceStopRequested.get() },
                        )
                    }
                }
                add(::stopSchedulerGracefullyIfAllowed)
            },
            ::reportRuntimeFailure,
        )

    private fun stopSchedulerGracefullyIfAllowed(): Mono<Void> =
        if (forceStopRequested.get()) {
            Mono.empty()
        } else {
            schedulerSupplier.stopGracefully()
        }

    final override fun forceStop() {
        forceStopRequested.set(true)
        forceAllReporting(
            buildList {
                eventComponentGroupSnapshot()?.let { group ->
                    add {
                        group.forceStop()?.let { throw it }
                    }
                }
                add(schedulerSupplier::forceStop)
            },
            ::reportRuntimeFailure,
        )?.let { throw it }
    }

    private fun reportRuntimeFailure(error: Throwable) {
        runtimeContext?.reportFailure(error)
    }

    private fun admitChildLifecycleAction(admission: () -> Boolean): Boolean =
        !forceStopRequested.get() &&
            childFailure.get() == null &&
            admission()

    private fun throwIfChildFailed() {
        childFailure.get()?.let { throw it }
    }

    private fun eventComponentGroupSnapshot(): RuntimeComponentGroup? =
        synchronized(childLifecycleMonitor) {
            eventComponentGroup
        }
}
