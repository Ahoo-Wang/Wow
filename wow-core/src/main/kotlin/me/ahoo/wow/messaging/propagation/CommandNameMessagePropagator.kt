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

package me.ahoo.wow.messaging.propagation

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message

class CommandNameMessagePropagator : MessagePropagator {
    companion object {
        private const val COMMAND_NAME = "command.name"
        val Header.commandName: String?
            get() {
                return this[COMMAND_NAME]
            }

        fun Header.withCommandName(commandName: String): Header {
            return this.with(COMMAND_NAME, commandName)
        }
    }

    override fun inject(header: Header, upstream: Message<*, *>) {
        if (upstream is CommandMessage) {
            header.withCommandName(upstream.name)
        }
    }
}
