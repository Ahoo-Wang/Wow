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

package me.ahoo.wow.messaging

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

/**
 * `receiver` is the one receive entry (9.3.0 T3); the deprecated `receive` and `runtimeReceiver` route through it,
 * and an implementation written before 9.3.0 against `receive` still serves `receiver`.
 */
@Suppress("DEPRECATION")
class MessageBusReceiveEntryTest {
    private val subscription = MessageSubscription(MaterializedNamedAggregate("sales", "Order"), "handler")

    @Test
    fun `receive streams the receiver messages with processing open`() {
        val bus = ReceiverOnlyBus()

        StepVerifier.create(bus.receive(subscription))
            .expectNextCount(2)
            .verifyComplete()

        bus.subscriptions.assert().containsExactly(subscription)
        bus.opened.assert().isEqualTo(1)
    }

    @Test
    fun `runtimeReceiver asks receiver for a runtime-owned subscription`() {
        val bus = ReceiverOnlyBus()

        bus.runtimeReceiver(subscription)

        bus.subscriptions.assert().containsExactly(subscription.copy(runtimeOwned = true))
    }

    @Test
    fun `an implementation of receive still serves receiver`() {
        val bus = ReceiveOnlyBus()

        StepVerifier.create(bus.receiver(subscription.copy(runtimeOwned = true)).messages)
            .expectNextCount(1)
            .verifyComplete()

        bus.subscriptions.assert().containsExactly(subscription.copy(runtimeOwned = true))
    }

    @Test
    fun `runtimeOwned defaults to false and survives copy`() {
        subscription.runtimeOwned.assert().isFalse()
        subscription.copy(runtimeOwned = true).copy(receiverGroup = "other").runtimeOwned.assert().isTrue()
    }

    private class ReceiverOnlyBus : MessageBus<Message<*, *>, MessageExchange<*, Message<*, *>>> {
        val subscriptions = mutableListOf<MessageSubscription>()
        var opened = 0

        override fun send(message: Message<*, *>): Mono<Void> = Mono.empty()

        @Suppress("UNCHECKED_CAST")
        override fun receiver(
            subscription: MessageSubscription,
        ): MessageReceiver<MessageExchange<*, Message<*, *>>> {
            subscriptions += subscription
            return MessageReceiver(
                messages = Flux.just("one", "two") as Flux<MessageExchange<*, Message<*, *>>>,
                processingAdmission = { opened++ },
            )
        }
    }

    private class ReceiveOnlyBus : MessageBus<Message<*, *>, MessageExchange<*, Message<*, *>>> {
        val subscriptions = mutableListOf<MessageSubscription>()

        override fun send(message: Message<*, *>): Mono<Void> = Mono.empty()

        @Suppress("UNCHECKED_CAST", "OVERRIDE_DEPRECATION")
        override fun receive(subscription: MessageSubscription): Flux<MessageExchange<*, Message<*, *>>> {
            subscriptions += subscription
            return Flux.just("legacy") as Flux<MessageExchange<*, Message<*, *>>>
        }
    }
}
