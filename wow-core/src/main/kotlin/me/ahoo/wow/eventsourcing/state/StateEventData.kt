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

package me.ahoo.wow.eventsourcing.state

import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

interface StateEvent<S : Any> : DomainEventStream, ReadOnlyStateAggregate<S> {
    override val aggregateType: Class<S>
        get() = state.javaClass
    override val eventId: String
        get() = id
    override val eventTime: Long
        get() = createTime

    override fun copy(): StateEvent<S>

    companion object {
        fun <S : Any> DomainEventStream.asStateEvent(
            state: S,
            firstEventTime: Long = createTime,
            deleted: Boolean = false
        ): StateEvent<S> {
            return StateEventData(this, state, firstEventTime, deleted)
        }

        fun <S : Any> DomainEventStream.asStateEvent(stateAggregate: ReadOnlyStateAggregate<S>): StateEvent<S> {
            return StateEventData(
                delegate = this,
                state = stateAggregate.state,
                firstEventTime = stateAggregate.firstEventTime,
                deleted = stateAggregate.deleted,
            )
        }
    }
}

data class StateEventData<S : Any>(
    override val delegate: DomainEventStream,
    override val state: S,
    override val firstEventTime: Long = delegate.createTime,
    override val deleted: Boolean = false
) : StateEvent<S>, Decorator<DomainEventStream>, DomainEventStream by delegate {
    override fun copy(): StateEvent<S> {
        return copy(delegate = delegate.copy())
    }
}
