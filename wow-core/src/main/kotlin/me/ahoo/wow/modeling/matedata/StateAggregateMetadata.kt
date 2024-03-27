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
package me.ahoo.wow.modeling.matedata

import me.ahoo.wow.api.modeling.TypedAggregate
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.infra.accessor.constructor.ConstructorAccessor
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.toMessageFunction
import me.ahoo.wow.metadata.Metadata

data class StateAggregateMetadata<S : Any>(
    /**
     * State Aggregation Type
     */
    override val aggregateType: Class<S>,
    val constructorAccessor: ConstructorAccessor<S>,
    val aggregateIdAccessor: PropertyGetter<S, String>,
    val sourcingFunctionRegistry: Map<Class<*>, FunctionAccessorMetadata<S, Void>>
) : TypedAggregate<S>, Metadata {

    fun toMessageFunctionRegistry(stateRoot: S): Map<Class<*>, MessageFunction<S, DomainEventExchange<*>, Void>> {
        return sourcingFunctionRegistry
            .map {
                it.key to it.value.toMessageFunction<S, DomainEventExchange<*>, Void>(stateRoot)
            }.toMap()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StateAggregateMetadata<*>) return false

        return aggregateType == other.aggregateType
    }

    override fun hashCode(): Int {
        return aggregateType.hashCode()
    }

    override fun toString(): String {
        return "StateAggregateMetadata(aggregateType=$aggregateType)"
    }
}
