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

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.RoutingEventStore
import me.ahoo.wow.eventsourcing.snapshot.RoutingSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotHandler
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.projection.ProjectionHandler
import me.ahoo.wow.saga.stateless.StatelessSagaHandler

/** Explicit, instance-scoped component instrumentation entry point. */
class MetricDecoratorFactory(
    private val metrics: WowMetrics,
) {
    @Suppress("CyclomaticComplexMethod", "IMPLICIT_CAST_TO_ANY")
    fun decorate(
        component: Any,
        source: String,
    ): Any {
        require(source.isNotBlank()) { "source must not be blank." }
        if (!metrics.enabled || component.containsMeteredDecorator()) {
            return component
        }
        val original = component.getOriginalDelegate()
        if (original is RoutingEventStore || original is RoutingSnapshotStore) {
            return component
        }
        val decorated = when (component) {
            is LocalFirstCommandBus -> component
            is CommandGateway -> component
            is LocalCommandBus -> MetricLocalCommandBus(component, metrics, source)
            is DistributedCommandBus -> MetricDistributedCommandBus(component, metrics, source)
            is LocalDomainEventBus -> MetricLocalDomainEventBus(component, metrics, source)
            is DistributedDomainEventBus -> MetricDistributedDomainEventBus(component, metrics, source)
            is LocalStateEventBus -> MetricLocalStateEventBus(component, metrics, source)
            is DistributedStateEventBus -> MetricDistributedStateEventBus(component, metrics, source)
            is EventStore -> MetricEventStore(component, metrics, source)
            is SnapshotStrategy -> MetricSnapshotStrategy(component, metrics, source)
            is SnapshotStore -> MetricSnapshotStore(component, metrics, source)
            is CommandHandler -> MetricCommandHandler(component, metrics, source)
            is SnapshotHandler -> MetricSnapshotHandler(component, metrics, source)
            is DomainEventHandler -> MetricDomainEventHandler(component, metrics, source)
            is StatelessSagaHandler -> MetricStatelessSagaHandler(component, metrics, source)
            is ProjectionHandler -> MetricProjectionHandler(component, metrics, source)
            else -> component
        }
        return decorated
    }
}

fun CommandBus.metered(
    metrics: WowMetrics,
    source: String,
): CommandBus = MetricDecoratorFactory(metrics).decorate(this, source) as CommandBus

fun DomainEventBus.metered(
    metrics: WowMetrics,
    source: String,
): DomainEventBus = MetricDecoratorFactory(metrics).decorate(this, source) as DomainEventBus

fun me.ahoo.wow.eventsourcing.state.StateEventBus.metered(
    metrics: WowMetrics,
    source: String,
): me.ahoo.wow.eventsourcing.state.StateEventBus =
    MetricDecoratorFactory(metrics).decorate(this, source) as me.ahoo.wow.eventsourcing.state.StateEventBus

fun EventStore.metered(
    metrics: WowMetrics,
    source: String,
): EventStore = MetricDecoratorFactory(metrics).decorate(this, source) as EventStore

fun SnapshotStore.metered(
    metrics: WowMetrics,
    source: String,
): SnapshotStore = MetricDecoratorFactory(metrics).decorate(this, source) as SnapshotStore

fun SnapshotStrategy.metered(
    metrics: WowMetrics,
    source: String,
): SnapshotStrategy = MetricDecoratorFactory(metrics).decorate(this, source) as SnapshotStrategy

fun CommandHandler.metered(
    metrics: WowMetrics,
    source: String,
): CommandHandler = MetricDecoratorFactory(metrics).decorate(this, source) as CommandHandler

fun SnapshotHandler.metered(
    metrics: WowMetrics,
    source: String,
): SnapshotHandler = MetricDecoratorFactory(metrics).decorate(this, source) as SnapshotHandler

fun DomainEventHandler.metered(
    metrics: WowMetrics,
    source: String,
): DomainEventHandler = MetricDecoratorFactory(metrics).decorate(this, source) as DomainEventHandler

fun StatelessSagaHandler.metered(
    metrics: WowMetrics,
    source: String,
): StatelessSagaHandler = MetricDecoratorFactory(metrics).decorate(this, source) as StatelessSagaHandler

fun ProjectionHandler.metered(
    metrics: WowMetrics,
    source: String,
): ProjectionHandler = MetricDecoratorFactory(metrics).decorate(this, source) as ProjectionHandler

private fun Any.containsMeteredDecorator(): Boolean {
    var current: Any = this
    while (true) {
        if (current is Metered) {
            return true
        }
        current = (current as? Decorator<*>)?.delegate ?: return false
    }
}
