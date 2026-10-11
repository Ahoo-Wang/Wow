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
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
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
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.DatasetQueryModelProfile
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasetChange
import me.ahoo.wow.query.schema.QueryDatasetListener
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.query.snapshot.filter.AbacQueryOptions
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy
import me.ahoo.wow.query.withQueryScope
import me.ahoo.wow.serialization.toObjectNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.util.context.Context
import reactor.util.context.ContextView
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
    fun `ABAC restricts a dataset by its tag columns, a string and a string array`() {
        val tagged = tagged()
        val schema = catalog(tagged).schema(tagged.namedAggregate, QueryModel.DATASET).block()!!
        fun condition(tags: AbacTags, options: AbacQueryOptions = AbacQueryOptions.DEFAULT): FilterExpression =
            abac(tags, options).evaluate(Context.empty(), context(schema)).block()!!

        condition(mapOf("region" to listOf("east"), "dept" to listOf("eng"), "role" to listOf("admin")))
            .assert().isEqualTo(
                AndFilter(
                    listOf(
                        filter { "region" isIn listOf("east", "") },
                        filter {
                            or {
                                "depts".isEmptyCollection()
                                "depts" isIn listOf("eng")
                            }
                        },
                    ),
                ),
            )
        condition(mapOf("region" to listOf("*"), "dept" to listOf("*"))).assert().isEqualTo(
            AndFilter(listOf(filter { "region" ne "" }, filter { nor { "depts".isEmptyCollection() } })),
        )
        val strict = AbacQueryOptions(matchMissingTagKey = false)
        condition(mapOf("region" to listOf("east")), strict).assert().isEqualTo(filter { "region" isIn listOf("east") })
        condition(mapOf("dept" to listOf("eng"), "role" to listOf("admin")), strict).assert().isEqualTo(
            AndFilter(listOf(filter { "depts" isIn listOf("eng") }, MatchNoneFilter)),
        )
        condition(mapOf("role" to listOf("*"))).assert().isSameAs(MatchNoneFilter)
        condition(mapOf("role" to listOf("admin"))).assert().isSameAs(MatchAllFilter)
    }

    @Test
    fun `admission accepts the ABAC condition on the tag columns`() {
        val backend = RecordingBackend()
        val tags = mapOf("region" to listOf("east"), "dept" to listOf("*"))
        gateway(backend, tagged(), listOf(abac(tags))).dynamicList(listQuery { }).test()
            .expectNextCount(1).verifyComplete()
        gateway(backend, tagged(), listOf(abac(tags, AbacQueryOptions(matchMissingTagKey = false))))
            .count(MatchAllFilter).test().expectNext(1).verifyComplete()
        backend.admitted.size.assert().isEqualTo(2)
    }

    @Test
    fun `ABAC leaves a dataset without tag columns unrestricted and resolves no principal tags`() {
        val schema = catalog(orders).schema(orders.namedAggregate, QueryModel.DATASET).block()!!
        val policy = object : AbacQueryPolicy() {
            override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> =
                Mono.error(IllegalStateException("not resolved"))
        }
        policy.evaluate(Context.empty(), context(schema)).test().expectNext(MatchAllFilter).verifyComplete()
    }

    @Test
    fun `datasets change in place, every listener validating before any applies`() {
        val datasets = QueryDatasets()
        val seen = mutableListOf<String>()
        datasets.listen(
            object : QueryDatasetListener {
                override fun validate(change: QueryDatasetChange) {
                    require(change.dataset.title != "refused") { "refused" }
                    seen += "validate ${change::class.simpleName}"
                }

                override fun apply(change: QueryDatasetChange) {
                    seen += "apply ${change::class.simpleName}"
                }
            },
        )
        datasets.add(orders)
        assertThrows<IllegalArgumentException> { datasets.add(orders) }
        datasets.replace(orders.copy(title = "Orders"))
        datasets[orders.namedAggregate]!!.title.assert().isEqualTo("Orders")
        assertThrows<IllegalArgumentException> { datasets.replace(orders.copy(title = "refused")) }
        datasets[orders.namedAggregate]!!.title.assert().isEqualTo("Orders")
        val other = QueryDatasetTest.orders().copy(namedAggregate = OTHER)
        assertThrows<IllegalArgumentException> { datasets.replace(other) }
        datasets.remove(orders.namedAggregate).assert().isTrue()
        datasets.remove(orders.namedAggregate).assert().isFalse()
        datasets.all.assert().isEmpty()
        seen.assert().containsExactly(
            "validate Added",
            "apply Added",
            "validate Replaced",
            "apply Replaced",
            "validate Removed",
            "apply Removed",
        )
        val ignoring = object : QueryDatasetListener {
            override fun apply(change: QueryDatasetChange) = Unit
        }
        assertThrows<IllegalStateException> { QueryDatasets.NONE.add(orders) }
        assertThrows<IllegalStateException> { QueryDatasets.NONE.listen(ignoring) }
    }

    @Test
    fun `the catalog recompiles a dataset that changes and refuses one that takes an aggregate's name`() {
        val datasets = QueryDatasets(listOf(orders))
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(
                datasetFactory = { QueryBackendBinding(RecordingBackend(), ADAPTER) },
                datasets = datasets,
            ),
            compiler = QueryModelCompiler.of(datasets = datasets),
            aggregates = listOf(OTHER),
            datasets = datasets,
        )
        catalog.schema(
            orders.namedAggregate,
            QueryModel.DATASET
        ).block()!!.field(QueryField("city")).assert().isNotNull()
        val renamed = QueryDataset.of(orders.namedAggregate) {
            tenant("tenant_id")
            field("tenant_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("region") {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        datasets.replace(renamed)
        val schema = catalog.schema(orders.namedAggregate, QueryModel.DATASET).block()!!
        schema.field(QueryField("region")).assert().isNotNull()
        schema.field(QueryField("city")).assert().isNull()
        catalog.versions().map { it.aggregate }.collectList().block()!!.assert().contains("analytics.orders")
        assertThrows<IllegalArgumentException> { datasets.add(QueryDatasetTest.orders().copy(namedAggregate = OTHER)) }
            .message.assert().contains("aggregate")
        datasets[OTHER].assert().isNull()
    }

    @Test
    fun `gateways follow the datasets, and an abstract factory rebinds a changed dataset`() {
        val datasets = QueryDatasets()
        val created = mutableListOf<QueryDataset>()
        val factory = object : AbstractDatasetQueryBackendFactory() {
            override fun createBinding(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend> {
                created += dataset
                return QueryBackendBinding(RecordingBackend(), ADAPTER)
            }
        }
        val gateways = DatasetQueryGateways.of(datasets, requireServable = { require(it.title != "unservable") }) {
            factory.create(it)
            gateway(dataset = it)
        }
        datasets.add(orders)
        val first = gateways[orders.namedAggregate]!!
        datasets.replace(orders.copy(title = "Orders"))
        gateways[orders.namedAggregate].assert().isNotSameAs(first)
        gateways[orders.namedAggregate]!!.dataset.title.assert().isEqualTo("Orders")
        factory.create(orders.copy(title = "Orders"))
        created.size.assert().isEqualTo(2)
        assertThrows<IllegalArgumentException> {
            datasets.add(QueryDatasetTest.orders().copy(namedAggregate = OTHER, title = "unservable"))
        }
        gateways[OTHER].assert().isNull()
        datasets.remove(orders.namedAggregate)
        gateways.all.assert().isEmpty()
        DatasetQueryGateways.of(QueryDatasets.NONE) { gateway(dataset = it) }.all.assert().isEmpty()
    }

    @Test
    fun `a dataset never takes the name of an aggregate of its context`() {
        assertThrows<IllegalArgumentException> {
            QuerySchemaCatalog(aggregates = listOf(orders.namedAggregate), datasets = QueryDatasets(listOf(orders)))
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

    @Test
    fun `an abstract factory creates each dataset's binding once`() {
        var created = 0
        val factory = object : AbstractDatasetQueryBackendFactory() {
            override fun createBinding(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend> {
                created++
                return QueryBackendBinding(RecordingBackend(), ADAPTER)
            }
        }
        factory.create(orders).assert().isSameAs(factory.create(orders))
        created.assert().isEqualTo(1)
    }

    @Test
    fun `a provider serves no dataset unless it says so, and storages need a dataset factory`() {
        val provider = object : QueryBackendProvider {
            override val name: String = "other"
        }
        provider.dataset.assert().isNull()
        QueryBackendStorages(datasets = QueryDatasets(listOf(orders)))
            .storage(orders.namedAggregate, QueryModel.DATASET).assert().isNull()
        val gateway = DefaultDatasetQueryGateway(
            orders,
            RecordingBackend(),
            catalog(orders).provider(orders.namedAggregate, QueryModel.DATASET),
        )
        gateway.dataset.assert().isSameAs(orders)
        gateway.dynamicList(listQuery { }).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `a custom model without a profile has no schema`() {
        val provider = me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider(
            me.ahoo.wow.query.schema.QuerySchemaContext(orders.namedAggregate, QueryModel("CUSTOM")),
            emptyList(),
            ADAPTER,
        )
        provider.schema().test().expectErrorSatisfies {
            it.assert().isInstanceOf(QuerySchemaValidationException::class.java).hasMessageContaining("CUSTOM")
        }.verify()
    }

    @Test
    fun `a custom model without a profile describes its metadata fields`() {
        val schema = me.ahoo.wow.query.schema.boundSchemaFixture(
            me.ahoo.wow.query.schema.objectFixture("tenantId" to me.ahoo.wow.query.schema.scalarFixture()),
            model = QueryModel("CUSTOM"),
        )
        val descriptor = schema.describe(null, null)
        descriptor.fields.single { it.path == "tenantId" }.role.assert().isEqualTo("TENANT_ID")
        descriptor.record.identity.assert().isEmpty()
    }

    @Test
    fun `a schema compiled with another profile is another schema`() {
        val first = catalog(orders).schema(orders.namedAggregate, QueryModel.DATASET).block()!!
        val renamed = QueryDataset.of(orders.namedAggregate) {
            tenant("owner_id")
            field("tenant_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("owner_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        val other = catalog(renamed).schema(orders.namedAggregate, QueryModel.DATASET).block()!!
        first.compilesSameAs(first).assert().isTrue()
        first.compilesSameAs(other).assert().isFalse()
    }

    private fun abac(tags: AbacTags, options: AbacQueryOptions = AbacQueryOptions.DEFAULT) =
        object : AbacQueryPolicy(options) {
            override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> =
                Mono.just(tags)
        }

    private fun context(schema: QueryModelSchema) = QueryContext<FilterExpression>(
        MatchAllFilter,
        orders.namedAggregate,
        schema,
        QueryType.LIST,
        QueryEntry.HTTP,
    )

    /** [orders] with a single-valued `region` tag column and a multi-valued `depts` one. */
    private fun tagged(): QueryDataset = QueryDataset.of(orders.namedAggregate) {
        tenant("tenant_id")
        identity("order_id")
        tag("region", "region")
        tag("dept", "depts")
        field("order_id") {
            types(QueryValueType.STRING)
            nullable(false)
        }
        field("tenant_id") {
            types(QueryValueType.STRING)
            nullable(false)
        }
        field("region") {
            types(QueryValueType.STRING)
            nullable(false)
        }
        field("depts") {
            kind(QueryValueKind.ARRAY)
            nullable(false)
            items {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
    }

    private fun catalog(dataset: QueryDataset, backend: DatasetQueryBackend = RecordingBackend()): QuerySchemaCatalog {
        val datasets = QueryDatasets(listOf(dataset))
        return QuerySchemaCatalog(
            storages = QueryBackendStorages(
                datasetFactory = { QueryBackendBinding(backend, ADAPTER) },
                datasets = datasets,
            ),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
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
        val OTHER = me.ahoo.wow.modeling.MaterializedNamedAggregate("analytics", "other")

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
