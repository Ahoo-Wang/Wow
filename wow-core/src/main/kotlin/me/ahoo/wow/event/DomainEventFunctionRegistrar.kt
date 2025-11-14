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

package me.ahoo.wow.event

import me.ahoo.wow.event.annotation.eventProcessorMetadata
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import reactor.core.publisher.Mono

/**
 * Abstract base class for event function registrars.
 *
 * This class provides a foundation for registering event processing functions.
 * It delegates to a MessageFunctionRegistrar and provides methods to register
 * processors, either directly as MessageFunction instances or by resolving
 * them from annotated classes.
 *
 * @property delegate The underlying message function registrar (default: SimpleMessageFunctionRegistrar)
 *
 * @see MessageFunctionRegistrar
 * @see MessageFunction
 * @see Decorator
 */
abstract class AbstractEventFunctionRegistrar(
    override val delegate: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> =
        SimpleMessageFunctionRegistrar()
) : MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> by delegate,
    Decorator<MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>> {
    /**
     * Registers an event processor.
     *
     * If the processor is already a MessageFunction, it registers it directly.
     * Otherwise, it resolves the processor into MessageFunction instances and registers them.
     *
     * @param processor The processor to register (MessageFunction or annotated class)
     *
     * @see MessageFunction
     * @see resolveProcessor
     */
    fun registerProcessor(processor: Any) {
        if (processor is MessageFunction<*, *, *>) {
            @Suppress("UNCHECKED_CAST")
            register(processor as MessageFunction<Any, DomainEventExchange<*>, Mono<*>>)
            return
        }
        resolveProcessor(processor).forEach {
            register(it)
        }
    }

    /**
     * Resolves a processor object into a set of message functions.
     *
     * This method must be implemented by subclasses to define how processors
     * (typically annotated classes) are converted to MessageFunction instances.
     *
     * @param processor The processor object to resolve
     * @return A set of message functions extracted from the processor
     *
     * @see MessageFunction
     */
    abstract fun resolveProcessor(processor: Any): Set<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>
}

/**
 * Registrar for domain event processing functions.
 *
 * This class extends AbstractEventFunctionRegistrar to provide domain event specific
 * function registration. It resolves processors by analyzing their annotations
 * and creating message functions for event handling.
 *
 * @param actual The underlying message function registrar (default: SimpleMessageFunctionRegistrar)
 *
 * @see AbstractEventFunctionRegistrar
 * @see DomainEventExchange
 * @see MessageFunction
 */
class DomainEventFunctionRegistrar(
    actual: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> =
        SimpleMessageFunctionRegistrar()
) : AbstractEventFunctionRegistrar(actual) {
    /**
     * Resolves a processor into domain event message functions.
     *
     * This method uses the processor's class annotations to create message functions
     * that can handle domain events.
     *
     * @param processor The processor object to resolve
     * @return A set of message functions for handling domain events
     *
     * @see AbstractEventFunctionRegistrar.resolveProcessor
     * @see eventProcessorMetadata
     */
    override fun resolveProcessor(processor: Any): Set<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> =
        processor.javaClass
            .eventProcessorMetadata()
            .toMessageFunctionRegistry(processor)
}
