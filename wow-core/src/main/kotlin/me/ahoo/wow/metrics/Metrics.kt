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

package me.ahoo.wow.metrics

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotHandler
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.projection.ProjectionHandler
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import reactor.core.publisher.Flux
import reactor.util.context.Context
import reactor.util.context.ContextView
import kotlin.jvm.optionals.getOrNull

/**
 * Central utility object for metrics collection and tagging in the Wow framework.
 * Provides constants for metric keys, extension functions for reactive streams tagging,
 * and factory methods to wrap components with metric decorators.
 *
 * This object serves as the main entry point for enabling metrics collection across
 * all Wow components like command buses, event buses, handlers, and repositories.
 */
@Suppress("TooManyFunctions")
object Metrics {
    /** Metric tag key for aggregate names */
    const val AGGREGATE_KEY = "aggregate"

    /** Context key for storing metrics subscriber information in reactive contexts */
    const val SUBSCRIBER_CONTEXT_KEY = "(MetricsSubscriber)"

    /** Metric tag key for subscriber identification */
    const val SUBSCRIBER_KEY = "subscriber"

    /** Metric tag key for command names */
    const val COMMAND_KEY = "command"

    /** Metric tag key for source component identification */
    const val SOURCE_KEY = "source"

    /** Metric tag key for event names */
    const val EVENT_KEY = "event"

    /** Metric tag key for processor names */
    const val PROCESSOR_KEY = "processor"

    /**
     * Flag indicating whether metrics collection is enabled.
     * Can be controlled via the system property "wow.metrics.enabled" (defaults to true).
     */
    val enabled = System.getProperty("wow.metrics.enabled", "true").toBoolean()

    /**
     * Retrieves the metrics subscriber identifier from the reactive context.
     *
     * @return the metrics subscriber identifier, or null if not present
     */
    fun ContextView.getMetricsSubscriber(): String? = getOrEmpty<String>(SUBSCRIBER_CONTEXT_KEY).getOrNull()

    /**
     * Sets the metrics subscriber identifier in the reactive context.
     *
     * @param metricsSubscriber the subscriber identifier to store
     * @return the updated context
     */
    fun Context.setMetricsSubscriber(metricsSubscriber: String): Context =
        this.put(
            SUBSCRIBER_CONTEXT_KEY,
            metricsSubscriber,
        )

    /**
     * Writes the metrics subscriber identifier to the reactive context of a Flux stream.
     *
     * @param T the type of elements in the Flux
     * @param metricsSubscriber the subscriber identifier to write to context
     * @return the Flux with updated context
     */
    fun <T> Flux<T>.writeMetricsSubscriber(metricsSubscriber: String): Flux<T> =
        contextWrite {
            it.setMetricsSubscriber(metricsSubscriber)
        }

    /**
     * Tags a Flux stream with subscriber information from the reactive context.
     * If no subscriber is present in the context, applies default metrics without subscriber tagging.
     *
     * @param T the type of elements in the Flux
     * @return the tagged Flux stream
     */
    fun <T> Flux<T>.tagMetricsSubscriber(): Flux<T> {
        return Flux.deferContextual {
            val metricsSubscriber = it.getMetricsSubscriber() ?: return@deferContextual this.metrics()
            tag(SUBSCRIBER_KEY, metricsSubscriber).metrics()
        }
    }

    /**
     * Wraps a LocalCommandBus with metrics collection capabilities.
     * Returns a MetricLocalCommandBus that collects metrics on command operations.
     *
     * @return the metrizable local command bus
     */
    fun LocalCommandBus.metrizable(): LocalCommandBus =
        metrizable {
            MetricLocalCommandBus(this)
        }

    /**
     * Wraps a DistributedCommandBus with metrics collection capabilities.
     * Returns a MetricDistributedCommandBus that collects metrics on command operations.
     *
     * @return the metrizable distributed command bus
     */
    fun DistributedCommandBus.metrizable(): DistributedCommandBus =
        metrizable {
            MetricDistributedCommandBus(this)
        }

    /**
     * Wraps a LocalDomainEventBus with metrics collection capabilities.
     * Returns a MetricLocalDomainEventBus that collects metrics on domain event operations.
     *
     * @return the metrizable local domain event bus
     */
    fun LocalDomainEventBus.metrizable(): LocalDomainEventBus =
        metrizable {
            MetricLocalDomainEventBus(this)
        }

    /**
     * Wraps a DistributedDomainEventBus with metrics collection capabilities.
     * Returns a MetricDistributedDomainEventBus that collects metrics on domain event operations.
     *
     * @return the metrizable distributed domain event bus
     */
    fun DistributedDomainEventBus.metrizable(): DistributedDomainEventBus =
        metrizable {
            MetricDistributedDomainEventBus(this)
        }

    /**
     * Wraps a LocalStateEventBus with metrics collection capabilities.
     * Returns a MetricLocalStateEventBus that collects metrics on state event operations.
     *
     * @return the metrizable local state event bus
     */
    fun LocalStateEventBus.metrizable(): LocalStateEventBus =
        metrizable {
            MetricLocalStateEventBus(this)
        }

    /**
     * Wraps a DistributedStateEventBus with metrics collection capabilities.
     * Returns a MetricDistributedStateEventBus that collects metrics on state event operations.
     *
     * @return the metrizable distributed state event bus
     */
    fun DistributedStateEventBus.metrizable(): DistributedStateEventBus =
        metrizable {
            MetricDistributedStateEventBus(this)
        }

    /**
     * Wraps an EventStore with metrics collection capabilities.
     * Returns a MetricEventStore that collects metrics on event storage operations.
     *
     * @return the metrizable event store
     */
    fun EventStore.metrizable(): EventStore =
        metrizable {
            MetricEventStore(this)
        }

    /**
     * Wraps a SnapshotStrategy with metrics collection capabilities.
     * Returns a MetricSnapshotStrategy that collects metrics on snapshot strategy operations.
     *
     * @return the metrizable snapshot strategy
     */
    fun SnapshotStrategy.metrizable(): SnapshotStrategy =
        metrizable {
            MetricSnapshotStrategy(this)
        }

    /**
     * Wraps a SnapshotRepository with metrics collection capabilities.
     * Returns a MetricSnapshotRepository that collects metrics on snapshot storage operations.
     *
     * @return the metrizable snapshot repository
     */
    fun SnapshotRepository.metrizable(): SnapshotRepository =
        metrizable {
            MetricSnapshotRepository(this)
        }

    /**
     * Wraps a CommandHandler with metrics collection capabilities.
     * Returns a MetricCommandHandler that collects metrics on command handling operations.
     *
     * @return the metrizable command handler
     */
    fun CommandHandler.metrizable(): CommandHandler =
        metrizable {
            MetricCommandHandler(this)
        }

    /**
     * Wraps a SnapshotHandler with metrics collection capabilities.
     * Returns a MetricSnapshotHandler that collects metrics on snapshot handling operations.
     *
     * @return the metrizable snapshot handler
     */
    fun SnapshotHandler.metrizable(): SnapshotHandler =
        metrizable {
            MetricSnapshotHandler(this)
        }

    /**
     * Wraps a DomainEventHandler with metrics collection capabilities.
     * Returns a MetricDomainEventHandler that collects metrics on domain event handling operations.
     *
     * @return the metrizable domain event handler
     */
    fun DomainEventHandler.metrizable(): DomainEventHandler =
        metrizable {
            MetricDomainEventHandler(this)
        }

    /**
     * Wraps a StatelessSagaHandler with metrics collection capabilities.
     * Returns a MetricStatelessSagaHandler that collects metrics on saga handling operations.
     *
     * @return the metrizable stateless saga handler
     */
    fun StatelessSagaHandler.metrizable(): StatelessSagaHandler =
        metrizable {
            MetricStatelessSagaHandler(this)
        }

    /**
     * Wraps a ProjectionHandler with metrics collection capabilities.
     * Returns a MetricProjectionHandler that collects metrics on projection handling operations.
     *
     * @return the metrizable projection handler
     */
    fun ProjectionHandler.metrizable(): ProjectionHandler =
        metrizable {
            MetricProjectionHandler(this)
        }

    /**
     * Automatically wraps supported Wow components with their corresponding metric decorators.
     * This function inspects the type of the component and applies the appropriate metric wrapper
     * if metrics are enabled and the component is not already metrizable.
     *
     * Supported component types include:
     * - Command buses (LocalCommandBus, DistributedCommandBus)
     * - Event buses (LocalDomainEventBus, DistributedDomainEventBus, LocalStateEventBus, DistributedStateEventBus)
     * - Event stores, snapshot repositories, and strategies
     * - Various handlers (CommandHandler, DomainEventHandler, ProjectionHandler, etc.)
     *
     * Components that are already Metrizable or not supported are returned unchanged.
     *
     * @param T the type of the component
     * @return the component wrapped with metrics collection, or the original component if not applicable
     */
    @Suppress("CyclomaticComplexMethod", "IMPLICIT_CAST_TO_ANY", "UNCHECKED_CAST")
    fun <T : Any> T.metrizable(): T {
        val metrizableBean =
            when (this) {
                is LocalFirstCommandBus -> this
                is CommandGateway -> this
                is LocalCommandBus -> metrizable()
                is DistributedCommandBus -> metrizable()
                is LocalDomainEventBus -> metrizable()
                is DistributedDomainEventBus -> metrizable()
                is LocalStateEventBus -> metrizable()
                is DistributedStateEventBus -> metrizable()
                is EventStore -> metrizable()
                is SnapshotStrategy -> metrizable()
                is SnapshotRepository -> metrizable()
                is CommandHandler -> metrizable()
                is SnapshotHandler -> metrizable()
                is DomainEventHandler -> metrizable()
                is StatelessSagaHandler -> metrizable()
                is ProjectionHandler -> metrizable()
                else -> this
            }

        return metrizableBean as T
    }

    /**
     * Conditionally applies a metric decorator to a component based on configuration.
     * This function checks if metrics are enabled and if the component is not already metrizable
     * before applying the provided decoration block.
     *
     * @param T the type of the component
     * @param block the function that creates the metric decorator
     * @return the decorated component if metrics are enabled and component is not already metrizable,
     *         otherwise returns the original component
     */
    @Suppress("ReturnCount")
    inline fun <T> T.metrizable(block: (T) -> T): T {
        if (!enabled) {
            return this
        }
        if (this is Metrizable) {
            return this
        }
        return block(this)
    }
}
