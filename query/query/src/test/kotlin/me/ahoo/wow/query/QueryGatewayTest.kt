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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryGatewayFixtures.AGGREGATE
import me.ahoo.wow.query.QueryGatewayFixtures.GatewayBackend
import me.ahoo.wow.query.QueryGatewayFixtures.errorObserver
import me.ahoo.wow.query.QueryGatewayFixtures.gateway
import me.ahoo.wow.query.QueryGatewayFixtures.recordPrepare
import me.ahoo.wow.query.QueryGatewayFixtures.snapshotNode
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gateway's admission, execution and observation, ported from 9.x `QueryGatewaySubscriptionTest`: a suspend call
 * admits on each call, a flow when it is collected.
 */

class QueryGatewayTest {
    @Test
    fun `a filter cannot replace the caller's scope or identity`() {
        runTest {
            val filter = object : QueryFilter {
                override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q =
                    context.query.withFilter(MatchAllFilter)
            }
            val policy = QueryPolicy { caller, _ -> OwnerIdFilter(checkNotNull(caller.subject)) }
            val backend = GatewayBackend()
            gateway(backend, filters = listOf(filter), policies = listOf(policy)).single(
                SingleQuery(TenantIdFilter("user-input")),
                QueryCaller(Caller(subject = "trusted"), scope = QueryScope(authenticated = TenantIdFilter("tenant"))),
            )
            // Admission normalizes: the backend receives the conjunction flattened.
            backend.filters.single().assert().isEqualTo(
                AndFilter(
                    listOf(TenantIdFilter("tenant"), OwnerIdFilter("trusted"), DeletionFilter(DeletionState.ACTIVE))
                ),
            )
        }
    }

    @Test
    fun `filters and policies see the operation and the entry`() {
        runTest {
            val contexts = mutableListOf<QueryContext<*>>()
            val policy = QueryPolicy { _, context ->
                contexts += context
                MatchAllFilter
            }
            val gateway = gateway(GatewayBackend(), policies = listOf(policy))
            gateway.count(CountQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.HTTP))
            gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            contexts.map { it.queryType to it.entry }.assert().containsExactly(
                QueryType.COUNT to QueryEntry.HTTP,
                QueryType.SINGLE to QueryEntry.UNSPECIFIED,
            )
            contexts.map { it.aggregateType }.distinct().assert().containsExactly(AGGREGATE)
        }
    }

    @Test
    fun `snapshot policies share one prepared scoped context and only append conditions`() {
        runTest {
            val backend = GatewayBackend()
            val contexts = mutableListOf<QueryContext<*>>()
            val policies = listOf(OwnerIdFilter("owner"), TenantIdFilter("authorized-tenant")).map { access ->
                QueryPolicy { _, context ->
                    contexts += context
                    access
                }
            }
            val query = SingleQuery(TenantIdFilter("user-input"))
            gateway(backend, policies = policies)
                .single(query, QueryCaller(scope = QueryScope(declared = TenantIdFilter("trusted-scope"))))

            contexts.assert().hasSize(2)
            contexts[0].assert().isSameAs(contexts[1])
            contexts[0].query.assert().isEqualTo(query.appendFilter(TenantIdFilter("trusted-scope")))
            backend.filters.single().assert().isEqualTo(
                AndFilter(
                    listOf(
                        TenantIdFilter("user-input"),
                        TenantIdFilter("trusted-scope"),
                        OwnerIdFilter("owner"),
                        TenantIdFilter("authorized-tenant"),
                        DeletionFilter(DeletionState.ACTIVE),
                    ),
                ),
            )
        }
    }

    @Test
    fun `query policy can constrain data lifecycle before model defaults`() {
        runTest {
            val backend = GatewayBackend()
            val policy = QueryPolicy { _, _ -> DeletionFilter(DeletionState.DELETED) }
            gateway(backend, policies = listOf(policy)).single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            backend.filters.single().assert().isEqualTo(DeletionFilter(DeletionState.DELETED))
        }
    }

    @Test
    fun `default deletion selection respects only root and AND scopes`() {
        runTest {
            val backend = GatewayBackend()
            val gateway = gateway(backend)
            val explicit = DeletionFilter(DeletionState.ALL)
            val conjunction = TenantIdFilter("tenant").appendFilter(explicit)
            val disjunction = OrFilter(listOf(TenantIdFilter("tenant"), explicit))
            listOf(MatchAllFilter, explicit, conjunction, disjunction).forEach {
                gateway.single(SingleQuery(it), QueryCaller.UNSPECIFIED)
            }
            backend.filters.assert().isEqualTo(
                listOf(
                    DeletionFilter(DeletionState.ACTIVE),
                    explicit,
                    conjunction,
                    disjunction.appendFilter(DeletionFilter(DeletionState.ACTIVE)),
                )
            )
        }
    }

    @Test
    fun `each call pins one schema through prepare and backend`() {
        runTest {
            val schemas = ConcurrentLinkedQueue(listOf(gatewaySchema(), gatewaySchema()))
            val providerCalls = AtomicInteger()
            val provider = object : QueryModelSchemaProvider {
                override suspend fun schema(): QueryModelSchema {
                    providerCalls.incrementAndGet()
                    return checkNotNull(schemas.poll())
                }

                override suspend fun refresh(): QueryModelSchema = schema()
            }
            val prepared = CopyOnWriteArrayList<QueryModelSchema>()
            val backend = GatewayBackend(row = ::snapshotNode)
            val gateway = gateway(backend, provider, filters = listOf(recordPrepare { prepared += it.schema }))
            repeat(2) { gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert().isNotNull() }
            providerCalls.get().assert().isEqualTo(2)
            val executed = backend.admitted.map { it.schema }
            prepared.zip(executed).forEach { (left, right) -> left.assert().isSameAs(right) }
            prepared.map(System::identityHashCode).toSet().assert().hasSize(2)
        }
    }

    @Test
    fun `schema failure is observed without constructing context or invoking backend`() {
        runTest {
            val original = QuerySchemaUnavailableException("unavailable")
            val prepares = AtomicInteger()
            val observed = mutableListOf<Throwable>()
            val provider = object : QueryModelSchemaProvider {
                override suspend fun schema(): QueryModelSchema = throw original
                override suspend fun refresh(): QueryModelSchema = schema()
            }
            val backend = GatewayBackend()
            val gateway = gateway(
                backend,
                provider,
                filters = listOf(recordPrepare { prepares.incrementAndGet() }),
                observer = errorObserver { observed += it },
            )
            assertThrows<QuerySchemaUnavailableException> {
                gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            }.assert().isSameAs(original)
            assertThrows<QuerySchemaUnavailableException> {
                gateway.list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).toList()
            }.assert().isSameAs(original)
            prepares.get().assert().isZero()
            backend.admitted.assert().isEmpty()
            observed.assert().containsExactly(original, original)
        }
    }

    @Test
    fun `policy list is captured when gateway is assembled`() {
        runTest {
            val policies = mutableListOf<QueryPolicy>()
            val backend = GatewayBackend()
            val gateway = gateway(backend, policies = policies)
            policies += QueryPolicy { _, _ -> error("added after assembly") }
            gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            backend.filters.single().assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        }
    }

    @Test
    fun `repeated and concurrent calls isolate contexts and raw nodes`() {
        runTest {
            val contexts = CopyOnWriteArrayList<QueryContext<*>>()
            val nodes = CopyOnWriteArrayList<ObjectNode>()
            val gateway = gateway(
                GatewayBackend(row = { snapshotNode().also { nodes += it } }),
                filters = listOf(recordPrepare { contexts += it }),
            )
            val results = List(2) { async { gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED) } }
                .awaitAll() + gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            contexts.map(System::identityHashCode).toSet().assert().hasSize(3)
            nodes.map(System::identityHashCode).toSet().assert().hasSize(3)
            results.map(System::identityHashCode).toSet().assert().hasSize(3)
        }
    }

    @Test
    fun `a list flow is cold and admits on every collection`() {
        runTest {
            val contexts = CopyOnWriteArrayList<QueryContext<*>>()
            val gateway = gateway(
                GatewayBackend(rows = { flowOf(snapshotNode()) }),
                filters = listOf(recordPrepare { contexts += it }),
            )
            val rows = gateway.list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            contexts.assert().isEmpty()
            val first = rows.toList().single()
            val second = rows.toList().single()
            contexts.map(System::identityHashCode).toSet().assert().hasSize(2)
            first.assert().isNotSameAs(second)
        }
    }

    @Test
    fun `cursor rejects an unknown model instead of guessing its identity`() {
        runTest {
            val backend = GatewayBackend()
            val provider = FixedQueryModelSchemaProvider(gatewaySchema(QueryModel("custom")))
            assertThrows<QuerySchemaValidationException> {
                gateway(backend, provider).cursor(CursorQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            }.violation.assert().isEqualTo(QueryViolation.IdentityUndefined(QueryModel("custom")))
            backend.admitted.assert().isEmpty()
        }
    }

    @Test
    fun `gateway adds the snapshot cursor identity once and preserves its explicit direction`() {
        runTest {
            val backend = GatewayBackend()
            val gateway = gateway(backend)
            val original = CursorQuery(MatchAllFilter)
            gateway.cursor(original, QueryCaller.UNSPECIFIED)
            val explicit = Sort(QueryField("aggregateId"), Sort.Direction.DESC)
            gateway.cursor(CursorQuery(MatchAllFilter, sort = listOf(explicit)), QueryCaller.UNSPECIFIED)
            original.sort.assert().isEmpty()
            val received = backend.admitted.map { it.query as CursorQuery }
            received[0].sort.assert().containsExactly(
                Sort(QueryField("tenantId"), Sort.Direction.ASC),
                Sort(QueryField("aggregateId"), Sort.Direction.ASC),
            )
            // The aggregate id is unique only within a tenant, so the tenant still breaks its ties, in the sort's
            // last direction.
            received[1].sort.assert().containsExactly(explicit, Sort(QueryField("tenantId"), Sort.Direction.DESC))
        }
    }

    @Test
    fun `order controls prepare order`() {
        runTest {
            val calls = mutableListOf<String>()
            val gateway = gateway(
                GatewayBackend(row = {
                    calls += "backend"
                    null
                }),
                filters = listOf(OrderedPrepare(calls, "late", 2), OrderedPrepare(calls, "early", 1)),
            )
            gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            calls.assert().isEqualTo(listOf("early", "late", "backend"))
        }
    }

    private class OrderedPrepare(
        private val calls: MutableList<String>,
        private val name: String,
        override val order: Int,
    ) : QueryFilter {
        override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q {
            calls += name
            return context.query
        }
    }
}
