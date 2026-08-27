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

package me.ahoo.wow.messaging.processor

import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.messaging.handler.MessageExchange
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono

/**
 * Base interface for message processors that handle message exchanges.
 *
 * Message processors are responsible for processing messages and producing results.
 * They can be synchronous or reactive depending on the result type.
 *
 * @param P The type of the processor instance
 * @param M The type of message exchange being processed
 * @param R The type of result produced by processing
 */
interface MessageProcessor<P : Any, M : MessageExchange<*, *>, out R> : ProcessorInfo {
    /**
     * Processes the given message exchange and returns a result.
     *
     * @param exchange The message exchange to process
     * @return The result of processing
     */
    fun process(exchange: M): R
}

/**
 * A reactive message processor that returns a Publisher result.
 *
 * This interface extends MessageProcessor for processors that return reactive streams.
 *
 * @param P The type of the processor instance
 * @param M The type of message exchange being processed
 * @param R The Publisher type result
 */
interface ReactiveMessageProcessor<P : Any, M : MessageExchange<*, *>, out R : Publisher<*>> : MessageProcessor<P, M, R>

/**
 * A message processor that returns a Mono result.
 *
 * This is a specialized reactive processor for single-value asynchronous results.
 *
 * @param P The type of the processor instance
 * @param M The type of message exchange being processed
 * @param R The Mono type result
 */
interface MonoMessageProcessor<P : Any, M : MessageExchange<*, *>, out R : Mono<*>> : ReactiveMessageProcessor<P, M, R>
