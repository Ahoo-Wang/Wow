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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext

/**
 * Registry for message functions that can be registered and queried.
 *
 * This interface provides a centralized mechanism for managing message functions within the Wow framework.
 * It allows registration and unregistration of functions, as well as querying for functions that can process
 * specific messages. The registry supports filtering to create scoped registrars for specific use cases.
 *
 * Example usage:
 * ```kotlin
 * val registrar = MessageFunctionRegistrar<MyMessageFunction>()
 * val myFunction = MyMessageFunction()
 * registrar.register(myFunction)
 *
 * val message = MyMessage()
 * val supported = registrar.supportedFunctions(message)
 * supported.forEach { it.process(message) }
 * ```
 *
 * @param F The type of message function being registered, constrained to implement MessageFunction<*, *, *>
 * @see MessageFunction for the base interface of message functions
 */
interface MessageFunctionRegistrar<F : MessageFunction<*, *, *>> {
    /**
     * The immutable set of all currently registered functions.
     *
     * This property provides read-only access to the functions managed by this registrar.
     * The set is immutable to prevent external modifications and ensure thread safety.
     *
     * @return A set containing all registered functions of type F
     */
    val functions: Set<F>

    /**
     * Registers a message function with this registrar.
     *
     * Adds the specified function to the registry, making it available for message processing queries.
     * If the function is already registered, this operation may be idempotent depending on the implementation.
     *
     * @param function The message function to register, must not be null
     * @throws IllegalArgumentException if the function is null or invalid for registration
     */
    fun register(function: F)

    /**
     * Unregisters a message function from this registrar.
     *
     * Removes the specified function from the registry, preventing it from being queried for message processing.
     * If the function is not currently registered, this operation may be a no-op depending on the implementation.
     *
     * @param function The message function to unregister, must not be null
     * @throws IllegalArgumentException if the function is null or invalid for unregistration
     */
    fun unregister(function: F)

    /**
     * Creates a filtered view of this registrar.
     *
     * Returns a new MessageFunctionRegistrar instance that only includes functions matching the provided predicate.
     * This allows for creating scoped registrars without modifying the original registry.
     *
     * @param predicate A function that tests each registered function; only functions for which the predicate returns true are included
     * @return A new MessageFunctionRegistrar containing only the functions that match the predicate
     * @throws IllegalArgumentException if the predicate is null
     */
    fun filter(predicate: (F) -> Boolean): MessageFunctionRegistrar<F>

    /**
     * Finds all registered functions that support processing the given message.
     *
     * Queries the registry for functions capable of handling the specified message.
     * The message must implement Message<*, Any>, NamedBoundedContext, and NamedAggregate interfaces.
     * The returned sequence is lazy and may be empty if no functions support the message.
     *
     * @param M The type of the message, constrained by the required interfaces
     * @param message The message instance to find supporting functions for, must not be null
     * @return A sequence of functions of type F that can process the message
     * @throws IllegalArgumentException if the message is null or does not meet the type constraints
     */
    fun <M> supportedFunctions(message: M): Sequence<F>
        where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate
}
