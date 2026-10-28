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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryTargetException
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CachedQueryBindings
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.error.QueryDatasetUnavailableException
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryScopeRequiredException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.memory.InMemoryQueryStorageAdapter
import me.ahoo.wow.query.policy.AbacQueryOptions
import me.ahoo.wow.query.policy.AbacQueryPolicy
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

class DatasetQueryGatewayTest {
    private val orders = QueryDatasetTest.orders()

    @Test
    fun `the catalog compiles a dataset's fields at the record root with its own profile`() = runTest {
        val schema = catalog(orders).schema(orders.name, QueryModel.DATASET)
        schema.model.assert().isEqualTo(QueryModel.DATASET)
        schema.profile.assert().isInstanceOf(DatasetQueryModelProfile::class.java)
        schema.field(QueryField("city")).assert().isNotNull()
        val descriptor = gateway().describe(QueryEntry.HTTP)
        descriptor.record.identity.assert().isEqualTo("order_id")
        gateway().aggregateType.assert().isEqualTo(orders.name)
    }

    @Test
    fun `the caller's tenant pins the dataset's tenant column`() = runTest {
        val backend = RecordingBackend()
        val caller = QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP)
        gateway(backend, policies = listOf(ScopeQueryPolicy()))
            .list(ListQuery(EqualFilter(QueryField("city"), "Hangzhou"), limit = 10), caller).toList()
            .assert().hasSize(1)
        val admitted = backend.admitted.single()
        val tenant = ((admitted.query as ListQuery).filter as AndFilter).operands.single { it is TenantIdFilter }
        admitted.systemPath(tenant).assert().isEqualTo("tenant_id")
    }

    @Test
    fun `an HTTP caller built directly reaches no further than the read side's rules (R5-C-1)`() = runTest {
        val gateway = gateway(policies = listOf(ScopeQueryPolicy()))
        val operator = Caller(tenantId = "t1", crossScope = true)
        val across = QueryCaller(operator, QueryEntry.HTTP, crossTenant = CrossTenant.ALL)
        assertThrows<QueryTargetException> { gateway.count(CountQuery(MatchAllFilter), across) }
            .errorCode.assert().isEqualTo(QueryErrors.FORBIDDEN)
        val named = QueryCaller(Caller(tenantId = "t1"), QueryEntry.HTTP, target = TargetScope.of("t2"))
        assertThrows<QueryTargetException> { gateway.count(CountQuery(MatchAllFilter), named) }
            .errorCode.assert().isEqualTo(QueryErrors.FORBIDDEN)
    }

    @Test
    fun `the scope policy fails closed on a principal the dataset has no column for`() = runTest {
        val caller = QueryCaller(
            Caller(scope = me.ahoo.wow.api.aggregate.Scope(ownerId = null, spaceId = "s1")),
            entry = QueryEntry.HTTP,
        )
        assertThrows<QuerySchemaValidationException> {
            gateway(policies = listOf(ScopeQueryPolicy())).count(CountQuery(MatchAllFilter), caller)
        }.message.assert().contains("[spaceId]")
    }

    @Test
    fun `a system field the dataset does not carry names an unknown field`() = runTest {
        assertThrows<QuerySchemaValidationException> {
            gateway().count(CountQuery(AggregateIdFilter("o1")), QueryCaller.IN_PROCESS)
        }.message.assert().contains("[aggregateId]")
    }

    @Test
    fun `a cursor needs the dataset's identity`() = runTest {
        val keyless = QueryDataset.of(orders.name) {
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
        assertThrows<QuerySchemaValidationException> {
            gateway(dataset = keyless).cursor(CursorQuery(MatchAllFilter, size = 10), QueryCaller.IN_PROCESS)
        }.message.assert().contains("identity")
        gateway().cursor(CursorQuery(MatchAllFilter, size = 10), QueryCaller.IN_PROCESS).list.assert().hasSize(1)
    }

    @Test
    fun `results mask sensitive columns, which cannot be compared`() = runTest {
        gateway().list(ListQuery(MatchAllFilter, limit = 10), QueryCaller.IN_PROCESS).toList().single()
            .get("phone").stringValue().assert().isEqualTo("*******1234")
        gateway().paged(me.ahoo.wow.api.query.PagedQuery(MatchAllFilter), QueryCaller.IN_PROCESS).list.single()
            .get("phone").stringValue().assert().isEqualTo("*******1234")
        gateway().single(me.ahoo.wow.api.query.SingleQuery(MatchAllFilter), QueryCaller.IN_PROCESS)!!
            .get("phone").stringValue().assert().isEqualTo("*******1234")
        assertThrows<QuerySchemaValidationException> {
            gateway().list(ListQuery(EqualFilter(QueryField("phone"), "13800001234")), QueryCaller.IN_PROCESS).toList()
        }
    }

    @Test
    fun `policies see the dataset by its name and model`() = runTest {
        val seen = mutableListOf<Pair<AggregateType, QueryModel>>()
        val policy = QueryPolicy { _, context ->
            seen += context.aggregateType to context.schema.model
            MatchAllFilter
        }
        gateway(policies = listOf(policy))
            .aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("orders"))), QueryCaller.IN_PROCESS)
            .toList().assert().hasSize(1)
        seen.single().assert().isEqualTo(orders.name to QueryModel.DATASET)
    }

    @Test
    fun `ABAC restricts a dataset by its tag columns, a string and a string array`() = runTest {
        val tagged = tagged()
        val schema = catalog(tagged).schema(tagged.name, QueryModel.DATASET)
        suspend fun condition(tags: Map<String, List<String>>, options: AbacQueryOptions = ENABLED): FilterExpression =
            AbacQueryPolicy(options).evaluate(caller(tags), context(schema))

        val region = QueryField("region")
        val depts = QueryField("depts")
        condition(mapOf("region" to listOf("east"), "dept" to listOf("eng"), "role" to listOf("admin")))
            .assert().isEqualTo(
                AndFilter(
                    listOf(
                        InFilter(region, listOf("east", "")),
                        OrFilter(listOf(IsEmptyFilter(depts), InFilter(depts, listOf("eng")))),
                    ),
                ),
            )
        condition(mapOf("region" to listOf("*"), "dept" to listOf("*"))).assert().isEqualTo(
            AndFilter(listOf(NotEqualFilter(region, ""), NorFilter(listOf(IsEmptyFilter(depts))))),
        )
        val strict = ENABLED.copyWith(matchMissingTagKey = false)
        condition(mapOf("region" to listOf("east")), strict).assert().isEqualTo(InFilter(region, listOf("east")))
        condition(mapOf("dept" to listOf("eng"), "role" to listOf("admin")), strict).assert().isEqualTo(
            AndFilter(listOf(InFilter(depts, listOf("eng")), MatchNoneFilter)),
        )
        condition(mapOf("role" to listOf("*"))).assert().isSameAs(MatchNoneFilter)
        condition(mapOf("role" to listOf("admin"))).assert().isSameAs(MatchAllFilter)
        condition(emptyMap()).assert().isSameAs(MatchAllFilter)
        condition(mapOf("region" to listOf("east")), AbacQueryOptions()).assert().isSameAs(MatchAllFilter)
        assertThrows<me.ahoo.wow.query.policy.AbacPrincipalTagsRequiredException> {
            condition(emptyMap(), ENABLED.copyWith(requirePrincipalTags = true))
        }
    }

    @Test
    fun `admission accepts the ABAC condition on the tag columns`() = runTest {
        val backend = RecordingBackend()
        val caller = caller(mapOf("region" to listOf("east"), "dept" to listOf("*")))
        gateway(backend, tagged(), listOf(AbacQueryPolicy(ENABLED)))
            .list(ListQuery(MatchAllFilter, limit = 10), caller).toList().assert().hasSize(1)
        gateway(backend, tagged(), listOf(AbacQueryPolicy(ENABLED.copyWith(matchMissingTagKey = false))))
            .count(CountQuery(MatchAllFilter), caller).assert().isEqualTo(CountResult(1, exact = true))
        backend.admitted.size.assert().isEqualTo(2)
    }

    @Test
    fun `ABAC leaves a dataset without tag columns unrestricted`() = runTest {
        val schema = catalog(orders).schema(orders.name, QueryModel.DATASET)
        val required = AbacQueryPolicy(ENABLED.copyWith(requirePrincipalTags = true))
        required.evaluate(caller(emptyMap()), context(schema)).assert().isSameAs(MatchAllFilter)
        required.evaluate(caller(mapOf("region" to listOf("east"))), context(schema)).assert().isSameAs(MatchAllFilter)
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
        datasets.replace(orders.with(title = "Orders"))
        datasets[orders.name]!!.title.assert().isEqualTo("Orders")
        assertThrows<IllegalArgumentException> { datasets.replace(orders.with(title = "refused")) }
        datasets[orders.name]!!.title.assert().isEqualTo("Orders")
        assertThrows<IllegalArgumentException> { datasets.replace(orders.with(name = OTHER)) }
        datasets.put(orders.with(title = "Put"))
        datasets[orders.name]!!.title.assert().isEqualTo("Put")
        datasets.remove(orders.name).assert().isTrue()
        datasets.remove(orders.name).assert().isFalse()
        datasets.all.assert().isEmpty()
        datasets.put(orders)
        seen.assert().containsExactly(
            "validate Added",
            "apply Added",
            "validate Replaced",
            "apply Replaced",
            "validate Replaced",
            "apply Replaced",
            "validate Removed",
            "apply Removed",
            "validate Added",
            "apply Added",
        )
        val ignoring = object : QueryDatasetListener {
            override fun apply(change: QueryDatasetChange) = Unit
        }
        ignoring.validate(QueryDatasetChange.Removed(orders))
        assertThrows<IllegalStateException> { QueryDatasets.NONE.add(orders) }
        assertThrows<IllegalStateException> { QueryDatasets.NONE.listen(ignoring) }
    }

    @Test
    fun `the catalog recompiles a dataset that changes and refuses one that takes an aggregate's name`() = runTest {
        val datasets = QueryDatasets(listOf(orders))
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(
                datasetBindings = { QueryBackendBinding(RecordingBackend(it.name), ADAPTER) },
                datasets = datasets
            ),
            compiler = QueryModelCompiler.of(datasets = datasets),
            aggregates = listOf(OTHER),
            datasets = datasets,
        )
        catalog.schema(orders.name, QueryModel.DATASET).field(QueryField("city")).assert().isNotNull()
        val renamed = QueryDataset.of(orders.name) {
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
        val schema = catalog.schema(orders.name, QueryModel.DATASET)
        schema.field(QueryField("region")).assert().isNotNull()
        schema.field(QueryField("city")).assert().isNull()
        catalog.versions().map { it.aggregate }.assert().contains("analytics.orders")
        catalog.revalidate("analytics.orders", QueryModel.DATASET).single().version.assert().isEqualTo(schema.version)
        assertThrows<IllegalArgumentException> { datasets.add(orders.with(name = OTHER)) }
            .message.assert().contains("aggregate")
        datasets[OTHER].assert().isNull()
        datasets.remove(orders.name)
        assertThrows<QuerySchemaUnavailableException> { catalog.schema(orders.name, QueryModel.DATASET) }
    }

    @Test
    fun `a dataset never takes the name of an aggregate of its context`() {
        assertThrows<IllegalArgumentException> {
            QuerySchemaCatalog(aggregates = listOf(orders.name), datasets = QueryDatasets(listOf(orders)))
        }
    }

    @Test
    fun `gateways follow the datasets, and cached bindings rebind a changed dataset`() = runTest {
        val datasets = QueryDatasets()
        val created = mutableListOf<QueryDataset>()
        val factory = object : CachedQueryBindings<QueryDataset>(QueryDataset::name) {
            override fun createBinding(key: QueryDataset): QueryBackendBinding<RecordingBackend> {
                created += key
                return QueryBackendBinding(RecordingBackend(key.name), ADAPTER)
            }
        }
        val gateways = DatasetQueryGateways.of(datasets, requireServable = { require(it.title != "unservable") }) {
            factory.binding(it)
            gateway(dataset = it)
        }
        datasets.add(orders)
        val first = gateways[orders.name]!!
        datasets.replace(orders.with(title = "Orders"))
        gateways[orders.name].assert().isNotSameAs(first)
        gateways[orders.name]!!.dataset.title.assert().isEqualTo("Orders")
        factory.binding(orders.with(title = "Orders"))
        created.size.assert().isEqualTo(2)
        factory.forget(orders.with(title = "Orders"))
        factory.binding(orders.with(title = "Orders"))
        created.size.assert().isEqualTo(3)
        assertThrows<IllegalArgumentException> {
            datasets.add(orders.with(name = OTHER, title = "unservable"))
        }
        gateways[OTHER].assert().isNull()
        datasets.remove(orders.name)
        gateways.all.assert().isEmpty()
        DatasetQueryGateways.of(QueryDatasets.NONE) { gateway(dataset = it) }.all.assert().isEmpty()
        DatasetQueryGateways.NONE.all.assert().isEmpty()
        assertThrows<IllegalArgumentException> { DatasetQueryGateways(listOf(gateway(), gateway())) }
    }

    @Test
    fun `an unavailable dataset is served with its reason, and every query of it answered so`() = runTest {
        val datasets = QueryDatasets(listOf(orders.withUnavailable("needs revision")))
        val gateways = DatasetQueryGateways.of(datasets) { gateway(dataset = it) }
        val gateway = gateways[orders.name]!!
        gateway.dataset.unavailable.assert().isEqualTo("needs revision")
        gateway.aggregateType.assert().isEqualTo(orders.name)
        gateway.entryPolicy.assert().isEqualTo(QueryEntryPolicy.DEFAULT)
        val caller = QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP)
        val failures = listOf<suspend () -> Any?>(
            { gateway.single(SingleQuery(MatchAllFilter), caller) },
            { gateway.list(ListQuery(MatchAllFilter), caller).toList() },
            { gateway.stream(ListQuery(MatchAllFilter), caller).toList() },
            { gateway.paged(PagedQuery(MatchAllFilter), caller) },
            { gateway.cursor(CursorQuery(MatchAllFilter), caller) },
            { gateway.count(CountQuery(MatchAllFilter), caller) },
            { gateway.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("n"))), caller).toList() },
            { gateway.describe(QueryEntry.HTTP) },
        )
        failures.forEach { query ->
            val failure = assertThrows<QueryDatasetUnavailableException> { query() }
            failure.errorCode.assert().isEqualTo(QueryErrors.DATASET_UNAVAILABLE)
            failure.errorMsg.assert().isEqualTo("Dataset [${orders.name}] cannot be queried now: needs revision.")
            failure.reason.assert().isEqualTo("needs revision")
            failure.dataset.assert().isEqualTo(orders.name)
        }
        // Available again: an ordinary gateway.
        datasets.put(orders)
        gateways[orders.name].assert().isNotInstanceOf(UnavailableDatasetQueryGateway::class.java)
        datasets.put(orders.withUnavailable("needs revision"))
        gateways[orders.name].assert().isInstanceOf(UnavailableDatasetQueryGateway::class.java)
        assertThrows<IllegalArgumentException> { orders.withUnavailable(" ") }
    }

    @Test
    fun `gateways over bindings and a catalog serve each dataset and refuse one the bindings do not serve`() = runTest {
        val datasets = QueryDatasets(listOf(orders))
        val factory = QueryBindings<QueryDataset> { dataset ->
            QueryBackendBinding(RecordingBackend(dataset.name), ADAPTER).takeIf { dataset.title != "unbound" }
        }
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(datasetBindings = factory, datasets = datasets),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        val gateways = DatasetQueryGateways.of(datasets, catalog, factory, policies = listOf(ScopeQueryPolicy()))
        gateways[orders.name]!!.count(
            CountQuery(MatchAllFilter),
            QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP)
        )
            .assert().isEqualTo(CountResult(1, exact = true))
        assertThrows<IllegalArgumentException> { datasets.add(orders.with(name = OTHER, title = "unbound")) }
        gateways[OTHER].assert().isNull()
    }

    @Test
    fun `a platform-level dataset is the platform tenant's and other tenants read nothing`() = runTest {
        val platform = QueryDataset.of(orders.name) {
            platform()
            identity("order_id")
            field("order_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("city") {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        val backend = RecordingBackend()
        val city = EqualFilter(QueryField("city"), "Hangzhou")
        suspend fun count(tenant: String) {
            gateway(backend, platform, listOf(ScopeQueryPolicy()))
                .count(CountQuery(city), QueryCaller(Caller(tenantId = tenant), entry = QueryEntry.HTTP))
                .assert().isEqualTo(CountResult(1, exact = true))
        }
        count(QueryDatasetScope.PLATFORM_TENANT)
        ((backend.admitted.last().query as CountQuery).filter).assert().isEqualTo(city)
        count("t1")
        ((backend.admitted.last().query as CountQuery).filter).assert().isSameAs(MatchNoneFilter)

        val authenticated = QueryEntryPolicy(requireAuthenticatedScope = true)
        assertThrows<QueryScopeRequiredException> {
            gateway(
                backend,
                platform,
                entryPolicy = authenticated
            ).count(CountQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.HTTP))
        }
        val pinned = QueryCaller(entry = QueryEntry.HTTP).withScope(
            QueryScope(authenticated = TenantIdFilter(QueryDatasetScope.PLATFORM_TENANT)),
        )
        gateway(backend, platform, entryPolicy = authenticated).count(CountQuery(MatchAllFilter), pinned)
            .assert().isEqualTo(CountResult(1, exact = true))
        platform.profile.systemField(SystemField.TENANT_ID).assert().isNull()
    }

    @Test
    fun `storages locate a dataset's backend by name`() {
        val factory = QueryBindings<QueryDataset> { QueryBackendBinding(RecordingBackend(it.name), ADAPTER) }
        val storages = QueryBackendStorages(datasetBindings = factory, datasets = QueryDatasets(listOf(orders)))
        storages.storage(orders.name, QueryModel.DATASET).assert().isSameAs(ADAPTER)
        storages.storage(QueryDatasetTest.OTHER, QueryModel.DATASET).assert().isNull()
        storages.storage(orders.name, QueryModel.SNAPSHOT).assert().isNull()
        storages.storage(orders.name, QueryModel("CUSTOM")).assert().isNull()
        QueryBackendStorages(datasets = QueryDatasets(listOf(orders)))
            .storage(orders.name, QueryModel.DATASET).assert().isNull()
    }

    @Test
    fun `cached bindings create each dataset's binding once and ask again for one they do not serve`() {
        var created = 0
        val factory = object : CachedQueryBindings<QueryDataset>(QueryDataset::name) {
            override fun createBinding(key: QueryDataset): QueryBackendBinding<RecordingBackend>? {
                created++
                return QueryBackendBinding(RecordingBackend(key.name), ADAPTER).takeIf { key.title != "unbound" }
            }
        }
        factory.binding(orders).assert().isSameAs(factory.binding(orders))
        created.assert().isEqualTo(1)
        val unbound = orders.with(name = OTHER, title = "unbound")
        factory.binding(unbound).assert().isNull()
        factory.binding(unbound).assert().isNull()
        created.assert().isEqualTo(3)
    }

    @Test
    fun `bindings that hold nothing forget nothing`() {
        val bindings = QueryBindings<QueryDataset> { QueryBackendBinding(RecordingBackend(it.name), ADAPTER) }
        bindings.forget(orders)
        bindings.binding(orders)?.storage.assert().isSameAs(ADAPTER)
    }

    @Test
    fun `a gateway serves only the backend of its dataset`() = runTest {
        val gateway = DefaultDatasetQueryGateway(
            orders,
            RecordingBackend(),
            catalog(orders).provider(orders.name, QueryModel.DATASET),
        )
        gateway.dataset.assert().isSameAs(orders)
        gateway.list(ListQuery(MatchAllFilter, limit = 10), QueryCaller.IN_PROCESS).toList().assert().hasSize(1)
        assertThrows<IllegalArgumentException> {
            DefaultDatasetQueryGateway(
                orders,
                RecordingBackend(OTHER),
                catalog(orders).provider(orders.name, QueryModel.DATASET)
            )
        }
    }

    private fun caller(tags: Map<String, List<String>>) = QueryCaller(
        Caller(attributes = tags.mapKeys { (key, _) -> "abac.$key" }),
        entry = QueryEntry.HTTP,
    )

    private fun context(schema: QueryModelSchema) = QueryContext<FilterExpression>(
        MatchAllFilter,
        orders.name,
        schema,
        QueryType.LIST,
        QueryEntry.HTTP,
    )

    /** [orders] with a single-valued `region` tag column and a multi-valued `depts` one. */
    private fun tagged(): QueryDataset = QueryDataset.of(orders.name) {
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

    private fun catalog(dataset: QueryDataset, backend: QueryBackend = RecordingBackend()): QuerySchemaCatalog {
        val datasets = QueryDatasets(listOf(dataset))
        return QuerySchemaCatalog(
            storages = QueryBackendStorages(
                datasetBindings = { QueryBackendBinding(backend, ADAPTER) },
                datasets = datasets
            ),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
    }

    private fun gateway(
        backend: QueryBackend = RecordingBackend(),
        dataset: QueryDataset = orders,
        policies: List<QueryPolicy> = emptyList(),
        entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
    ): DatasetQueryGateway = DefaultDatasetQueryGateway(
        dataset = dataset,
        backend = backend,
        schemaProvider = catalog(dataset, backend).provider(dataset.name, QueryModel.DATASET),
        policies = policies,
        entryPolicy = entryPolicy,
    )

    private class RecordingBackend(override val aggregateType: AggregateType = QueryDatasetTest.ORDERS) : QueryBackend {
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

    private companion object {
        val OTHER = AggregateType("analytics", "other")
        val ENABLED = AbacQueryOptions(enabled = true)
        val ADAPTER = InMemoryQueryStorageAdapter
    }
}

/** This dataset under [name] and with [title]; the dataset's other properties unchanged. */
private fun QueryDataset.with(name: AggregateType = this.name, title: String? = this.title): QueryDataset =
    QueryDataset(name, scope, declaration, identity, eventTime, title, storage, unavailable)
