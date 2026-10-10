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

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.handler.MessageExchange
import reactor.core.publisher.Mono

/**
 * Represents a message bus for sending and receiving messages in a distributed system.
 *
 * This interface provides the core functionality for message-based communication between
 * different components of the system. Implementations can be local or distributed.
 *
 * @param M The type of message being handled, must extend [Message]
 * @param E The type of message exchange, must extend [MessageExchange]
 */
interface MessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : AutoCloseable {
    /**
     * Closes the message bus and releases any resources.
     * Default implementation does nothing.
     */
    override fun close() = Unit

    /**
     * Sends a message through the message bus.
     *
     * @param message The message to send
     * @return A [Mono] that completes when the message has been sent
     */
    fun send(message: M): Mono<Void>

    /**
     * The one receive entry: a single message source for [subscription], with an explicit transport readiness
     * boundary and processing admission (see [MessageReceiver]).
     *
     * Cold transports whose subscription requires asynchronous initialization complete [MessageReceiver.readiness]
     * only when new messages can no longer be missed. A [runtime-owned][MessageSubscription.runtimeOwned]
     * subscription is a [me.ahoo.wow.runtime.WowRuntime] dispatcher's: local buses may let it take part in
     * local-first delivery receipts.
     */
    fun receiver(subscription: MessageSubscription): MessageReceiver<E>
}

/**
 * A local message bus that operates within a single JVM instance.
 *
 * This interface extends [MessageBus] and provides additional functionality
 * for monitoring subscriber counts in a local context.
 *
 * @param M The type of message being handled, must extend [Message]
 * @param E The type of message exchange, must extend [MessageExchange]
 */
interface LocalMessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : MessageBus<M, E> {
    /**
     * Returns the number of subscribers for the specified named aggregate.
     *
     * @param namedAggregate The named aggregate to check subscriber count for
     * @return The number of subscribers for the aggregate
     */
    fun subscriberCount(namedAggregate: NamedAggregate): Int

    /**
     * Hands [message] to the local receivers, only while every routed receiver is subscribed and open for processing.
     *
     * Completes as soon as the message entered the local sink ([LocalHandoff.accepted]) or could not
     * ([LocalHandoff.REFUSED]: no routable receiver, a full or closed sink); it never waits for a receiver's demand.
     * [LocalHandoff.admission] then tells whether every routed receiver admitted it. The conservative default refuses,
     * which disables local-first routing.
     */
    fun handOff(message: M): Mono<LocalHandoff> = Mono.just(LocalHandoff.REFUSED)
}

/**
 * A distributed message bus that operates across multiple JVM instances or nodes.
 *
 * This interface extends [MessageBus] and is designed for scenarios where
 * message distribution needs to happen across a cluster or distributed system.
 *
 * @param M The type of message being handled, must extend [Message]
 * @param E The type of message exchange, must extend [MessageExchange]
 */
interface DistributedMessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : MessageBus<M, E>
