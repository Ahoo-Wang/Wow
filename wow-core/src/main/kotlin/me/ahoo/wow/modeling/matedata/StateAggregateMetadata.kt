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

/**
 * Represents the metadata for a state aggregate, containing information needed for state management and event sourcing.
 *
 * This data class holds the configuration for a state aggregate, including constructor accessors,
 * aggregate ID accessors, and sourcing function registries for handling domain events.
 *
 * @param S The type of the state aggregate.
 * @property aggregateType The class of the state aggregate.
 * @property constructorAccessor The accessor for creating state aggregate instances.
 * @property aggregateIdAccessor The optional property getter for accessing the aggregate ID from the state.
 * @property sourcingFunctionRegistry Map of event types to their sourcing function metadata.
 *
 * @constructor Creates a new StateAggregateMetadata with the specified properties.
 */
data class StateAggregateMetadata<S : Any>(
    /**
     * State Aggregation Type
     */
    override val aggregateType: Class<S>,
    val constructorAccessor: ConstructorAccessor<S>,
    val aggregateIdAccessor: PropertyGetter<S, String>?,
    val sourcingFunctionRegistry: Map<Class<*>, FunctionAccessorMetadata<S, Void>>
) : TypedAggregate<S>,
    Metadata {
    /**
     * Converts the sourcing function registry into executable message functions.
     *
     * This method creates a map of event types to their corresponding message functions for event sourcing.
     *
     * @param stateRoot The state aggregate instance to bind functions to.
     * @return A map of event classes to their message functions.
     */
    fun toMessageFunctionRegistry(stateRoot: S): Map<Class<*>, MessageFunction<S, DomainEventExchange<*>, Void>> =
        sourcingFunctionRegistry
            .map {
                it.key to it.value.toMessageFunction<S, DomainEventExchange<*>, Void>(stateRoot)
            }.toMap()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StateAggregateMetadata<*>) return false

        return aggregateType == other.aggregateType
    }

    override fun hashCode(): Int = aggregateType.hashCode()

    override fun toString(): String = "StateAggregateMetadata(aggregateType=$aggregateType)"
}
