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

package me.ahoo.wow.command.factory

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import reactor.core.publisher.Mono

/**
 * Factory interface for creating command messages from command builders.
 *
 * CommandMessageFactory provides methods to convert CommandBuilder instances
 * into CommandMessage objects, which can then be sent to the command bus.
 *
 * @see CommandBuilder
 * @see CommandMessage
 */
interface CommandMessageFactory {
    /**
     * Creates a CommandMessage from a CommandBuilder.
     *
     * @param TARGET the type of the command body
     * @param commandBuilder the command builder containing all message properties
     * @return a Mono emitting the created CommandMessage
     */
    fun <TARGET : Any> create(commandBuilder: CommandBuilder): Mono<CommandMessage<TARGET>>

    /**
     * Creates a CommandMessage from a command body object.
     *
     * This convenience method creates a default CommandBuilder from the body
     * and then converts it to a CommandMessage.
     *
     * @param TARGET the type of the command body
     * @param body the command payload object
     * @return a Mono emitting the created CommandMessage
     */
    fun <TARGET : Any> create(body: Any): Mono<CommandMessage<TARGET>> {
        val commandBuilder = body.commandBuilder()
        return create(commandBuilder)
    }
}
