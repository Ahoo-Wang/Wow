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

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageLocator
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
@WowSpi
interface QueryBackendProvider {
    /** The storage or binding name routes refer to, for example `mongo` or `elasticsearch`. */
    val name: String

    /** The snapshot read model, when this provider serves it. */
    val snapshot: SnapshotQueryBackendFactory?
        get() = null

    /** The event-stream read model, when this provider serves it. */
    val eventStream: EventStreamQueryBackendFactory?
        get() = null

    /** The dataset read model, when this provider serves it. */
    val dataset: DatasetQueryBackendFactory?
        get() = null

    companion object {
        @JvmStatic
        fun snapshot(name: String, factory: SnapshotQueryBackendFactory): QueryBackendProvider =
            SimpleQueryBackendProvider(name, snapshot = factory)

        @JvmStatic
        fun eventStream(name: String, factory: EventStreamQueryBackendFactory): QueryBackendProvider =
            SimpleQueryBackendProvider(name, eventStream = factory)

        @JvmStatic
        fun dataset(name: String, factory: DatasetQueryBackendFactory): QueryBackendProvider =
            SimpleQueryBackendProvider(name, dataset = factory)
    }
}

@WowSpi
data class SimpleQueryBackendProvider(
    override val name: String,
    override val snapshot: SnapshotQueryBackendFactory? = null,
    override val eventStream: EventStreamQueryBackendFactory? = null,
    override val dataset: DatasetQueryBackendFactory? = null,
) : QueryBackendProvider {
    init {
        require(name.isNotBlank()) { "Query backend provider name must not be blank." }
        require(snapshot != null || eventStream != null || dataset != null) {
            "Query backend provider [$name] supplies no read model."
        }
    }
}

/** Every provider's read models by name; rejects two providers that supply one read model under one name. */
@WowSpi
class QueryBackendProviders(providers: List<QueryBackendProvider>) {
    val snapshots: Map<String, SnapshotQueryBackendFactory> = index(providers, "snapshot") { it.snapshot }
    val eventStreams: Map<String, EventStreamQueryBackendFactory> = index(providers, "event-stream") { it.eventStream }
    val datasets: Map<String, DatasetQueryBackendFactory> = index(providers, "dataset") { it.dataset }

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

/**
 * The storages of the query backends [snapshots] and [eventStreams] create, as the
 * [QuerySchemaCatalog][me.ahoo.wow.query.schema.QuerySchemaCatalog] locates them: each aggregate model's
 * [QueryBackendBinding.storage], each declared dataset's from [datasetFactory], or `null` for a read model without a
 * factory, an undeclared dataset and another custom model.
 */
class QueryBackendStorages @JvmOverloads constructor(
    private val snapshots: SnapshotQueryBackendFactory? = null,
    private val eventStreams: EventStreamQueryBackendFactory? = null,
    private val datasetFactory: DatasetQueryBackendFactory? = null,
    private val datasets: QueryDatasets = QueryDatasets.NONE,
) : QueryStorageLocator {
    override fun storage(namedAggregate: NamedAggregate, model: QueryModel): QueryStorageAdapter? = when (model) {
        QueryModel.SNAPSHOT -> snapshots?.create(namedAggregate)?.storage
        QueryModel.EVENT_STREAM -> eventStreams?.create(namedAggregate)?.storage
        QueryModel.DATASET -> datasets[namedAggregate]?.let { datasetFactory?.create(it)?.storage }
        else -> null
    }
}
