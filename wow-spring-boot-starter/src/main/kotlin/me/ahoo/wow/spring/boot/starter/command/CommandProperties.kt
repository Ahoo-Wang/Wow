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

package me.ahoo.wow.spring.boot.starter.command

import me.ahoo.wow.api.Wow
import me.ahoo.wow.spring.boot.starter.MessageBusType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConstructorBinding
@ConfigurationProperties(prefix = CommandProperties.PREFIX)
data class CommandProperties(val bus: Bus = Bus()) {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}command"
    }

    data class Bus(
        val type: MessageBusType = MessageBusType.KAFKA,
    ) {
        companion object {
            const val TYPE = "$PREFIX.bus.type"
            const val COMMAND_BUS_BEAN_NAME = "commandBus"
        }
    }
}
