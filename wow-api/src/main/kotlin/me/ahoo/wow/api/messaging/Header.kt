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

import me.ahoo.wow.api.Copyable

/**
 * Represents the header of a message, containing metadata key-value pairs.
 *
 * This interface extends [MutableMap]<String, String> to provide standard map operations
 * for header fields, and [Copyable]<Header> to support creating copies of headers.
 * It provides a fluent API for setting header values and supports read-only mode
 * to prevent modifications after the header is finalized.
 *
 * @author ahoo wang
 */
interface Header :
    MutableMap<String, String>,
    Copyable<Header> {
    /**
     * Indicates whether this header is in read-only mode.
     *
     * When `true`, the header's contents cannot be modified. This is useful for
     * ensuring immutability after the header has been finalized or sent.
     *
     * @return `true` if the header is read-only, `false` otherwise
     */
    val isReadOnly: Boolean

    /**
     * Creates a new read-only copy of this header.
     *
     * This method creates a copy of the current header with all the same key-value pairs,
     * but marks it as read-only to prevent further modifications.
     *
     * @return A new [Header] instance that is read-only and contains the same data as this header
     */
    fun withReadOnly(): Header

    /**
     * Adds a key-value pair to this header and returns the header for method chaining.
     *
     * This method provides a fluent API for setting header values. If the header is
     * read-only, this operation may throw an exception or be ignored depending on
     * the implementation.
     *
     * @param key The header key to set (must not be null)
     * @param value The header value to associate with the key (must not be null)
     * @return This header instance to support method chaining
     * @throws UnsupportedOperationException if the header is read-only and modifications are not allowed
     */
    fun with(
        key: String,
        value: String
    ): Header {
        this[key] = value
        return this
    }

    /**
     * Adds all key-value pairs from the provided map to this header and returns the header for method chaining.
     *
     * This method provides a fluent API for bulk setting header values. If the header is
     * read-only, this operation may throw an exception or be ignored depending on
     * the implementation.
     *
     * @param additional A map containing key-value pairs to add to this header (must not be null)
     * @return This header instance to support method chaining
     * @throws UnsupportedOperationException if the header is read-only and modifications are not allowed
     */
    fun with(additional: Map<String, String>): Header {
        putAll(additional)
        return this
    }
}
