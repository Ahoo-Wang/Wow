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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.memory.InMemoryQueryStorageAdapter
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode

/** What the dataset gateway tests share: the orders dataset per test, its catalog, its gateway and a recording backend. */
internal abstract class DatasetQueryGatewayFixtures {
    protected val orders = QueryDatasetTest.orders()

    protected fun catalog(
        dataset: QueryDataset,
        backend: QueryBackend = RecordingBackend(),
        adapter: QueryStorageAdapter = ADAPTER,
    ): QuerySchemaCatalog {
        val datasets = QueryDatasets(listOf(dataset))
        return QuerySchemaCatalog(
            storages = QueryBackendStorages(
                datasetBindings = { QueryBackendBinding(backend, adapter) },
                datasets = datasets
            ),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
    }

    protected fun gateway(
        backend: QueryBackend = RecordingBackend(),
        dataset: QueryDataset = orders,
        policies: List<QueryPolicy> = emptyList(),
        entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
        adapter: QueryStorageAdapter = ADAPTER,
    ): DatasetQueryGateway = DefaultDatasetQueryGateway(
        dataset = dataset,
        backend = backend,
        schemaProvider = catalog(dataset, backend, adapter).provider(dataset.name, QueryModel.DATASET),
        policies = policies,
        entryPolicy = entryPolicy,
    )

    protected class RecordingBackend(override val aggregateType: AggregateType = QueryDatasetTest.ORDERS) : QueryBackend {
        val admitted = mutableListOf<AdmittedQuery<*>>()
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        override fun stream(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> {
            admitted += query
            return flowOf(row())
        }

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
            admitted += query
            return BackendPage(listOf(row()), total = 1, positions = listOf(CursorPosition(listOf("o1"))))
        }

        override suspend fun count(query: AdmittedQuery<CountQuery>): Long {
            admitted += query
            return 1
        }

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> {
            admitted += query
            return flowOf(WowJson.mapper.createObjectNode().put("orders", 1))
        }

        private fun row(): ObjectNode = WowJson.mapper.readTree(
            """{"order_id":"o1","tenant_id":"t1","owner_id":"u1","city":"Hangzhou","amount":1.5,"updated_at":1,"phone":"13800001234"}""",
        ) as ObjectNode
    }

    protected companion object {
        val OTHER = AggregateType("analytics", "other")
        val ADAPTER = InMemoryQueryStorageAdapter
    }
}
