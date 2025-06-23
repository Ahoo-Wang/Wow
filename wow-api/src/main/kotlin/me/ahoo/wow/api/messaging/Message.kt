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
 * The Message interface represents a message with a source and a body of generic types.
 * It extends Identifier and CreateTimeCapable interfaces, indicating that each message has a unique identifier and creation time.
 * The interface provides methods to manipulate message headers and to mark messages as read-only.
 *
 * @param SOURCE The type of the message source, self-referential to allow for fluent API design.
 * @param T The type of the message body.
 */
@Suppress("UNCHECKED_CAST")
interface Message<SOURCE : Message<SOURCE, T>, out T> : Identifier, CreateTimeCapable {
    /**
     * The header of the message, containing metadata about the message.
     */
    val header: Header

    /**
     * The body of the message, containing the actual data payload of the message.
     */
    val body: T

    /**
     * The create time of the message, represented as a Unix timestamp in milliseconds.
     */
    override val createTime: Long

    /**
     * Indicates whether the message is read-only, based on the state of the header.
     */
    val isReadOnly: Boolean
        get() = header.isReadOnly

    /**
     * Marks the message as read-only and returns the message itself for method chaining.
     *
     * @return The message itself, allowing for method chaining.
     */
    fun withReadOnly(): SOURCE {
        header.withReadOnly()
        return this as SOURCE
    }

    /**
     * Adds a key-value pair to the message header and returns the message itself for method chaining.
     *
     * @param key The key of the header.
     * @param value The value of the header.
     * @return The message itself, allowing for method chaining.
     */
    fun withHeader(key: String, value: String): SOURCE {
        header[key] = value
        return this as SOURCE
    }

    /**
     * Adds all entries from the provided map to the message header and returns the message itself for method chaining.
     *
     * @param additionalSource A map containing key-value pairs to be added to the header.
     * @return The message itself, allowing for method chaining.
     */
    fun withHeader(additionalSource: Map<String, String>): SOURCE {
        header.putAll(additionalSource)
        return this as SOURCE
    }
}

/**
 * An extension of the Message interface that incorporates the NamedBoundedContext trait.
 * This interface represents a message that is associated with a bounded context and has a name.
 *
 * @param SOURCE The type of the message source, self-referential to allow for fluent API design.
 * @param T The type of the message body.
 */
interface NamedBoundedContextMessage<SOURCE : NamedBoundedContextMessage<SOURCE, T>, out T> :
    Message<SOURCE, T>,
    NamedBoundedContext

/**
 * A further specialization of the NamedBoundedContextMessage interface that adds support for named entities.
 * This interface represents a message that is named and can be associated with a bounded context.
 *
 * @param SOURCE The type of the message source, self-referential to allow for fluent API design.
 * @param T The type of the message body.
 */
interface NamedMessage<SOURCE : NamedMessage<SOURCE, T>, out T> : NamedBoundedContextMessage<SOURCE, T>, Named
