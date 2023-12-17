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
import me.ahoo.wow.configuration.requiredNamedBoundedContext
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.messaging.function.MethodFunctionMetadata
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metadata.CacheableMetadataParser
import reactor.core.publisher.Mono
import java.lang.reflect.Method

class MessageAnnotationFunctionCondition(private vararg val onMessageAnnotations: Class<out Annotation>) :
    (Method) -> Boolean {
    private val defaultFunctionNames = onMessageAnnotations.mapNotNull {
        it.scan<OnMessage>()?.defaultFunctionName
    }.toSet()

    override fun invoke(method: Method): Boolean {
        if (method.parameterCount == 0) {
            return false
        }
        val annotated = onMessageAnnotations.any {
            method.isAnnotationPresent(it)
        }
        if (annotated) {
            return true
        }
        return method.name in defaultFunctionNames
    }
}

/**
 * sess [me.ahoo.wow.api.annotation.OnMessage]
 */
open class ProcessorMetadataParser<M : MessageExchange<*, *>>(
    private val functionCondition: (Method) -> Boolean = { true }
) : CacheableMetadataParser<Class<*>, ProcessorMetadata<*, *>>() {

    override fun parseToMetadata(type: Class<*>): ProcessorMetadata<*, *> {
        @Suppress("UNCHECKED_CAST")
        val visitor = ProcessorMetadataVisitor<Any, M>(type as Class<Any>, functionCondition)
        ClassMetadata.visit(type, visitor)
        return visitor.toMetadata()
    }
}

internal class ProcessorMetadataVisitor<P : Any, M : MessageExchange<*, *>>(
    private val processorType: Class<P>,
    private val functionCondition: (Method) -> Boolean
) : ClassVisitor {
    private val functionRegistry: MutableSet<MethodFunctionMetadata<P, Mono<*>>> = mutableSetOf()

    override fun visitMethod(method: Method) {
        if (!functionCondition(method)) {
            return
        }

        val handler = method.toMonoFunctionMetadata<P, Any>()
        functionRegistry.add(handler)
    }

    fun toMetadata(): ProcessorMetadata<P, M> {
        return ProcessorMetadata(
            namedBoundedContext = processorType.requiredNamedBoundedContext(),
            name = processorType.simpleName,
            processorType = processorType,
            functionRegistry = functionRegistry,
        )
    }
}
