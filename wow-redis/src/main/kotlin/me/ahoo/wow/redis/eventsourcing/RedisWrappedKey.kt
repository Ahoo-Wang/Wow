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

package me.ahoo.wow.redis.eventsourcing

internal object RedisWrappedKey {
    const val KEY_PREFIX = "{"
    const val KEY_SUFFIX = "}"

    fun String.isWrapped(): Boolean {
        if (!startsWith(KEY_PREFIX)) {
            return false
        }
        return endsWith(KEY_SUFFIX)
    }

    fun String.wrap(): String {
        return "$KEY_PREFIX$this$KEY_SUFFIX"
    }

    fun String.unwrap(): String {
        if (!isWrapped()) {
            return this
        }
        return removePrefix(KEY_PREFIX).removeSuffix(KEY_SUFFIX)
    }
}
