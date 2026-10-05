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

package me.ahoo.wow.benchmark.scenario

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.LongAdder

/**
 * A distributed command bus that accepts and drops every message, so a local-first bus can be measured without a
 * broker. [sent] counts the distributed copies, which local-first still sends after local admission.
 */
class DiscardingDistributedCommandBus : DistributedCommandBus {
    val sent = LongAdder()

    override fun send(message: CommandMessage<*>): Mono<Void> = Mono.fromRunnable { sent.increment() }

    override fun receiver(subscription: MessageSubscription): MessageReceiver<ServerCommandExchange<*>> =
        MessageReceiver(Flux.never())
}

/**
 * The event-side counterpart of [DiscardingDistributedCommandBus].
 */
class DiscardingDistributedDomainEventBus : DistributedDomainEventBus {
    val sent = LongAdder()

    override fun send(message: DomainEventStream): Mono<Void> = Mono.fromRunnable { sent.increment() }

    override fun receiver(subscription: MessageSubscription): MessageReceiver<EventStreamExchange> =
        MessageReceiver(Flux.never())
}
