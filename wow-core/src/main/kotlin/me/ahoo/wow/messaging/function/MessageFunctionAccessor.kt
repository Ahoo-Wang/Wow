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

import me.ahoo.wow.messaging.handler.MessageExchange

/**
 * A simple message function accessor for functions without injectable parameters.
 *
 * This accessor handles functions that only need the primary message parameter
 * and don't require dependency injection for additional parameters.
 *
 * @param P The processor type
 * @param M The message exchange type
 * @param R The return type
 * @property processor The processor instance
 * @property metadata The function metadata
 */
data class SimpleMessageFunctionAccessor<P : Any, in M : MessageExchange<*, *>, out R>(
    override val processor: P,
    override val metadata: FunctionAccessorMetadata<P, R>
) : MessageFunctionAccessor<P, M, R> {
    /**
     * Invokes the function with the message exchange.
     *
     * Extracts the first argument from the exchange and calls the underlying function
     * with just that single argument.
     *
     * @param exchange The message exchange to process
     * @return The result of the function invocation
     */
    override fun invoke(exchange: M): R {
        val firstArgument = metadata.extractFirstArgument(exchange)
        return metadata.accessor.invoke(processor, arrayOf(firstArgument))
    }

    override fun toString(): String = "SimpleMessageFunctionAccessor(metadata=$metadata)"
}

/**
 * A message function accessor for functions with injectable parameters.
 *
 * This accessor handles functions that require dependency injection for additional
 * parameters beyond the primary message parameter.
 *
 * @param P The processor type
 * @param M The message exchange type
 * @param R The return type
 * @property processor The processor instance
 * @property metadata The function metadata
 */
data class InjectableMessageFunctionAccessor<P : Any, in M : MessageExchange<*, *>, out R>(
    override val processor: P,
    override val metadata: FunctionAccessorMetadata<P, R>
) : MessageFunctionAccessor<P, M, R> {
    /**
     * Invokes the function with dependency injection for additional parameters.
     *
     * Extracts the first argument from the exchange, then resolves injectable parameters
     * either by name from the service provider or by type from the exchange.
     *
     * @param exchange The message exchange to process
     * @return The result of the function invocation
     */
    override fun invoke(exchange: M): R {
        val args = arrayOfNulls<Any>(1 + metadata.injectParameterLength)
        args[0] = metadata.extractFirstArgument(exchange)
        for (i in 0 until metadata.injectParameterLength) {
            val injectParameter = metadata.injectParameters[i]
            if (injectParameter.name.isNotBlank()) {
                args[i + 1] = exchange.getServiceProvider()?.getService(injectParameter.name)
            } else {
                args[i + 1] = exchange.extractObject(injectParameter.type)
            }
        }
        return metadata.accessor.invoke(processor, args)
    }

    override fun toString(): String = "InjectableMessageFunctionAccessor(metadata=$metadata)"
}
