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

package me.ahoo.wow.modeling.command.after

import me.ahoo.wow.api.Ordered
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.function.MessageFunction
import reactor.core.publisher.Mono

/**
 * Represents an after-command function that executes after command processing.
 *
 * This class wraps a message function with metadata about which commands it should execute for,
 * providing ordering and decoration capabilities.
 *
 * @param C The type of the command aggregate.
 * @property metadata The metadata describing this after-command function's configuration.
 * @property delegate The underlying message function to execute.
 *
 * @constructor Creates a new AfterCommandFunction with the specified metadata and delegate.
 */
class AfterCommandFunction<C : Any>(
    val metadata: AfterCommandFunctionMetadata<C>,
    override val delegate: MessageFunction<C, ServerCommandExchange<*>, Mono<*>>
) : MessageFunction<C, ServerCommandExchange<*>, Mono<*>> by delegate,
    Decorator<MessageFunction<C, ServerCommandExchange<*>, Mono<*>>>,
    Ordered by metadata
