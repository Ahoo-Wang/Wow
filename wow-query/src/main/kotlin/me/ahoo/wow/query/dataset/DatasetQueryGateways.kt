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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasetChange
import me.ahoo.wow.query.schema.QueryDatasetListener
import me.ahoo.wow.query.schema.QueryDatasets
import java.util.concurrent.ConcurrentHashMap

/**
 * The gateways of the datasets this instance serves, by name: what the dataset routes and applications look up. Built
 * [from][of] [QueryDatasets], it follows them: a dataset that joins gets a gateway, one replaced by another
 * declaration gets a new one (its schema and binding are the new declaration's), one that leaves loses it.
 */
class DatasetQueryGateways(gateways: Collection<DatasetQueryGateway>) {
    private val byName = ConcurrentHashMap<MaterializedNamedAggregate, DatasetQueryGateway>(
        gateways.associateBy { it.dataset.namedAggregate },
    )

    init {
        require(byName.size == gateways.size) { "Dataset gateways must have unique names." }
    }

    val all: Collection<DatasetQueryGateway>
        get() = byName.values

    /** The gateway of [namedAggregate], or `null` when this instance serves no such dataset. */
    operator fun get(namedAggregate: NamedAggregate): DatasetQueryGateway? = byName[namedAggregate.materialize()]

    companion object {
        @JvmField
        val NONE: DatasetQueryGateways = DatasetQueryGateways(emptyList())

        /**
         * The gateways of [datasets], each [created][create] for its dataset, now and whenever one joins or changes.
         * [requireServable] refuses a dataset no gateway can be made for (no backend serves datasets, say) before it joins.
         * A gateway is created once the change is applied, so it reads the schema of the declaration it serves: build
         * this after the Catalog that follows the same [datasets].
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
                        val name = change.dataset.namedAggregate
                        when (change) {
                            is QueryDatasetChange.Removed -> gateways.byName.remove(name)
                            else -> gateways.byName[name] = create(change.dataset)
                        }
                    }
                },
            )
            return gateways
        }
    }
}
