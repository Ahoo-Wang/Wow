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

import me.ahoo.wow.api.annotation.DEFAULT_ON_EVENT_NAME
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.configuration.asNamedBoundedContext
import me.ahoo.wow.event.annotation.asEventMetadata
import me.ahoo.wow.infra.accessor.method.MethodAccessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.asNamedAggregate
import me.ahoo.wow.modeling.matedata.MetadataNamedAggregateGetter
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

object FunctionMetadataParser {

    fun <P, R> parse(method: Method, accessorFactory: (Method) -> MethodAccessor<P, R>): MethodFunctionMetadata<P, R> {
        val parameterTypes = method.parameterTypes
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
                val messageWrappedBodyType = method.genericParameterTypes[0]
                val parameterizedType = messageWrappedBodyType as ParameterizedType
                parameterizedType.actualTypeArguments[0] as Class<*>
            }

            FirstParameterKind.MESSAGE_BODY -> {
                firstParameterType
            }
        }

        val injectParameterTypes = parameterTypes.sliceArray(1 until parameterTypes.size)

        val topics = parseOnEventTopics(method, bodyType)
        return MethodFunctionMetadata(
            accessor = accessorFactory(method),
            supportedType = bodyType,
            supportedTopics = topics,
            firstParameterKind = firstParameterKind,
            injectParameterTypes = injectParameterTypes,
        )
    }

    private fun parseOnEventTopics(method: Method, bodyType: Class<*>): Set<Any> {
        val onEvent = method.scan<OnEvent>()
        if (onEvent == null && method.name != DEFAULT_ON_EVENT_NAME) {
            return emptySet()
        }

        return if (onEvent != null && onEvent.value.isNotEmpty()) {
            val namedBoundedContext =
                bodyType.asNamedBoundedContext() ?: method.declaringClass.asNamedBoundedContext()
            onEvent.value.map {
                it.asNamedAggregate(namedBoundedContext?.contextName)
            }.toSet()
        } else {
            val namedAggregateGetter = bodyType.asEventMetadata().namedAggregateGetter
            require(namedAggregateGetter is MetadataNamedAggregateGetter<*>)
            return setOf(namedAggregateGetter.namedAggregate)
        }
    }
}

fun <P, R> Method.asFunctionMetadata(accessorFactory: (Method) -> MethodAccessor<P, R>): MethodFunctionMetadata<P, R> {
    return FunctionMetadataParser.parse(this, accessorFactory)
}
