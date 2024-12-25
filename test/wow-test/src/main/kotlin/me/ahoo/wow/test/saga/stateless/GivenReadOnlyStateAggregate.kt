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
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

class GivenReadOnlyStateAggregate<S : Any>(
    override val aggregateId: AggregateId,
    override val state: S,
    override val version: Int,
    override val deleted: Boolean,
    override val eventId: String,
    override val firstOperator: String,
    override val operator: String,
    override val firstEventTime: Long,
    override val eventTime: Long
) : ReadOnlyStateAggregate<S> {

    companion object {
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
