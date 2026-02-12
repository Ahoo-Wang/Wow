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
 * Headers are typically used to store metadata such as correlation IDs, timestamps,
 * routing information, and other contextual data that accompanies the message body.
 * Once a header is marked as read-only, any attempt to modify it will result in
 * an [UnsupportedOperationException] being thrown.
 *
 * @author ahoo wang
 *
 * @see DefaultHeader for the default implementation
 * @see Message.header for usage in message contexts
 */
interface Header :
    MutableMap<String, String>,
    Copyable<Header> {
    /**
     * Indicates whether this header is in read-only mode.
     *
     * When `true`, the header's contents cannot be modified. Any attempt to modify
     * the header (via [put], [remove], [putAll], [clear], or the fluent [with] methods)
     * will result in an [UnsupportedOperationException]. This is useful for ensuring
     * immutability after the header has been finalized or sent.
     *
     * @return `true` if the header is read-only, `false` otherwise
     */
    val isReadOnly: Boolean

    /**
     * Marks this header as read-only and returns it for method chaining.
     *
     * After calling this method, the header becomes immutable and any subsequent
     * modification attempts will throw [UnsupportedOperationException]. This method
     * enables fluent API usage for marking headers as read-only.
     *
     * @return This header instance (now read-only) to support method chaining
     *
     * @sample
     * ```kotlin
     * val header = DefaultHeader()
     *     .with("correlationId", "123")
     *     .withReadOnly() // Header is now immutable
     * ```
     */
    fun withReadOnly(): Header

    /**
     * Adds a key-value pair to this header and returns the header for method chaining.
     *
     * This method provides a fluent API for setting header values, allowing multiple
     * header modifications to be chained together. Internally, this delegates to the
     * [put] method, which will check the read-only status before making changes.
     *
     * @param key The header key to set (must not be null or empty)
     * @param value The header value to associate with the key (must not be null)
     * @return This header instance to support method chaining
     * @throws UnsupportedOperationException if the header is read-only
     *
     * @sample
     * ```kotlin
     * val header = DefaultHeader()
     *     .with("correlationId", "abc-123")
     *     .with("timestamp", System.currentTimeMillis().toString())
     * ```
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
     * This method provides a fluent API for bulk header modifications, allowing multiple
     * header fields to be set at once. Internally, this delegates to the [putAll] method,
     * which will check the read-only status before making changes.
     *
     * @param additional A map containing key-value pairs to add to this header (must not be null)
     * @return This header instance to support method chaining
     * @throws UnsupportedOperationException if the header is read-only
     *
     * @sample
     * ```kotlin
     * val contextHeaders = mapOf(
     *     "userId" to "user123",
     *     "requestId" to "req456"
     * )
     * val header = DefaultHeader()
     *     .with(contextHeaders)
     *     .with("processedAt", System.currentTimeMillis().toString())
     * ```
     */
    fun with(additional: Map<String, String>): Header {
        putAll(additional)
        return this
    }
}
