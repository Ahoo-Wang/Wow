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

package me.ahoo.wow.clickhouse.query

import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog

/** A dataset served by a factory: its backend, and the Catalog that compiles its schema from the table's facts. */
class DatasetTarget(val dataset: QueryDataset, factory: DatasetQueryBackendFactory) {
    val datasets = QueryDatasets(listOf(dataset))
    val catalog = QuerySchemaCatalog(
        storages = QueryBackendStorages(datasetFactory = factory, datasets = datasets),
        compiler = QueryModelCompiler.of(datasets = datasets),
        datasets = datasets,
    )
    val backend: DatasetQueryBackend = factory.create(dataset).backend

    suspend fun schema(): QueryModelSchema = catalog.schema(dataset.name, QueryModel.DATASET)
}
