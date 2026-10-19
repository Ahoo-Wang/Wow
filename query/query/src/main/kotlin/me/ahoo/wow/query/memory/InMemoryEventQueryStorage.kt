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

package me.ahoo.wow.query.memory

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.events.EventStreamRecords
import me.ahoo.wow.spi.log.EventStreamRecord
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-memory reference backend of the `events` model (decision D235): the [InMemoryQueryBackend] of each aggregate
 * type over the event streams [streams] gives for it, each as stored ([EventStreamRecords.stored]), in the order
 * given. It passes `QueryBackendSpec` as the snapshot one does, and declares the same capabilities: every filter
 * operator but full-text search, element scopes and their expansion, sorts, pages, counts and aggregation, HAVING,
 * top-N, dense fill and windows left to the core.
 *
 * A host serves the events of a log in memory from it, over the streams the log holds; it reads them all on every
 * query, so it is for local runs, demos and tests.
 */
class InMemoryEventQueryStorage(
    private val streams: suspend (AggregateType) -> List<EventStreamRecord>,
    private val delay: Duration = Duration.ZERO,
) : QueryBackendFactory<InMemoryQueryBackend> {
    private val backends = ConcurrentHashMap<AggregateType, QueryBackendBinding<InMemoryQueryBackend>>()

    override fun create(aggregateType: AggregateType): QueryBackendBinding<InMemoryQueryBackend> =
        backends.computeIfAbsent(aggregateType) { type ->
            QueryBackendBinding(
                InMemoryQueryBackend(type, { streams(type).map(EventStreamRecords::stored) }, delay),
                InMemoryQueryStorageAdapter,
            )
        }
}
