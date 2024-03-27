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

import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.FunctionKindCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.requiredNamedBoundedContext
import me.ahoo.wow.infra.accessor.function.FunctionAccessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.messaging.handler.MessageExchange
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.jvmErasure

enum class FirstParameterKind {
    MESSAGE_EXCHANGE,
    MESSAGE,
    MESSAGE_BODY
}

data class InjectParameter(val parameter: KParameter) {
    val javaType: Class<*> by lazy {
        parameter.type.jvmErasure.java
    }
    val name = parameter.scanAnnotation<Name>()?.value.orEmpty()
}

data class FunctionAccessorMetadata<P, out R>(
    override val functionKind: FunctionKind,
    val accessor: FunctionAccessor<P, R>,
    val supportedType: Class<*>,
    val supportedTopics: Set<NamedAggregate>,
    val firstParameterKind: FirstParameterKind,
    val injectParameters: Array<InjectParameter>
) : FunctionKindCapable, NamedBoundedContext, Named {
    val injectParameterLength: Int = injectParameters.size
    val processorType: Class<P> = accessor.targetType
    val processorName = checkNotNull(processorType.simpleName)
    override val name: String = "$processorName.${accessor.method.name}(${supportedType.simpleName})"
    override val contextName: String = processorType.requiredNamedBoundedContext().contextName

    fun extractFirstArgument(exchange: MessageExchange<*, *>): Any {
        return when (firstParameterKind) {
            FirstParameterKind.MESSAGE_EXCHANGE -> exchange
            FirstParameterKind.MESSAGE -> exchange.message
            FirstParameterKind.MESSAGE_BODY -> exchange.message.body as Any
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FunctionAccessorMetadata<*, *>) return false

        return accessor == other.accessor
    }

    override fun hashCode(): Int {
        return accessor.hashCode()
    }

    override fun toString(): String {
        return "FunctionAccessorMetadata(accessor=$accessor)"
    }
}
