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
import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.messaging.TopicKindCapable
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.messaging.LocalMessageBus
import me.ahoo.wow.messaging.MessageBus

/**
 * Command Bus interface for handling command messages in the CQRS architecture.
 *
 * The Command Bus is responsible for routing command messages to their appropriate handlers.
 * It acts as a central hub for command processing, supporting both local and distributed
 * command handling scenarios.
 *
 * @author ahoo wang
 * @see InMemoryCommandBus
 * @see LocalCommandBus
 * @see DistributedCommandBus
 * @property topicKind The topic kind for command messages, always returns [TopicKind.COMMAND]
 */
interface CommandBus :
    MessageBus<CommandMessage<*>, ServerCommandExchange<*>>,
    TopicKindCapable {
    override val topicKind: TopicKind
        get() = TopicKind.COMMAND
}

/**
 * Local Command Bus interface for handling commands within the same JVM instance.
 *
 * This interface extends both [CommandBus] and [LocalMessageBus], providing
 * synchronous command processing capabilities within a single application instance.
 * Commands are processed locally without network communication.
 *
 * @see CommandBus
 * @see LocalMessageBus
 */
interface LocalCommandBus :
    CommandBus,
    LocalMessageBus<CommandMessage<*>, ServerCommandExchange<*>>

/**
 * Distributed Command Bus interface for handling commands across multiple instances or services.
 *
 * This interface extends both [CommandBus] and [DistributedMessageBus], enabling
 * asynchronous command processing across distributed systems. Commands may be routed
 * to different services or instances based on load balancing and availability.
 *
 * @see CommandBus
 * @see DistributedMessageBus
 */
interface DistributedCommandBus :
    CommandBus,
    DistributedMessageBus<CommandMessage<*>, ServerCommandExchange<*>>
