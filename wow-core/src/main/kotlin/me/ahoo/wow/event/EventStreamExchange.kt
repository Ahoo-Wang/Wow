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

import me.ahoo.wow.messaging.handler.MessageExchange
import java.util.concurrent.ConcurrentHashMap

/**
 * Exchange interface for domain event stream processing.
 *
 * This interface extends MessageExchange to provide event stream specific functionality
 * for handling collections of domain events within the messaging system.
 *
 * @see MessageExchange
 * @see DomainEventStream
 */
interface EventStreamExchange : MessageExchange<EventStreamExchange, DomainEventStream>

/**
 * Simple implementation of EventStreamExchange.
 *
 * This class provides a basic implementation of the EventStreamExchange interface,
 * storing the domain event stream message and managing exchange attributes.
 *
 * @property message The domain event stream being processed
 * @property attributes Mutable map for storing exchange attributes (default: empty ConcurrentHashMap)
 *
 * @constructor Creates a new SimpleEventStreamExchange with the given message and attributes
 *
 * @param message The domain event stream message
 * @param attributes The mutable map of attributes (default: ConcurrentHashMap)
 *
 * @see EventStreamExchange
 * @see DomainEventStream
 */
class SimpleEventStreamExchange(
    override val message: DomainEventStream,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : EventStreamExchange
