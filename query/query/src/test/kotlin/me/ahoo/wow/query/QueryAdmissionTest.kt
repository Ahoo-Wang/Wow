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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Modifier
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation

class QueryAdmissionTest {
    private val epoch = scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS))
    private val schema = boundSchemaFixture(
        objectFixture(
            "timeoutAt" to epoch,
            "note" to scalarFixture(),
            "items" to arrayFixture(objectFixture("at" to epoch)),
        ),
    )

    @Test
    fun `admission hands backends a normalized query`() {
        val timeoutAt = QueryField("timeoutAt")
        val admitted = QueryAdmission.Trusted.count(
            CountQuery(
                AndFilter(
                    listOf(
                        AndFilter(listOf(ExistsFilter(timeoutAt), EqualFilter(QueryField("note"), null))),
                        BeforeNowFilter(timeoutAt),
                    ),
                ),
            ),
            schema,
        )
        val operands = (admitted.query.filter as AndFilter).operands
        operands.take(2).assert().containsExactly(ExistsFilter(timeoutAt), IsNullFilter(QueryField("note")))
        (operands[2] as LessThanFilter).field.assert().isEqualTo(timeoutAt)
        admitted.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
        // The trusted path counts with the query's own limit, without a timeout.
        admitted.query.limit.assert().isNull()
        admitted.timeout.assert().isNull()
        QueryAdmission.Trusted.count(CountQuery(MatchAllFilter, limit = 3), schema).query.limit.assert().isEqualTo(3)
    }

    @Test
    fun `every filter of one aggregation resolves against the same moment`() {
        val at = QueryField("at")
        val admitted = QueryAdmission.Trusted.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("items"), AfterNowFilter(at))),
                metrics = listOf(AggregationMetric.Count("due", BeforeNowFilter(at))),
            ),
            schema,
        ).query
        val element = admitted.elements.single().filter as GreaterThanFilter
        val metric = admitted.metrics.single().filter as LessThanFilter
        element.value.assert().isEqualTo(metric.value)
    }

    private val snapshot = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "tenantId" to scalarFixture(),
            "ownerId" to scalarFixture(),
            "deleted" to scalarFixture(QueryValueType.BOOLEAN),
            "note" to scalarFixture(),
        ),
    )
    private val note = EqualFilter(QueryField("note"), null)
    private val aggregateType = AggregateType("admission", "order")
    private val http = QueryCaller(QueryEntry.HTTP)

    private fun <Q : RewritableFilter<Q>> QueryAdmission.admitBlocking(
        operation: QueryOperation<Q>,
        query: Q,
        caller: QueryCaller = QueryCaller.UNSPECIFIED,
        schema: QueryModelSchema = snapshot,
        trail: QueryAuditTrail? = null,
    ): AdmittedQuery<Q> {
        lateinit var admitted: AdmittedQuery<Q>
        runTest { admitted = admit(operation, query, caller, { schema }, trail) }
        return admitted
    }

    @Test
    fun `governed admission runs filters, then scope and policies, then the default scope, then the last steps`() {
        val seen = mutableListOf<String>()
        val filter = object : QueryFilter {
            override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q {
                seen += "filter:${(context.query as IListQuery).filter}"
                return context.query
            }
        }
        val policy = QueryPolicy { caller, context ->
            seen += "policy:${(context.query as IListQuery).filter}"
            caller.entry.assert().isEqualTo(QueryEntry.HTTP)
            context.queryType.assert().isEqualTo(QueryType.LIST)
            context.aggregateType.assert().isEqualTo(aggregateType)
            OwnerIdFilter("o1")
        }
        val trail = QueryAuditTrail(aggregateType, QueryType.LIST, Unit, http)
        val admitted = QueryAdmission(aggregateType, listOf(filter), listOf(policy))
            .admitBlocking(
                QueryOperation.LIST,
                ListQuery(note, limit = 10) as IListQuery,
                http.withScope(QueryScope(TenantIdFilter("t1"))),
                trail = trail,
            )
        seen.assert().containsExactly(
            "filter:$note",
            "policy:${AndFilter(listOf(note, TenantIdFilter("t1")))}",
        )
        (admitted.query.filter as AndFilter).operands.assert().containsExactly(
            IsNullFilter(QueryField("note")),
            TenantIdFilter("t1"),
            OwnerIdFilter("o1"),
            DeletionFilter(DeletionState.ACTIVE),
        )
        admitted.entry.assert().isEqualTo(QueryEntry.HTTP)
        admitted.model.assert().isEqualTo(QueryModel.SNAPSHOT)
        admitted.provisional.assert().isFalse()
        admitted.toString().assert().startsWith("AdmittedQuery(entry=HTTP, model=")
        trail.audit(QueryAudit.Outcome.COMPLETE).run {
            model.assert().isEqualTo(QueryModel.SNAPSHOT)
            policies.assert().hasSize(1)
        }
    }

    @Test
    fun `filters and policies run in their order`() {
        val seen = mutableListOf<String>()
        fun filter(name: String, order: Int) = object : QueryFilter {
            override val order: Int = order

            override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q {
                seen += name
                return context.query
            }
        }
        fun policy(name: String, order: Int) = object : QueryPolicy {
            override val order: Int = order

            override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
                seen += name
                return MatchAllFilter
            }
        }
        QueryAdmission(
            aggregateType,
            listOf(filter("late", 2), filter("early", 1), object : QueryFilter {}),
            listOf(policy("second", 10), policy("first", -10)),
        ).admitBlocking(QueryOperation.SINGLE, SingleQuery(note) as ISingleQuery).query.filter.assert()
            .isEqualTo(AndFilter(listOf(IsNullFilter(QueryField("note")), DeletionFilter(DeletionState.ACTIVE))))
        seen.assert().containsExactly("early", "late", "first", "second")
    }

    @Test
    fun `a policy's deletion scope replaces the snapshot default`() {
        val policy = QueryPolicy { _, _ -> DeletionFilter(DeletionState.DELETED) }
        QueryAdmission(aggregateType, policies = listOf(policy))
            .admitBlocking(QueryOperation.COUNT, CountQuery(MatchAllFilter) as ICountQuery)
            .query.filter.assert().isEqualTo(DeletionFilter(DeletionState.DELETED))
    }

    @Test
    fun `a model without a profile gets no default scope`() {
        val custom = boundSchemaFixture(objectFixture("note" to scalarFixture()), model = QueryModel("custom"))
        QueryAdmission(aggregateType)
            .admitBlocking(QueryOperation.COUNT, CountQuery(MatchAllFilter) as ICountQuery, schema = custom)
            .query.filter.assert().isEqualTo(MatchAllFilter)
    }

    @Test
    fun `governed admission checks the entry budget before the schema loads`() {
        var loaded = false
        val rejected = runCatching {
            runTest {
                QueryAdmission(aggregateType, entryPolicy = QueryEntryPolicy(requireExplicitEntry = true))
                    .admit(
                        QueryOperation.COUNT,
                        CountQuery(MatchAllFilter) as ICountQuery,
                        QueryCaller.UNSPECIFIED,
                        {
                            loaded = true
                            snapshot
                        },
                    )
            }
        }.exceptionOrNull() as QueryRequestException
        rejected.code.assert().isEqualTo(QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED)
        rejected.bindingErrors.single().name.assert().isEqualTo(QueryRequestException.BODY)
        loaded.assert().isFalse()

        val budgeted = runCatching {
            runTest {
                QueryAdmission(aggregateType).admit(
                    QueryOperation.LIST,
                    ListQuery(note, limit = 1001) as IListQuery,
                    http,
                    {
                        loaded = true
                        snapshot
                    },
                )
            }
        }.exceptionOrNull() as QueryRequestException
        budgeted.code.assert().isEqualTo(QueryErrorCodes.SIZE_OUT_OF_RANGE)
        loaded.assert().isFalse()
    }

    @Test
    fun `admission errors carry their framework codes`() {
        QueryRequestException("Body is not JSON.", QueryErrorCodes.INVALID_JSON).run {
            errorCode.assert().isEqualTo(QueryErrors.MALFORMED)
            violation.assert().isNull()
            bindingErrors.assert().containsExactly(
                QueryBindingError(QueryRequestException.BODY, "Body is not JSON.", QueryErrorCodes.INVALID_JSON),
            )
        }
        QueryExecutionException("Storage failed.").run {
            errorCode.assert().isEqualTo(QueryErrors.FAILED)
            errorMsg.assert().isEqualTo("Storage failed.")
            bindingErrors.assert().isEmpty()
        }
        assertThrows<QueryExecutionException> { checkExecution(false) { "Broken row." } }
            .message.assert().isEqualTo("Broken row.")
        checkExecution(true) { "unused" }
        QueryTimeoutException(Duration.ofMillis(1500)).run {
            errorCode.assert().isEqualTo(QueryErrors.TIMEOUT)
            timeout.assert().isEqualTo(Duration.ofMillis(1500))
            errorMsg.assert().isEqualTo("Query exceeded its execution timeout of 1500 ms.")
        }
        QueryPolicy { _, _ -> MatchAllFilter }.order.assert().isEqualTo(0)
    }

    @Test
    fun `an explicit entry passes the policy that requires one`() {
        val policy = QueryEntryPolicy(requireExplicitEntry = true)
        policy.admit(QueryEntry.HTTP).assert().isEqualTo(QueryEntry.HTTP)
        policy.admit(QueryEntry.IN_PROCESS).assert().isEqualTo(QueryEntry.IN_PROCESS)
        QueryEntryPolicy.DEFAULT.admit(QueryEntry.UNSPECIFIED).assert().isEqualTo(QueryEntry.UNSPECIFIED)
    }

    @Test
    fun `the trusted path is named for what it skips and runs no governance`() {
        val policy = QueryPolicy { _, _ -> OwnerIdFilter("o1") }
        val query = ListQuery(note, limit = 10)
        val trusted = QueryAdmission.Trusted.list(query, snapshot)
        trusted.query.filter.assert().isEqualTo(IsNullFilter(QueryField("note")))
        trusted.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
        val governed = QueryAdmission(aggregateType, policies = listOf(policy))
            .admitBlocking(QueryOperation.LIST, query as IListQuery)
        governed.query.filter.assert().isNotEqualTo(trusted.query.filter)
        // Every entry of a governed admission suspends with the caller as an argument; only QueryAdmission.Trusted
        // admits synchronously, without a caller.
        QueryAdmission::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .all { it.parameterTypes.lastOrNull() == Continuation::class.java }
            .assert().isTrue()
        QueryAdmission.Trusted::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .all { AdmittedQuery::class.java.isAssignableFrom(it.returnType) }
            .assert().isTrue()
    }

    @Test
    fun `the trusted path admits every operation under the entry it is given`() {
        QueryAdmission.Trusted.single(SingleQuery(note), snapshot, QueryEntry.HTTP).entry
            .assert().isEqualTo(QueryEntry.HTTP)
        QueryAdmission.Trusted.single(SingleQuery(note), snapshot).entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
        QueryAdmission.Trusted.paged(PagedQuery(note), snapshot).query.filter
            .assert().isEqualTo(IsNullFilter(QueryField("note")))
        QueryAdmission.Trusted.cursor(CursorQuery(note), snapshot).query.sort
            .assert().containsExactly(Sort(QueryField("aggregateId"), Sort.Direction.ASC))
        QueryAdmission.Trusted.aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("note"), "note")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            snapshot,
        ).query.groupBy.single().field.assert().isEqualTo(QueryField("note"))
    }

    @Test
    fun `a cursor tie-breaker is found under an alias of the identity`() {
        val aliased = boundSchemaFixture(
            objectFixture(
                "aggregateId" to me.ahoo.wow.query.schema.QueryValueSchema(
                    kind = scalarFixture().kind,
                    valueTypes = scalarFixture().valueTypes,
                    aliases = setOf(QueryField("orderId")),
                ),
                "note" to scalarFixture(),
            ),
        )
        QueryAdmission.Trusted.cursor(
            CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("orderId"), Sort.Direction.DESC))),
            aliased,
        ).query.sort.assert().containsExactly(Sort(QueryField("aggregateId"), Sort.Direction.DESC))
    }

    @Test
    fun `governed admission finishes every operation`() {
        val admission = QueryAdmission(aggregateType)
        admission.admitBlocking(QueryOperation.SINGLE, SingleQuery(note) as ISingleQuery, http).entry
            .assert().isEqualTo(QueryEntry.HTTP)
        admission.admitBlocking(QueryOperation.PAGED, PagedQuery(note) as IPagedQuery, http).query.total
            .assert().isEqualTo(TotalMode.BOUNDED)
        admission.admitBlocking(QueryOperation.CURSOR, CursorQuery(note) as ICursorQuery, http).query.sort
            .assert().containsExactly(Sort(QueryField("aggregateId"), Sort.Direction.ASC))
        admission.admitBlocking(
            QueryOperation.AGGREGATION,
            AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
            http,
        ).query.filter.assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        QueryOperation.COUNT.queryType.assert().isEqualTo(QueryType.COUNT)
        QueryOperation.AGGREGATION.toString().assert().isEqualTo("QueryOperation(AGGREGATION)")
    }

    @Test
    fun `a route selection is appended at step 2, hidden from filters and not caller scope`() {
        val seen = mutableListOf<FilterExpression>()
        val replacing = object : QueryFilter {
            override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q {
                seen += (context.query as IListQuery).filter
                return context.query.withFilter(MatchAllFilter)
            }
        }
        val policies = mutableListOf<FilterExpression>()
        val policy = QueryPolicy { _, context ->
            policies += (context.query as IListQuery).filter
            MatchAllFilter
        }
        val selection = EqualFilter(QueryField("aggregateId"), "a1")
        val caller = QueryCaller.UNSPECIFIED.withScope(QueryScope(TenantIdFilter("t1"))).withSelection(selection)
        val trail = QueryAuditTrail(aggregateType, QueryType.LIST, Unit, caller)
        val admitted = QueryAdmission(aggregateType, listOf(replacing), listOf(policy))
            .admitBlocking(QueryOperation.LIST, ListQuery(note, limit = 10) as IListQuery, caller, trail = trail)
        seen.assert().containsExactly(note)
        policies.single().assert().isEqualTo(AndFilter(listOf(TenantIdFilter("t1"), selection)))
        (admitted.query.filter as AndFilter).operands.assert().containsExactly(
            TenantIdFilter("t1"),
            selection,
            DeletionFilter(DeletionState.ACTIVE),
        )
        // A policy that restricts nothing is not audited, and the selection is not a scope field.
        val audit = trail.audit(QueryAudit.Outcome.COMPLETE)
        audit.policies.assert().isEmpty()
        audit.scopeFields.assert().containsExactly("tenantId")
        caller.scope.filter.assert().isEqualTo(TenantIdFilter("t1"))
        caller.inProcess().selection.assert().isEqualTo(MatchAllFilter)
    }

    @Test
    fun `a route selection is not budgeted, since the caller did not write it`() {
        val selection = AndFilter((1..5).map { EqualFilter(QueryField("aggregateId"), "a$it") })
        val policy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, maxFilterNodes = 3))
        val caller = http.withScope(QueryScope(TenantIdFilter("t1"))).withSelection(selection)
        QueryAdmission(aggregateType, entryPolicy = policy)
            .admitBlocking(QueryOperation.SINGLE, SingleQuery(MatchAllFilter) as ISingleQuery, caller)
            .query.filter.assert().isInstanceOf(AndFilter::class.java)
        // The caller's scope is budgeted.
        assertThrows<QueryRequestException> {
            QueryAdmission(aggregateType, entryPolicy = policy).admitBlocking(
                QueryOperation.SINGLE,
                SingleQuery(MatchAllFilter) as ISingleQuery,
                http.withScope(QueryScope(selection)),
            )
        }.code.assert().isEqualTo(QueryErrorCodes.FILTER_TOO_LARGE)
    }

    @Test
    fun `a route selection counts as a deletion scope only when it states one`() {
        val tracing = DeletionFilter(DeletionState.ALL)
        QueryAdmission(aggregateType)
            .admitBlocking(
                QueryOperation.COUNT,
                CountQuery(MatchAllFilter) as ICountQuery,
                QueryCaller.UNSPECIFIED.withSelection(tracing),
            )
            .query.filter.assert().isEqualTo(tracing)
    }

    @Test
    fun `the in-process entry is unbudgeted unless the policy gives it a budget`() {
        val expensive = ListQuery(ContainsFilter(QueryField("note"), "x"), limit = 10) as IListQuery
        QueryAdmission(aggregateType).admitBlocking(QueryOperation.LIST, expensive, QueryCaller.IN_PROCESS)
        val strict = QueryEntryPolicy(inProcess = QueryBudget("In-process", allowExpensiveOperators = false))
        assertThrows<QueryRequestException> {
            QueryAdmission(aggregateType, entryPolicy = strict)
                .admitBlocking(QueryOperation.LIST, expensive, QueryCaller.IN_PROCESS)
        }.message.assert()
            .isEqualTo("In-process query operator[CONTAINS] is disabled because expensive operators are not allowed.")
    }

    @Test
    fun `admission puts the budget's count limit on a count`() {
        val count = CountQuery(EqualFilter(QueryField("note"), "x")) as ICountQuery
        val admission = QueryAdmission(aggregateType)
        admission.admitBlocking(QueryOperation.COUNT, count, http).query.limit
            .assert().isEqualTo(QueryBudget.DEFAULT_COUNT_LIMIT)
        admission.admitBlocking(QueryOperation.COUNT, CountQuery(count.filter, limit = 5) as ICountQuery, http)
            .query.limit.assert().isEqualTo(5)
        // An unbudgeted entry counts as the query asks.
        admission.admitBlocking(QueryOperation.COUNT, count, QueryCaller.IN_PROCESS).query.limit.assert().isNull()
        admission.admitBlocking(
            QueryOperation.COUNT,
            CountQuery(count.filter, limit = 50_000) as ICountQuery,
            QueryCaller.IN_PROCESS,
        ).query.limit.assert().isEqualTo(50_000)
        // A budget whose count limit is 0 counts exactly.
        QueryAdmission(
            aggregateType,
            entryPolicy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 0)),
        ).admitBlocking(QueryOperation.COUNT, count, http).query.limit.assert().isNull()
        assertThrows<QueryRequestException> {
            admission.admitBlocking(QueryOperation.COUNT, CountQuery(count.filter, limit = 10_001) as ICountQuery, http)
        }.bindingErrors.single().name.assert().isEqualTo("limit")
    }

    @Test
    fun `admission puts the entry's timeout on the admitted query`() {
        val query = SingleQuery(note) as ISingleQuery
        QueryAdmission(aggregateType).admitBlocking(QueryOperation.SINGLE, query, http).timeout
            .assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        QueryAdmission(aggregateType).admitBlocking(QueryOperation.SINGLE, query, QueryCaller.IN_PROCESS).timeout
            .assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        val custom = QueryEntryPolicy(
            http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ZERO),
            inProcessTimeout = Duration.ofSeconds(3),
        )
        QueryAdmission(aggregateType, entryPolicy = custom).admitBlocking(QueryOperation.SINGLE, query, http).timeout
            .assert().isNull()
        QueryAdmission(aggregateType, entryPolicy = custom)
            .admitBlocking(QueryOperation.SINGLE, query, QueryCaller.UNSPECIFIED).timeout
            .assert().isEqualTo(Duration.ofSeconds(3))
        QueryAdmission(aggregateType, entryPolicy = QueryEntryPolicy(inProcessTimeout = null))
            .admitBlocking(QueryOperation.SINGLE, query, QueryCaller.IN_PROCESS).timeout
            .assert().isNull()
    }

    @Test
    fun `the entry policy gives each entry its timeout`() {
        val policy = QueryEntryPolicy(
            http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ofSeconds(5)),
            inProcessTimeout = Duration.ofSeconds(7),
        )
        policy.timeout(QueryEntry.HTTP).assert().isEqualTo(Duration.ofSeconds(5))
        policy.timeout(QueryEntry.IN_PROCESS).assert().isEqualTo(Duration.ofSeconds(7))
        policy.timeout(QueryEntry.UNSPECIFIED).assert().isEqualTo(Duration.ofSeconds(7))
        QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ZERO))
            .timeout(QueryEntry.HTTP).assert().isNull()
        QueryEntryPolicy(inProcess = QueryBudget("In-process", timeout = Duration.ofSeconds(2)))
            .timeout(QueryEntry.IN_PROCESS).assert().isEqualTo(Duration.ofSeconds(2))
        QueryEntryPolicy(inProcessTimeout = null).timeout(QueryEntry.IN_PROCESS).assert().isNull()
        QueryEntryPolicy.DEFAULT.timeout(QueryEntry.HTTP).assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        QueryEntryPolicy.DEFAULT.budget(QueryEntry.HTTP).assert().isSameAs(QueryBudget.HTTP_DEFAULT)
        QueryEntryPolicy.DEFAULT.budget(QueryEntry.UNSPECIFIED).assert().isNull()
        assertThrows<IllegalArgumentException> { QueryEntryPolicy(inProcessTimeout = Duration.ZERO) }
            .message.assert().isEqualTo("inProcessTimeout must be positive.")
        assertThrows<IllegalArgumentException> { QueryEntryPolicy(inProcessTimeout = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `an HTTP query must pin the tenant in its authenticated scope when the policy requires it`() {
        val admission = QueryAdmission(
            aggregateType,
            entryPolicy = QueryEntryPolicy(requireAuthenticatedScope = true),
        )
        val query = SingleQuery(note) as ISingleQuery
        fun admit(caller: QueryCaller, schema: QueryModelSchema = snapshot) =
            admission.admitBlocking(QueryOperation.SINGLE, query, caller, schema)

        listOf(
            TenantIdFilter("t1"),
            EqualFilter(QueryField("tenantId"), "t1"),
            AndFilter(listOf(OwnerIdFilter("o1"), TenantIdFilter("t1"))),
        ).forEach { authenticated -> admit(http.withScope(QueryScope(authenticated = authenticated))) }

        listOf(
            MatchAllFilter,
            OwnerIdFilter("o1"),
            IdFilter("a1"),
            EqualFilter(QueryField("tenantId"), null),
            EqualFilter(QueryField("ownerId"), "o1"),
            IsNullFilter(QueryField("tenantId")),
        ).forEach { authenticated ->
            assertThrows<QueryScopeRequiredException> {
                admit(http.withScope(QueryScope(authenticated = authenticated)))
            }.run {
                errorCode.assert().isEqualTo(QueryErrors.FORBIDDEN)
                model.assert().isEqualTo(QueryModel.SNAPSHOT)
                field.assert().isEqualTo(QueryField("tenantId"))
                errorMsg.assert().isEqualTo("Query on [$model] requires an authenticated [tenantId] scope.")
            }
        }
        // A declared tenant restricts the query but does not satisfy the requirement.
        assertThrows<QueryScopeRequiredException> {
            admit(http.withScope(QueryScope(declared = TenantIdFilter("t1"))))
        }
        // In-process callers and models without a profile are not checked.
        admit(QueryCaller.IN_PROCESS)
        admit(http, boundSchemaFixture(objectFixture("note" to scalarFixture()), model = QueryModel("custom")))
    }
}
