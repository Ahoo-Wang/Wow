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
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import reactor.core.publisher.Mono
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

class MessageAnnotationFunctionCondition(private vararg val onMessageAnnotations: KClass<out Annotation>) :
    (KFunction<*>) -> Boolean {
    private val defaultFunctionNames = onMessageAnnotations.mapNotNull {
        it.scanAnnotation<OnMessage>()?.defaultFunctionName
    }.toSet()

    override fun invoke(function: KFunction<*>): Boolean {
        if (function.parameters.isEmpty()) {
            return false
        }
        val annotated = onMessageAnnotations.any {
            function.annotations.any { annotation -> annotation.annotationClass == it }
        }
        if (annotated) {
            return true
        }
        return function.name in defaultFunctionNames
    }
}

/**
 * sess [me.ahoo.wow.api.annotation.OnMessage]
 */
open class ProcessorMetadataParser<E : MessageExchange<*, *>>(
    private val functionCondition: (KFunction<*>) -> Boolean = { true }
) : CacheableMetadataParser() {

    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val visitor = ProcessorMetadataVisitor<TYPE, E>(type, functionCondition)
        type.kotlin.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }
}

internal class ProcessorMetadataVisitor<P : Any, E : MessageExchange<*, *>>(
    private val processorType: Class<P>,
    private val functionCondition: (KFunction<*>) -> Boolean
) : ClassVisitor<P, ProcessorMetadata<P, E>> {
    private val functionRegistry: MutableSet<FunctionAccessorMetadata<P, Mono<*>>> = mutableSetOf()

    override fun visitFunction(function: KFunction<*>) {
        if (!functionCondition(function)) {
            return
        }

        val handler = function.toMonoFunctionMetadata<P, Any>()
        functionRegistry.add(handler)
    }

    override fun toMetadata(): ProcessorMetadata<P, E> {
        return ProcessorMetadata(
            namedBoundedContext = processorType.requiredNamedBoundedContext(),
            name = processorType.simpleName,
            processorType = processorType,
            functionRegistry = functionRegistry,
        )
    }
}
