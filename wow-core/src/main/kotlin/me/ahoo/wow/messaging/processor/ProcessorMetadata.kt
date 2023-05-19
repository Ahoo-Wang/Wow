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
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MethodFunctionMetadata
import me.ahoo.wow.messaging.function.asMessageFunction
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metadata.Metadata
import reactor.core.publisher.Mono

data class ProcessorMetadata<P : Any, M : MessageExchange<*, *>>(
    private val namedBoundedContext: NamedBoundedContext,
    override val name: String,
    val processorType: Class<P>,
    val functionRegistry: Set<MethodFunctionMetadata<P, Mono<*>>>,
) : NamedBoundedContext by namedBoundedContext, Named, Metadata {

    fun asMessageFunctionRegistry(processor: P): Set<MessageFunction<P, M, Mono<*>>> {
        return functionRegistry
            .map {
                it.asMessageFunction<P, M, Mono<*>>(processor)
            }.toSet()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessorMetadata<*, *>) return false

        if (processorType != other.processorType) return false

        return true
    }

    override fun hashCode(): Int {
        return processorType.hashCode()
    }

    override fun toString(): String {
        return "ProcessorMetadata(processorType=$processorType)"
    }
}
