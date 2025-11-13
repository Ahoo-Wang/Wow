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

package me.ahoo.wow.projection

import me.ahoo.wow.event.AbstractEventFunctionRegistrar
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import me.ahoo.wow.projection.annotation.projectionProcessorMetadata
import reactor.core.publisher.Mono

/**
 * Registrar for projection functions that creates message functions from processors
 * annotated with projection metadata.
 *
 * @property actual The underlying message function registrar (default: [SimpleMessageFunctionRegistrar]).
 */
class ProjectionFunctionRegistrar(
    actual: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> =
        SimpleMessageFunctionRegistrar()
) : AbstractEventFunctionRegistrar(actual) {
    /**
     * Resolves the processor by extracting projection metadata and creating message functions.
     * This method parses the processor's class for projection annotations and creates
     * corresponding message functions for event handling.
     *
     * @param processor The processor instance to resolve functions for.
     * @return A set of message functions for the processor.
     */
    override fun resolveProcessor(processor: Any): Set<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> =
        processor.javaClass
            .projectionProcessorMetadata()
            .toMessageFunctionRegistry(processor)
}
