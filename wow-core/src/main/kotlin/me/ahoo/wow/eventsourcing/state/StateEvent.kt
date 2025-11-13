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

import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

/**
 * Represents a state event that combines domain event stream data with aggregate state.
 * State events are used in event sourcing to capture both the event and the resulting state.
 *
 * @param S the type of the state
 */
interface StateEvent<S : Any> :
    DomainEventStream,
    ReadOnlyStateAggregate<S> {
    /**
     * The event ID, delegated to the id property.
     */
    override val eventId: String
        get() = id

    /**
     * The operator who triggered the event, from the header.
     */
    override val operator: String
        get() = header.operator.orEmpty()

    /**
     * The time when the event occurred, delegated to createTime.
     */
    override val eventTime: Long
        get() = createTime

    /**
     * Creates a copy of this state event.
     *
     * @return a copy of the state event
     */
    override fun copy(): StateEvent<S>

    companion object {
        /**
         * Converts a DomainEventStream to a StateEvent with the given state.
         *
         * @param state the state of the aggregate
         * @param firstOperator the first operator (default: from header)
         * @param firstEventTime the first event time (default: createTime)
         * @param deleted whether the aggregate is deleted (default: false)
         * @return a StateEvent wrapping this domain event stream
         */
        fun <S : Any> DomainEventStream.toStateEvent(
            state: S,
            firstOperator: String = header.operator.orEmpty(),
            firstEventTime: Long = createTime,
            deleted: Boolean = false
        ): StateEvent<S> =
            StateEventData(
                delegate = this,
                state = state,
                firstOperator = firstOperator,
                firstEventTime = firstEventTime,
                deleted = deleted,
            )

        /**
         * Converts a DomainEventStream to a StateEvent using the state from a ReadOnlyStateAggregate.
         *
         * @param stateAggregate the state aggregate to extract state from
         * @return a StateEvent wrapping this domain event stream
         */
        fun <S : Any> DomainEventStream.toStateEvent(stateAggregate: ReadOnlyStateAggregate<S>): StateEvent<S> =
            StateEventData(
                delegate = this,
                state = stateAggregate.state,
                firstOperator = stateAggregate.firstOperator,
                firstEventTime = stateAggregate.firstEventTime,
                deleted = stateAggregate.deleted,
            )
    }
}

/**
 * Data class implementation of StateEvent that wraps a DomainEventStream with state information.
 *
 * @param delegate the domain event stream being wrapped
 * @param state the state of the aggregate
 * @param firstOperator the first operator (default: from delegate header)
 * @param firstEventTime the first event time (default: from delegate)
 * @param deleted whether the aggregate is deleted (default: false)
 */
data class StateEventData<S : Any>(
    override val delegate: DomainEventStream,
    override val state: S,
    override val firstOperator: String = delegate.header.operator.orEmpty(),
    override val firstEventTime: Long = delegate.createTime,
    override val deleted: Boolean = false
) : StateEvent<S>,
    Decorator<DomainEventStream>,
    DomainEventStream by delegate {
    /**
     * Creates a copy of this StateEventData with a copied delegate.
     *
     * @return a copy of the state event data
     */
    override fun copy(): StateEvent<S> = copy(delegate = delegate.copy())
}
