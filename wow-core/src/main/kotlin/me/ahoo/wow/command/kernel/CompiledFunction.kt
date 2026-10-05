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

package me.ahoo.wow.command.kernel

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.messaging.function.FirstParameterKind
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.InjectParameter
import me.ahoo.wow.messaging.handler.MessageExchange
import kotlin.reflect.jvm.jvmErasure

/**
 * A handler function compiled once per aggregate type: the receiver (command root or state root) is an argument, so
 * one instance serves every aggregate instance and every call.
 *
 * Arguments resolve exactly as a bound message function resolves them: the first from the exchange by its
 * [FirstParameterKind], the others through [InjectedParameter].
 */
internal class CompiledFunction<P : Any, out R>(val metadata: FunctionAccessorMetadata<P, R>) {
    private val accessor = metadata.accessor
    private val parameters: Array<InjectedParameter> = Array(metadata.injectParameterLength) {
        InjectedParameter(metadata, metadata.injectParameters[it])
    }

    /** Whether the function reads anything beyond the message or its body, so a call needs a whole exchange. */
    val needsExchange: Boolean =
        metadata.firstParameterKind == FirstParameterKind.MESSAGE_EXCHANGE || parameters.isNotEmpty()

    fun invoke(receiver: P, exchange: MessageExchange<*, *>): R {
        val firstArgument = metadata.extractFirstArgument(exchange)
        if (parameters.isEmpty()) {
            return accessor.invoke1(receiver, firstArgument)
        }
        val args = arrayOfNulls<Any>(1 + parameters.size)
        args[0] = firstArgument
        for (index in parameters.indices) {
            args[index + 1] = parameters[index].resolve(exchange)
        }
        return accessor.invoke(receiver, args)
    }

    /** Calls a function that does not [need the exchange][needsExchange] with its first argument. */
    fun invoke1(receiver: P, firstArgument: Any): R = accessor.invoke1(receiver, firstArgument)

    override fun toString(): String = "CompiledFunction(metadata=$metadata)"
}

/**
 * A parameter after the first. A `@Name`d parameter is looked up by name in the exchange's service provider; any other
 * by type, first from the exchange ([MessageExchange.extractDeclared]) and then from the service provider. The type's
 * erasure is computed once.
 *
 * A parameter that resolves to nothing is injected as `null`, as before. When its type is not nullable that call will
 * most likely fail, so the first such miss logs a warning naming the function and the parameter (V7). Services are
 * known only once the container is up, so the check runs on first use rather than when the model compiles.
 */
internal class InjectedParameter(
    private val function: FunctionAccessorMetadata<*, *>,
    private val parameter: InjectParameter
) {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    private val name: String = parameter.name
    private val type = parameter.type

    @Suppress("UNCHECKED_CAST")
    private val erasure: Class<Any> = type.jvmErasure.java as Class<Any>
    private val required: Boolean = !type.isMarkedNullable

    @Volatile
    private var warned = false

    fun resolve(exchange: MessageExchange<*, *>): Any? {
        val value: Any? = if (name.isNotBlank()) {
            exchange.getServiceProvider()?.getService(name)
        } else {
            exchange.extractDeclared(erasure) ?: exchange.getServiceProvider()?.getService(type)
        }
        if (value == null && required && !warned) {
            warned = true
            log.warn {
                "Parameter[${parameter.parameter.name}: $type] of ${function.processorName}.${function.name} " +
                    "resolved to nothing and is injected as null: neither the exchange nor the service provider " +
                    "supplies it."
            }
        }
        return value
    }
}
