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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.QueryGatewayFixtures.AGGREGATE
import me.ahoo.wow.query.QueryGatewayFixtures.GatewayBackend
import me.ahoo.wow.query.QueryGatewayFixtures.SCHEMA
import me.ahoo.wow.query.QueryGatewayFixtures.gateway
import me.ahoo.wow.query.QueryGatewayFixtures.recordPrepare
import me.ahoo.wow.query.QueryGatewayFixtures.snapshotNode
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.error.QueryScopeRequiredException
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.observe.QueryAudit
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.observe.audits
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** What the entry of a call decides at the gateway: its requirements, the HTTP budget, and the D150 bounded counts, totals and timeouts. */
class QueryGatewayEntryTest {
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
            backend.filters.assert().hasSize(QueryEntry.entries.size - 1)
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
                    message.assert().isEqualTo("Query on [snapshot] requires an authenticated [tenantId] scope.")
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
            received.assert().hasSize(3 + QueryEntry.entries.size - 1)
        }
    }

    @Test
    fun `an in-process caller drops the inherited scope, selection and entry`() {
        val http = QueryCaller(
            Caller(subject = "user", attributes = mapOf("region" to listOf("east"))),
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
    fun `unspecified entries are admitted by default`() {
        runTest {
            val gateway = gateway(GatewayBackend())
            gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert().isNull()
            gateway.entryPolicy.assert().isEqualTo(QueryEntryPolicy.DEFAULT)
            gateway.aggregateType.assert().isEqualTo(AGGREGATE)
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
            backend.admitted.map { (it.query as CountQuery).limit }.assert().containsExactly(5, 3)
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
                QueryEntryPolicy(inProcessTimeout = Duration.ofMillis(100).toKotlinDuration()) to QueryCaller.IN_PROCESS,
                QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ofMillis(100).toKotlinDuration())) to
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
                entryPolicy = QueryEntryPolicy(
                    http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ZERO.toKotlinDuration())
                )
            )
                .count(CountQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.HTTP)).count.assert().isEqualTo(1)
            slow.admitted.map { it.timeout }.assert().containsExactly(null, null)
            gateway(slow).count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            slow.admitted.last().timeout.assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT.toJavaDuration())
        }
    }

    @Test
    fun `the entry policy gives each entry its budget and timeout`() {
        val inProcess = QueryBudget("In-process", timeout = Duration.ofSeconds(5).toKotlinDuration())
        val policy =
            QueryEntryPolicy(inProcess = inProcess, inProcessTimeout = Duration.ofSeconds(1).toKotlinDuration())
        policy.budget(QueryEntry.HTTP).assert().isSameAs(QueryBudget.HTTP_DEFAULT)
        policy.budget(QueryEntry.UNSPECIFIED).assert().isSameAs(inProcess)
        policy.timeout(QueryEntry.IN_PROCESS).assert().isEqualTo(Duration.ofSeconds(5).toKotlinDuration())
        policy.timeout(QueryEntry.HTTP).assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        QueryEntryPolicy().timeout(QueryEntry.IN_PROCESS).assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        assertThrows<IllegalArgumentException> { QueryEntryPolicy(inProcessTimeout = Duration.ZERO.toKotlinDuration()) }
        assertThrows<IllegalArgumentException> {
            QueryBudget(
                "HTTP",
                timeout = Duration.ofSeconds(-1).toKotlinDuration()
            )
        }
        assertThrows<IllegalArgumentException> { QueryBudget("HTTP", countLimit = -1) }
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
        override suspend fun count(query: AdmittedQuery<CountQuery>): Long {
            delay(SLOW)
            return super.count(query)
        }
    }

    private companion object {
        const val SLOW = 10_000L
    }
}
