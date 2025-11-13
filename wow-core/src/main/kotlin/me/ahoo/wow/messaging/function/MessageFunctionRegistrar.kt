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
 * Provides functionality to register/unregister functions and find functions
 * that support processing specific messages.
 *
 * @param F The type of message function being registered
 */
interface MessageFunctionRegistrar<F : MessageFunction<*, *, *>> {
    /**
     * The set of all registered functions.
     */
    val functions: Set<F>

    /**
     * Registers a message function.
     *
     * @param function The function to register
     */
    fun register(function: F)

    /**
     * Unregisters a message function.
     *
     * @param function The function to unregister
     */
    fun unregister(function: F)

    /**
     * Finds all functions that support processing the given message.
     *
     * @param message The message to find supporting functions for
     * @return A sequence of functions that support this message
     */
    fun <M> supportedFunctions(message: M): Sequence<F>
        where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate
}
