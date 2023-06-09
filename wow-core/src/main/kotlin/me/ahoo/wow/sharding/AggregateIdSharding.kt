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

interface AggregateIdSharding : PreciseSharding<AggregateId>

interface NamedAggregateIdSharding : AggregateIdSharding, Named

class CompositeAggregateIdSharding(private val registrar: Map<NamedAggregate, AggregateIdSharding>) :
    AggregateIdSharding {

    override fun sharding(aggregateId: AggregateId): String {
        val namedAggregate = aggregateId.materialize()
        val sharding = registrar[namedAggregate]
        checkNotNull(sharding) {
            "AggregateIdSharding not found for $namedAggregate"
        }
        return sharding.sharding(aggregateId)
    }
}

private val DEFAULT_HASH_FUNCTION: (String) -> Long = {
    GlobalIdGenerator.stateParser.asState(it).sequence.toLong()
}

data class SingleAggregateIdSharding(val node: String) : AggregateIdSharding {
    override fun sharding(aggregateId: AggregateId): String = node
}

class CosIdShardingDecorator(
    private val sharding: PreciseSharding<Long>,
    private val hashFunction: (String) -> Long = DEFAULT_HASH_FUNCTION
) : AggregateIdSharding {
    override fun sharding(aggregateId: AggregateId): String {
        val hashed = hashFunction(aggregateId.id)
        return sharding.sharding(hashed)
    }
}
