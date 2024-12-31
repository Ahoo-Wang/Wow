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

package me.ahoo.wow.event.compensation

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.messaging.compensation.EventCompensator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import reactor.core.publisher.Mono

class StateEventCompensator(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val stateEventBus: StateEventBus
) : EventCompensator<StateEvent<*>> {

    override fun compensate(eventStream: StateEvent<*>, target: CompensationTarget): Mono<Void> {
        eventStream.withCompensation(target)
        return stateEventBus.send(eventStream)
    }

    override fun resend(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int,
        target: CompensationTarget
    ): Mono<Long> {
        val aggregateMetadata = aggregateId.requiredAggregateType<Any>()
            .aggregateMetadata<Any, Any>()
        return stateAggregateFactory.createAsMono(aggregateMetadata.state, aggregateId).flatMap { stateAggregate ->
            eventStore
                .load(
                    aggregateId = aggregateId,
                    tailVersion = tailVersion,
                )
                .map {
                    stateAggregate.onSourcing(it)
                    it.toStateEvent(stateAggregate)
                }
                .filter {
                    it.version in headVersion..tailVersion
                }
                .concatMap {
                    compensate(it, target).thenReturn(it.aggregateId)
                }.count()
        }
    }
}
