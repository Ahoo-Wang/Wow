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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.TraceContext
import me.ahoo.wow.spi.observation.trace
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * The gateway's admission, execution and observation, ported from 9.x `QueryGatewaySubscriptionTest`: a suspend call
 * admits on each call, a flow when it is collected.
 */
@Suppress("LargeClass")
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
    fun `an auditing observer gets one audit per query without filter values`() {
        runTest {
            val audits = mutableListOf<QueryAudit>()
            val restricting = QueryPolicy { _, _ -> OwnerIdFilter("owner") }
            val permissive = QueryPolicy { _, _ -> MatchAllFilter }
            val gateway = gateway(
                GatewayBackend(rows = { flowOf(snapshotNode(), snapshotNode()) }),
                policies = listOf(permissive, restricting),
                observer = auditObserver { audits += it },
            )
            val query = ListQuery(EqualFilter(QueryField("aggregateId"), "secret-value"), limit = 10)
            val caller = QueryCaller(
                entry = QueryEntry.HTTP,
                scope = QueryScope(authenticated = TenantIdFilter("tenant"))
            )
            gateway.list(query, caller).toList().assert().hasSize(2)
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert()
                .isEqualTo(CountResult(0, exact = true))

            val (list, count) = audits
            list.aggregateType.assert().isEqualTo(AGGREGATE)
            list.queryType.assert().isEqualTo(QueryType.LIST)
            list.entry.assert().isEqualTo(QueryEntry.HTTP)
            list.model.assert().isEqualTo(QueryModel.SNAPSHOT)
            list.modelVersion.assert().isEqualTo(SCHEMA.version)
            list.scopeFields.assert().containsExactly("tenantId")
            list.policies.assert().containsExactly(restricting::class.java.name)
            list.rows.assert().isEqualTo(2)
            list.outcome.assert().isEqualTo(QueryAudit.Outcome.COMPLETE)
            list.errorCode.assert().isNull()
            list.caller.assert().isSameAs(caller)
            list.fingerprint.assert().isEqualTo(fingerprintOf(QueryType.LIST, query))
            (list.elapsed >= Duration.ZERO).assert().isTrue()
            list.toString().assert().doesNotContain("secret-value", "tenant\"")
            count.rows.assert().isEqualTo(1)
            count.entry.assert().isEqualTo(QueryEntry.UNSPECIFIED)

            // Scope fields come from each system filter's spec and each field predicate, not from its element predicate.
            val ids = OrFilter(listOf(IdsFilter(listOf("a")), EqualFilter(QueryField("state.region"), null)))
            val items = ElementMatchFilter(QueryField("state.items"), EqualFilter(QueryField("sku"), null))
            val scoped = QueryCaller(scope = QueryScope(declared = AndFilter(listOf(ids, items))))
            assertThrows<QuerySchemaValidationException> { gateway.count(CountQuery(MatchAllFilter), scoped) }
            audits.last().scopeFields.assert().containsExactly("aggregateId", "state.region", "state.items")
            audits.last().outcome.assert().isEqualTo(QueryAudit.Outcome.ERROR)
            audits.last().errorCode!!.assert().startsWith("${QueryErrors.INVALID}:")
        }
    }

    @Test
    fun `the route selection restricts the query but is not audited as scope`() {
        runTest {
            val audits = mutableListOf<QueryAudit>()
            val backend = GatewayBackend(row = ::snapshotNode)
            val caller = QueryCaller.IN_PROCESS.withSelection(AggregateIdFilter("aggregate"))
            gateway(backend, observer = auditObserver { audits += it }).single(SingleQuery(MatchAllFilter), caller)
                .assert().isNotNull()
            backend.filters.single().assert()
                .isEqualTo(AndFilter(listOf(AggregateIdFilter("aggregate"), DeletionFilter(DeletionState.ACTIVE))))
            audits.single().scopeFields.assert().isEmpty()
            audits.single().rows.assert().isEqualTo(1)
        }
    }

    @Test
    fun `the fingerprint groups equal shapes and a rejection is audited with its code`() {
        runTest {
            fun list(field: String, value: String) = ListQuery(EqualFilter(QueryField(field), value))
            fingerprintOf(QueryType.LIST, list("state.name", "a")).assert()
                .isEqualTo(fingerprintOf(QueryType.LIST, list("state.name", "b")))
                .isNotEqualTo(fingerprintOf(QueryType.LIST, list("state.other", "a")))
            val audits = mutableListOf<QueryAudit>()
            val gateway = gateway(
                GatewayBackend(),
                observer = auditObserver { audits += it },
                entryPolicy = QueryEntryPolicy(requireExplicitEntry = true),
            )
            assertThrows<QueryRequestException> { gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED) }
            audits.single().outcome.assert().isEqualTo(QueryAudit.Outcome.ERROR)
            audits.single().model.assert().isNull()
            audits.single().modelVersion.assert().isNull()
            audits.single().errorCode.assert()
                .isEqualTo("${QueryErrors.INVALID}:${QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED}")
        }
    }

    @Test
    fun `required explicit entry rejects unspecified queries before touching the schema or backend`() {
        runTest {
            val backend = GatewayBackend()
            val gateway = gateway(backend, entryPolicy = QueryEntryPolicy(requireExplicitEntry = true))
            assertThrows<QueryRequestException> { gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED) }
                .code.assert().isEqualTo(QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED)
            assertThrows<QueryRequestException> { gateway.count(CountQuery(MatchAllFilter), QueryCaller.UNSPECIFIED) }
            backend.filters.assert().isEmpty()

            QueryEntry.entries.filter { it != QueryEntry.UNSPECIFIED }.forEach { entry ->
                gateway.single(SingleQuery(MatchAllFilter), QueryCaller(entry = entry)).assert().isNull()
            }
            backend.filters.assert().hasSize(2)
        }
    }

    @Test
    fun `required authenticated scope counts only an authenticated tenant, and only for HTTP`() {
        runTest {
            val received = mutableListOf<QueryContext<*>>()
            val gateway = gateway(
                GatewayBackend(),
                filters = listOf(recordPrepare { received += it }),
                entryPolicy = QueryEntryPolicy(requireAuthenticatedScope = true),
            )
            val tenant = TenantIdFilter("tenant")
            listOf(
                QueryScope.NONE,
                QueryScope(declared = tenant),
                QueryScope(authenticated = OwnerIdFilter("owner")),
                QueryScope(authenticated = EqualFilter(QueryField("tenantId"), null)),
            ).forEach { scope ->
                assertThrows<QueryScopeRequiredException> {
                    gateway.count(CountQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.HTTP, scope = scope))
                }.apply {
                    message.assert().isEqualTo("Query on [SNAPSHOT] requires an authenticated [tenantId] scope.")
                    errorCode.assert().isEqualTo(QueryErrors.FORBIDDEN)
                    errorMsg.assert().isEqualTo(message)
                    model.assert().isEqualTo(QueryModel.SNAPSHOT)
                    field.assert().isEqualTo(QueryField("tenantId"))
                }
            }
            received.assert().isEmpty()

            listOf(
                QueryScope(authenticated = tenant),
                QueryScope(authenticated = AndFilter(listOf(OwnerIdFilter("owner"), tenant)), declared = tenant),
                QueryScope(authenticated = EqualFilter(QueryField("tenantId"), "tenant")),
            ).forEach { scope ->
                gateway.count(
                    CountQuery(MatchAllFilter),
                    QueryCaller(entry = QueryEntry.HTTP, scope = scope)
                ).count.assert().isZero()
            }
            QueryEntry.entries.filter { it != QueryEntry.HTTP }.forEach { entry ->
                gateway.count(
                    CountQuery(MatchAllFilter),
                    QueryCaller(entry = entry, scope = QueryScope(declared = tenant))
                )
                    .count.assert().isZero()
            }
            received.assert().hasSize(5)
        }
    }

    @Test
    fun `an in-process caller drops the inherited scope, selection and entry`() {
        val http = QueryCaller(
            Caller(subject = "user", attributes = mapOf("abac.region" to listOf("east"))),
            entry = QueryEntry.HTTP,
            scope = QueryScope(authenticated = TenantIdFilter("tenant")),
        ).withSelection(AggregateIdFilter("aggregate"))
        val nested = http.inProcess()
        nested.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
        nested.scope.assert().isEqualTo(QueryScope.NONE)
        nested.selection.assert().isEqualTo(MatchAllFilter)
        nested.assert().isSameAs(QueryCaller.IN_PROCESS)
        http.withSelection(MatchAllFilter).assert().isSameAs(http)
        http.withScope(QueryScope(declared = OwnerIdFilter("owner"))).scope.declared.assert()
            .isEqualTo(OwnerIdFilter("owner"))
        val scope = QueryScope(TenantIdFilter("tenant"), OwnerIdFilter("owner"))
        QueryCaller.IN_PROCESS.withScope(scope).scope.assert().isEqualTo(scope)
        QueryScope(TenantIdFilter("tenant"), OwnerIdFilter("owner")).filter.assert()
            .isEqualTo(TenantIdFilter("tenant").appendFilter(OwnerIdFilter("owner")))
    }

    @Test
    fun `the HTTP budget counts the edge scope and rejects before the schema or backend while in-process runs unchecked`() {
        runTest {
            val schemaRequests = AtomicInteger()
            val provider = object : QueryModelSchemaProvider {
                override suspend fun schema(): QueryModelSchema = SCHEMA.also { schemaRequests.incrementAndGet() }
                override suspend fun refresh(): QueryModelSchema = schema()
            }
            val backend = GatewayBackend()
            val gateway = gateway(
                backend,
                provider = provider,
                entryPolicy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, maxFilterValues = 1)),
            )
            val scope = QueryScope(declared = IdsFilter(listOf("first", "second")))
            assertThrows<QueryRequestException> {
                gateway.single(SingleQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.HTTP, scope = scope))
            }.message.assert().isEqualTo("HTTP query filter values[2] must not exceed 1.")
            schemaRequests.get().assert().isZero()
            backend.filters.assert().isEmpty()

            gateway.single(SingleQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.IN_PROCESS, scope = scope))
            backend.filters.assert().hasSize(1)
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
    fun `unspecified entries are admitted by default`() {
        runTest {
            val gateway = gateway(GatewayBackend())
            gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert().isNull()
            gateway.entryPolicy.assert().isEqualTo(QueryEntryPolicy.DEFAULT)
            gateway.aggregateType.assert().isEqualTo(AGGREGATE)
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
            val provider = FixedQueryModelSchemaProvider(gatewaySchema(QueryModel("CUSTOM")))
            assertThrows<QuerySchemaValidationException> {
                gateway(backend, provider).cursor(CursorQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            }.violation.assert().isEqualTo(QueryViolation.IdentityUndefined(QueryModel("CUSTOM")))
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
            val received = backend.admitted.map { it.query as ICursorQuery }
            received[0].sort.assert().containsExactly(
                Sort(QueryField("tenantId"), Sort.Direction.ASC),
                Sort(QueryField("aggregateId"), Sort.Direction.ASC),
            )
            // The aggregate id is unique only within a tenant, so the tenant still breaks its ties.
            received[1].sort.assert().containsExactly(explicit, Sort(QueryField("tenantId"), Sort.Direction.ASC))
        }
    }

    @Test
    fun `observer failure cannot replace original backend failure or mutate it`() {
        runTest {
            val original = IllegalStateException("backend")
            val observerFailure = AssertionError("observer")
            val gateway = gateway(
                GatewayBackend(row = { throw original }),
                observer = errorObserver { throw observerFailure },
            )
            assertThrows<QueryExecutionException> {
                gateway.single(
                    SingleQuery(MatchAllFilter),
                    QueryCaller.UNSPECIFIED
                )
            }
                .apply {
                    message.assert().isEqualTo("Query storage failed.")
                    generateSequence(cause) { it.cause }.last().assert().isSameAs(original)
                    suppressed.assert().isEmpty()
                }
            original.suppressed.assert().isEmpty()
        }
    }

    @Test
    fun `observer failure cannot replace successful completion`() {
        runTest {
            val observer = object : QueryObserver {
                override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
                    throw AssertionError("observer")
                }
            }
            gateway(GatewayBackend(row = ::snapshotNode), observer = observer)
                .single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert().isNotNull()
        }
    }

    @Test
    fun `empty backend results complete normally`() {
        runTest {
            val completed = mutableListOf<QueryType>()
            val observer = object : QueryObserver {
                override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
                    completed += queryType
                }
            }
            val gateway = gateway(GatewayBackend(), observer = observer)
            gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert().isNull()
            gateway.list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).toList().assert().isEmpty()
            completed.assert().isEqualTo(listOf(QueryType.SINGLE, QueryType.LIST))
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

    @Test
    fun `prepare rejection is observed and stops backend`() {
        runTest {
            val original = IllegalStateException("prepare")
            val errors = mutableListOf<Throwable>()
            val filter = object : QueryFilter {
                override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q = throw original
            }
            val backend = GatewayBackend()
            assertThrows<IllegalStateException> {
                gateway(backend, filters = listOf(filter), observer = errorObserver { errors += it })
                    .single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            }.assert().isSameAs(original)
            backend.admitted.assert().isEmpty()
            errors.single().assert().isSameAs(original)
        }
    }

    @Test
    fun `a cancelled suspend query reports only its cancellation`() {
        runTest {
            val terminals = mutableListOf<String>()
            val audits = mutableListOf<QueryAudit>()
            val observer = terminalObserver(terminals) { audits += it }
            val gateway = gateway(GatewayBackend(row = { awaitCancellation() }), observer = observer)
            val call = launch { gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED) }
            runCurrent()
            call.cancel()
            call.join()
            terminals.assert().containsExactly("cancel")
            audits.single().outcome.assert().isEqualTo(QueryAudit.Outcome.CANCEL)
        }
    }

    @Test
    fun `stream observation follows terminal completion error and cancellation`() {
        runTest {
            val terminals = mutableListOf<String>()
            val audits = mutableListOf<QueryAudit>()
            val observer = terminalObserver(terminals) { audits += it }
            val original = IllegalStateException("late-error")
            fun rows(source: Flow<ObjectNode>) =
                gateway(GatewayBackend(rows = { source }), observer = observer)
                    .list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)

            rows(flowOf(snapshotNode())).collect { terminals.size.assert().isEqualTo(0) }
            assertThrows<QueryExecutionException> {
                rows(
                    flow {
                        emit(snapshotNode())
                        throw original
                    }
                ).collect { terminals.size.assert().isEqualTo(1) }
            }.let { generateSequence(it.cause) { cause -> cause.cause }.last().assert().isSameAs(original) }
            rows(
                flow {
                    emit(snapshotNode())
                    awaitCancellation()
                }
            ).take(1).collect { terminals.size.assert().isEqualTo(2) }
            terminals.assert().isEqualTo(listOf("complete", "error", "cancel"))
            audits.map { it.outcome }.assert().containsExactly(
                QueryAudit.Outcome.COMPLETE,
                QueryAudit.Outcome.ERROR,
                QueryAudit.Outcome.CANCEL,
            )
            audits.map { it.rows }.assert().containsExactly(1L, 1L, 1L)
            audits[1].errorCode.assert().isEqualTo(QueryErrors.FAILED)
        }
    }

    @Test
    fun `an aggregation is observed like a list`() {
        runTest {
            val terminals = mutableListOf<String>()
            val audits = mutableListOf<QueryAudit>()
            val gateway = gateway(
                GatewayBackend(groups = { flowOf(WowJson.mapper.createObjectNode().put("count", 3)) }),
                observer = terminalObserver(terminals) { audits += it },
            )
            val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
            gateway.aggregate(
                query,
                QueryCaller.UNSPECIFIED
            ).toList().single()["count"].intValue().assert().isEqualTo(3)
            terminals.assert().containsExactly("complete")
            audits.single().queryType.assert().isEqualTo(QueryType.AGGREGATION)
            audits.single().rows.assert().isEqualTo(1)
        }
    }

    @Test
    fun `cancellation while schema is loading is observed`() {
        runTest {
            val cancelled = AtomicInteger()
            val provider = object : QueryModelSchemaProvider {
                override suspend fun schema(): QueryModelSchema = awaitCancellation()
                override suspend fun refresh(): QueryModelSchema = schema()
            }
            val observer = object : QueryObserver {
                override fun onCancel(aggregateType: AggregateType, queryType: QueryType) {
                    cancelled.incrementAndGet()
                }
            }
            val call = launch {
                gateway(GatewayBackend(), provider, observer = observer)
                    .single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            }
            runCurrent()
            call.cancel()
            call.join()
            cancelled.get().assert().isOne()
        }
    }

    // D150: bounded counts and totals at the gateway.

    @Test
    fun `an HTTP count runs with the budget's limit and reports a larger count as the limit`() {
        runTest {
            val backend = GatewayBackend(matches = 7)
            val gateway = gateway(
                backend,
                entryPolicy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 5)),
            )
            val http = QueryCaller(entry = QueryEntry.HTTP)
            gateway.count(CountQuery(MatchAllFilter), http).assert().isEqualTo(CountResult(5, exact = false))
            gateway.count(CountQuery(MatchAllFilter, limit = 3), http).assert().isEqualTo(CountResult(3, exact = false))
            backend.admitted.map { (it.query as ICountQuery).limit }.assert().containsExactly(5, 3)
            assertThrows<QueryRequestException> { gateway.count(CountQuery(MatchAllFilter, limit = 6), http) }
                .code.assert().isEqualTo(QueryErrorCodes.SIZE_OUT_OF_RANGE)
            // An entry without a budget counts exactly unless the query bounds itself.
            gateway.count(
                CountQuery(MatchAllFilter),
                QueryCaller.IN_PROCESS
            ).assert().isEqualTo(CountResult(7, exact = true))
            gateway.count(CountQuery(MatchAllFilter, limit = 6), QueryCaller.IN_PROCESS)
                .assert().isEqualTo(CountResult(6, exact = false))
            // A count limit of 0 counts exactly.
            gateway(
                GatewayBackend(matches = 20_000),
                entryPolicy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 0)),
            ).count(CountQuery(MatchAllFilter), http).assert().isEqualTo(CountResult(20_000, exact = true))
        }
    }

    @Test
    fun `a paged total is bounded by the entry's count limit`() {
        runTest {
            val backend = GatewayBackend(row = ::snapshotNode, total = 7)
            val budget = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 5)
            val gateway = gateway(backend, entryPolicy = QueryEntryPolicy(http = budget))
            val http = QueryCaller(entry = QueryEntry.HTTP)

            gateway.paged(PagedQuery(MatchAllFilter), http).apply {
                total.assert().isEqualTo(5)
                exact.assert().isFalse()
                list.assert().hasSize(1)
            }
            backend.windows.last().assert().isEqualTo(PageWindow.Offset(0, 10, withTotal = true, totalLimit = 5))

            gateway.paged(PagedQuery(MatchAllFilter, total = TotalMode.NONE), http).apply {
                total.assert().isNull()
                exact.assert().isFalse()
            }
            backend.windows.last().assert().isEqualTo(PageWindow.Offset(0, 10, withTotal = false))

            assertThrows<QueryRequestException> {
                gateway.paged(PagedQuery(MatchAllFilter, total = TotalMode.EXACT), http)
            }.violation.assert().isEqualTo(QueryViolation.ExactCountDisabled(QueryBudget.HTTP_LABEL))

            // An entry without a budget bounds its total by the default count limit.
            gateway.paged(PagedQuery(MatchAllFilter), QueryCaller.IN_PROCESS).total.assert().isEqualTo(7)
            backend.windows.last().assert()
                .isEqualTo(PageWindow.Offset(0, 10, withTotal = true, totalLimit = QueryBudget.DEFAULT_COUNT_LIMIT))
            gateway.paged(PagedQuery(MatchAllFilter, total = TotalMode.EXACT), QueryCaller.IN_PROCESS)
                .exact.assert().isTrue()
            backend.windows.last().assert().isEqualTo(PageWindow.Offset(0, 10, withTotal = true))
        }
    }

    @Test
    fun `an exact total needs the budget's leave or an unbounded budget`() {
        runTest {
            listOf(
                QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 5, allowExactCount = true),
                QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 0),
            ).forEach { budget ->
                val backend = GatewayBackend(row = ::snapshotNode, total = 7)
                gateway(backend, entryPolicy = QueryEntryPolicy(http = budget))
                    .paged(PagedQuery(MatchAllFilter, total = TotalMode.EXACT), QueryCaller(entry = QueryEntry.HTTP))
                    .apply {
                        total.assert().isEqualTo(7)
                        exact.assert().isTrue()
                    }
                backend.windows.single().assert().isEqualTo(PageWindow.Offset(0, 10, withTotal = true))
            }
            val unbounded = GatewayBackend(row = ::snapshotNode, total = 7)
            gateway(
                unbounded,
                entryPolicy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 0)),
            ).paged(PagedQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.HTTP)).exact.assert().isTrue()
            unbounded.windows.single().assert().isEqualTo(PageWindow.Offset(0, 10, withTotal = true))
        }
    }

    // D150: timeouts at the gateway.

    @Test
    fun `every operation fails with the entry's timeout and is observed as an error`() {
        runTest {
            val errors = mutableListOf<Throwable>()
            val audits = mutableListOf<QueryAudit>()
            val observer = object : QueryObserver {
                override val audits: Boolean = true
                override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) {
                    errors += error
                }

                override fun onAudit(audit: QueryAudit) {
                    audits += audit
                }
            }
            val slow = SlowBackend()
            listOf(
                QueryEntryPolicy(inProcessTimeout = Duration.ofMillis(100)) to QueryCaller.IN_PROCESS,
                QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ofMillis(100))) to
                    QueryCaller(entry = QueryEntry.HTTP),
            ).forEach { (entryPolicy, caller) ->
                val gateway = gateway(slow, observer = observer, entryPolicy = entryPolicy)
                val operations = listOf<suspend () -> Any?>(
                    { gateway.single(SingleQuery(MatchAllFilter), caller) },
                    { gateway.list(ListQuery(MatchAllFilter, limit = 10), caller).toList() },
                    { gateway.paged(PagedQuery(MatchAllFilter), caller) },
                    { gateway.cursor(CursorQuery(MatchAllFilter), caller) },
                    { gateway.count(CountQuery(MatchAllFilter), caller) },
                    {
                        gateway.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))), caller)
                            .toList()
                    },
                )
                operations.forEach { operation ->
                    assertThrows<QueryTimeoutException> { operation() }.timeout.assert().isEqualTo(
                        Duration.ofMillis(100)
                    )
                }
            }
            errors.assert().hasSize(12)
            audits.map { it.errorCode }.distinct().assert().containsExactly(QueryErrors.TIMEOUT)
        }
    }

    @Test
    fun `a budget timeout of zero and no in-process timeout run without one`() {
        runTest {
            val slow = SlowBackend()
            gateway(slow, entryPolicy = QueryEntryPolicy(inProcessTimeout = null))
                .count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS).count.assert().isEqualTo(1)
            gateway(
                slow,
                entryPolicy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ZERO))
            )
                .count(CountQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.HTTP)).count.assert().isEqualTo(1)
            slow.admitted.map { it.timeout }.assert().containsExactly(null, null)
            gateway(slow).count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            slow.admitted.last().timeout.assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        }
    }

    @Test
    fun `the entry policy gives each entry its budget and timeout`() {
        val inProcess = QueryBudget("In-process", timeout = Duration.ofSeconds(5))
        val policy = QueryEntryPolicy(inProcess = inProcess, inProcessTimeout = Duration.ofSeconds(1))
        policy.budget(QueryEntry.HTTP).assert().isSameAs(QueryBudget.HTTP_DEFAULT)
        policy.budget(QueryEntry.UNSPECIFIED).assert().isSameAs(inProcess)
        policy.timeout(QueryEntry.IN_PROCESS).assert().isEqualTo(Duration.ofSeconds(5))
        policy.timeout(QueryEntry.HTTP).assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        QueryEntryPolicy().timeout(QueryEntry.IN_PROCESS).assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        assertThrows<IllegalArgumentException> { QueryEntryPolicy(inProcessTimeout = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", timeout = Duration.ofSeconds(-1)) }
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", countLimit = -1) }
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

    private fun recordPrepare(record: (QueryContext<*>) -> Unit) = object : QueryFilter {
        override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q {
            record(context)
            return context.query
        }
    }

    private fun auditObserver(record: (QueryAudit) -> Unit) = object : QueryObserver {
        override val audits: Boolean = true
        override fun onAudit(audit: QueryAudit) = record(audit)
    }

    private fun errorObserver(record: (Throwable) -> Unit) = object : QueryObserver {
        override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) = record(error)
    }

    private fun terminalObserver(terminals: MutableList<String>, audit: (QueryAudit) -> Unit) =
        object : QueryObserver {
            override val audits: Boolean = true
            override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
                terminals += "complete"
            }

            override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) {
                terminals += "error"
            }

            override fun onCancel(aggregateType: AggregateType, queryType: QueryType) {
                terminals += "cancel"
            }

            override fun onAudit(audit: QueryAudit) = audit(audit)
        }

    /** The trace of an observed query, named after its observation. */
    private class Trace(name: String) : TraceContext() {
        override val fields: Map<String, String> = mapOf("traceparent" to name)
    }

    /** Records in [told] what the observation [name] is told; it runs in the trace [name]. */
    private class Told(private val name: String, private val told: MutableList<String>) : Observation {
        override val context: CoroutineContext = Trace(name)

        override fun outcome(outcome: String) {
            told += "$name $outcome"
        }

        override fun failed(error: Throwable) {
            told += "$name failed"
        }

        override fun end() {
            told += "$name end"
        }
    }

    private fun startingObserver(name: String, told: MutableList<String>) = object : QueryObserver {
        override fun start(aggregateType: AggregateType, queryType: QueryType): Observation =
            Told("$name ${queryType.name}", told)
    }

    @Test
    fun `a query should run in its observation, which ends with it and hears its failure (D174)`() {
        runTest {
            val told = CopyOnWriteArrayList<String>()
            val traces = CopyOnWriteArrayList<Map<String, String>>()
            val backend = GatewayBackend(row = {
                traces += currentCoroutineContext().trace
                null
            })
            val observer = startingObserver("q", told)
            gateway(backend, observer = observer).single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            traces.single().assert().isEqualTo(mapOf("traceparent" to "q SINGLE"))
            gateway(backend, observer = observer).list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).toList()
            val failing = GatewayBackend(rows = { flow { error("The store is down.") } })
            assertThrows<QueryExecutionException> {
                gateway(failing, observer = observer).list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).toList()
            }
            told.assert().containsExactly("q SINGLE end", "q LIST end", "q LIST failed", "q LIST end")
        }
    }

    @Test
    fun `observers together should each observe, and one that fails to start observe nothing (D174)`() {
        val told = CopyOnWriteArrayList<String>()
        val failing = object : QueryObserver {
            override fun start(aggregateType: AggregateType, queryType: QueryType): Observation = error("No tracer.")
        }
        QueryObserver.of(listOf(failing, QueryLogObserver())).start(AGGREGATE, QueryType.LIST)
            .assert().isSameAs(Observation.NONE)
        QueryObserver.of(listOf(startingObserver("a", told), QueryLogObserver())).start(AGGREGATE, QueryType.LIST)
            .context.trace.assert().isEqualTo(mapOf("traceparent" to "a LIST"))
        val both = QueryObserver.of(listOf(startingObserver("a", told), startingObserver("b", told)))
            .start(AGGREGATE, QueryType.COUNT)
        both.context.trace.assert().isEqualTo(mapOf("traceparent" to "a COUNT"))
        both.outcome("ok")
        both.failed(IllegalStateException("boom"))
        both.end()
        told.assert().containsExactly(
            "a COUNT ok",
            "b COUNT ok",
            "a COUNT failed",
            "b COUNT failed",
            "a COUNT end",
            "b COUNT end",
        )
        QueryObserver.NONE.start(AGGREGATE, QueryType.LIST).assert().isSameAs(Observation.NONE)
    }

    private fun gateway(
        backend: QueryBackend,
        provider: QueryModelSchemaProvider = FixedQueryModelSchemaProvider(SCHEMA),
        filters: List<QueryFilter> = emptyList(),
        policies: List<QueryPolicy> = emptyList(),
        observer: QueryObserver = QueryObserver.NONE,
        entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
    ) = DefaultQueryGateway(AGGREGATE, backend, provider, filters, policies, observer, entryPolicy)

    /**
     * Answers a page with [row] (a keyset page with one row per position asked for), streams [rows], counts
     * [matches] and aggregates [groups]; records every admitted query and page window.
     */
    private open class GatewayBackend(
        private val row: suspend () -> ObjectNode? = { null },
        private val rows: () -> Flow<ObjectNode> = { emptyFlow() },
        private val total: Long = 0,
        private val matches: Long = 0,
        private val groups: () -> Flow<ObjectNode> = { emptyFlow() },
    ) : QueryBackend {
        override val aggregateType: AggregateType = AGGREGATE
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
        val admitted = CopyOnWriteArrayList<AdmittedQuery<*>>()
        val windows = CopyOnWriteArrayList<PageWindow>()
        val filters: List<FilterExpression>
            get() = admitted.map { (it.query as FilterCapable<*>).filter }

        override fun stream(query: AdmittedQuery<IListQuery>): Flow<ObjectNode> = flow {
            admitted += query
            emitAll(rows())
        }

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
            admitted += query
            windows += window
            return when (window) {
                is PageWindow.Keyset -> {
                    val found = (0 until window.limit).mapNotNull { row() }
                    BackendPage(found, positions = found.indices.map { CursorPosition(listOf("row-$it")) })
                }

                is PageWindow.Offset -> BackendPage(listOfNotNull(row()), total.takeIf { window.withTotal })
            }
        }

        override suspend fun count(query: AdmittedQuery<ICountQuery>): Long {
            admitted += query
            return matches
        }

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
            admitted += query
            emitAll(groups())
        }
    }

    /** A backend that takes ten seconds for every primitive, then answers one record. */
    private class SlowBackend : GatewayBackend(
        row = {
            delay(SLOW)
            snapshotNode()
        },
        rows = {
            flow {
                delay(SLOW)
                emit(snapshotNode())
            }
        },
        total = 1,
        matches = 1,
        groups = {
            flow {
                delay(SLOW)
                emit(WowJson.mapper.createObjectNode().put("count", 1))
            }
        },
    ) {
        override suspend fun count(query: AdmittedQuery<ICountQuery>): Long {
            delay(SLOW)
            return super.count(query)
        }
    }

    private companion object {
        const val SLOW = 10_000L
        val AGGREGATE = AggregateType("context", "aggregate")
        val SCHEMA = gatewaySchema()

        fun snapshotNode(): ObjectNode = WowJson.mapper.readTree(
            """
            {"tenantId":"tenant","aggregateId":"aggregate","version":1,"ownerId":null,"spaceId":null,
             "deleted":false,"tags":{},"firstEventTime":1,"lastEventTime":1,"state":{"value":"state-value"}}
            """.trimIndent()
        ) as ObjectNode
    }
}
