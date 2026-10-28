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

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageLocator
import java.util.concurrent.ConcurrentHashMap

/**
 * What a storage serves for each [K]: the [QueryBackendBinding] of each aggregate type's snapshots or events
 * (`QueryBindings<AggregateType>`), or of each dataset (`QueryBindings<QueryDataset>`), null for a key it does not
 * serve. A dataset whose [storage][QueryDataset.storage] binding it cannot read fails with an
 * [IllegalArgumentException]: that is a misconfiguration, not a key it does not serve.
 */
@WowSpi
fun interface QueryBindings<in K : Any> {
    /** The binding of [key], or null when this storage does not serve it. */
    fun binding(key: K): QueryBackendBinding<QueryBackend>?

    /** Forgets what it holds for [key], as when a dataset left; nothing for bindings that hold nothing. */
    fun forget(key: K) = Unit
}

/**
 * [QueryBindings] that create each key's binding once and reuse it, by the key's name ([nameOf]): a key that no
 * longer equals the one its binding was created for (a dataset redeclared with another table or other columns) gets
 * a binding of its own, and [forget] drops a name's binding. A key it does not serve is asked again each time.
 *
 * [createBinding] must be cheap and free of side effects: callers racing on a name's first binding may each create
 * one, and all but one are dropped. A binding that opens resources would need a cache that creates it once.
 */
@WowSpi
abstract class CachedQueryBindings<K : Any>(private val nameOf: (K) -> AggregateType) : QueryBindings<K> {
    private val cache = ConcurrentHashMap<AggregateType, Pair<K, QueryBackendBinding<QueryBackend>>>()

    override fun binding(key: K): QueryBackendBinding<QueryBackend>? {
        cache[nameOf(key)]?.takeIf { it.first == key }?.let { return it.second }
        val created = createBinding(key) ?: return null
        return checkNotNull(
            cache.compute(nameOf(key)) { _, cached -> cached?.takeIf { it.first == key } ?: (key to created) }
        ).second
    }

    override fun forget(key: K) {
        cache.remove(nameOf(key))
    }

    /** The binding of [key], created once for each name and key, or null when this storage does not serve it. */
    protected abstract fun createBinding(key: K): QueryBackendBinding<QueryBackend>?
}

/**
 * The storages of the query backends, as the [QuerySchemaCatalog][me.ahoo.wow.query.schema.QuerySchemaCatalog]
 * locates them: each aggregate's snapshots from [snapshots], its event streams from [events], each dataset of
 * [datasets] from [datasetBindings]; `null` for a model without bindings, a key they do not serve, a dataset that is
 * not declared and any other model.
 */
@InternalWowApi
class QueryBackendStorages @JvmOverloads constructor(
    private val snapshots: QueryBindings<AggregateType>? = null,
    private val datasetBindings: QueryBindings<QueryDataset>? = null,
    private val datasets: QueryDatasets = QueryDatasets.NONE,
    private val events: QueryBindings<AggregateType>? = null,
) : QueryStorageLocator {
    override fun storage(aggregateType: AggregateType, model: QueryModel): QueryStorageAdapter? = when (model) {
        QueryModel.SNAPSHOT -> snapshots?.binding(aggregateType)?.storage
        QueryModel.EVENTS -> events?.binding(aggregateType)?.storage
        QueryModel.DATASET -> datasets[aggregateType]?.let { datasetBindings?.binding(it)?.storage }
        else -> null
    }
}
