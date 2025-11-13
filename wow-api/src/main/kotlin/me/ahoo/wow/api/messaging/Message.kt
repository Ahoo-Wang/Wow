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

package me.ahoo.wow.api.messaging

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.modeling.CreateTimeCapable
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.naming.NamedBoundedContext

/**
 * Represents a message with metadata headers and a typed body payload.
 *
 * This interface defines the core structure of messages in the messaging system,
 * providing access to headers for metadata, a typed body for the actual content,
 * and methods for fluent manipulation. Messages can be marked as read-only to
 * prevent modifications after creation or transmission.
 *
 * @param SOURCE The self-referential type for fluent method chaining (typically the implementing class)
 * @param T The type of the message body payload
 *
 * @property header The message header containing metadata key-value pairs
 * @property body The typed payload of the message
 * @property createTime The timestamp when the message was created (Unix timestamp in milliseconds)
 * @property isReadOnly Whether the message is in read-only mode (derived from header state)
 */
@Suppress("UNCHECKED_CAST")
interface Message<SOURCE : Message<SOURCE, T>, out T> :
    Identifier,
    CreateTimeCapable {
    /**
     * The header containing metadata about this message.
     *
     * Headers provide additional context such as correlation IDs, timestamps,
     * routing information, and other metadata that doesn't belong in the body.
     */
    val header: Header

    /**
     * The typed payload of the message.
     *
     * This contains the actual data being transmitted. The type parameter T
     * allows for type-safe access to the message content.
     */
    val body: T

    /**
     * The timestamp when this message was created, as a Unix timestamp in milliseconds.
     *
     * This value represents the time the message was instantiated and can be used
     * for ordering, expiration, or auditing purposes.
     */
    override val createTime: Long

    /**
     * Indicates whether this message is in read-only mode.
     *
     * When read-only, the message headers cannot be modified. This is useful
     * for ensuring immutability after the message has been sent or processed.
     * The state is derived from the header's read-only status.
     */
    val isReadOnly: Boolean
        get() = header.isReadOnly

    /**
     * Marks this message as read-only and returns it for method chaining.
     *
     * This method sets the header to read-only mode, preventing further modifications
     * to the message metadata. The message body is typically immutable by design.
     *
     * @return This message instance (cast to SOURCE type) to support method chaining
     */
    fun withReadOnly(): SOURCE {
        header.withReadOnly()
        return this as SOURCE
    }

    /**
     * Adds a key-value pair to the message header and returns the message for method chaining.
     *
     * This provides a fluent API for setting header metadata. If the message is read-only,
     * this operation may throw an exception or be ignored.
     *
     * @param key The header key to set (must not be null)
     * @param value The header value to associate with the key (must not be null)
     * @return This message instance (cast to SOURCE type) to support method chaining
     * @throws UnsupportedOperationException if the message is read-only and header modifications are not allowed
     */
    fun withHeader(
        key: String,
        value: String
    ): SOURCE {
        header[key] = value
        return this as SOURCE
    }

    /**
     * Adds all key-value pairs from the provided map to the message header and returns the message for method chaining.
     *
     * This provides a fluent API for bulk setting header metadata. If the message is read-only,
     * this operation may throw an exception or be ignored.
     *
     * @param additionalSource A map containing key-value pairs to add to the header (must not be null)
     * @return This message instance (cast to SOURCE type) to support method chaining
     * @throws UnsupportedOperationException if the message is read-only and header modifications are not allowed
     */
    fun withHeader(additionalSource: Map<String, String>): SOURCE {
        header.putAll(additionalSource)
        return this as SOURCE
    }
}

/**
 * A message that is associated with a named bounded context.
 *
 * This interface extends [Message] to include bounded context information,
 * allowing messages to be scoped to specific business domains or contexts.
 * The bounded context provides namespace isolation and helps with message routing
 * and processing within the appropriate domain boundaries.
 *
 * @param SOURCE The self-referential type for fluent method chaining
 * @param T The type of the message body payload
 *
 * @see NamedBoundedContext for bounded context naming capabilities
 */
interface NamedBoundedContextMessage<SOURCE : NamedBoundedContextMessage<SOURCE, T>, out T> :
    Message<SOURCE, T>,
    NamedBoundedContext

/**
 * A message that has both a name and is associated with a bounded context.
 *
 * This interface combines the capabilities of [NamedBoundedContextMessage] and [Named],
 * providing messages with identity within a specific bounded context. The name
 * typically represents the message type or command/event name, while the bounded
 * context provides the domain scope.
 *
 * @param SOURCE The self-referential type for fluent method chaining
 * @param T The type of the message body payload
 *
 * @see Named for naming capabilities
 * @see NamedBoundedContextMessage for bounded context association
 */
interface NamedMessage<SOURCE : NamedMessage<SOURCE, T>, out T> :
    NamedBoundedContextMessage<SOURCE, T>,
    Named
