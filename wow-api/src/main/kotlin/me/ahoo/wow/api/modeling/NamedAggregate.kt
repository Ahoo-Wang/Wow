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

import me.ahoo.wow.api.naming.NamedBoundedContext

/**
 * Aggregate Name .
 *
 * @author ahoo wang
 * @see me.ahoo.wow.command.CommandBus
 * @see me.ahoo.wow.eventsourcing.EventStore
 */
interface NamedAggregate : NamedBoundedContext {
    /**
     * aggregate name.
     *
     * @return aggregate name
     */
    val aggregateName: String

    fun isSameAggregateName(other: NamedAggregate): Boolean {
        return contextName == other.contextName && aggregateName == other.aggregateName
    }
}

interface NamedAggregateDecorator : NamedAggregate {
    val namedAggregate: NamedAggregate
    override val contextName: String
        get() = namedAggregate.contextName
    override val aggregateName: String
        get() = namedAggregate.aggregateName
}
