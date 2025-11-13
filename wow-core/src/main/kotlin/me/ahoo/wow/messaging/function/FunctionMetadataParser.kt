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

import me.ahoo.wow.api.annotation.DEFAULT_AFTER_COMMAND_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_COMMAND_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_ERROR_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_EVENT_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_SOURCING_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_STATE_EVENT_NAME
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnMessage
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.namedAggregate
import me.ahoo.wow.configuration.namedBoundedContext
import me.ahoo.wow.infra.accessor.function.FunctionAccessor
import me.ahoo.wow.infra.accessor.function.SimpleFunctionAccessor
import me.ahoo.wow.infra.accessor.function.reactive.toMonoFunctionAccessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.toNamedAggregate
import reactor.core.publisher.Mono
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

/**
 * Parser for extracting function metadata from Kotlin reflection.
 *
 * Provides utilities to analyze Kotlin functions and create metadata objects
 * that describe how message functions should be invoked.
 */
object FunctionMetadataParser {
    /**
     * Parses a Kotlin function to create function accessor metadata.
     *
     * Analyzes the function's parameters, annotations, and context to determine
     * how it should be invoked as a message function.
     *
     * @param accessorFactory Factory function to create the function accessor
     * @return Metadata describing how to invoke this function
     * @throws IllegalStateException if the function is not properly annotated
     */
    fun <P, R> KFunction<*>.toFunctionMetadata(
        accessorFactory: (KFunction<*>) -> FunctionAccessor<P, R>
    ): FunctionAccessorMetadata<P, R> {
        val parameterTypes = valueParameters
        check(parameterTypes.isNotEmpty()) { "The function has at least one parameter." }
        /*
         * 消息函数第一个参数必须为消息.
         */
        val firstParameterType = parameterTypes[0]
        val firstParameterKind = firstParameterType.toFirstParameterKind()
        val functionKind = toFunctionKind()
        val supportedType = firstParameterType.toSupportedType(firstParameterKind)
        val accessor = accessorFactory(this)
        val supportedTopics = toSupportedTopics(functionKind, supportedType)

        val injectParameterTypes = parameterTypes.asSequence()
            .drop(1)
            .map {
                InjectParameter(it)
            }.toList()
            .toTypedArray()
        return FunctionAccessorMetadata(
            functionKind = functionKind,
            accessor = accessor,
            supportedType = supportedType,
            supportedTopics = supportedTopics,
            firstParameterKind = firstParameterKind,
            injectParameters = injectParameterTypes,
        )
    }

    private fun KParameter.toFirstParameterKind(): FirstParameterKind =
        when {
            type.isSubtypeOf(MessageExchange::class.starProjectedType) -> {
                FirstParameterKind.MESSAGE_EXCHANGE
            }

            type.isSubtypeOf(Message::class.starProjectedType) -> {
                FirstParameterKind.MESSAGE
            }

            else -> {
                FirstParameterKind.MESSAGE_BODY
            }
        }

    private fun KFunction<*>.toFunctionKind(): FunctionKind {
        scanAnnotation<OnMessage>()?.let {
            return it.functionKind
        }
        when (name) {
            DEFAULT_ON_COMMAND_NAME, DEFAULT_AFTER_COMMAND_NAME -> {
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
        throw IllegalStateException("The method [$$name] is not annotated by @OnMessage.")
    }

    private fun KParameter.toSupportedType(firstParameterKind: FirstParameterKind): Class<*> =
        when (firstParameterKind) {
            FirstParameterKind.MESSAGE_EXCHANGE, FirstParameterKind.MESSAGE -> {
                checkNotNull(type.arguments[0].type).jvmErasure.java
            }

            FirstParameterKind.MESSAGE_BODY -> {
                type.jvmErasure.java
            }
        }

    private fun KFunction<*>.toSupportedTopics(
        functionKind: FunctionKind,
        supportedType: Class<*>
    ): Set<NamedAggregate> {
        return when (functionKind) {
            FunctionKind.EVENT -> {
                val onEvent = scanAnnotation<OnEvent>()
                return parseEventTopics(supportedType, onEvent?.value)
            }

            FunctionKind.STATE_EVENT -> {
                val onStateEvent = scanAnnotation<OnStateEvent>()
                return parseEventTopics(supportedType, onStateEvent?.value)
            }

            else -> setOf()
        }
    }

    private fun KFunction<*>.parseEventTopics(
        bodyType: Class<*>,
        aggregateNames: Array<out String>?
    ): Set<NamedAggregate> {
        if (aggregateNames.isNullOrEmpty()) {
            return bodyType.typeAsTopics()
        }
        val namedBoundedContext =
            bodyType.namedBoundedContext() ?: javaMethod!!.declaringClass.namedBoundedContext()
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

    /**
     * Creates function metadata using a simple function accessor.
     *
     * @return Metadata for synchronous function invocation
     */
    fun <P, R> KFunction<*>.toFunctionMetadata(): FunctionAccessorMetadata<P, R> =
        this.toFunctionMetadata {
            SimpleFunctionAccessor(it)
        }

    /**
     * Creates function metadata for reactive Mono-returning functions.
     *
     * @return Metadata for reactive function invocation
     */
    fun <P, R> KFunction<*>.toMonoFunctionMetadata(): FunctionAccessorMetadata<P, Mono<R>> =
        this.toFunctionMetadata {
            it.toMonoFunctionAccessor()
        }
}
