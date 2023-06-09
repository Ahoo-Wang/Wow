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
package me.ahoo.wow.messaging.function

import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.messaging.handler.MessageExchange

data class SimpleMethodMessageFunction<P : Any, in M : MessageExchange<*, *>, out R>(
    override val processor: P,
    override val metadata: MethodFunctionMetadata<P, R>
) :
    MethodMessageFunction<P, M, R> {
    override val supportedTopics: Set<Any>
        get() = metadata.supportedTopics
    override val functionKind: FunctionKind
        get() = metadata.functionKind
    override fun handle(exchange: M): R {
        val firstArgument = metadata.extractFirstArgument(exchange)
        return metadata.accessor.invoke(processor, arrayOf(firstArgument))
    }

    override fun toString(): String {
        return "SimpleMethodMessageFunction(metadata=$metadata)"
    }
}

data class InjectableMethodMessageFunction<P : Any, in M : MessageExchange<*, *>, out R>(
    override val processor: P,
    override val metadata: MethodFunctionMetadata<P, R>
) :
    MethodMessageFunction<P, M, R> {
    override val supportedTopics: Set<Any>
        get() = metadata.supportedTopics
    override val functionKind: FunctionKind
        get() = metadata.functionKind

    override fun handle(exchange: M): R {
        val args = arrayOfNulls<Any>(1 + metadata.injectParameterLength)
        args[0] = metadata.extractFirstArgument(exchange)
        for (i in 0 until metadata.injectParameterLength) {
            val serviceType = metadata.injectParameterTypes[i]
            args[i + 1] = exchange.extractObject(serviceType)
        }
        return metadata.accessor.invoke(processor, args)
    }

    override fun toString(): String {
        return "InjectableMethodMessageFunction(metadata=$metadata)"
    }
}
