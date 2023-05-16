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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.webflux.ExceptionHandler
import me.ahoo.wow.webflux.route.appender.RoutePaths
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

data class BatchRegenerateSnapshotResult(
    val cursorId: String,
    val size: Int
)

class BatchRegenerateSnapshotHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository,
    private val exceptionHandler: ExceptionHandler,
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val cursorId = request.pathVariable(RoutePaths.BATCH_REGENERATE_SNAPSHOT_CURSOR_ID)
        val limit = request.pathVariable(RoutePaths.BATCH_REGENERATE_SNAPSHOT_LIMIT).toInt()
        return snapshotRepository.scrollAggregateId(aggregateMetadata.namedAggregate, cursorId, limit)
            .flatMap { aggregateId ->
                regenerateSnapshot(
                    aggregateMetadata = aggregateMetadata,
                    stateAggregateFactory = stateAggregateFactory,
                    eventStore = eventStore,
                    snapshotRepository = snapshotRepository,
                    aggregateId = aggregateId
                )
            }
            .reduce(BatchRegenerateSnapshotResult(cursorId, 0)) { acc, snapshot ->
                val nextCursorId = if (snapshot.aggregateId.id > acc.cursorId) {
                    snapshot.aggregateId.id
                } else {
                    acc.cursorId
                }
                BatchRegenerateSnapshotResult(nextCursorId, acc.size + 1)
            }
            .flatMap {
                ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(it)
            }
            .onErrorResume {
                exceptionHandler.handle(it)
            }
    }

    companion object {
        fun regenerateSnapshot(
            aggregateMetadata: AggregateMetadata<*, *>,
            stateAggregateFactory: StateAggregateFactory,
            eventStore: EventStore,
            snapshotRepository: SnapshotRepository,
            aggregateId: AggregateId
        ): Mono<Snapshot<*>> =
            stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
                .flatMap { stateAggregate ->
                    eventStore
                        .load(
                            aggregateId = aggregateId,
                            headVersion = stateAggregate.expectedNextVersion
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
