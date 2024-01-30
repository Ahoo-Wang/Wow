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

import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.FunctionKindCapable
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.messaging.handler.MessageExchange

interface MessageFunction<P : Any, in M : MessageExchange<*, *>, out R> :
    FunctionKindCapable,
    ProcessorInfo,
    Named {

    /**
     * Message body types supported by the message function.
     */
    val supportedType: Class<*>

    /**
     * @see me.ahoo.wow.api.annotation.OnEvent.value
     * @see me.ahoo.wow.api.annotation.OnStateEvent.value
     */
    val supportedTopics: Set<NamedAggregate>
    val processor: P
    override val processorName: String
        get() = processor::class.java.simpleName

    fun <M> supportMessage(message: M): Boolean
            where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate {
        return supportedType.isInstance(message.body) &&
                supportedTopics.any {
                    it.isSameAggregateName(message)
                }
    }

    fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A?
    operator fun invoke(exchange: M): R

    fun handle(exchange: M): R {
        return invoke(exchange)
    }
}

interface MethodMessageFunction<P : Any, in M : MessageExchange<*, *>, out R> : MessageFunction<P, M, R> {
    val metadata: MethodFunctionMetadata<P, R>
    override val contextName: String get() = metadata.contextName
    override val supportedTopics: Set<NamedAggregate> get() = metadata.supportedTopics
    override val functionKind: FunctionKind get() = metadata.functionKind
    override val supportedType: Class<*> get() = metadata.supportedType
    override val processorName: String get() = metadata.processorName
    override val name: String get() = metadata.name

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? {
        return metadata.accessor.method.getAnnotation(annotationClass)
    }
}

fun <P : Any, M : MessageExchange<*, *>, R> MethodFunctionMetadata<P, R>.toMessageFunction(processor: P): MethodMessageFunction<P, M, R> {
    return if (injectParameterLength == 0) {
        SimpleMethodMessageFunction(processor, this)
    } else {
        InjectableMethodMessageFunction(processor, this)
    }
}
