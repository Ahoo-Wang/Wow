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

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.QueryDataset
import java.util.concurrent.ConcurrentHashMap

/** The query backend of one dataset: the same four primitives as an aggregate model's. */
@WowSpi
interface DatasetQueryBackend : QueryBackend

/**
 * Pairs a dataset's query backend with its storage adapter. It receives the whole [QueryDataset], whose
 * [storage][QueryDataset.storage] says where the records live.
 */
@WowSpi
fun interface DatasetQueryBackendFactory {
    fun create(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend>
}

/**
 * A [DatasetQueryBackendFactory] that creates each dataset's binding once and reuses it while the dataset keeps its
 * declaration; a dataset replaced by another declaration (another table, say) gets a new binding.
 */
@WowSpi
abstract class AbstractDatasetQueryBackendFactory : DatasetQueryBackendFactory {
    private val bindingCache =
        ConcurrentHashMap<MaterializedNamedAggregate, Pair<QueryDataset, QueryBackendBinding<DatasetQueryBackend>>>()

    override fun create(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend> =
        bindingCache.compute(dataset.namedAggregate) { _, cached ->
            cached?.takeIf { it.first == dataset } ?: (dataset to createBinding(dataset))
        }.let(::checkNotNull).second

    protected abstract fun createBinding(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend>
}
