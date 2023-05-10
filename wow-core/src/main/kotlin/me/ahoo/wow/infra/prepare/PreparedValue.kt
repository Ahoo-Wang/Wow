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

interface PreparedValue<V> {
    val value: V
    val ttlAt: Long

    val isForever: Boolean
        get() = isForever(ttlAt)
    val isExpired: Boolean
        get() = if (isForever) {
            false
        } else {
            ttlAt < System.currentTimeMillis()
        }

    companion object {
        fun isForever(ttlAt: Long): Boolean {
            return ttlAt >= TTL_FOREVER
        }

        /**
         * UTC: 6666-06-06 00:00:00.
         * 148204944000000
         */
        const val TTL_FOREVER = 148204944000000L

        fun <V> V.asForever(): PreparedValue<V> {
            return asTtlAt(TTL_FOREVER)
        }

        fun <V> V.asTtlAt(ttlAt: Long): PreparedValue<V> {
            return DefaultPreparedValue(this, ttlAt)
        }
    }
}
