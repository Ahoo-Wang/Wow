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
package me.ahoo.wow.eventsourcing

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * Event Store State Aggregate Repository .
 *
 * @author ahoo wang
 */
class EventStoreStateAggregateRepository(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore
) : StateAggregateRepository {
    companion object {
        private val log = LoggerFactory.getLogger(EventStoreStateAggregateRepository::class.java)
    }

    override fun <S : Any> load(
        aggregateId: AggregateId,
        metadata: StateAggregateMetadata<S>,
        version: Int
    ): Mono<StateAggregate<S>> {
        if (log.isDebugEnabled) {
            log.debug("Load {} version:{}.", aggregateId, version)
        }
        return stateAggregateFactory.create(metadata, aggregateId)
            .flatMap { stateAggregate ->
                eventStore
                    .load(
                        aggregateId = aggregateId,
                        headVersion = stateAggregate.expectedNextVersion,
                        tailVersion = version
                    )
                    .map {
                        stateAggregate.onSourcing(it)
                    }
                    .then(Mono.just(stateAggregate))
            }
    }
}
