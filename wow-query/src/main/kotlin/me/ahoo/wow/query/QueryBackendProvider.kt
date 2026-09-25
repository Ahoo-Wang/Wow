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

package me.ahoo.wow.query

import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory

/**
 * The registration SPI of a query storage (design §5.5): under one [name], the factories that pair a query backend
 * with its schema provider for each aggregate, one per read model the storage serves.
 *
 * Hosts collect every provider and route by name, so a new storage only implements its backends and registers a
 * provider; the host needs no change. Several providers may share a name when they supply different read models, as
 * when a storage's snapshot and event-stream backends are configured independently; two providers of one name must
 * not supply the same read model.
 */
interface QueryBackendProvider {
    /** The storage or binding name routes refer to, for example `mongo` or `elasticsearch`. */
    val name: String

    /** The snapshot read model, when this provider serves it. */
    val snapshot: SnapshotQueryBackendFactory?
        get() = null

    /** The event-stream read model, when this provider serves it. */
    val eventStream: EventStreamQueryBackendFactory?
        get() = null

    companion object {
        @JvmStatic
        fun snapshot(name: String, factory: SnapshotQueryBackendFactory): QueryBackendProvider =
            SimpleQueryBackendProvider(name, snapshot = factory)

        @JvmStatic
        fun eventStream(name: String, factory: EventStreamQueryBackendFactory): QueryBackendProvider =
            SimpleQueryBackendProvider(name, eventStream = factory)
    }
}

data class SimpleQueryBackendProvider(
    override val name: String,
    override val snapshot: SnapshotQueryBackendFactory? = null,
    override val eventStream: EventStreamQueryBackendFactory? = null,
) : QueryBackendProvider {
    init {
        require(name.isNotBlank()) { "Query backend provider name must not be blank." }
        require(snapshot != null || eventStream != null) { "Query backend provider [$name] supplies no read model." }
    }
}

/** Every provider's read models by name; rejects two providers that supply one read model under one name. */
class QueryBackendProviders(providers: List<QueryBackendProvider>) {
    val snapshots: Map<String, SnapshotQueryBackendFactory> = index(providers, "snapshot") { it.snapshot }
    val eventStreams: Map<String, EventStreamQueryBackendFactory> = index(providers, "event-stream") { it.eventStream }

    private fun <F : Any> index(
        providers: List<QueryBackendProvider>,
        model: String,
        factory: (QueryBackendProvider) -> F?,
    ): Map<String, F> = providers.mapNotNull { provider -> factory(provider)?.let { provider.name to it } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (name, factories) ->
            require(factories.size == 1) { "Query backend providers [$name] supply the $model read model twice." }
            factories.single()
        }
}
