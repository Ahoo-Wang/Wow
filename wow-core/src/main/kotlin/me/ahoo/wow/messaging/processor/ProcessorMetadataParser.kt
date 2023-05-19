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

import me.ahoo.wow.api.annotation.OnMessage
import me.ahoo.wow.configuration.asRequiredNamedBoundedContext
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.messaging.function.MethodFunctionMetadata
import me.ahoo.wow.messaging.function.asMonoFunctionMetadata
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.naming.annotation.asName
import reactor.core.publisher.Mono
import java.lang.reflect.Method

/**
 * sess [me.ahoo.wow.api.annotation.OnMessage]
 */
open class ProcessorMetadataParser<OM : Annotation, M : MessageExchange<*, *>>(
    private val onMessageType: Class<OM>,
    private val functionCondition: (Method) -> Boolean = { true },
) : CacheableMetadataParser<Class<*>, ProcessorMetadata<*, *>>() {

    override fun parseAsMetadata(type: Class<*>): ProcessorMetadata<*, *> {
        @Suppress("UNCHECKED_CAST")
        val visitor = ProcessorMetadataVisitor<Any, OM, M>(type as Class<Any>, onMessageType, functionCondition)
        ClassMetadata.visit(type, visitor)
        return visitor.asMetadata()
    }
}

internal class ProcessorMetadataVisitor<P : Any, OM : Annotation, M : MessageExchange<*, *>>(
    private val processorType: Class<P>,
    private val onMessageType: Class<OM>,
    private val functionCondition: (Method) -> Boolean,
) : ClassVisitor {
    private val onMessage: OnMessage = onMessageType.scan()!!
    private val functionRegistry: MutableSet<MethodFunctionMetadata<P, Mono<*>>> = mutableSetOf()

    override fun visitMethod(method: Method) {
        if (method.parameterCount == 0) {
            return
        }
        if (!method.isAnnotationPresent(onMessageType) &&
            onMessage.defaultHandlerName != method.name
        ) {
            return
        }

        if (!functionCondition(method)) {
            return
        }

        val handler = method.asMonoFunctionMetadata<P, Any>()
        functionRegistry.add(handler)
    }

    fun asMetadata(): ProcessorMetadata<P, M> {
        return ProcessorMetadata(
            namedBoundedContext = processorType.asRequiredNamedBoundedContext(),
            name = processorType.asName(),
            processorType = processorType,
            functionRegistry = functionRegistry,
        )
    }
}
