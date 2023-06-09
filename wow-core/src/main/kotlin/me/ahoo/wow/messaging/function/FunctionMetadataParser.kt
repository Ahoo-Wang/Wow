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
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnMessage
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.TopicKind
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
         * 处理函数第一个参数必须为消息.
         */
        val firstParameterType = parameterTypes[0]
        val firstParameterKind = when {
            MessageExchange::class.java.isAssignableFrom(firstParameterType) -> {
                FirstParameterKind.MESSAGE_EXCHANGE
            }

            Message::class.java.isAssignableFrom(firstParameterType) -> {
                FirstParameterKind.MESSAGE
            }

            else -> {
                FirstParameterKind.MESSAGE_BODY
            }
        }

        val bodyType = when (firstParameterKind) {
            FirstParameterKind.MESSAGE_EXCHANGE, FirstParameterKind.MESSAGE -> {
                val messageWrappedBodyType = genericParameterTypes[0]
                val parameterizedType = messageWrappedBodyType as ParameterizedType
                parameterizedType.actualTypeArguments[0] as Class<*>
            }

            FirstParameterKind.MESSAGE_BODY -> {
                firstParameterType
            }
        }

        val injectParameterTypes = parameterTypes.sliceArray(1 until parameterTypes.size)

        val topicKind = asTopicKind()
        val topics = when (topicKind) {
            TopicKind.EVENT_STREAM -> {
                parseOnEventTopics(bodyType)
            }

            else -> setOf()
        }
        return MethodFunctionMetadata(
            topicKind = topicKind,
            accessor = accessorFactory(this),
            supportedType = bodyType,
            supportedTopics = topics,
            firstParameterKind = firstParameterKind,
            injectParameterTypes = injectParameterTypes,
        )
    }

    private fun Method.asTopicKind(): TopicKind {
        scan<OnMessage>()?.let {
            return it.topicKind
        }
        when (name) {
            DEFAULT_ON_COMMAND_NAME -> {
                return TopicKind.COMMAND
            }

            DEFAULT_ON_SOURCING_NAME -> {
                return TopicKind.EVENT_STREAM
            }

            DEFAULT_ON_EVENT_NAME -> {
                return TopicKind.EVENT_STREAM
            }
        }
        throw IllegalStateException("The method [$declaringClass.$name] is not annotated by @OnMessage.")
    }

    private fun Method.parseOnEventTopics(bodyType: Class<*>): Set<Any> {
        val onEvent = scan<OnEvent>()
        return parseTopics(bodyType, onEvent?.value)
    }

    private fun Method.parseTopics(
        bodyType: Class<*>,
        aggregateNames: Array<out String>?
    ): Set<Any> {
        if (aggregateNames.isNullOrEmpty()) {
            return bodyType.asTopics()
        }
        val namedBoundedContext =
            bodyType.asNamedBoundedContext() ?: declaringClass.asNamedBoundedContext()
        return aggregateNames.map {
            it.asNamedAggregate(namedBoundedContext?.contextName)
        }.toSet()
    }

    private fun Class<*>.asTopics(): Set<Any> {
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
