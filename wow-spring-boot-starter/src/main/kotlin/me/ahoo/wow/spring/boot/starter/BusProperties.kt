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

import me.ahoo.wow.spring.boot.starter.BusType.Companion.KAFKA_NAME
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.boot.context.properties.bind.DefaultValue

class BusProperties(
    @DefaultValue(KAFKA_NAME)
    var type: BusType = BusType.KAFKA,
    @NestedConfigurationProperty
    var localFirst: LocalFirstProperties = LocalFirstProperties()
) {
    companion object {
        const val TYPE_SUFFIX_KEY = ".bus.type"
        const val LOCAL_FIRST_ENABLED_SUFFIX_KEY = ".bus.local-first.enabled"
    }
}

class LocalFirstProperties(@DefaultValue("true") override var enabled: Boolean = true) : EnabledCapable

enum class BusType {
    KAFKA,
    REDIS,
    IN_MEMORY,
    NO_OP
    ;

    companion object {
        const val KAFKA_NAME = "kafka"
        const val REDIS_NAME = "redis"
        const val IN_MEMORY_NAME = "in_memory"
        const val NO_OP_NAME = "no_op"
    }
}
