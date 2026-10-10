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

package me.ahoo.wow.metrics.decorator

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.LocalHandoff
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import reactor.core.publisher.Mono

/**
 * Meters a message bus under [component]: each send, each local hand-off and the receive stream of each subscription.
 * The command, domain event and state event buses differ only in their message types and [messageName].
 */
internal abstract class MetricMessageBus<M, E : MessageExchange<*, M>, T : MessageBus<M, E>>(
    delegate: T,
    metrics: WowMetrics,
    source: String,
    private val component: String,
) : MetricComponentDecorator<T>(delegate, metrics, source),
    MessageBus<M, E>
    where M : Message<*, *>, M : NamedAggregate {
    override fun send(message: M): Mono<Void> =
        metrics.operation(
            delegate.send(message),
            messageDescriptor(message, "send"),
        )

    override fun receiver(subscription: MessageSubscription): MessageReceiver<E> =
        delegate.receiver(subscription).mapMessages { messages ->
            metrics.stream(messages, receiveDescriptor(subscription), subscription.namedAggregates) { it.message }
        }

    /** Meters [handOff], the local hand-off of [message]. */
    protected fun meteredHandOff(
        message: M,
        handOff: Mono<LocalHandoff>,
    ): Mono<LocalHandoff> = metrics.operation(handOff, messageDescriptor(message, "send_if_subscribed"))

    /** The `message` tag of [message]; untagged by default. */
    protected open fun messageName(message: M): String = MetricDescriptor.NONE

    private fun messageDescriptor(
        message: M,
        operation: String,
    ): MetricDescriptor = descriptor(
        component = component,
        operation = operation,
        context = message.contextName,
        aggregate = message.aggregateName,
        message = messageName(message),
    )

    private fun receiveDescriptor(subscription: MessageSubscription): MetricDescriptor = descriptor(
        component = component,
        operation = "receive",
        context = subscription.metricContext(),
        aggregate = subscription.metricAggregate(),
        subscriber = subscription.receiverGroup,
    )

    override fun close() = delegate.close()
}
