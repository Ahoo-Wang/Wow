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

package me.ahoo.wow.infra.prepare

import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * Interface representing a value that has been prepared with optional time-to-live (TTL) support.
 * Prepared values can either be permanent (never expire) or temporary (expire after a certain time).
 *
 * This interface is used in conjunction with PrepareKey to provide TTL-based key reservations
 * in EventSourcing architectures where uniqueness constraints need to be enforced.
 *
 * @param V the type of the prepared value
 */
interface PreparedValue<V> {
    /**
     * The actual value being prepared.
     */
    val value: V

    /**
     * The timestamp (in milliseconds since epoch) when this prepared value expires.
     * Values with ttlAt >= TTL_FOREVER are considered permanent and never expire.
     *
     * Unit: milliseconds since Unix epoch
     */
    val ttlAt: Long

    /**
     * Checks if this prepared value is permanent (never expires).
     * A value is considered permanent if its ttlAt is greater than or equal to TTL_FOREVER.
     */
    @get:JsonIgnore
    val isForever: Boolean
        get() = isForever(ttlAt)

    /**
     * Checks if this prepared value has expired.
     * Permanent values (isForever = true) never expire.
     * Temporary values expire when the current time exceeds ttlAt.
     */
    @get:JsonIgnore
    val isExpired: Boolean
        get() =
            if (isForever) {
                false
            } else {
                ttlAt < System.currentTimeMillis()
            }

    companion object {
        /**
         * Checks if a given ttlAt timestamp represents a permanent (forever) value.
         *
         * @param ttlAt the timestamp to check
         * @return true if the timestamp represents a permanent value
         */
        fun isForever(ttlAt: Long): Boolean = ttlAt >= TTL_FOREVER

        /**
         * A special timestamp representing permanent values that never expire.
         * Corresponds to UTC: 6666-06-06 00:00:00 (far future date).
         * Value: 148204944000000 milliseconds since Unix epoch
         */
        const val TTL_FOREVER = 148204944000000L

        /**
         * Converts a value to a permanent PreparedValue that never expires.
         *
         * @param V the type of the value
         * @return a PreparedValue that never expires
         *
         * @sample
         * ```
         * val permanentValue = "myValue".toForever()
         * assert(permanentValue.isForever) // true
         * assert(!permanentValue.isExpired) // true
         * ```
         */
        fun <V> V.toForever(): PreparedValue<V> = toTtlAt(TTL_FOREVER)

        /**
         * Converts a value to a PreparedValue with a specific expiration timestamp.
         *
         * @param V the type of the value
         * @param ttlAt the expiration timestamp in milliseconds since epoch
         * @return a PreparedValue with the specified TTL
         *
         * @sample
         * ```
         * val tempValue = "myValue".toTtlAt(System.currentTimeMillis() + 3600000) // expires in 1 hour
         * assert(!tempValue.isForever) // false
         * ```
         */
        fun <V> V.toTtlAt(ttlAt: Long): PreparedValue<V> = DefaultPreparedValue(this, ttlAt)
    }
}
