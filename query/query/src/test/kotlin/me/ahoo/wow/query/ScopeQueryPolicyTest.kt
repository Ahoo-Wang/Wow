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

package me.ahoo.wow.query

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.observe.QueryAudit
import me.ahoo.wow.query.policy.QueryOwnership
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.policy.queryTenantOf
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import me.ahoo.wow.manifest.Ownership as ManifestOwnership

class ScopeQueryPolicyTest {
    private suspend fun context(filter: FilterExpression = MatchAllFilter, schema: QueryModelSchema? = null) =
        QueryContext(
            ListQuery(filter),
            ManifestFixtures.ORDER,
            schema ?: ManifestFixtures.orderSchema(),
            QueryType.LIST,
            QueryEntry.HTTP,
        )

    @Test
    fun `the caller's tenant, owner and space restrict the query, with the active default`() {
        runTest {
            val caller = QueryCaller(Caller(tenantId = "t1", scope = Scope("o1", "s1")), entry = QueryEntry.HTTP)
            ScopeQueryPolicy().evaluate(caller, context()).assert().isEqualTo(
                AndFilter(
                    listOf(
                        TenantIdFilter("t1"),
                        OwnerIdFilter("o1"),
                        SpaceIdFilter("s1"),
                        DeletionFilter(DeletionState.ACTIVE),
                    ),
                ),
            )
        }
    }

    @Test
    fun `only the present parts of the caller's scope restrict`() {
        runTest {
            val policy = ScopeQueryPolicy()
            policy.evaluate(QueryCaller(Caller(tenantId = "t1")), context()).assert()
                .isEqualTo(AndFilter(listOf(TenantIdFilter("t1"), DeletionFilter(DeletionState.ACTIVE))))
            policy.evaluate(QueryCaller(Caller(tenantId = "t1", scope = Scope(spaceId = "s1"))), context()).assert()
                .isEqualTo(
                    AndFilter(listOf(TenantIdFilter("t1"), SpaceIdFilter("s1"), DeletionFilter(DeletionState.ACTIVE))),
                )
            // Trusted in-process code is restricted by nothing but the deletion default.
            policy.evaluate(QueryCaller.IN_PROCESS, context()).assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        }
    }

    @Test
    fun `an aggregate of its tenant is not restricted by the caller's owner, but by its space`() {
        runTest {
            val caller = QueryCaller(Caller(tenantId = "t1", scope = Scope("o1", "s1")), entry = QueryEntry.HTTP)
            val tenants = QueryOwnership { if (it == ManifestFixtures.ORDER) Ownership.TENANT else Ownership.OWNER }
            ScopeQueryPolicy(ownerships = tenants).evaluate(caller, context()).assert().isEqualTo(
                AndFilter(
                    listOf(TenantIdFilter("t1"), SpaceIdFilter("s1"), DeletionFilter(DeletionState.ACTIVE)),
                ),
            )
        }
    }

    @Test
    fun `the ownerships of a Manifest are its aggregates'`() {
        val product = AggregateManifest(
            "product",
            state = StateManifest(Manifest.schemaRef("Product")),
            ownership = ManifestOwnership.TENANT,
        )
        val order = AggregateManifest("order", state = product.state, ownership = ManifestOwnership.OWNER)
        val manifest = Manifest(contexts = listOf(ContextManifest("shop", aggregates = listOf(product, order))))
        val ownerships = QueryOwnership.of(manifest)
        ownerships.ownershipOf(AggregateType("shop", "product")).assert().isEqualTo(Ownership.TENANT)
        ownerships.ownershipOf(AggregateType("shop", "order")).assert().isEqualTo(Ownership.OWNER)
        ownerships.ownershipOf(AggregateType("other", "product")).assert().isEqualTo(Ownership.OWNER)
        QueryOwnership.of(Manifest()).assert().isSameAs(QueryOwnership.OWNERS)
        QueryOwnership.OWNERS.ownershipOf(AggregateType("shop", "product")).assert().isEqualTo(Ownership.OWNER)
    }

    @Test
    fun `a query stating its own deletion scope keeps it`() {
        runTest {
            val policy = ScopeQueryPolicy()
            policy.evaluate(QueryCaller.IN_PROCESS, context(DeletionFilter(DeletionState.DELETED))).assert()
                .isEqualTo(MatchAllFilter)
            policy.evaluate(QueryCaller(Caller(tenantId = "t1")), context(DeletionFilter(DeletionState.ALL))).assert()
                .isEqualTo(TenantIdFilter("t1"))
        }
    }

    @Test
    fun `a model without a profile gets the caller's scope and no deletion default`() {
        runTest {
            val schema = boundSchemaFixture(
                objectFixture("tenantId" to scalarFixture()),
                model = QueryModel("CUSTOM"),
            )
            ScopeQueryPolicy().evaluate(QueryCaller(Caller(tenantId = "t1")), context(schema = schema))
                .assert().isEqualTo(TenantIdFilter("t1"))
        }
    }

    @Test
    fun `the tenant comes from the host's tenant policy`() {
        runTest {
            val fixed = TenantPolicy.fixed(mapOf(ManifestFixtures.ORDER to "fixed"))
            val policy = ScopeQueryPolicy(fixed)
            policy.order.assert().isEqualTo(ScopeQueryPolicy.ORDER)
            policy.evaluate(QueryCaller(Caller(tenantId = "t1")), context(DeletionFilter(DeletionState.ALL))).assert()
                .isEqualTo(TenantIdFilter("fixed"))
            TenantPolicy.CALLERS.queryTenantOf(AggregateType("a", "b"), QueryCaller(Caller(tenantId = "t2")))
                .assert().isEqualTo("t2")
            TenantPolicy.CALLERS.queryTenantOf(AggregateType("a", "b"), QueryCaller.IN_PROCESS).assert().isNull()
            fixed.queryTenantOf(AggregateType("a", "b"), QueryCaller(Caller(tenantId = "t2"))).assert().isEqualTo("t2")
        }
    }

    @Test
    fun `a caller's tenant is not blank and an in-process copy drops the scope`() {
        runCatching {
            QueryCaller(Caller(tenantId = " "))
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        val caller = QueryCaller(Caller(tenantId = "t", scope = Scope("o"), subject = "u"), entry = QueryEntry.HTTP)
        caller.inProcess().run {
            entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
            tenantId.assert().isNull()
            principal.assert().isEqualTo(Scope.NONE)
            subject.assert().isNull()
        }
    }

    @Test
    fun `through the gateway each caller sees its own scope only`() {
        runTest {
            val storage = InMemoryQueryStorage()
            listOf(
                snapshot("a", "t1", Scope("o1", "s1")),
                snapshot("b", "t1", Scope("o2", "s1")),
                snapshot("c", "t2", Scope("o1", "s1")),
                snapshot("d", "t1", Scope("o1", "s2")),
                snapshot("e", "t1", Scope("o1", "s1"), deleted = true),
            ).forEach(storage::save)
            val gateway = DefaultQueryGateway(
                ManifestFixtures.ORDER,
                storage.binding(ManifestFixtures.ORDER).backend,
                FixedQueryModelSchemaProvider(ManifestFixtures.orderSchema()),
                policies = listOf(ScopeQueryPolicy()),
            )
            suspend fun ids(caller: QueryCaller, filter: FilterExpression = MatchAllFilter) =
                gateway.list(
                    ListQuery(filter, sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC)), limit = 100),
                    caller,
                )
                    .toList().map { it.path("aggregateId").stringValue() }
            ids(QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP)).assert().containsExactly("a", "b", "d")
            ids(
                QueryCaller(Caller(tenantId = "t1", scope = Scope("o1")), entry = QueryEntry.HTTP)
            ).assert().containsExactly("a", "d")
            ids(
                QueryCaller(Caller(tenantId = "t1", scope = Scope("o1", "s1")), entry = QueryEntry.HTTP)
            ).assert().containsExactly("a")
            ids(QueryCaller(Caller(tenantId = "t2"), entry = QueryEntry.HTTP)).assert().containsExactly("c")
            ids(QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP), DeletionFilter(DeletionState.DELETED))
                .assert().containsExactly("e")
            ids(QueryCaller.IN_PROCESS).assert().containsExactly("a", "b", "c", "d")
            // A caller cannot widen its scope by naming another tenant in its own filter.
            ids(QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP), TenantIdFilter("t2")).assert().isEmpty()
            gateway.count(
                CountQuery(MatchAllFilter),
                QueryCaller(Caller(tenantId = "t2"), entry = QueryEntry.HTTP)
            ).count
                .assert().isEqualTo(1L)
            gateway.single(
                SingleQuery(AggregateIdFilter("c")),
                QueryCaller(Caller(tenantId = "t1"), entry = QueryEntry.HTTP)
            ).assert().isNull()
        }
    }

    @Test
    fun `a cross-tenant caller is restricted to its tenants, a fixed tenant kept`() {
        runTest {
            val caller = Caller(tenantId = "t1", scope = Scope("o1"), crossScope = true)
            val policy = ScopeQueryPolicy()
            val active = DeletionFilter(DeletionState.ACTIVE)
            policy.evaluate(QueryCaller(caller, crossTenant = CrossTenant.ALL), context()).assert()
                .isEqualTo(AndFilter(listOf(OwnerIdFilter("o1"), active)))
            policy.evaluate(QueryCaller(caller, crossTenant = CrossTenant(setOf("t2"))), context()).assert()
                .isEqualTo(AndFilter(listOf(TenantIdFilter("t2"), OwnerIdFilter("o1"), active)))
            policy.evaluate(QueryCaller(caller, crossTenant = CrossTenant(setOf("t2", "t3"))), context()).assert()
                .isEqualTo(
                    AndFilter(
                        listOf(
                            OrFilter(listOf(TenantIdFilter("t2"), TenantIdFilter("t3"))),
                            OwnerIdFilter("o1"),
                            active
                        ),
                    ),
                )
            val fixed = TenantPolicy.fixed(mapOf(ManifestFixtures.ORDER to "fixed"))
            ScopeQueryPolicy(fixed).evaluate(QueryCaller(caller, crossTenant = CrossTenant.ALL), context()).assert()
                .isEqualTo(AndFilter(listOf(TenantIdFilter("fixed"), OwnerIdFilter("o1"), active)))
            QueryCaller(caller, crossTenant = CrossTenant.ALL).tenantId.assert().isNull()
            QueryCaller(caller, crossTenant = CrossTenant.ALL).inProcess().crossTenant.assert().isNull()
            runCatching { CrossTenant(emptySet()) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
            runCatching { CrossTenant(setOf(" ")) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a fixed-tenant type never leaks another tenant to a cross-tenant caller, and every such query is logged`() {
        runTest {
            val storage = InMemoryQueryStorage()
            listOf(
                snapshot("a", "t1", Scope.NONE),
                snapshot("b", "t2", Scope.NONE),
                snapshot("c", "fixed", Scope.NONE),
            ).forEach(storage::save)
            val schema = ManifestFixtures.orderSchema()
            fun gateway(tenants: TenantPolicy) = DefaultQueryGateway(
                ManifestFixtures.ORDER,
                storage.binding(ManifestFixtures.ORDER).backend,
                FixedQueryModelSchemaProvider(schema),
                policies = listOf(ScopeQueryPolicy(tenants, crossTenant = true)),
            )
            val sorted = ListQuery(
                MatchAllFilter,
                sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC)),
                limit = 100
            )
            val operator = Caller(tenantId = "t1", subject = "operator", crossScope = true)
            val all = QueryCaller(operator, QueryEntry.HTTP, crossTenant = CrossTenant.ALL)
            val logger = LoggerFactory.getLogger(QueryAudit.CROSS_TENANT_LOGGER) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                // No observer audits: the gateway's observer is none.
                val callers = gateway(TenantPolicy.CALLERS)
                callers.list(sorted, all).toList().map { it.path("aggregateId").stringValue() }
                    .assert().containsExactly("a", "b", "c")
                callers.list(sorted, all.copy(crossTenant = CrossTenant(setOf("t2", "fixed")))).toList()
                    .map { it.path("aggregateId").stringValue() }.assert().containsExactly("b", "c")
                callers.count(CountQuery(MatchAllFilter), all).count.assert().isEqualTo(3L)
                val pinned = gateway(TenantPolicy.fixed(mapOf(ManifestFixtures.ORDER to "fixed")))
                pinned.list(sorted, all).toList().map { it.path("aggregateId").stringValue() }
                    .assert().containsExactly("c")
                pinned.count(CountQuery(MatchAllFilter), all).count.assert().isEqualTo(1L)
                // An ordinary caller of the same tenant is not served the cross-tenant count.
                callers.count(CountQuery(MatchAllFilter), QueryCaller(operator, QueryEntry.HTTP)).count
                    .assert().isEqualTo(1L)
            } finally {
                logger.detachAppender(appender)
            }
            val lines = appender.list.map { it.formattedMessage }
            lines.size.assert().isEqualTo(5)
            appender.list.all { it.level == Level.INFO }.assert().isTrue()
            lines.first().assert().contains("operator", "tenant t1", "crossTenant=*", "outcome=COMPLETE")
            lines[1].assert().contains("crossTenant=[t2, fixed]")
        }
    }

    private fun snapshot(id: String, tenant: String, scope: Scope, deleted: Boolean = false) = SnapshotRecord(
        ref = ManifestFixtures.ORDER.ref(tenant, id),
        version = 1,
        schemaFingerprint = "test",
        state = """{"buyer":"x"}""",
        scope = scope,
        deleted = deleted,
        firstEventTime = 1,
        lastEventTime = 1,
    )
}
