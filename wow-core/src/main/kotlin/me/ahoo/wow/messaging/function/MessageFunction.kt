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

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.api.naming.QualifiedNamed
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.messaging.handler.MessageExchange

/**
 * Represents a message function that processes messages within a message exchange.
 * This interface defines the contract for functions that can handle specific message types and topics,
 * providing a way to invoke processing logic on message exchanges.
 *
 * @param P the type of the processor
 * @param M the type of the message exchange, contravariant
 * @param R the return type of the function
 */
interface MessageFunction<P : Any, in M : MessageExchange<*, *>, out R> :
    QualifiedNamed,
    FunctionInfo {
    /**
     * The class type of message bodies that this function can process.
     * Used to determine if a message is supported by checking if the message body is an instance of this type.
     */
    val supportedType: Class<*>

    /**
     * The set of named aggregates (topics) that this function supports.
     * The function will only process messages that match one of these aggregates.
     * @see me.ahoo.wow.api.annotation.OnEvent.value
     * @see me.ahoo.wow.api.annotation.OnStateEvent.value
     */
    val supportedTopics: Set<NamedAggregate>

    /**
     * The processor instance that contains the actual business logic for handling messages.
     */
    val processor: P

    /**
     * The name of the processor class, derived from the simple name of the processor's class.
     */
    override val processorName: String
        get() = processor::class.java.simpleName

    /**
     * The fully qualified name of the function, formatted as "processorName.functionName(supportedTypeSimpleName)".
     * This provides a unique identifier for the function.
     */
    override val qualifiedName: String
        get() = "$processorName.$name(${supportedType.simpleName})"

    /**
     * Checks if the given message is supported by this function.
     * A message is supported if its body is an instance of the supported type and its aggregate matches one of the supported topics.
     *
     * @param message the message to check
     * @return true if the message is supported, false otherwise
     */
    fun <M> supportMessage(message: M): Boolean
        where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate =
        supportedType.isInstance(message.body) &&
            supportedTopics.any {
                it.isSameAggregateName(message)
            }

    /**
     * Retrieves an annotation of the specified type from the function's metadata.
     *
     * @param annotationClass the class of the annotation to retrieve
     * @return the annotation instance if present, null otherwise
     */
    fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A?

    /**
     * Invokes the message function with the given exchange.
     * This is the primary method to execute the function's logic.
     *
     * @param exchange the message exchange to process
     * @return the result of the function execution
     */
    operator fun invoke(exchange: M): R

    /**
     * Handles the message exchange by invoking the function.
     * This method provides an alternative way to process the exchange.
     *
     * @param exchange the message exchange to handle
     * @return the result of handling the exchange
     */
    fun handle(exchange: M): R = invoke(exchange)
}

/**
 * An accessor-based implementation of MessageFunction that uses metadata to provide function information.
 * This interface extends MessageFunction and provides implementations for various properties and methods
 * by delegating to the underlying metadata.
 *
 * @param P the type of the processor
 * @param M the type of the message exchange, contravariant
 * @param R the return type of the function
 */
interface MessageFunctionAccessor<P : Any, in M : MessageExchange<*, *>, out R> : MessageFunction<P, M, R> {
    /**
     * The metadata containing information about the function, such as supported types, topics, and accessor details.
     */
    val metadata: FunctionAccessorMetadata<P, R>

    /**
     * The name of the bounded context, obtained from the metadata.
     */
    override val contextName: String get() = metadata.contextName

    /**
     * The set of supported topics, obtained from the metadata.
     */
    override val supportedTopics: Set<NamedAggregate> get() = metadata.supportedTopics

    /**
     * The kind of the function (e.g., command, event), obtained from the metadata.
     */
    override val functionKind: FunctionKind get() = metadata.functionKind

    /**
     * The supported message body type, obtained from the metadata.
     */
    override val supportedType: Class<*> get() = metadata.supportedType

    /**
     * The name of the processor, obtained from the metadata.
     */
    override val processorName: String get() = metadata.processorName

    /**
     * The name of the function, obtained from the metadata.
     */
    override val name: String get() = metadata.name

    /**
     * Retrieves an annotation from the function's accessor using reflection.
     *
     * @param annotationClass the class of the annotation to retrieve
     * @return the annotation instance if present, null otherwise
     */
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? =
        metadata.accessor.function.scanAnnotation(annotationClass.kotlin)
}

/**
 * Converts FunctionAccessorMetadata to a MessageFunctionAccessor.
 * Creates either a SimpleMessageFunctionAccessor or InjectableMessageFunctionAccessor based on whether
 * the function has injectable parameters.
 *
 * @param processor the processor instance to use
 * @return a MessageFunctionAccessor implementation
 *
 * Example usage:
 * ```
 * val metadata = FunctionAccessorMetadata(...)
 * val function = metadata.toMessageFunction(myProcessor)
 * ```
 */
fun <P : Any, M : MessageExchange<*, *>, R> FunctionAccessorMetadata<P, R>.toMessageFunction(
    processor: P
): MessageFunctionAccessor<P, M, R> =
    if (injectParameterLength == 0) {
        SimpleMessageFunctionAccessor(processor, this)
    } else {
        InjectableMessageFunctionAccessor(processor, this)
    }
