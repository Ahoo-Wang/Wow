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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test

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
            val caller = QueryCaller(QueryEntry.HTTP, tenantId = "t1", principal = Scope("o1", "s1"))
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
            policy.evaluate(QueryCaller(tenantId = "t1"), context()).assert()
                .isEqualTo(AndFilter(listOf(TenantIdFilter("t1"), DeletionFilter(DeletionState.ACTIVE))))
            policy.evaluate(QueryCaller(principal = Scope(spaceId = "s1")), context()).assert()
                .isEqualTo(AndFilter(listOf(SpaceIdFilter("s1"), DeletionFilter(DeletionState.ACTIVE))))
            // Trusted in-process code is restricted by nothing but the deletion default.
            policy.evaluate(QueryCaller.IN_PROCESS, context()).assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        }
    }

    @Test
    fun `a query stating its own deletion scope keeps it`() {
        runTest {
            val policy = ScopeQueryPolicy()
            policy.evaluate(QueryCaller.IN_PROCESS, context(DeletionFilter(DeletionState.DELETED))).assert()
                .isEqualTo(MatchAllFilter)
            policy.evaluate(QueryCaller(tenantId = "t1"), context(DeletionFilter(DeletionState.ALL))).assert()
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
            ScopeQueryPolicy().evaluate(QueryCaller(tenantId = "t1"), context(schema = schema))
                .assert().isEqualTo(TenantIdFilter("t1"))
        }
    }

    @Test
    fun `the tenant comes from the host's tenant policy`() {
        runTest {
            val fixed =
                QueryTenantPolicy { type, caller -> if (type == ManifestFixtures.ORDER) "fixed" else caller.tenantId }
            val policy = ScopeQueryPolicy(fixed)
            policy.order.assert().isEqualTo(ScopeQueryPolicy.ORDER)
            policy.evaluate(QueryCaller(tenantId = "t1"), context(DeletionFilter(DeletionState.ALL))).assert()
                .isEqualTo(TenantIdFilter("fixed"))
            QueryTenantPolicy.CALLERS.tenantOf(AggregateType("a", "b"), QueryCaller(tenantId = "t2"))
                .assert().isEqualTo("t2")
            QueryTenantPolicy.CALLERS.tenantOf(AggregateType("a", "b"), QueryCaller.IN_PROCESS).assert().isNull()
        }
    }

    @Test
    fun `a caller's tenant is not blank and an in-process copy drops the scope`() {
        runCatching {
            QueryCaller(tenantId = " ")
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        val caller = QueryCaller(QueryEntry.HTTP, subject = "u", tenantId = "t", principal = Scope("o"))
        caller.inProcess().run {
            entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
            tenantId.assert().isNull()
            principal.assert().isEqualTo(Scope.NONE)
            subject.assert().isEqualTo("u")
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
                storage.create(ManifestFixtures.ORDER).backend,
                FixedQueryModelSchemaProvider(ManifestFixtures.orderSchema()),
                policies = listOf(ScopeQueryPolicy()),
            )
            suspend fun ids(caller: QueryCaller, filter: FilterExpression = MatchAllFilter) =
                gateway.list(
                    ListQuery(filter, sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC)), limit = 100),
                    caller,
                )
                    .toList().map { it.path("aggregateId").stringValue() }
            ids(QueryCaller(QueryEntry.HTTP, tenantId = "t1")).assert().containsExactly("a", "b", "d")
            ids(
                QueryCaller(QueryEntry.HTTP, tenantId = "t1", principal = Scope("o1"))
            ).assert().containsExactly("a", "d")
            ids(
                QueryCaller(QueryEntry.HTTP, tenantId = "t1", principal = Scope("o1", "s1"))
            ).assert().containsExactly("a")
            ids(QueryCaller(QueryEntry.HTTP, tenantId = "t2")).assert().containsExactly("c")
            ids(QueryCaller(QueryEntry.HTTP, tenantId = "t1"), DeletionFilter(DeletionState.DELETED))
                .assert().containsExactly("e")
            ids(QueryCaller.IN_PROCESS).assert().containsExactly("a", "b", "c", "d")
            // A caller cannot widen its scope by naming another tenant in its own filter.
            ids(QueryCaller(QueryEntry.HTTP, tenantId = "t1"), TenantIdFilter("t2")).assert().isEmpty()
            gateway.count(CountQuery(MatchAllFilter), QueryCaller(QueryEntry.HTTP, tenantId = "t2")).count
                .assert().isEqualTo(1L)
            gateway.single(
                SingleQuery(AggregateIdFilter("c")),
                QueryCaller(QueryEntry.HTTP, tenantId = "t1")
            ).assert().isNull()
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
