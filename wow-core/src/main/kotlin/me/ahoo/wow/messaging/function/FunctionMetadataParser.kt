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

import me.ahoo.wow.api.annotation.DEFAULT_ON_COMMAND_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_EVENT_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_SOURCING_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_STATE_EVENT_NAME
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnMessage
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.configuration.asNamedAggregate
import me.ahoo.wow.configuration.asNamedBoundedContext
import me.ahoo.wow.infra.accessor.method.MethodAccessor
import me.ahoo.wow.infra.accessor.method.SimpleMethodAccessor
import me.ahoo.wow.infra.accessor.method.reactive.asMonoMethodAccessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.asNamedAggregate
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

object FunctionMetadataParser {

    fun <P, R> Method.asFunctionMetadata(accessorFactory: (Method) -> MethodAccessor<P, R>): MethodFunctionMetadata<P, R> {
        val parameterTypes = parameterTypes
        check(parameterTypes.isNotEmpty()) { "The function has at least one parameter." }
        /*
         * 消息函数第一个参数必须为消息.
         */
        val firstParameterType = parameterTypes[0]
        val firstParameterKind = firstParameterType.asFirstParameterKind()
        val functionKind = asFunctionKind()
        val supportedType = asSupportedType(firstParameterKind, firstParameterType)
        val accessor = accessorFactory(this)
        val supportedTopics = asSupportedTopics(functionKind, supportedType)
        val injectParameterTypes = parameterTypes.copyOfRange(1, parameterTypes.size)
        return MethodFunctionMetadata(
            functionKind = functionKind,
            accessor = accessor,
            supportedType = supportedType,
            supportedTopics = supportedTopics,
            firstParameterKind = firstParameterKind,
            injectParameterTypes = injectParameterTypes,
        )
    }

    private fun Class<*>.asFirstParameterKind(): FirstParameterKind {
        return when {
            MessageExchange::class.java.isAssignableFrom(this) -> {
                FirstParameterKind.MESSAGE_EXCHANGE
            }

            Message::class.java.isAssignableFrom(this) -> {
                FirstParameterKind.MESSAGE
            }

            else -> {
                FirstParameterKind.MESSAGE_BODY
            }
        }
    }

    private fun Method.asFunctionKind(): FunctionKind {
        scan<OnMessage>()?.let {
            return it.functionKind
        }
        when (name) {
            DEFAULT_ON_COMMAND_NAME -> {
                return FunctionKind.COMMAND
            }

            DEFAULT_ON_SOURCING_NAME -> {
                return FunctionKind.SOURCING
            }

            DEFAULT_ON_EVENT_NAME -> {
                return FunctionKind.EVENT
            }

            DEFAULT_ON_STATE_EVENT_NAME -> {
                return FunctionKind.STATE_EVENT
            }
        }
        throw IllegalStateException("The method [$declaringClass.$name] is not annotated by @OnMessage.")
    }

    private fun Method.asSupportedType(firstParameterKind: FirstParameterKind, firstParameterType: Class<*>): Class<*> {
        return when (firstParameterKind) {
            FirstParameterKind.MESSAGE_EXCHANGE, FirstParameterKind.MESSAGE -> {
                val messageWrappedBodyType = genericParameterTypes[0]
                val parameterizedType = messageWrappedBodyType as ParameterizedType
                parameterizedType.actualTypeArguments[0] as Class<*>
            }

            FirstParameterKind.MESSAGE_BODY -> {
                firstParameterType
            }
        }
    }

    private fun Method.asSupportedTopics(functionKind: FunctionKind, supportedType: Class<*>): Set<Any> {
        return when (functionKind) {
            FunctionKind.EVENT -> {
                val onEvent = scan<OnEvent>()
                return parseEventTopics(supportedType, onEvent?.value)
            }

            FunctionKind.STATE_EVENT -> {
                val onStateEvent = scan<OnStateEvent>()
                return parseEventTopics(supportedType, onStateEvent?.value)
            }

            else -> setOf()
        }
    }

    private fun Method.parseEventTopics(
        bodyType: Class<*>,
        aggregateNames: Array<out String>?
    ): Set<Any> {
        if (aggregateNames.isNullOrEmpty()) {
            return bodyType.typeAsTopics()
        }
        val namedBoundedContext =
            bodyType.asNamedBoundedContext() ?: declaringClass.asNamedBoundedContext()
        return aggregateNames.map {
            it.asNamedAggregate(namedBoundedContext?.contextName)
        }.toSet()
    }

    private fun Class<*>.typeAsTopics(): Set<Any> {
        asNamedAggregate()?.let {
            return setOf(it)
        }
        return setOf()
    }

    fun <P, R> Method.asFunctionMetadata(): MethodFunctionMetadata<P, R> {
        return this.asFunctionMetadata(::SimpleMethodAccessor)
    }

    fun <P, R> Method.asMonoFunctionMetadata(): MethodFunctionMetadata<P, Mono<R>> {
        return this.asFunctionMetadata {
            it.asMonoMethodAccessor()
        }
    }
}
