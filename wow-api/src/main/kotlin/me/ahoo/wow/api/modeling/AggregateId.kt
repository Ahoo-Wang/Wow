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

package me.ahoo.wow.api.modeling

import me.ahoo.wow.api.Identifier

/**
 * Defines an aggregate root identifier that inherits multiple interfaces to support various functional requirements.
 * As an identifier, it not only identifies the aggregate root but also supports naming, decorator pattern,
 * tenant identification, and value comparison.
 *
 * @see Identifier
 * @see NamedAggregate
 * @see NamedAggregateDecorator
 * @see TenantId
 * @see Comparable
 */
interface AggregateId :
    Identifier,
    NamedAggregate,
    NamedAggregateDecorator,
    TenantId,
    Comparable<AggregateId> {
    /**
     * The named aggregate that this ID belongs to.
     * @see MaterializedNamedAggregate
     */
    override val namedAggregate: NamedAggregate

    /**
     * Compares two AggregateId instances for ordering, first ensuring they belong to the same aggregate.
     * If they don't belong to the same aggregate, an IllegalArgumentException is thrown.
     * Otherwise, the comparison is based on the identifier.
     *
     * @param other The other AggregateId instance to compare with.
     * @return A negative integer if this is less than other, zero if equal, positive if greater.
     *         Follows the Comparable interface convention.
     * @throws IllegalArgumentException if the two AggregateIds don't belong to the same aggregate.
     */
    override fun compareTo(other: AggregateId): Int {
        require(isSameAggregateName(other)) {
            "NamedAggregate[$namedAggregate VS ${other.namedAggregate}] are different and cannot be compared."
        }
        return id.compareTo(other.id)
    }
}

/**
 * Compares the current AggregateId object for equality with another object.
 * This method provides business logic equality comparison for AggregateId objects,
 * comparing all relevant properties rather than just reference equality.
 *
 * @param other The object to compare with this AggregateId.
 * @return true if the objects are equal based on their properties, false otherwise.
 */
fun AggregateId.equalTo(other: Any?): Boolean =
    when {
        // Reference equality check - if same object, definitely equal
        this === other -> true
        // Type check - if not AggregateId, cannot be equal
        other !is AggregateId -> false
        // Property comparisons - all must match for equality
        tenantId != other.tenantId -> false
        contextName != other.contextName -> false
        aggregateName != other.aggregateName -> false
        id != other.id -> false
        // All properties match, objects are equal
        else -> true
    }

/**
 * Magic number constant used in hash calculations for better distribution.
 */
private const val HASH_MAGIC = 31

/**
 * Calculates a hash value for the AggregateId.
 *
 * This method generates a unique hash by combining the hash codes of tenantId, contextName,
 * aggregateName, and id. The HASH_MAGIC constant ensures better distribution and consistency.
 *
 * @return The computed hash value for this AggregateId.
 */
fun AggregateId.hash(): Int {
    var result = tenantId.hashCode()
    result = HASH_MAGIC * result + contextName.hashCode()
    result = HASH_MAGIC * result + aggregateName.hashCode()
    result = HASH_MAGIC * result + id.hashCode()
    return result
}

/**
 * Calculates the remainder when the ID's hash code is divided by the given divisor.
 *
 * This method is used in distributed systems for partitioning or sharding aggregates.
 * By taking the hash code modulo the divisor, aggregates can be evenly distributed
 * across different partitions or shards.
 *
 * @param divisor The divisor used to calculate the remainder.
 * @return The remainder of the ID's hash code divided by the divisor.
 */
fun AggregateId.mod(divisor: Int): Int = id.hashCode().mod(divisor)
