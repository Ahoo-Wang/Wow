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
import me.ahoo.wow.configuration.asRequiredAggregateType
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.asStateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.compensation.CompensationConfig
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import me.ahoo.wow.messaging.compensation.EventCompensator
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import reactor.core.publisher.Mono

class StateEventCompensator(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val stateEventBus: StateEventBus
) :
    EventCompensator {
    override fun compensate(
        aggregateId: AggregateId,
        config: CompensationConfig,
        headVersion: Int,
        tailVersion: Int
    ): Mono<Long> {
        val aggregateMetadata = aggregateId.asRequiredAggregateType<Any>()
            .asAggregateMetadata<Any, Any>()
        return stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
            .flatMapMany { stateAggregate ->
                eventStore
                    .load(
                        aggregateId = aggregateId,
                        tailVersion = tailVersion,
                    )
                    .map {
                        stateAggregate.onSourcing(it)
                        it.asStateEvent(stateAggregate)
                    }
                    .filter {
                        it.version in headVersion..tailVersion
                    }
                    .concatMap {
                        it.withCompensation(config)
                        stateEventBus.send(it).thenReturn(it.aggregateId)
                    }
            }.count()
    }
}
