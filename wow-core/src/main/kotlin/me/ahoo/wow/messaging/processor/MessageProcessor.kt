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

interface MessageProcessor<P : Any, M : MessageExchange<*, *>, out R> : ProcessorInfo {
    fun process(exchange: M): R
}

interface ReactiveMessageProcessor<P : Any, M : MessageExchange<*, *>, out R : Publisher<*>> : MessageProcessor<P, M, R>

interface MonoMessageProcessor<P : Any, M : MessageExchange<*, *>, out R : Mono<*>> : ReactiveMessageProcessor<P, M, R>
