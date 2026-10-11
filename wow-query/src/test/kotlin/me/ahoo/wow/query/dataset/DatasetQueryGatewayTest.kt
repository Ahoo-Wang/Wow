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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryBackendProvider
import me.ahoo.wow.query.QueryBackendProviders
import me.ahoo.wow.query.QueryBackendStorages
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.SimpleQueryBackendProvider
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.cursorQuery
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.schema.DatasetQueryModelProfile
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.withQueryScope
import me.ahoo.wow.serialization.toObjectNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

class DatasetQueryGatewayTest {
    private val orders = QueryDatasetTest.orders()

    @Test
    fun `the catalog compiles a dataset's fields at the record root with its own profile`() {
        val schema = catalog(orders).schema(orders.namedAggregate, QueryModel.DATASET).block()!!
        schema.model.assert().isEqualTo(QueryModel.DATASET)
        schema.profile.assert().isInstanceOf(DatasetQueryModelProfile::class.java)
        schema.field(QueryField("city")).assert().isNotNull()
        gateway().describe(QueryEntry.HTTP).block()!!.record.identity.assert().isEqualTo("order_id")
    }

    @Test
    fun `the caller scope pins the dataset's tenant column`() {
        val backend = RecordingBackend()
        gateway(backend).dynamicList(listQuery { filter { "city" eq "Hangzhou" } })
            .contextWrite { it.withQueryScope(TenantIdFilter("t1")) }
            .test()
            .expectNextCount(1)
            .verifyComplete()
        val admitted = backend.admitted.single()
        val tenant = ((admitted.query as IListQuery).filter as AndFilter).operands.single { it is TenantIdFilter }
        admitted.systemPath(tenant).assert().isEqualTo("tenant_id")
    }

    @Test
    fun `a system field the dataset does not carry names an unknown field`() {
        gateway().count(AggregateIdFilter("o1")).test().expectErrorSatisfies {
            it.assert().isInstanceOf(QuerySchemaValidationException::class.java).hasMessageContaining("[aggregateId]")
        }.verify()
    }

    @Test
    fun `a cursor needs the dataset's identity`() {
        val keyless = QueryDataset.of(orders.namedAggregate) {
            tenant("tenant_id")
            field("tenant_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("city") {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        gateway(dataset = keyless).dynamicCursor(cursorQuery { size(10) }).test().expectErrorSatisfies {
            it.assert().isInstanceOf(QuerySchemaValidationException::class.java).hasMessageContaining("identity")
        }.verify()
        gateway().dynamicCursor(cursorQuery { size(10) }).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `results mask sensitive columns, which cannot be compared`() {
        gateway().dynamicList(listQuery { }).test()
            .assertNext { it.get("phone").stringValue().assert().isEqualTo("*******1234") }
            .verifyComplete()
        gateway().dynamicList(listQuery { filter { "phone" eq "13800001234" } })
            .test().expectError().verify()
    }

    @Test
    fun `policies see the dataset by its name and model`() {
        val seen = mutableListOf<Pair<NamedAggregate, QueryModel>>()
        val policy = QueryPolicy { _, context ->
            seen += context.namedAggregate to context.schema.model
            Mono.just(MatchAllFilter)
        }
        gateway(policies = listOf(policy)).aggregate(aggregation { count("orders") }).test()
            .expectNextCount(1).verifyComplete()
        seen.single().assert().isEqualTo(orders.namedAggregate to QueryModel.DATASET)
    }

    @Test
    fun `a dataset never takes the name of an aggregate of its context`() {
        assertThrows<IllegalArgumentException> {
            QuerySchemaCatalog(aggregates = listOf(orders.namedAggregate), datasets = listOf(orders.namedAggregate))
        }
    }

    @Test
    fun `the catalog revalidates every dataset`() {
        catalog(orders).versions().test()
            .assertNext { status ->
                status.model.assert().isEqualTo(QueryModel.DATASET)
                status.version.assert().isNotNull()
            }
            .verifyComplete()
    }

    @Test
    fun `providers register a dataset backend and storages locate it by name`() {
        val factory = DatasetQueryBackendFactory { QueryBackendBinding(RecordingBackend(), ADAPTER) }
        val providers = QueryBackendProviders(listOf(QueryBackendProvider.dataset("clickhouse", factory)))
        providers.datasets.getValue("clickhouse").assert().isSameAs(factory)
        providers.snapshots.assert().isEmpty()
        assertThrows<IllegalArgumentException> { SimpleQueryBackendProvider("none") }
        val storages = QueryBackendStorages(datasetFactory = factory, datasets = QueryDatasets(listOf(orders)))
        storages.storage(orders.namedAggregate, QueryModel.DATASET).assert().isSameAs(ADAPTER)
        storages.storage(QueryDatasetTest.OTHER, QueryModel.DATASET).assert().isNull()
        storages.storage(orders.namedAggregate, QueryModel.SNAPSHOT).assert().isNull()
    }

    private fun catalog(dataset: QueryDataset, backend: DatasetQueryBackend = RecordingBackend()): QuerySchemaCatalog {
        val datasets = QueryDatasets(listOf(dataset))
        return QuerySchemaCatalog(
            storages = QueryBackendStorages(
                datasetFactory = { QueryBackendBinding(backend, ADAPTER) },
                datasets = datasets,
            ),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = listOf(dataset.namedAggregate),
        )
    }

    private fun gateway(
        backend: DatasetQueryBackend = RecordingBackend(),
        dataset: QueryDataset = orders,
        policies: List<QueryPolicy> = emptyList(),
    ): DatasetQueryGateway = DefaultDatasetQueryGateway(
        dataset = dataset,
        backend = backend,
        schemaProvider = catalog(dataset, backend).provider(dataset.namedAggregate, QueryModel.DATASET),
        policies = policies,
    )

    private class RecordingBackend : DatasetQueryBackend {
        val admitted = mutableListOf<AdmittedQuery<*>>()
        override val namedAggregate: NamedAggregate = QueryDatasetTest.ORDERS
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> {
            admitted += query
            return Flux.just(row())
        }

        override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> {
            admitted += query
            return Mono.just(
                BackendPage(
                    listOf(row()),
                    total = 1,
                    positions = listOf(me.ahoo.wow.query.CursorPosition(listOf("o1")))
                )
            )
        }

        override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> {
            admitted += query
            return Mono.just(1)
        }

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> {
            admitted += query
            return Flux.just("""{"orders":1}""".toObjectNode())
        }

        private fun row(): ObjectNode =
            """{"order_id":"o1","tenant_id":"t1","owner_id":"u1","city":"Hangzhou","amount":1.5,"updated_at":1,"phone":"13800001234"}"""
                .toObjectNode()
    }

    private companion object {
        val CAPABILITIES = setOf(
            QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH, QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE, QueryCapability.SORT, QueryCapability.CURSOR_SORT,
            QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC, QueryCapability.AGGREGATE_TEMPORAL,
        )

        /** Binds every declared root column to itself, with every capability. */
        val ADAPTER = QueryStorageAdapter { logical ->
            Mono.just(
                QueryStorageFacts(
                    bindings = logical.values.keys.filter { it.segments.size == 1 }.associateWith { path ->
                        val native = QueryFieldBindingTemplate(path, null)
                        QueryValueBindings(CAPABILITIES.associateWith { native }, path, path)
                    },
                ),
            )
        }
    }
}
