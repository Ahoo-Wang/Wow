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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal open class MetricStateEventBus<T : StateEventBus>(
    delegate: T,
    metrics: WowMetrics,
    source: String,
) : MetricComponentDecorator<T>(delegate, metrics, source),
    StateEventBus {
    override fun send(message: StateEvent<*>): Mono<Void> =
        metrics.operation(
            delegate.send(message),
            messageDescriptor(message, "send"),
        )

    override fun receive(subscription: MessageSubscription): Flux<StateEventExchange<*>> =
        metrics.stream(
            delegate.receive(subscription),
            receiveDescriptor(subscription),
        )

    override fun receiver(subscription: MessageSubscription): MessageReceiver<StateEventExchange<*>> =
        metricReceiver(delegate.receiver(subscription), subscription)

    override fun runtimeReceiver(subscription: MessageSubscription): MessageReceiver<StateEventExchange<*>> =
        metricReceiver(delegate.runtimeReceiver(subscription), subscription)

    private fun metricReceiver(
        receiver: MessageReceiver<StateEventExchange<*>>,
        subscription: MessageSubscription,
    ): MessageReceiver<StateEventExchange<*>> =
        receiver.mapMessages { messages ->
            metrics.stream(messages, receiveDescriptor(subscription))
        }

    protected fun messageDescriptor(
        message: StateEvent<*>,
        operation: String,
    ): MetricDescriptor = descriptor(
        component = COMPONENT,
        operation = operation,
        context = message.contextName,
        aggregate = message.aggregateName,
    )

    private fun receiveDescriptor(subscription: MessageSubscription): MetricDescriptor = descriptor(
        component = COMPONENT,
        operation = "receive",
        context = subscription.metricContext(),
        aggregate = subscription.metricAggregate(),
        subscriber = subscription.receiverGroup,
    )

    override fun close() = delegate.close()

    private companion object {
        const val COMPONENT = "state_event_bus"
    }
}

internal class MetricLocalStateEventBus(
    delegate: LocalStateEventBus,
    metrics: WowMetrics,
    source: String,
) : MetricStateEventBus<LocalStateEventBus>(delegate, metrics, source),
    LocalStateEventBus {
    override fun sendIfSubscribed(message: StateEvent<*>): Mono<Boolean> =
        metrics.operation(
            delegate.sendIfSubscribed(message),
            messageDescriptor(message, "send_if_subscribed"),
        )

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

internal class MetricDistributedStateEventBus(
    delegate: DistributedStateEventBus,
    metrics: WowMetrics,
    source: String,
) : MetricStateEventBus<DistributedStateEventBus>(delegate, metrics, source),
    DistributedStateEventBus
