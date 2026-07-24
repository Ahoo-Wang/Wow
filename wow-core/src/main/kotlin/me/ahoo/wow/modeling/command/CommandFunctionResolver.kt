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

private class FunctionCacheEntry<F : Any>(
    val functionType: Class<*>,
    val function: F
)

/**
 * Aggregate-instance function cache optimized for the common single-command-type lifecycle.
 *
 * A successful first resolution uses one immutable entry. A second distinct success promotes
 * the cache to a map, while misses and resolver failures leave the current state unchanged.
 * The immutable entry keeps its key and function coherent under accidental concurrent access,
 * but this cache remains non-thread-safe like the aggregate instance that owns it.
 */
internal class FunctionCache<F : Any> {
    private var singleEntry: FunctionCacheEntry<F>? = null
    private var cache: MutableMap<Class<*>, F>? = null

    fun get(functionType: Class<*>, resolver: (Class<*>) -> F?): F? {
        val currentCache = cache
        if (currentCache != null) {
            currentCache[functionType]?.let {
                return it
            }
            val function = resolver(functionType) ?: return null
            currentCache[functionType] = function
            return function
        }

        val entry = singleEntry
        if (entry != null && entry.functionType === functionType) {
            return entry.function
        }
        val function = resolver(functionType) ?: return null
        if (entry == null) {
            singleEntry = FunctionCacheEntry(functionType, function)
            return function
        }

        val promotedCache = HashMap<Class<*>, F>(4)
        promotedCache[entry.functionType] = entry.function
        promotedCache[functionType] = function
        cache = promotedCache
        singleEntry = null
        return function
    }
}
