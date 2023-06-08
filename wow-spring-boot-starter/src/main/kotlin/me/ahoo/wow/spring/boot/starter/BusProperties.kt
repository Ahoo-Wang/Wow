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

package me.ahoo.wow.spring.boot.starter

data class BusProperties(
    val type: Type = Type.KAFKA,
    val localFirst: LocalFirst = LocalFirst()
) {
    companion object {
        const val TYPE_SUFFIX_KEY = ".bus.type"
        const val LOCAL_FIRST_ENABLED_SUFFIX_KEY = ".bus.local-first.enabled"
    }

    data class LocalFirst(val enabled: Boolean = true)

    enum class Type {
        KAFKA,
        REDIS,
        IN_MEMORY
        ;

        companion object {
            const val KAFKA_NAME = "kafka"
            const val REDIS_NAME = "redis"
            const val IN_MEMORY_NAME = "in_memory"
        }
    }
}
