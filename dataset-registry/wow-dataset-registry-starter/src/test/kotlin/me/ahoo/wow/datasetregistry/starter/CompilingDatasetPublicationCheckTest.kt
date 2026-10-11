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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.datasetregistry.api.DatasetRegistryErrorCodes
import me.ahoo.wow.datasetregistry.domain.DatasetRegistryException
import me.ahoo.wow.datasetregistry.starter.RegistryFixtures.ADAPTER
import me.ahoo.wow.datasetregistry.starter.RegistryFixtures.document
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QueryStorageAdapter
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class CompilingDatasetPublicationCheckTest {
    private val orders = MaterializedNamedAggregate("analytics", "orders")
    private val dataset = QueryDataset.parse(orders, document().toString())

    private fun factory(adapter: QueryStorageAdapter = ADAPTER) =
        DatasetQueryBackendFactory { QueryBackendBinding<DatasetQueryBackend>(NoDatasetBackend, adapter) }

    private fun check(
        datasets: QueryDatasets = QueryDatasets(),
        registered: Set<MaterializedNamedAggregate> = emptySet(),
        aggregates: Set<MaterializedNamedAggregate> = emptySet(),
        factory: DatasetQueryBackendFactory? = factory(),
    ) = CompilingDatasetPublicationCheck(datasets, { registered }, aggregates, factory).check(dataset)

    private fun Mono<Void>.refusedWith(errorCode: String, message: String) = test().expectErrorSatisfies {
        it.assert().isInstanceOf(DatasetRegistryException::class.java)
        (it as DatasetRegistryException).errorCode.assert().isEqualTo(errorCode)
        it.errorMsg.assert().contains(message)
    }.verify()

    @Test
    fun `a dataset that compiles against its table and takes no other name passes`() {
        check().test().verifyComplete()
        check(datasets = QueryDatasets(listOf(dataset)), registered = setOf(orders)).test().verifyComplete()
    }

    @Test
    fun `a dataset is refused the name of an aggregate or of a declaration file's dataset`() {
        check(aggregates = setOf(orders)).refusedWith(DatasetRegistryErrorCodes.DATASET_NAME_TAKEN, "aggregate")
        check(
            datasets = QueryDatasets(listOf(dataset))
        ).refusedWith(DatasetRegistryErrorCodes.DATASET_NAME_TAKEN, "file")
    }

    @Test
    fun `a dataset is refused without a backend or when it does not compile`() {
        check(factory = null).refusedWith(DatasetRegistryErrorCodes.DATASET_NOT_PUBLISHABLE, "no dataset query backend")
        check(factory = factory { Mono.error(IllegalStateException("table missing")) })
            .refusedWith(DatasetRegistryErrorCodes.DATASET_NOT_PUBLISHABLE, "table missing")
    }
}
