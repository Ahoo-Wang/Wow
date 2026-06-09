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
package me.ahoo.wow.modeling.command

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.metadata.CommandAggregateMetadata
import reactor.core.publisher.Mono

private typealias BoundCommandFunction<C> = MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>>
private typealias BoundErrorFunction<C> = MessageFunction<C, ServerCommandExchange<*>, Mono<*>>

internal class CommandFunctionResolver<C : Any>(
    private val metadata: CommandAggregateMetadata<C>,
    private val commandAggregate: CommandAggregate<C, *>
) {
    private var commandFunctionCache: MutableMap<Class<*>, BoundCommandFunction<C>>? = null
    private var errorFunctionCache: MutableMap<Class<*>, BoundErrorFunction<C>>? = null

    fun commandFunction(commandType: Class<*>): BoundCommandFunction<C>? {
        commandFunctionCache?.get(commandType)?.let {
            return it
        }
        val commandFunction = metadata.toCommandFunction(commandAggregate, commandType) ?: return null
        val cache = commandFunctionCache ?: HashMap<Class<*>, BoundCommandFunction<C>>(1).also {
            commandFunctionCache = it
        }
        cache[commandType] = commandFunction
        return commandFunction
    }

    fun errorFunction(commandType: Class<*>): BoundErrorFunction<C>? {
        errorFunctionCache?.get(commandType)?.let {
            return it
        }
        val errorFunction = metadata.toErrorFunction(commandAggregate.commandRoot, commandType) ?: return null
        val cache = errorFunctionCache ?: HashMap<Class<*>, BoundErrorFunction<C>>(1).also {
            errorFunctionCache = it
        }
        cache[commandType] = errorFunction
        return errorFunction
    }
}
