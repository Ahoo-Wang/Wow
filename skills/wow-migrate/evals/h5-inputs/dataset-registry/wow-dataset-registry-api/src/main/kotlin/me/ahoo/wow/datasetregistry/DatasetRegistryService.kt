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

package me.ahoo.wow.datasetregistry

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.datasetregistry.api.DraftDataset

/**
 * The dataset registry (dataset platform design §4): datasets as data. A data owner drafts a dataset from a table,
 * revises its declaration, reviews the sensitivity of every string field and publishes it; every instance that
 * serves datasets serves the published versions. Its events are the audit trail of who declared what.
 *
 * The one aggregate, [DATASET_AGGREGATE_NAME], is the platform's, not a tenant's: it has a static tenant, so no route
 * carries a tenant. Its id is the dataset's name, `{context}.{dataset}`.
 */
@BoundedContext(
    name = DatasetRegistryService.SERVICE_NAME,
    alias = DatasetRegistryService.SERVICE_ALIAS,
    description = "Datasets declared and published at run time.",
    aggregates = [
        BoundedContext.Aggregate(
            name = DatasetRegistryService.DATASET_AGGREGATE_NAME,
            packageScopes = [DraftDataset::class],
        ),
    ],
)
object DatasetRegistryService {
    const val SERVICE_ALIAS = "dataset-registry"
    const val SERVICE_NAME = SERVICE_ALIAS
    const val DATASET_AGGREGATE_NAME = "dataset"
}
