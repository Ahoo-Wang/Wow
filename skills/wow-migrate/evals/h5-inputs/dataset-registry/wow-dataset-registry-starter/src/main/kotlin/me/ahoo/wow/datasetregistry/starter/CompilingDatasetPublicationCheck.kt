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

package me.ahoo.wow.datasetregistry.starter

import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.datasetregistry.domain.DatasetPublicationCheck
import me.ahoo.wow.datasetregistry.domain.DatasetRegistryException
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import reactor.core.publisher.Mono

/**
 * Proves this instance can serve a dataset before the registry publishes it: its name is not an aggregate's of its
 * context nor a declaration file's dataset, a dataset backend serves datasets here, and the declaration compiles
 * against its table as the Catalog will compile it.
 */
class CompilingDatasetPublicationCheck(
    private val datasets: QueryDatasets,
    private val registered: () -> Set<MaterializedNamedAggregate>,
    private val aggregates: Set<MaterializedNamedAggregate>,
    private val factory: DatasetQueryBackendFactory?,
) : DatasetPublicationCheck {
    override fun check(dataset: QueryDataset): Mono<Void> = Mono.defer {
        val name = dataset.namedAggregate
        if (name in aggregates) {
            throw DatasetRegistryException.nameTaken("Dataset [$name] takes the name of an aggregate of its context.")
        }
        if (datasets[name] != null && name !in registered()) {
            throw DatasetRegistryException.nameTaken(
                "Dataset [$name] is declared by a file, which wins over the registry."
            )
        }
        val backend = factory ?: throw DatasetRegistryException.notPublishable(
            "Dataset [$name] has no dataset query backend to serve it here.",
        )
        QueryModelCompiler.of(datasets = QueryDatasets(listOf(dataset)))
            .compile(QuerySchemaContext(name, QueryModel.DATASET), backend.create(dataset).storage)
            .schema()
            .onErrorMap { error ->
                DatasetRegistryException.notPublishable(
                    "Dataset [$name] does not compile against its table: ${error.message}",
                    error,
                )
            }
            .then()
    }
}
