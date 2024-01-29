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
import me.ahoo.wow.api.annotation.DEFAULT_ON_ERROR_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_EVENT_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_SOURCING_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_STATE_EVENT_NAME
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnMessage
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.namedAggregate
import me.ahoo.wow.configuration.namedBoundedContext
import me.ahoo.wow.infra.accessor.method.MethodAccessor
import me.ahoo.wow.infra.accessor.method.SimpleMethodAccessor
import me.ahoo.wow.infra.accessor.method.reactive.toMonoMethodAccessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.toNamedAggregate
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

object FunctionMetadataParser {

    fun <P, R> Method.toFunctionMetadata(accessorFactory: (Method) -> MethodAccessor<P, R>): MethodFunctionMetadata<P, R> {
        val parameterTypes = parameterTypes
        check(parameterTypes.isNotEmpty()) { "The function has at least one parameter." }
        /*
         * 消息函数第一个参数必须为消息.
         */
        val firstParameterType = parameterTypes[0]
        val firstParameterKind = firstParameterType.toFirstParameterKind()
        val functionKind = toFunctionKind()
        val supportedType = toSupportedType(firstParameterKind, firstParameterType)
        val accessor = accessorFactory(this)
        val supportedTopics = toSupportedTopics(functionKind, supportedType)

        val injectParameterTypes = parameterTypes.copyOfRange(1, parameterTypes.size).mapIndexed { idx, parameterType ->
            val name = parameterAnnotations[idx + 1].firstOrNull {
                it is Name
            } as Name?
            InjectParameter(parameterType, name?.value.orEmpty())
        }.toTypedArray()
        return MethodFunctionMetadata(
            functionKind = functionKind,
            accessor = accessor,
            supportedType = supportedType,
            supportedTopics = supportedTopics,
            firstParameterKind = firstParameterKind,
            injectParameters = injectParameterTypes,
        )
    }

    private fun Class<*>.toFirstParameterKind(): FirstParameterKind {
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

    private fun Method.toFunctionKind(): FunctionKind {
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

            DEFAULT_ON_ERROR_NAME -> {
                return FunctionKind.ERROR
            }
        }
        throw IllegalStateException("The method [$declaringClass.$name] is not annotated by @OnMessage.")
    }

    private fun Method.toSupportedType(firstParameterKind: FirstParameterKind, firstParameterType: Class<*>): Class<*> {
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

    private fun Method.toSupportedTopics(functionKind: FunctionKind, supportedType: Class<*>): Set<NamedAggregate> {
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
    ): Set<NamedAggregate> {
        if (aggregateNames.isNullOrEmpty()) {
            return bodyType.typeAsTopics()
        }
        val namedBoundedContext =
            bodyType.namedBoundedContext() ?: declaringClass.namedBoundedContext()
        return aggregateNames.map {
            it.toNamedAggregate(namedBoundedContext?.contextName)
        }.toSet()
    }

    private fun Class<*>.typeAsTopics(): Set<NamedAggregate> {
        namedAggregate()?.let {
            return setOf(it)
        }
        return setOf()
    }

    fun <P, R> Method.toFunctionMetadata(): MethodFunctionMetadata<P, R> {
        return this.toFunctionMetadata(::SimpleMethodAccessor)
    }

    fun <P, R> Method.toMonoFunctionMetadata(): MethodFunctionMetadata<P, Mono<R>> {
        return this.toFunctionMetadata {
            it.toMonoMethodAccessor()
        }
    }
}
