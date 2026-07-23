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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Simple implementation of MessageFunctionRegistrar using a thread-safe set.
 *
 * Provides basic registration and lookup functionality for message functions
 * using a CopyOnWriteArraySet for thread safety.
 *
 * @param F The type of message function being registered
 */
class SimpleMessageFunctionRegistrar<F : MessageFunction<*, *, *>> : MessageFunctionRegistrar<F> {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Thread-safe set for storing registered functions.
     */
    private val registrar: CopyOnWriteArraySet<F> = CopyOnWriteArraySet()
    private val topicIndex: ConcurrentHashMap<MaterializedNamedAggregate, CopyOnWriteArraySet<F>> = ConcurrentHashMap()
    private val unindexedRegistrar: CopyOnWriteArraySet<F> = CopyOnWriteArraySet()
    private val mutationLock = Any()
    private val indexedTopics = HashMap<F, Set<MaterializedNamedAggregate>>()

    private fun index(
        function: F,
        supportedTopics: Set<MaterializedNamedAggregate>
    ) {
        if (supportedTopics.isEmpty()) {
            unindexedRegistrar.add(function)
            return
        }
        supportedTopics.forEach { topic ->
            topicIndex.compute(topic) { _, functions ->
                (functions ?: CopyOnWriteArraySet()).also {
                    it.add(function)
                }
            }
        }
    }

    private fun deindex(
        function: F,
        supportedTopics: Set<MaterializedNamedAggregate>
    ) {
        if (supportedTopics.isEmpty()) {
            unindexedRegistrar.remove(function)
            return
        }
        supportedTopics.forEach { topic ->
            topicIndex.computeIfPresent(topic) { _, functions ->
                functions.remove(function)
                if (functions.isEmpty()) {
                    null
                } else {
                    functions
                }
            }
        }
    }

    /**
     * Registers a function and logs the registration.
     *
     * @param function The function to register
     */
    override fun register(function: F) {
        log.info {
            "Register $function."
        }
        synchronized(mutationLock) {
            if (function in registrar) {
                return
            }
            val supportedTopics = function.supportedTopics
                .mapTo(LinkedHashSet()) { it.materialize() }
            if (registrar.add(function)) {
                indexedTopics[function] = supportedTopics
                index(function, supportedTopics)
            }
        }
    }

    /**
     * Unregisters a function and logs the unregistration.
     *
     * @param function The function to unregister
     */
    override fun unregister(function: F) {
        log.info {
            "Unregister $function."
        }
        synchronized(mutationLock) {
            if (registrar.remove(function)) {
                deindex(function, indexedTopics.remove(function).orEmpty())
            }
        }
    }

    override fun filter(predicate: (F) -> Boolean): MessageFunctionRegistrar<F> {
        val filteredRegistrar = SimpleMessageFunctionRegistrar<F>()
        val filteredFunctions = registrar.filter(predicate)
        filteredFunctions.forEach {
            filteredRegistrar.register(it)
        }
        return filteredRegistrar
    }

    /**
     * Returns the set of registered functions.
     */
    override val functions: Set<F>
        get() = registrar

    /**
     * Returns a sequence of functions that support the given message.
     *
     * Filters the registered functions to only include those that can handle
     * the message's type and aggregate.
     *
     * @param M The message type.
     * @param message The message to find supporting functions for
     * @return A sequence of functions that support this message
     */
    override fun <M> supportedFunctions(
        message: M
    ): Sequence<F>
        where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate {
        val topicFunctions = topicIndex[message.materialize()]
        if (topicFunctions.isNullOrEmpty()) {
            return unindexedRegistrar.asSequence()
                .filter {
                    it.supportMessage(message)
                }
        }
        if (unindexedRegistrar.isEmpty()) {
            return topicFunctions.asSequence()
                .filter {
                    it.supportMessage(message)
                }
        }
        return (topicFunctions.asSequence() + unindexedRegistrar.asSequence())
            .filter {
                it.supportMessage(message)
            }
    }
}
