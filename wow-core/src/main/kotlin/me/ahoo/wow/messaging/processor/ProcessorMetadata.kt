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

import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.toMessageFunction
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metadata.Metadata
import reactor.core.publisher.Mono

/**
 * Metadata describing a message processor and its functions.
 *
 * Contains information about the processor class, its bounded context,
 * and the registry of functions it provides.
 *
 * @param P The processor type
 * @param M The message exchange type
 * @property namedBoundedContext The bounded context this processor belongs to
 * @property name The name of the processor
 * @property processorType The class of the processor
 * @property functionRegistry Set of function metadata for this processor
 */
data class ProcessorMetadata<P : Any, in M : MessageExchange<*, *>>(
    private val namedBoundedContext: NamedBoundedContext,
    override val name: String,
    val processorType: Class<P>,
    val functionRegistry: Set<FunctionAccessorMetadata<P, Mono<*>>>
) : NamedBoundedContext by namedBoundedContext,
    Named,
    Metadata {
    /**
     * Converts the function metadata registry to actual message functions.
     *
     * @param processor The processor instance to bind functions to
     * @return A set of message functions for this processor
     */
    fun toMessageFunctionRegistry(processor: P): Set<MessageFunction<P, M, Mono<*>>> =
        functionRegistry
            .map {
                it.toMessageFunction<P, M, Mono<*>>(processor)
            }.toSet()

    /**
     * Checks equality based on processor type.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessorMetadata<*, *>) return false

        return processorType == other.processorType
    }

    /**
     * Returns hash code based on processor type.
     */
    override fun hashCode(): Int = processorType.hashCode()

    /**
     * Returns a string representation of this metadata.
     */
    override fun toString(): String = "ProcessorMetadata(processorType=$processorType)"
}
