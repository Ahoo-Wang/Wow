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

import me.ahoo.cosid.sharding.PreciseSharding
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.materialize

/**
 * Interface for sharding aggregate IDs across multiple nodes or partitions.
 *
 * This interface extends PreciseSharding to provide sharding functionality specifically
 * for AggregateId objects. Implementations determine which shard (node, partition, etc.)
 * an aggregate should be routed to based on its ID.
 *
 * Sharding is crucial for distributing data across multiple storage nodes to improve
 * performance, scalability, and load balancing in distributed systems.
 *
 * @see PreciseSharding
 * @see AggregateId
 */
interface AggregateIdSharding : PreciseSharding<AggregateId>

/**
 * Named sharding implementation for aggregate IDs.
 *
 * This interface combines AggregateIdSharding with Named, allowing sharding strategies
 * to be identified by a name. This is useful for registering and retrieving specific
 * sharding configurations for different aggregate types.
 *
 * @see AggregateIdSharding
 * @see Named
 */
interface NamedAggregateIdSharding :
    AggregateIdSharding,
    Named

/**
 * Composite sharding implementation that routes aggregates based on their type.
 *
 * This class maintains a registry of sharding strategies mapped to specific named aggregates.
 * When sharding an aggregate ID, it looks up the appropriate sharding strategy based on
 * the aggregate's name and type, then delegates the sharding decision to that strategy.
 *
 * This allows different aggregate types to use different sharding algorithms while
 * maintaining a unified interface.
 *
 * Example usage:
 * ```kotlin
 * val orderSharding = SingleAggregateIdSharding("order-node")
 * val userSharding = SingleAggregateIdSharding("user-node")
 *
 * val registrar = mapOf(
 *     NamedAggregate("order", "OrderAggregate") to orderSharding,
 *     NamedAggregate("user", "UserAggregate") to userSharding
 * )
 *
 * val compositeSharding = CompositeAggregateIdSharding(registrar)
 * val shard = compositeSharding.sharding(orderAggregateId) // Returns "order-node"
 * ```
 *
 * @property registrar A map from NamedAggregate to their corresponding AggregateIdSharding strategies
 * @throws IllegalStateException if no sharding strategy is registered for the aggregate type
 *
 * @see AggregateIdSharding
 * @see NamedAggregate
 */
class CompositeAggregateIdSharding(
    private val registrar: Map<NamedAggregate, AggregateIdSharding>
) : AggregateIdSharding {
    /**
     * Determines the shard for the given aggregate ID.
     *
     * This method extracts the named aggregate from the aggregate ID, looks up the
     * corresponding sharding strategy in the registrar, and delegates the sharding
     * decision to that strategy.
     *
     * @param aggregateId The aggregate ID to shard
     * @return The shard identifier (node name, partition key, etc.)
     * @throws IllegalStateException if no sharding strategy is registered for this aggregate type
     */
    override fun sharding(aggregateId: AggregateId): String {
        val namedAggregate = aggregateId.materialize()
        val sharding = registrar[namedAggregate]
        checkNotNull(sharding) {
            "AggregateIdSharding not found for $namedAggregate"
        }
        return sharding.sharding(aggregateId)
    }
}

/**
 * Default hash function that converts an aggregate ID string to a Long value.
 *
 * This function uses the GlobalIdGenerator's state parser to extract the sequence
 * number from the ID string, which provides a good distribution for sharding.
 * The sequence number is typically the numeric part of globally unique IDs.
 *
 * @see GlobalIdGenerator.stateParser
 */
private val DEFAULT_HASH_FUNCTION: (String) -> Long = {
    GlobalIdGenerator.stateParser
        .asState(it)
        .sequence
        .toLong()
}

/**
 * Simple sharding implementation that always routes to a single node.
 *
 * This implementation ignores the aggregate ID and always returns the same node name.
 * It's useful for scenarios where all aggregates of a certain type should be stored
 * on the same node, or for testing purposes.
 *
 * Example usage:
 * ```kotlin
 * val sharding = SingleAggregateIdSharding("primary-node")
 * val shard1 = sharding.sharding(orderId1) // Returns "primary-node"
 * val shard2 = sharding.sharding(orderId2) // Returns "primary-node"
 * ```
 * @property node The fixed node name to return for all sharding requests
 *
 * @see AggregateIdSharding
 */
data class SingleAggregateIdSharding(
    val node: String
) : AggregateIdSharding {
    /**
     * Returns the fixed node name regardless of the aggregate ID.
     *
     * @param aggregateId The aggregate ID (ignored in this implementation)
     * @return The fixed node name
     */
    override fun sharding(aggregateId: AggregateId): String = node
}

/**
 * Decorator that adapts a numeric sharding strategy for aggregate IDs using a hash function.
 *
 * This class wraps a PreciseSharding<Long> implementation and converts aggregate IDs
 * to Long values using a hash function before delegating to the underlying sharding strategy.
 * This allows using existing numeric sharding algorithms with string-based aggregate IDs.
 *
 * The default hash function extracts the sequence number from globally unique IDs,
 * providing good distribution characteristics.
 *
 * Example usage:
 * ```kotlin
 * val numericSharding = ModuloSharding(4) // Shards into 4 buckets
 * val aggregateSharding = CosIdShardingDecorator(numericSharding)
 *
 * val shard = aggregateSharding.sharding(aggregateId) // Returns "0", "1", "2", or "3"
 * ```
 * @property sharding The underlying numeric sharding strategy
 * @property hashFunction Function to convert aggregate ID strings to Long values for sharding
 *
 * @see AggregateIdSharding
 * @see PreciseSharding
 */
class CosIdShardingDecorator(
    private val sharding: PreciseSharding<Long>,
    private val hashFunction: (String) -> Long = DEFAULT_HASH_FUNCTION
) : AggregateIdSharding {
    /**
     * Determines the shard by hashing the aggregate ID and delegating to the numeric sharding strategy.
     *
     * This method converts the aggregate ID string to a Long value using the hash function,
     * then delegates the sharding decision to the wrapped PreciseSharding<Long> implementation.
     *
     * @param aggregateId The aggregate ID to shard
     * @return The shard identifier determined by the underlying numeric sharding strategy
     */
    override fun sharding(aggregateId: AggregateId): String {
        val hashed = hashFunction(aggregateId.id)
        return sharding.sharding(hashed)
    }
}
