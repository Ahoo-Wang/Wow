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

package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.messaging.LocalFirstMessageBus
import me.ahoo.wow.messaging.withLocalFirst
import reactor.core.publisher.Mono

/**
 * Command bus that prioritizes local processing before falling back to distributed processing.
 * Void commands are automatically configured to skip local-first behavior since they don't
 * require waiting for results.
 *
 * @param distributedBus The distributed command bus for fallback processing.
 * @param localBus The local command bus for primary processing. Defaults to InMemoryCommandBus.
 */
class LocalFirstCommandBus(
    override val distributedBus: DistributedCommandBus,
    override val localBus: LocalCommandBus = InMemoryCommandBus()
) : CommandBus,
    LocalFirstMessageBus<CommandMessage<*>, ServerCommandExchange<*>> {
    /**
     * Sends a command message, prioritizing local processing.
     * Void commands are automatically configured to not use local-first behavior.
     *
     * @param message The command message to send.
     * @return A Mono that completes when the command is processed.
     */
    override fun send(message: CommandMessage<*>): Mono<Void> {
        if (message.isVoid) {
            message.withLocalFirst(false)
        }
        return super.send(message)
    }
}
