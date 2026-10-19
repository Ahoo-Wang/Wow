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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageLocator
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairs a query backend with its storage adapter, for each aggregate type: what a storage module (PostgreSQL 6.2a,
 * MongoDB 6.2b) registers for the snapshot model.
 */
@WowSpi
fun interface QueryBackendFactory<out B : QueryBackend> {
    fun create(aggregateType: AggregateType): QueryBackendBinding<B>
}

/** A [QueryBackendFactory] that creates each aggregate type's binding once and reuses it. */
abstract class AbstractQueryBackendFactory<B : QueryBackend> : QueryBackendFactory<B> {
    private val bindingCache = ConcurrentHashMap<AggregateType, QueryBackendBinding<B>>()

    override fun create(aggregateType: AggregateType): QueryBackendBinding<B> =
        bindingCache.computeIfAbsent(aggregateType, ::createBinding)

    protected abstract fun createBinding(aggregateType: AggregateType): QueryBackendBinding<B>
}

/** Routes each aggregate type to the factory of its route, or to [defaultFactory]. */
internal open class RoutingQueryBackendFactory<B : QueryBackend>(
    private val defaultFactory: QueryBackendFactory<B>,
    routes: Map<AggregateType, QueryBackendFactory<B>>,
) : QueryBackendFactory<B> {
    private val routes: Map<AggregateType, QueryBackendFactory<B>> = routes.toMap()

    override fun create(aggregateType: AggregateType): QueryBackendBinding<B> =
        (routes[aggregateType] ?: defaultFactory).create(aggregateType)
}

/**
 * The storages of the query backends, as the [QuerySchemaCatalog][me.ahoo.wow.query.schema.QuerySchemaCatalog]
 * locates them: each aggregate's snapshots from [snapshots], its event streams from [events] (decision D235), each
 * dataset of [datasets] from [datasetFactory]; `null` for a model without a factory, a dataset that is not declared
 * and any other model. [events] answers `null` for an aggregate type no events backend serves.
 */
class QueryBackendStorages @JvmOverloads constructor(
    private val snapshots: QueryBackendFactory<QueryBackend>? = null,
    private val datasetFactory: DatasetQueryBackendFactory? = null,
    private val datasets: QueryDatasets = QueryDatasets.NONE,
    private val events: ((AggregateType) -> QueryBackendBinding<QueryBackend>?)? = null,
) : QueryStorageLocator {
    override fun storage(aggregateType: AggregateType, model: QueryModel): QueryStorageAdapter? = when (model) {
        QueryModel.SNAPSHOT -> snapshots?.create(aggregateType)?.storage
        QueryModel.EVENTS -> events?.invoke(aggregateType)?.storage
        QueryModel.DATASET -> datasets[aggregateType]?.let { datasetFactory?.create(it)?.storage }
        else -> null
    }
}
