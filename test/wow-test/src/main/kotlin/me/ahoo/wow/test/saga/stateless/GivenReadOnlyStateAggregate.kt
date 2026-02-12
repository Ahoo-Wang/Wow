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

package me.ahoo.wow.test.saga.stateless

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

/**
 * A test implementation of [ReadOnlyStateAggregate] for stateless saga testing.
 *
 * This class provides a concrete implementation of read-only state aggregate
 * that can be used in test scenarios where state needs to be provided
 * to saga functions.
 *
 * @param S The type of the aggregate state.
 * @property aggregateId The identifier of the aggregate.
 * @property state The current state of the aggregate.
 * @property version The current version of the aggregate.
 * @property ownerId The owner identifier of the aggregate.
 * @property deleted Whether the aggregate is marked as deleted.
 * @property eventId The ID of the last processed event.
 * @property firstOperator The operator who created the first event.
 * @property operator The operator who created the last event.
 * @property firstEventTime The timestamp of the first event.
 * @property eventTime The timestamp of the last event.
 */
class GivenReadOnlyStateAggregate<S : Any>(
    override val aggregateId: AggregateId,
    override val state: S,
    override val version: Int,
    override val ownerId: String,
    override val spaceId: SpaceId,
    override val deleted: Boolean,
    override val eventId: String,
    override val firstOperator: String,
    override val operator: String,
    override val firstEventTime: Long,
    override val eventTime: Long
) : ReadOnlyStateAggregate<S> {
    companion object {
        /**
         * Converts any object to a [ReadOnlyStateAggregate] for testing purposes.
         *
         * If the object is already a [ReadOnlyStateAggregate], it is returned as-is.
         * Otherwise, it creates a new [GivenReadOnlyStateAggregate] with the object
         * as state and metadata derived from the domain event.
         *
         * @param domainEvent The domain event that provides context for the aggregate.
         * @return A [ReadOnlyStateAggregate] representation of this object.
         */
        fun Any.toReadOnlyStateAggregate(domainEvent: DomainEvent<*>): ReadOnlyStateAggregate<*> {
            if (this is ReadOnlyStateAggregate<*>) {
                return this
            }
            var firstOperator = ""
            var operator = ""
            var firstEventTime = 0L
            if (domainEvent.isInitialVersion) {
                firstOperator = domainEvent.header.operator.orEmpty()
                operator = firstOperator
                firstEventTime = domainEvent.createTime
            }
            return GivenReadOnlyStateAggregate(
                aggregateId = domainEvent.aggregateId,
                state = this,
                version = domainEvent.version,
                ownerId = domainEvent.ownerId,
                spaceId = domainEvent.spaceId,
                deleted = false,
                eventId = domainEvent.id,
                firstOperator = firstOperator,
                operator = operator,
                firstEventTime = firstEventTime,
                eventTime = domainEvent.createTime
            )
        }
    }
}
