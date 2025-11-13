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

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.naming.NamedBoundedContext

/**
 * Interface for entities that have an aggregate name.
 */
interface AggregateNameCapable {
    /**
     * The name of the aggregate.
     */
    val aggregateName: String
}

/**
 * Represents an aggregate with a unique name within a specific bounded context.
 * It inherits from NamedBoundedContext to get the context name and additionally defines the aggregate name.
 *
 * @see me.ahoo.wow.command.CommandBus
 * @see me.ahoo.wow.eventsourcing.EventStore
 */
interface NamedAggregate :
    NamedBoundedContext,
    AggregateNameCapable {
    /**
     * Checks if two aggregates belong to the same context and have the same aggregate name.
     *
     * @param other Another NamedAggregate instance to compare with.
     * @return true if both aggregates belong to the same context and have the same name, false otherwise.
     */
    fun isSameAggregateName(
        other: NamedAggregate
    ): Boolean = contextName == other.contextName && aggregateName == other.aggregateName
}

/**
 * Decorator interface for named aggregates following the decorator pattern.
 * It inherits from NamedAggregate and delegates to the actual named aggregate implementation.
 * This interface allows adding functionality dynamically without modifying the original aggregate logic.
 */
interface NamedAggregateDecorator : NamedAggregate {
    /**
     * The decorated named aggregate.
     */
    val namedAggregate: NamedAggregate

    @get:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    @get:JsonIgnore
    override val contextName: String
        get() = namedAggregate.contextName

    @get:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    @get:JsonIgnore
    override val aggregateName: String
        get() = namedAggregate.aggregateName
}
