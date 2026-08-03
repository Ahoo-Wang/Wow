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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal open class MetricCommandBus<T : CommandBus>(
    delegate: T,
    metrics: WowMetrics,
    source: String,
) : MetricComponentDecorator<T>(delegate, metrics, source),
    CommandBus {
    override fun send(message: CommandMessage<*>): Mono<Void> =
        metrics.operation(
            delegate.send(message),
            messageDescriptor(message, "send"),
        )

    override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> =
        metrics.stream(
            delegate.receive(subscription),
            receiveDescriptor(subscription),
        )

    override fun receiver(subscription: MessageSubscription): MessageReceiver<ServerCommandExchange<*>> =
        metricReceiver(delegate.receiver(subscription), subscription)

    override fun runtimeReceiver(subscription: MessageSubscription): MessageReceiver<ServerCommandExchange<*>> =
        metricReceiver(delegate.runtimeReceiver(subscription), subscription)

    private fun metricReceiver(
        receiver: MessageReceiver<ServerCommandExchange<*>>,
        subscription: MessageSubscription,
    ): MessageReceiver<ServerCommandExchange<*>> =
        receiver.mapMessages { messages ->
            metrics.stream(messages, receiveDescriptor(subscription))
        }

    protected fun messageDescriptor(
        message: CommandMessage<*>,
        operation: String,
    ): MetricDescriptor = descriptor(
        component = COMPONENT,
        operation = operation,
        context = message.contextName,
        aggregate = message.aggregateName,
        message = message.name,
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
        const val COMPONENT = "command_bus"
    }
}

internal class MetricLocalCommandBus(
    delegate: LocalCommandBus,
    metrics: WowMetrics,
    source: String,
) : MetricCommandBus<LocalCommandBus>(delegate, metrics, source),
    LocalCommandBus {
    override fun sendIfSubscribed(message: CommandMessage<*>): Mono<Boolean> =
        metrics.operation(
            delegate.sendIfSubscribed(message),
            messageDescriptor(message, "send_if_subscribed"),
        )

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

internal class MetricDistributedCommandBus(
    delegate: DistributedCommandBus,
    metrics: WowMetrics,
    source: String,
) : MetricCommandBus<DistributedCommandBus>(delegate, metrics, source),
    DistributedCommandBus
