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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEvent
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

class GivenReadOnlyStateAggregate<S : Any>(
    override val aggregateId: AggregateId,
    override val state: S,
    override val aggregateType: Class<S>,
    override val version: Int,
    override val deleted: Boolean,
    override val eventId: String,
    override val firstEventTime: Long,
    override val eventTime: Long
) : ReadOnlyStateAggregate<S> {
    companion object {
        fun Any.asReadOnlyStateAggregate(domainEvent: DomainEvent<*>): ReadOnlyStateAggregate<*> {
            if (this is ReadOnlyStateAggregate<*>) {
                return this
            }
            val firstEventTime = if (domainEvent.isInitialVersion) {
                domainEvent.createTime
            } else {
                0
            }
            return GivenReadOnlyStateAggregate(
                aggregateId = domainEvent.aggregateId,
                state = this,
                aggregateType = this.javaClass,
                version = domainEvent.version,
                deleted = false,
                eventId = domainEvent.id,
                firstEventTime = firstEventTime,
                eventTime = domainEvent.createTime
            )
        }
    }
}