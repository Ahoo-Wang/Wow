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
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.requiredNamedBoundedContext
import me.ahoo.wow.infra.accessor.function.FunctionAccessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.messaging.handler.MessageExchange
import kotlin.reflect.KParameter
import kotlin.reflect.KType

/**
 * Enumeration of the different kinds of first parameters that message functions can accept.
 */
enum class FirstParameterKind {
    /**
     * The function accepts a MessageExchange as its first parameter.
     */
    MESSAGE_EXCHANGE,

    /**
     * The function accepts a Message as its first parameter.
     */
    MESSAGE,

    /**
     * The function accepts the message body directly as its first parameter.
     */
    MESSAGE_BODY
}

/**
 * Represents a parameter that can be injected into a message function.
 *
 * @property parameter The Kotlin parameter reflection object
 * @property type The type of the parameter (lazy-loaded)
 * @property name The name of the parameter from @Name annotation, or empty if not annotated
 */
data class InjectParameter(
    val parameter: KParameter
) {
    /**
     * The Kotlin type of the parameter.
     */
    val type: KType by lazy {
        parameter.type
    }

    /**
     * The name of the parameter, extracted from @Name annotation if present.
     */
    val name by lazy {
        parameter.scanAnnotation<Name>()?.value.orEmpty()
    }
}

/**
 * Metadata describing a message function's properties and access patterns.
 *
 * Contains all the information needed to invoke a message function reflectively,
 * including supported types, topics, parameter kinds, and injection requirements.
 *
 * @param P The processor type
 * @param R The return type
 * @property functionKind The kind of function (COMMAND, EVENT, etc.)
 * @property accessor The function accessor for invocation
 * @property supportedType The class of supported message bodies
 * @property supportedTopics The set of supported named aggregates
 * @property firstParameterKind How the first parameter should be extracted
 * @property injectParameters Array of parameters that need dependency injection
 */
data class FunctionAccessorMetadata<P, out R>(
    override val functionKind: FunctionKind,
    val accessor: FunctionAccessor<P, R>,
    val supportedType: Class<*>,
    val supportedTopics: Set<NamedAggregate>,
    val firstParameterKind: FirstParameterKind,
    val injectParameters: Array<InjectParameter>
) : FunctionInfo,
    NamedBoundedContext {
    /**
     * The number of injectable parameters.
     */
    val injectParameterLength: Int = injectParameters.size

    /**
     * The class of the processor.
     */
    val processorType: Class<P> = accessor.targetType

    /**
     * The name of the processor class.
     */
    override val processorName = checkNotNull(processorType.simpleName)

    /**
     * The name of the function/method.
     */
    override val name: String = accessor.method.name

    /**
     * The bounded context name that this function belongs to.
     */
    override val contextName: String = processorType.requiredNamedBoundedContext().contextName

    /**
     * Extracts the first argument for function invocation based on the parameter kind.
     *
     * @param exchange The message exchange to extract from
     * @return The appropriate first argument for the function
     */
    fun extractFirstArgument(exchange: MessageExchange<*, *>): Any =
        when (firstParameterKind) {
            FirstParameterKind.MESSAGE_EXCHANGE -> exchange
            FirstParameterKind.MESSAGE -> exchange.message
            FirstParameterKind.MESSAGE_BODY -> exchange.message.body as Any
        }

    /**
     * Checks equality based on the accessor, since that's the unique identifier.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FunctionAccessorMetadata<*, *>) return false

        return accessor == other.accessor
    }

    /**
     * Returns hash code based on the accessor.
     */
    override fun hashCode(): Int = accessor.hashCode()

    /**
     * Returns a string representation of this metadata.
     */
    override fun toString(): String = "FunctionAccessorMetadata(accessor=$accessor)"
}
