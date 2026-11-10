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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.admission.QueryTargetException
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryScopeRequiredException
import me.ahoo.wow.query.memory.InMemoryDatasetStorageAdapter
import me.ahoo.wow.query.policy.AccessFixtures
import me.ahoo.wow.query.policy.AccessQueryPolicy
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class DatasetQueryGatewayTest : DatasetQueryGatewayFixtures() {
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
    fun `the rows of a dataset bound to a governed aggregate are read by its policy, not by the caller's owner`() =
        runTest {
            suspend fun admitted(
                dataset: QueryDataset,
                vararg policies: AccessPolicy<*>,
                adapter: QueryStorageAdapter = ACCESS_ADAPTER,
                caller: QueryCaller = U1,
            ): FilterExpression {
                val backend = RecordingBackend()
                val access = AccessFixtures.policies(*policies)
                gateway(
                    backend,
                    dataset,
                    listOf(ScopeQueryPolicy(access = access), AccessQueryPolicy(access)),
                    adapter = adapter
                )
                    .count(CountQuery(MatchAllFilter), caller)
                return (backend.admitted.single().query as CountQuery).filter
            }
            // The whole predicate is one restriction on the aggregate's current attributes, the tenant beside it.
            val restricted = admitted(bound(AccessFixtures.DOCUMENT), AccessFixtures.documents) as AndFilter
            restricted.operands.single { it is TenantIdFilter }
            restricted.operands.single { DatasetAccess.predicateOf(it) != null }
                .let(DatasetAccess::predicateOf).assert().isEqualTo(SALES_OR_SHARED)
            // A storage that cannot read the attributes reads none of the rows.
            admitted(bound(AccessFixtures.DOCUMENT), AccessFixtures.documents, adapter = ADAPTER)
                .assert().isSameAs(MatchNoneFilter)
            // A grant of every aggregate reads no attributes: the tenant alone, here and on any storage.
            val open = accessPolicy<AccessFixtures.DocumentState> { readWrite { allowAll() } }
            admitted(bound(AccessFixtures.DOCUMENT), open).assert().isInstanceOf(TenantIdFilter::class.java)
            admitted(bound(AccessFixtures.DOCUMENT), open, adapter = ADAPTER).assert()
                .isInstanceOf(TenantIdFilter::class.java)
            // A service reading as itself, and trusted code, are read by no access policy.
            val service = QueryCaller(Caller(tenantId = "t1", subject = "billing", service = true), QueryEntry.HTTP)
            admitted(bound(AccessFixtures.DOCUMENT), AccessFixtures.documents, caller = service).assert()
                .isInstanceOf(TenantIdFilter::class.java)
            admitted(bound(AccessFixtures.DOCUMENT), AccessFixtures.documents, caller = QueryCaller.IN_PROCESS)
                .assert().isSameAs(MatchAllFilter)
            // An aggregate without a policy of its own, or none bound: the dataset's owner column, as for any dataset.
            (admitted(bound(AccessFixtures.NOTE), AccessFixtures.documents) as AndFilter).operands.assert().hasSize(2)
            (admitted(orders, AccessFixtures.documents) as AndFilter).operands.assert().hasSize(2)
            // A dataset that cannot be queried answers no record: none is masked by a policy's rules.
            val record = WowJson.mapper.createObjectNode().put("city", "Hangzhou")
            UnavailableDatasetQueryGateway(orders.withUnavailable("rebuilding")).maskRecord(record).assert()
                .isSameAs(record)
        }

    @Test
    fun `no filter, sort or projection of the caller's reaches an access restriction`() = runTest {
        val access = AccessFixtures.policies(AccessFixtures.documents)
        val backend = RecordingBackend()
        val gateway = gateway(
            backend,
            bound(AccessFixtures.DOCUMENT),
            listOf(ScopeQueryPolicy(access = access), AccessQueryPolicy(access)),
            adapter = ACCESS_ADAPTER,
        )
        val city = QueryField("city")
        val written = listOf(
            EqualFilter(city, "Hangzhou"),
            OrFilter(listOf(EqualFilter(city, "Hangzhou"), EqualFilter(city, "Shanghai"))),
            NorFilter(listOf(EqualFilter(city, "Hangzhou"))),
            MatchAllFilter,
        )
        written.forEach { filter ->
            gateway.list(
                ListQuery(
                    filter,
                    Projection(include = listOf(city)),
                    listOf(Sort(city, Sort.Direction.DESC)),
                    limit = 10,
                ),
                U1,
            ).toList()
        }
        backend.admitted.map { admitted ->
            val operands = ((admitted.query as ListQuery).filter as AndFilter).operands
            operands.mapNotNull(DatasetAccess::predicateOf).single()
        }.assert().containsOnly(SALES_OR_SHARED)
        // The caller cannot name the attributes: in a filter, under any operator, in a sort or a projection.
        val own = ElementMatchFilter(DatasetAccess.FIELD, EqualFilter(QueryField("department"), "hr"))
        val refused = listOf(
            ListQuery(own, limit = 10),
            ListQuery(OrFilter(listOf(own, EqualFilter(city, "Hangzhou"))), limit = 10),
            ListQuery(NorFilter(listOf(own)), limit = 10),
            ListQuery(MatchAllFilter, sort = listOf(Sort(DatasetAccess.FIELD, Sort.Direction.ASC)), limit = 10),
            ListQuery(MatchAllFilter, Projection(include = listOf(DatasetAccess.FIELD)), limit = 10),
        )
        refused.forEach { query ->
            assertThrows<QuerySchemaValidationException> { gateway.list(query, U1).toList() }
                .message.assert().contains("[@access]")
        }
        // Nor can trusted code: only admission appends it.
        assertThrows<QuerySchemaValidationException> {
            gateway.count(CountQuery(own), QueryCaller.IN_PROCESS)
        }.message.assert().contains("[@access]")
        assertThrows<IllegalArgumentException> {
            QueryDataset.of(orders.name) {
                tenant("tenant_id")
                field("tenant_id") { types(QueryValueType.STRING) }
                field(DatasetAccess.FIELD.path) { types(QueryValueType.STRING) }
            }
        }
    }

    @Test
    fun `an access restriction is read only on a dataset bound to an aggregate, by a storage that reads it`() = runTest {
        val restriction = DatasetAccess.restriction(EqualFilter(QueryField("department"), "sales"))
        val appending = QueryPolicy { _, _ -> restriction }
        val count = CountQuery(MatchAllFilter)
        // A dataset bound to no aggregate has no access attributes: an unknown field, even appended.
        assertThrows<QuerySchemaValidationException> {
            gateway(policies = listOf(appending), adapter = ACCESS_ADAPTER).count(count, QueryCaller.IN_PROCESS)
        }.message.assert().contains("[@access]")
        // A storage that does not declare it never receives one.
        assertThrows<QuerySchemaValidationException> {
            gateway(dataset = bound(AccessFixtures.DOCUMENT), policies = listOf(appending))
                .count(count, QueryCaller.IN_PROCESS)
        }.message.assert().contains("access attributes")
        val backend = RecordingBackend()
        gateway(backend, bound(AccessFixtures.DOCUMENT), listOf(appending), adapter = ACCESS_ADAPTER)
            .count(count, QueryCaller.IN_PROCESS)
        (backend.admitted.single().query as CountQuery).filter.assert().isEqualTo(restriction)
    }

    private fun bound(aggregate: AggregateType) = QueryDataset(
        orders.name,
        QueryDatasetScope.of(
            tenant = orders.scope.tenant,
            owner = orders.scope.owner,
            access = QueryDatasetAccess.of(aggregate, QueryField("order_id")),
        ),
        orders.declaration,
        orders.identity,
    )

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

    private companion object {
        val ACCESS_ADAPTER = InMemoryDatasetStorageAdapter
        val U1 = QueryCaller(
            Caller(
                tenantId = "t1",
                scope = Scope("u1"),
                subject = "u1",
                attributes = mapOf("departments" to listOf("sales"))
            ),
            QueryEntry.HTTP,
        )

        /** What the documents' policy lets [U1] read: a sales document, or one shared with u1. */
        val SALES_OR_SHARED = OrFilter(
            listOf(
                InFilter(QueryField("department"), listOf("sales")),
                ContainsAllFilter(QueryField("sharedWith"), listOf("u1")),
            ),
        )
    }
}
