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

import me.ahoo.wow.event.DomainEventExchange

/**
 * Key used to store the command stream in the domain event exchange attributes.
 */
const val COMMAND_STREAM_KEY = "__COMMAND_STREAM__"

/**
 * Sets the command stream in the domain event exchange attributes.
 * This allows the command stream to be associated with the domain event for later retrieval.
 *
 * @param commandStream The command stream to set.
 * @return The domain event exchange with the command stream attribute set.
 */
fun DomainEventExchange<*>.setCommandStream(commandStream: CommandStream): DomainEventExchange<*> =
    setAttribute(COMMAND_STREAM_KEY, commandStream)

/**
 * Retrieves the command stream from the domain event exchange attributes.
 *
 * @return The command stream if present, null otherwise.
 */
fun DomainEventExchange<*>.getCommandStream(): CommandStream? = getAttribute<CommandStream>(COMMAND_STREAM_KEY)
