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

package me.ahoo.wow.sharding

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing aggregate ID sharding strategies by name.
 *
 * This interface extends MutableMap to provide standard map operations for storing
 * and retrieving sharding strategies, with additional convenience methods for
 * registering named sharding implementations.
 *
 * It's commonly used to maintain different sharding strategies for different
 * aggregate types or contexts, allowing dynamic configuration and lookup.
 *
 * @see AggregateIdSharding
 * @see NamedAggregateIdSharding
 */
interface ShardingRegistrar : MutableMap<String, AggregateIdSharding> {
    /**
     * Registers a named sharding strategy in this registrar.
     *
     * This method stores the sharding strategy using its name as the key,
     * allowing it to be retrieved later using the name.
     *
     * Example usage:
     * ```kotlin
     * val registrar = SimpleShardingRegistrar()
     * val sharding = MyNamedSharding("order-sharding")
     *
     * val previous = registrar.register(sharding)
     * val retrieved = registrar["order-sharding"] // Returns the sharding instance
     * ```
     * @param namedAggregateIdSharding The named sharding strategy to register
     * @return The previous sharding strategy associated with the name, or null if none existed
     */
    fun register(namedAggregateIdSharding: NamedAggregateIdSharding): AggregateIdSharding? =
        put(namedAggregateIdSharding.name, namedAggregateIdSharding)
}

/**
 * Simple thread-safe implementation of ShardingRegistrar using ConcurrentHashMap.
 *
 * This class provides a basic implementation of the ShardingRegistrar interface
 * using a ConcurrentHashMap for storage, making it safe for concurrent access
 * in multi-threaded environments.
 *
 * Example usage:
 * ```kotlin
 * val registrar = SimpleShardingRegistrar()
 *
 * // Register sharding strategies
 * registrar["orders"] = SingleAggregateIdSharding("order-node-1")
 * registrar["users"] = SingleAggregateIdSharding("user-node-1")
 *
 * // Or using the register method
 * val namedSharding = MyNamedSharding("products")
 * registrar.register(namedSharding)
 *
 * // Retrieve sharding strategies
 * val orderSharding = registrar["orders"]
 * ```
 * @property registrar The underlying map used for storing sharding strategies (defaults to ConcurrentHashMap)
 *
 * @see ShardingRegistrar
 * @see ConcurrentHashMap
 */
class SimpleShardingRegistrar(
    private val registrar: MutableMap<String, AggregateIdSharding> = ConcurrentHashMap()
) : ShardingRegistrar,
    MutableMap<String, AggregateIdSharding> by registrar
