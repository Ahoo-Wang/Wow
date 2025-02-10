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

fun AggregateId.equalTo(other: Any?): Boolean {
    return when {
        this === other -> true
        other !is AggregateId -> false
        tenantId != other.tenantId -> false
        ownerId != other.ownerId -> false
        contextName != other.contextName -> false
        aggregateName != other.aggregateName -> false
        id != other.id -> false
        else -> true
    }
}

private const val HASH_MAGIC = 31
fun AggregateId.hash(): Int {
    var result = tenantId.hashCode()
    result = HASH_MAGIC * result + ownerId.hashCode()
    result = HASH_MAGIC * result + contextName.hashCode()
    result = HASH_MAGIC * result + aggregateName.hashCode()
    result = HASH_MAGIC * result + id.hashCode()
    return result
}

fun AggregateId.mod(divisor: Int): Int {
    return id.hashCode().mod(divisor)
}

/**
 * Aggregate Id .
 *
 * @author ahoo wang
 */
interface AggregateId :
    Identifier,
    NamedAggregate,
    NamedAggregateDecorator,
    TenantId,
    OwnerId,
    Comparable<AggregateId> {
    /**
     * @see MaterializedNamedAggregate
     */
    override val namedAggregate: NamedAggregate

    override fun compareTo(other: AggregateId): Int {
        require(isSameAggregateName(other)) {
            "NamedAggregate[$namedAggregate VS ${other.namedAggregate}] are different and cannot be compared."
        }
        return id.compareTo(other.id)
    }
}
