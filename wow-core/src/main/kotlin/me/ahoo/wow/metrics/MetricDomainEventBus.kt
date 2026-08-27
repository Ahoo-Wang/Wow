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
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal open class MetricDomainEventBus<T : DomainEventBus>(
    delegate: T,
    metrics: WowMetrics,
    source: String,
) : MetricComponentDecorator<T>(delegate, metrics, source),
    DomainEventBus {
    override fun send(message: DomainEventStream): Mono<Void> =
        metrics.operation(
            delegate.send(message),
            messageDescriptor(message, "send"),
        )

    override fun receive(subscription: MessageSubscription): Flux<EventStreamExchange> =
        metrics.stream(
            delegate.receive(subscription),
            receiveDescriptor(subscription),
        )

    override fun receiver(subscription: MessageSubscription): MessageReceiver<EventStreamExchange> =
        metricReceiver(delegate.receiver(subscription), subscription)

    override fun runtimeReceiver(subscription: MessageSubscription): MessageReceiver<EventStreamExchange> =
        metricReceiver(delegate.runtimeReceiver(subscription), subscription)

    private fun metricReceiver(
        receiver: MessageReceiver<EventStreamExchange>,
        subscription: MessageSubscription,
    ): MessageReceiver<EventStreamExchange> =
        receiver.mapMessages { messages ->
            metrics.stream(messages, receiveDescriptor(subscription))
        }

    protected fun messageDescriptor(
        message: DomainEventStream,
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
        const val COMPONENT = "domain_event_bus"
    }
}

internal class MetricLocalDomainEventBus(
    delegate: LocalDomainEventBus,
    metrics: WowMetrics,
    source: String,
) : MetricDomainEventBus<LocalDomainEventBus>(delegate, metrics, source),
    LocalDomainEventBus {
    override fun sendIfSubscribed(message: DomainEventStream): Mono<Boolean> =
        metrics.operation(
            delegate.sendIfSubscribed(message),
            messageDescriptor(message, "send_if_subscribed"),
        )

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

internal class MetricDistributedDomainEventBus(
    delegate: DistributedDomainEventBus,
    metrics: WowMetrics,
    source: String,
) : MetricDomainEventBus<DistributedDomainEventBus>(delegate, metrics, source),
    DistributedDomainEventBus
