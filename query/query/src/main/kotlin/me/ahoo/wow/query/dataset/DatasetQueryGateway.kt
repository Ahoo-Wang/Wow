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
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import java.util.concurrent.ConcurrentHashMap

/**
 * The query gateway of one dataset: the [QueryGateway] operations (`single`, `list`, `paged`, `cursor`, `count`,
 * `aggregate`, `describe`), each with its explicit [QueryCaller][me.ahoo.wow.query.QueryCaller], admitted against the
 * dataset's schema with the same filters, policies (scope and ABAC, decision D129), budgets and masking as an
 * aggregate's snapshots. Its [aggregateType] is the dataset's name.
 */
interface DatasetQueryGateway : QueryGateway {
    val dataset: QueryDataset
}

/** A [DatasetQueryGateway] over [backend], admitting against the schema [schemaProvider] compiles. */
class DefaultDatasetQueryGateway @JvmOverloads constructor(
    override val dataset: QueryDataset,
    backend: DatasetQueryBackend,
    schemaProvider: QueryModelSchemaProvider,
    filters: List<QueryFilter> = emptyList(),
    policies: List<QueryPolicy> = emptyList(),
    observer: QueryObserver = QueryObserver.NONE,
    entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) : DatasetQueryGateway,
    QueryGateway by DefaultQueryGateway(
        dataset.name,
        backend,
        schemaProvider,
        filters,
        policies,
        observer,
        entryPolicy,
    ) {
    init {
        require(backend.aggregateType == dataset.name) {
            "The backend of [${backend.aggregateType}] does not serve dataset [${dataset.name}]."
        }
    }
}

/**
 * The gateways of the datasets a node serves, by dataset name, following [QueryDatasets]: a dataset that joins or
 * changes gets a new gateway, one that leaves loses its own.
 */
class DatasetQueryGateways(gateways: Collection<DatasetQueryGateway>) {
    private val byName = ConcurrentHashMap(gateways.associateBy { it.dataset.name })

    init {
        require(byName.size == gateways.size) { "Dataset gateways must have unique names." }
    }

    val all: Collection<DatasetQueryGateway>
        get() = byName.values

    /** The gateway of [name], or `null` when this node serves no such dataset. */
    operator fun get(name: AggregateType): DatasetQueryGateway? = byName[name]

    companion object {
        @JvmField
        val NONE: DatasetQueryGateways = DatasetQueryGateways(emptyList())

        /**
         * The gateways of [datasets], each [create]d for its dataset, now and whenever one joins or changes.
         * [requireServable] refuses a dataset no gateway can be made for (no backend serves its binding, say) before
         * it joins. A gateway is created once the change is applied, so it reads the schema of the declaration it
         * serves: build this after the Catalog that follows the same [datasets].
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            datasets: QueryDatasets,
            requireServable: (QueryDataset) -> Unit = {},
            create: (QueryDataset) -> DatasetQueryGateway,
        ): DatasetQueryGateways {
            datasets.all.forEach(requireServable)
            val gateways = DatasetQueryGateways(datasets.all.map(create))
            if (datasets === QueryDatasets.NONE) return gateways
            datasets.listen(
                object : QueryDatasetListener {
                    override fun validate(change: QueryDatasetChange) {
                        if (change !is QueryDatasetChange.Removed) requireServable(change.dataset)
                    }

                    override fun apply(change: QueryDatasetChange) {
                        val name = change.dataset.name
                        when (change) {
                            is QueryDatasetChange.Removed -> gateways.byName.remove(name)
                            else -> gateways.byName[name] = create(change.dataset)
                        }
                    }
                },
            )
            return gateways
        }

        /**
         * The gateways of [datasets], each over the backend [factory] binds and the schema [catalog] compiles for it
         * (a Catalog built over the same [datasets] and [factory], see `QueryBackendStorages`); a dataset [factory]
         * cannot bind is refused before it joins.
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("LongParameterList")
        fun of(
            datasets: QueryDatasets,
            catalog: QuerySchemaCatalog,
            factory: DatasetQueryBackendFactory,
            filters: List<QueryFilter> = emptyList(),
            policies: List<QueryPolicy> = emptyList(),
            observer: QueryObserver = QueryObserver.NONE,
            entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
        ): DatasetQueryGateways = of(datasets, requireServable = { factory.create(it) }) { dataset ->
            DefaultDatasetQueryGateway(
                dataset = dataset,
                backend = factory.create(dataset).backend,
                schemaProvider = catalog.provider(dataset.name, QueryModel.DATASET),
                filters = filters,
                policies = policies,
                observer = observer,
                entryPolicy = entryPolicy,
            )
        }
    }
}
