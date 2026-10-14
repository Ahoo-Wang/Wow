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
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import java.util.concurrent.CopyOnWriteArrayList

/** One change to the [QueryDatasets]: a dataset joined, was replaced by a new declaration, or left. */
@ExperimentalWowApi
sealed interface QueryDatasetChange {
    /** The dataset the change leaves in place, or the one it removed. */
    val dataset: QueryDataset

    data class Added(override val dataset: QueryDataset) : QueryDatasetChange

    data class Replaced(val previous: QueryDataset, override val dataset: QueryDataset) : QueryDatasetChange

    data class Removed(override val dataset: QueryDataset) : QueryDatasetChange
}

/**
 * Follows the changes of [QueryDatasets]: every listener [validate]s a change before any [apply]s it, so a listener
 * that cannot follow a change (a gateway that cannot be built, a name an aggregate takes) refuses it for all of them.
 */
@ExperimentalWowApi
interface QueryDatasetListener {
    /** Refuses [change] by throwing; nothing has changed yet. */
    fun validate(change: QueryDatasetChange) = Unit

    /** Follows [change], which every listener accepted and the datasets already show. */
    fun apply(change: QueryDatasetChange)
}

/**
 * The runtime catalog of the datasets this node serves (decision D75): declared in code, read from files
 * ([QueryDatasetFiles]) or registered while the node runs, as dataset-registry does. The schema Catalog, the dataset
 * gateways and the storages follow it through [listen].
 */
@ExperimentalWowApi
class QueryDatasets(datasets: Collection<QueryDataset> = emptyList()) {
    @Volatile
    private var byName: Map<AggregateType, QueryDataset> = datasets.associateBy { it.name }
    private val listeners = CopyOnWriteArrayList<QueryDatasetListener>()

    init {
        require(byName.size == datasets.size) { "Datasets must have unique names: ${datasets.map { it.name }}." }
    }

    val all: Collection<QueryDataset>
        get() = byName.values

    operator fun get(name: AggregateType): QueryDataset? = byName[name]

    /** Follows every later change; the datasets already here are [all]. */
    fun listen(listener: QueryDatasetListener) {
        check(this !== NONE) { "QueryDatasets.NONE never changes." }
        listeners += listener
    }

    /** Adds [dataset]; refused when a dataset of that name is already here, or a listener refuses it. */
    fun add(dataset: QueryDataset) = change {
        require(dataset.name !in byName) { "Dataset [${dataset.name}] is already served." }
        QueryDatasetChange.Added(dataset)
    }

    /** Replaces the dataset of [dataset]'s name with this declaration; refused when there is none. */
    fun replace(dataset: QueryDataset) = change {
        val previous = requireNotNull(byName[dataset.name]) { "Dataset [${dataset.name}] is not served." }
        QueryDatasetChange.Replaced(previous, dataset)
    }

    /** Adds [dataset], or replaces the one of its name. */
    fun put(dataset: QueryDataset) = change {
        byName[dataset.name]?.let { QueryDatasetChange.Replaced(it, dataset) } ?: QueryDatasetChange.Added(dataset)
    }

    /** Removes the dataset of [name]; `false` when there is none. */
    fun remove(name: AggregateType): Boolean {
        var removed = false
        change {
            val dataset = byName[name] ?: return@change null
            removed = true
            QueryDatasetChange.Removed(dataset)
        }
        return removed
    }

    private inline fun change(propose: () -> QueryDatasetChange?) {
        check(this !== NONE) { "QueryDatasets.NONE never changes." }
        synchronized(this) {
            val change = propose() ?: return
            listeners.forEach { it.validate(change) }
            val name = change.dataset.name
            byName = when (change) {
                is QueryDatasetChange.Removed -> byName - name
                else -> byName + (name to change.dataset)
            }
            listeners.forEach { it.apply(change) }
        }
    }

    /** The profile of [context]'s model: the dataset's own for [QueryModel.DATASET], the built-in one otherwise. */
    fun profile(context: QuerySchemaContext): QueryModelProfile? = if (context.model == QueryModel.DATASET) {
        get(context.aggregateType)?.profile
    } else {
        QueryModelProfile.of(context.model)
    }

    /**
     * The schema source of the datasets' fields: a dataset's declaration is the base of its model, as the Manifest is
     * for an aggregate's, and other sources (a bean) may enrich it at a higher priority.
     */
    val schemaSource: QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.MANIFEST

        override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> =
            if (context.model == QueryModel.DATASET) {
                listOfNotNull(get(context.aggregateType)?.declaration)
            } else {
                emptyList()
            }
    }

    companion object {
        /** No dataset, ever: it refuses every change. */
        @JvmField
        val NONE: QueryDatasets = QueryDatasets(emptyList())
    }
}
