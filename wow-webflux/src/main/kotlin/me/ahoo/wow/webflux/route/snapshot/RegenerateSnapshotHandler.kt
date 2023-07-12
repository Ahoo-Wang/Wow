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

package me.ahoo.wow.webflux.route.snapshot

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import reactor.core.publisher.Mono

class RegenerateSnapshotHandler(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository
) {

    fun handle(aggregateId: AggregateId): Mono<Snapshot<*>> {
        return stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
            .flatMap { stateAggregate ->
                eventStore
                    .load(
                        aggregateId = aggregateId,
                        headVersion = stateAggregate.expectedNextVersion,
                    )
                    .map {
                        stateAggregate.onSourcing(it)
                    }
                    .then(Mono.just(stateAggregate))
            }.filter {
                it.initialized
            }.flatMap {
                val snapshot = SimpleSnapshot(it)
                snapshotRepository.save(snapshot).thenReturn(snapshot)
            }
    }
}
