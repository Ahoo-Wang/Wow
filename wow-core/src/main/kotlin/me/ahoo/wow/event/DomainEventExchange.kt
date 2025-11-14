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

package me.ahoo.wow.event

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Exchange interface for domain event processing.
 *
 * This interface extends MessageExchange to provide domain event specific functionality
 * for handling domain events within the messaging system. It provides access to
 * event processing functions and manages the exchange lifecycle.
 *
 * @param T The type of the domain event body
 *
 * @see MessageExchange
 * @see DomainEvent
 * @see MessageFunction
 */
interface DomainEventExchange<T : Any> : MessageExchange<DomainEventExchange<T>, DomainEvent<T>> {
    /**
     * Retrieves the event processing function for this exchange.
     *
     * @return The message function for processing this domain event, or null if not set
     *
     * @see MessageFunction
     * @see getFunctionAs
     */
    fun getEventFunction(): MessageFunction<Any, DomainEventExchange<*>, Mono<*>>? = getFunctionAs()
}

/**
 * Simple implementation of DomainEventExchange.
 *
 * This class provides a basic implementation of the DomainEventExchange interface,
 * storing the domain event message and managing exchange attributes in a concurrent map.
 *
 * @param T The type of the domain event body
 * @property message The domain event being processed
 * @property attributes Mutable map for storing exchange attributes (default: empty ConcurrentHashMap)
 *
 * @constructor Creates a new SimpleDomainEventExchange with the given message and attributes
 *
 * @param message The domain event message
 * @param attributes The mutable map of attributes (default: ConcurrentHashMap)
 *
 * @see DomainEventExchange
 * @see DomainEvent
 */
class SimpleDomainEventExchange<T : Any>(
    override val message: DomainEvent<T>,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : DomainEventExchange<T>

/**
 * Exchange interface for domain events with state context.
 *
 * This interface extends DomainEventExchange to provide access to the current state
 * of an aggregate during event processing. It allows extracting state-related objects
 * from the exchange context.
 *
 * @param S The type of the aggregate state
 * @param T The type of the domain event body
 * @property state The read-only state aggregate containing current state information
 *
 * @see DomainEventExchange
 * @see ReadOnlyStateAggregate
 */
interface StateDomainEventExchange<S : Any, T : Any> : DomainEventExchange<T> {
    /**
     * The read-only state aggregate containing current state information.
     */
    val state: ReadOnlyStateAggregate<S>

    /**
     * Extracts an object of the specified type from the exchange context.
     *
     * This method first tries to extract from the parent implementation, then checks
     * if the requested type matches the state aggregate or its state.
     *
     * @param type The class type to extract
     * @return The extracted object of the specified type, or null if not found
     * @param T The type to extract
     *
     * @see MessageExchange.extractDeclared
     */
    override fun <T : Any> extractDeclared(type: Class<T>): T? {
        val extracted = super.extractDeclared(type)
        if (extracted != null) {
            return extracted
        }
        if (type.isInstance(state)) {
            return type.cast(state)
        }
        if (type.isInstance(state.state)) {
            return type.cast(state.state)
        }

        return null
    }
}

/**
 * Simple implementation of StateDomainEventExchange.
 *
 * This class provides a basic implementation of the StateDomainEventExchange interface,
 * combining a read-only state aggregate with a domain event message and managing
 * exchange attributes.
 *
 * @param S The type of the aggregate state
 * @param T The type of the domain event body
 * @property state The read-only state aggregate
 * @property message The domain event being processed
 * @property attributes Mutable map for storing exchange attributes (default: empty ConcurrentHashMap)
 *
 * @constructor Creates a new SimpleStateDomainEventExchange with state, message, and attributes
 *
 * @param state The read-only state aggregate
 * @param message The domain event message
 * @param attributes The mutable map of attributes (default: ConcurrentHashMap)
 *
 * @see StateDomainEventExchange
 * @see ReadOnlyStateAggregate
 * @see DomainEvent
 */
class SimpleStateDomainEventExchange<S : Any, T : Any>(
    override val state: ReadOnlyStateAggregate<S>,
    override val message: DomainEvent<T>,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : StateDomainEventExchange<S, T>
