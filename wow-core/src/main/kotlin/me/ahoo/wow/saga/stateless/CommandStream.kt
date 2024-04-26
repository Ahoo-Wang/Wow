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

package me.ahoo.wow.saga.stateless

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent

interface CommandStream : Iterable<SagaCommand<*>> {
    val domainEvent: DomainEvent<*>
    val size: Int
}

data class DefaultCommandStream(
    override val domainEvent: DomainEvent<*>,
    private val commands: List<SagaCommand<*>>
) : CommandStream, Iterable<SagaCommand<*>> by commands {
    override val size: Int
        get() = commands.size
}

data class SagaCommand<C : Any>(val command: C, val index: Int = 0) {
    @Suppress("UNCHECKED_CAST")
    val body: C
        get() {
            if (command is CommandMessage<*>) {
                return command.body as C
            }
            return command
        }
}
