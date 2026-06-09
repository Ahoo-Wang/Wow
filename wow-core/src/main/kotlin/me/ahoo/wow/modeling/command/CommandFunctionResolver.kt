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
    private val commandFunctionCache = FunctionCache<BoundCommandFunction<C>>()
    private val errorFunctionCache = FunctionCache<BoundErrorFunction<C>>()

    fun commandFunction(commandType: Class<*>): BoundCommandFunction<C>? {
        return commandFunctionCache.get(commandType) {
            metadata.toCommandFunction(commandAggregate, it)
        }
    }

    fun errorFunction(commandType: Class<*>): BoundErrorFunction<C>? {
        return errorFunctionCache.get(commandType) {
            metadata.toErrorFunction(commandAggregate.commandRoot, it)
        }
    }
}

internal class FunctionCache<F : Any> {
    private var cache: MutableMap<Class<*>, F>? = null

    fun get(functionType: Class<*>, resolver: (Class<*>) -> F?): F? {
        cache?.get(functionType)?.let {
            return it
        }
        val function = resolver(functionType) ?: return null
        val currentCache = cache ?: HashMap<Class<*>, F>(1).also {
            cache = it
        }
        currentCache[functionType] = function
        return function
    }
}
