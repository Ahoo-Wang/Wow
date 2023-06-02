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

package me.ahoo.wow.webflux.handler

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotSink
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.webflux.route.BatchResult
import reactor.core.publisher.Mono

class SnapshotSinkHandler(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotSink: SnapshotSink,
) {
    fun handle(cursorId: String, limit: Int): Mono<BatchResult> {
        return eventStore.scanAggregateId(aggregateMetadata.namedAggregate, cursorId, limit)
            .flatMap({ aggregateId ->
                stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
                    .flatMapMany { stateAggregate ->
                        eventStore
                            .load(
                                aggregateId = aggregateId,
                                headVersion = stateAggregate.expectedNextVersion,
                            )
                            .map {
                                stateAggregate.onSourcing(it)
                            }.flatMapSequential {
                                val snapshot = SimpleSnapshot(it)
                                snapshotSink.sink(snapshot).thenReturn(snapshot)
                            }
                    }
            }, limit)
            .reduce(BatchResult(cursorId, 0)) { acc, snapshot ->
                val nextCursorId = if (snapshot.aggregateId.id > acc.cursorId) {
                    snapshot.aggregateId.id
                } else {
                    acc.cursorId
                }
                BatchResult(nextCursorId, acc.size + 1)
            }
    }
}
