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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import java.util.concurrent.ConcurrentHashMap

/**
 * The query backend of one dataset (the ClickHouse one of work package 6.4a, say): the four primitives of
 * [QueryBackend] over the dataset's table, whose [aggregateType] is the dataset's [name][QueryDataset.name]. Records
 * are the table's rows, fields at the root, read only through `AdmittedQuery.field`/`systemField`; the backend passes
 * [DatasetQueryBackendSpec][me.ahoo.wow.query.tck.DatasetQueryBackendSpec] (`:query` testFixtures).
 */
@ExperimentalWowApi
interface DatasetQueryBackend : QueryBackend

/**
 * Binds a dataset to its backend: the [DatasetQueryBackend] and the storage adapter whose facts (bindings,
 * capabilities, [StorageSupport][me.ahoo.wow.query.schema.StorageSupport]) the schema Catalog compiles the dataset's
 * declaration against. It reads the dataset's [storage][QueryDataset.storage] binding; a dataset it cannot serve
 * (a binding it does not understand) fails with an [IllegalArgumentException].
 */
@ExperimentalWowApi
fun interface DatasetQueryBackendFactory {
    fun create(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend>
}

/**
 * A [DatasetQueryBackendFactory] that creates each dataset's binding once, and again when the dataset changes: a
 * dataset replaced by a new declaration (another table, other columns) gets a binding of its own.
 */
@ExperimentalWowApi
abstract class AbstractDatasetQueryBackendFactory : DatasetQueryBackendFactory {
    private val bindingCache =
        ConcurrentHashMap<AggregateType, Pair<QueryDataset, QueryBackendBinding<DatasetQueryBackend>>>()

    override fun create(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend> =
        checkNotNull(
            bindingCache.compute(dataset.name) { _, cached ->
                cached?.takeIf { it.first == dataset } ?: (dataset to createBinding(dataset))
            }
        ).second

    /** Forgets the binding of [name], as when its dataset left. */
    fun evict(name: AggregateType) {
        bindingCache.remove(name)
    }

    protected abstract fun createBinding(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend>
}
