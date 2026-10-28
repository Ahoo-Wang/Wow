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

package me.ahoo.wow.query.admission

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.SortCapable
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.observe.QueryAuditTrail
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.resolve.FilterNormalizer
import me.ahoo.wow.query.resolve.QueryResolver
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.query.schema.cursorUniqueKey
import me.ahoo.wow.query.schema.requireValid
import me.ahoo.wow.query.schema.withCanonicalFields
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import kotlin.time.toJavaDuration

/**
 * The one admission pipeline of an aggregate type's queries: it turns what a caller submitted into the
 * [AdmittedQuery] a [QueryBackend] may execute, in the fixed order of the design (§5.4):
 *
 * 0. the entry is accepted by the [entry policy][QueryEntryPolicy] and the submitted query plus the caller's scope fit
 *    the entry's budget; once the schema is known, an authenticated scope is required when the policy says so;
 * 1. the [QueryFilter]s rewrite the query, in order;
 * 2. the caller's [scope][QueryCaller.scope], the route's [selection][QueryCaller.selection] (an operation constraint,
 *    such as the aggregate id of a load route, not caller scope) and every [QueryPolicy] restriction are appended,
 *    after all rewrites, so no filter can see the selection or remove any of them;
 * 3. the model's [default scope][QueryModelProfile.defaultScope] is appended, judged on the query steps 1 and 2
 *    produced: a snapshot query already carrying any deletion scope, a policy's included, keeps it;
 * 4. to 6. the [operation][QueryOperation] finishes the query (a cursor's unique tie-breaker sort, a count's limit),
 *    then it is validated, normalized and resolved ([Trusted]).
 *
 * The caller's entry, scope and route selection come from the explicit [QueryCaller]. The admitted
 * query carries the execution timeout the entry's budget gives. Every rejection is thrown.
 *
 * [admitRecord] runs step 0 (without a budget), 2 and 3 for a record read without a backend query (a state point read).
 *
 * Code that drives a backend directly and owns governance itself (backend conformance tests, tools, benchmarks)
 * admits through [Trusted] instead, whose name says it skips steps 0 to 3.
 */
class QueryAdmission(
    private val aggregateType: AggregateType,
    filters: List<QueryFilter> = emptyList(),
    policies: List<QueryPolicy> = emptyList(),
    private val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) {
    private val filters = filters.sortedBy(QueryFilter::order)
    private val policies = policies.sortedBy(QueryPolicy::order)

    /** Admits [query] for [operation] against [schema], under [caller]'s entry and scope. */
    internal suspend fun <Q : RewritableFilter<Q>> admit(
        operation: QueryOperation<Q>,
        query: Q,
        caller: QueryCaller,
        schema: suspend () -> QueryModelSchema,
        trail: QueryAuditTrail? = null,
    ): AdmittedQuery<Q> {
        val entry = entryPolicy.admit(caller.entry)
        val budget = entryPolicy.budget(entry)
        // The route selection is an operation constraint the caller did not write, so only the caller's scope counts.
        budget?.let { operation.budget(it, query, caller.scope.filter) }
        val model = schema()
        trail?.schema(model)
        entryPolicy.requireScope(entry, caller.scope.authenticated, model.profile)
        fun context(current: Q) = QueryContext(current, aggregateType, model, operation.queryType, entry)
        val appended = mutableListOf<FilterExpression>()
        val rewritten = rewrite(query, ::context)
        val restricted = restrict(rewritten, caller, ::context, trail, appended)
        val defaulted = restricted.withDefaultScope(model.profile, appended)
        return operation.finish(defaulted, model, entry, appended, budget)
            .also { admitted -> budget?.let { requireIndexedSorts(it, query, admitted) } }
            .withTimeout(entryPolicy.timeout(entry)?.toJavaDuration())
    }

    /**
     * With [QueryBudget.unindexedSortExpensive] on an entry that refuses expensive operators, rejects a sort the caller
     * wrote ([submitted]) whose field the storage sorts without an index. The tie-breakers admission appends are not
     * the caller's, so they never count.
     */
    private fun requireIndexedSorts(budget: QueryBudget, submitted: Any, admitted: AdmittedQuery<*>) {
        if (!budget.unindexedSortExpensive || budget.allowExpensiveOperators) return
        val written = (submitted as? SortCapable)?.sort?.size ?: return
        val sort = (admitted.query as? SortCapable)?.sort ?: return
        sort.take(written).forEach { order ->
            val field = admitted.field(order.field)
            requireValid(field.indexed != false) {
                QueryViolation.ExpensiveOperatorDisabled(
                    budget.label,
                    "unindexed sort on [${field.logicalField.path}]",
                    location = "sort",
                )
            }
        }
    }

    /**
     * Point-read admission of [record], the snapshot-shaped record of a state that [selection] chose (by id; a
     * tracing read also states its deletion scope), under [caller]'s entry and scope.
     *
     * Step 0 accepts the entry and, when the [entry policy][QueryEntryPolicy] says so, requires an authenticated scope
     * against [schema]'s profile (the snapshot profile without one), as a query route does. Steps 2 and 3 then run on a
     * [QueryType.SINGLE] query of [selection]: the caller's scope and every [QueryPolicy] restriction are appended,
     * then the snapshot model's default scope. There is no submitted query to budget or rewrite, so the budget and
     * step 1 do not apply. The resulting filter is put in canonical form (aliases replaced when [schema] is given,
     * `EQ`/`NE` of `null` lowered, logical nodes simplified) and evaluated on [record] in memory with the semantics
     * of the filter semantics matrix; an operator the evaluation does not support fails closed.
     *
     * Returns [record], or `null` when the caller may not read it. Policies need the schema, so a read with policies
     * fails when [schema] is `null`. The record returned is masked by [schema]'s response masks ([maskRecord]).
     */
    @InternalWowApi
    suspend fun admitRecord(
        selection: FilterExpression,
        record: ObjectNode,
        schema: QueryModelSchema?,
        caller: QueryCaller,
    ): ObjectNode? = record.takeIf(recordFilter(selection, schema, caller))?.let { schema?.maskRecord(it) ?: it }

    /**
     * The in-memory condition [admitRecord] evaluates, built once for every record [selection] chooses: the batch of
     * the events model's pinned check admits each record of its aggregates with it.
     */
    internal suspend fun recordFilter(
        selection: FilterExpression,
        schema: QueryModelSchema?,
        caller: QueryCaller,
    ): (ObjectNode) -> Boolean {
        val entry = entryPolicy.admit(caller.entry)
        val profile = schema?.profile ?: SnapshotQueryModelProfile
        entryPolicy.requireScope(entry, caller.scope.authenticated, profile)
        fun context(current: SingleQuery): QueryContext<SingleQuery> {
            schema ?: throw QueryExecutionException(
                "Point-read admission needs the snapshot query schema to evaluate query policies."
            )
            return QueryContext(current, aggregateType, schema, QueryType.SINGLE, entry)
        }
        val appended = mutableListOf<FilterExpression>()
        val restricted = restrict(SingleQuery(selection) as SingleQuery, caller, ::context, null, appended)
        val filter = restricted.withDefaultScope(profile, appended).filter
        val canonical = if (schema == null) filter else filter.withCanonicalFields(schema)
        return RecordFilter(normalizer.normalize(canonical))::admits
    }

    /** Step 1: the [QueryFilter]s in order, each seeing the query the previous one produced. */
    private suspend fun <Q : RewritableFilter<Q>> rewrite(query: Q, context: (Q) -> QueryContext<Q>): Q =
        filters.fold(query) { current, filter -> filter.prepare(context(current)) }

    /**
     * Step 2: the caller's scope and the route selection, then every policy's restriction of the scoped query,
     * appended. Each condition admission appends is recorded in [appended], so validation treats it as trusted.
     */
    private suspend fun <Q : RewritableFilter<Q>> restrict(
        query: Q,
        caller: QueryCaller,
        context: (Q) -> QueryContext<Q>,
        trail: QueryAuditTrail?,
        appended: MutableList<FilterExpression>,
    ): Q {
        val callerScope = caller.scope.filter.also(appended::add)
        val selection = caller.selection.also(appended::add)
        val scoped = query.restrict(callerScope).restrict(selection)
        if (policies.isEmpty()) {
            return scoped
        }
        val policyContext = context(scoped)
        val combined = policies.fold<QueryPolicy, FilterExpression>(MatchAllFilter) { combined, policy ->
            val restriction = policy.evaluate(caller, policyContext)
            appended += restriction
            if (restriction !== MatchAllFilter) trail?.policy(policy)
            combined.restrict(restriction)
        }
        return scoped.restrict(combined)
    }

    /** Step 3: the model's default scope, judged on the whole query steps 1 and 2 produced. */
    private fun <Q : RewritableFilter<Q>> Q.withDefaultScope(
        profile: QueryModelProfile?,
        appended: MutableList<FilterExpression>,
    ): Q {
        profile ?: return this
        return restrict(profile.defaultScope(filter).also(appended::add))
    }

    private val <Q : RewritableFilter<Q>> Q.filter: FilterExpression
        get() = when (this) {
            is FilterExpression -> this
            is FilterCapable<*> -> filter
            else -> error("Unsupported query filter contract.")
        }

    private fun <Q : RewritableFilter<Q>> Q.restrict(restriction: FilterExpression): Q =
        if (restriction === MatchAllFilter) this else appendFilter(restriction)

    /**
     * Admission steps 4 to 6 alone, for code that drives a [QueryBackend] directly and owns governance itself:
     * backend conformance tests, tools and benchmarks. Field aliases are replaced by their canonical fields, the
     * operation finishes the query (a cursor's unique tie-breaker sort), and the query is validated against the
     * schema, normalized and resolved. Normalization resolves relative time against one server `now` per admitted
     * query, encoded as each field stores time, lowers derived operators and simplifies logical nodes. Resolution
     * rebuilds every field-carrying node as a fresh instance and registers its [ResolvedField].
     *
     * **No governance runs here**: no entry budget or gate, no [QueryFilter], no caller scope, no [QueryPolicy] and
     * no model default scope; the query runs with the [timeout][AdmittedQuery.timeout] it is given, none by default,
     * and a count with its own limit. The caller gets exactly the query it wrote, validated, normalized and resolved.
     * A query from anyone but trusted code goes through a [QueryAdmission] instance, as the gateway does.
     */
    @InternalWowApi
    object Trusted {
        @JvmStatic
        @JvmOverloads
        fun single(query: SingleQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            single(query, schema, entry, emptyList())

        @JvmStatic
        @JvmOverloads
        fun list(query: ListQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            list(query, schema, entry, emptyList())

        @JvmStatic
        @JvmOverloads
        fun paged(query: PagedQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            paged(query, schema, entry, emptyList())

        /**
         * Appends the model's unique key ([QueryModelProfile.cursorUniqueKey]: a snapshot's tenant, then its
         * aggregate id) as the tie-breaker sort before resolving.
         */
        @JvmStatic
        @JvmOverloads
        fun cursor(query: CursorQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            cursor(query, schema, entry, emptyList())

        /** A count with the query's own limit; `null` counts exactly. */
        @JvmStatic
        @JvmOverloads
        fun count(query: CountQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            count(query, schema, entry, emptyList(), query.limit)

        /** Resolves every relative time of the aggregation's filters against [now], one moment for all of them. */
        @JvmStatic
        @JvmOverloads
        fun aggregate(
            query: AggregationQuery,
            schema: QueryModelSchema,
            entry: QueryEntry = QueryEntry.IN_PROCESS,
            now: Instant = Instant.now(),
        ) = resolve(schema, entry, emptyList(), now) { aggregate(query) }

        internal fun single(
            query: SingleQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<SingleQuery> = resolve(schema, entry, trusted) { single(query) }

        internal fun list(
            query: ListQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<ListQuery> = resolve(schema, entry, trusted) { list(query) }

        internal fun paged(
            query: PagedQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<PagedQuery> = resolve(schema, entry, trusted) { paged(query) }

        internal fun cursor(
            query: CursorQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<CursorQuery> = resolve(schema, entry, trusted) {
            cursor(query.withCanonicalSort(schema), schema.cursorUniqueKey())
        }

        internal fun count(
            query: CountQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
            limit: Int?,
        ): AdmittedQuery<CountQuery> = resolve(schema, entry, trusted) { count(query, limit) }

        internal fun aggregate(
            query: AggregationQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<AggregationQuery> = resolve(schema, entry, trusted, Instant.now()) { aggregate(query) }

        private inline fun <Q : Any> resolve(
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
            now: Instant = Instant.now(),
            resolve: QueryResolver.() -> Q,
        ): AdmittedQuery<Q> {
            val resolver = QueryResolver(schema, now, trusted)
            val query = resolver.resolve()
            return AdmittedQuery(query, schema, entry, resolver.fields)
        }

        /** The cursor's sort with aliases replaced, so the identity tie-breaker is found under any name. */
        private fun CursorQuery.withCanonicalSort(schema: QueryModelSchema): CursorQuery =
            if (!schema.hasAliases) {
                this
            } else {
                CursorQuery(
                    filter,
                    projection,
                    sort.map { it.copy(field = schema.definition.canonical(it.field)) },
                    size,
                    cursor
                )
            }
    }

    private companion object {
        val normalizer = FilterNormalizer()
    }
}

/**
 * One gateway operation as [QueryAdmission] runs it: its [queryType], the entry budget of step 0 and the finishing
 * steps 4 to 6, which read the budget for a count's limit.
 */
internal class QueryOperation<Q : RewritableFilter<Q>> private constructor(
    val queryType: QueryType,
    private val budget: QueryBudget.(Q, FilterExpression) -> Unit,
    private val finish:
    (Q, QueryModelSchema, QueryEntry, Collection<FilterExpression>, QueryBudget?) -> AdmittedQuery<Q>,
) {
    internal fun budget(budget: QueryBudget, query: Q, scope: FilterExpression) = budget.budget(query, scope)

    internal fun finish(
        query: Q,
        schema: QueryModelSchema,
        entry: QueryEntry,
        trusted: Collection<FilterExpression>,
        budget: QueryBudget?,
    ): AdmittedQuery<Q> = finish.invoke(query, schema, entry, trusted, budget)

    override fun toString(): String = "QueryOperation($queryType)"

    companion object {
        @JvmField
        val SINGLE =
            QueryOperation<SingleQuery>(QueryType.SINGLE, QueryBudget::check) { query, schema, entry, trusted, _ ->
                QueryAdmission.Trusted.single(query, schema, entry, trusted)
            }

        @JvmField
        val LIST = QueryOperation<ListQuery>(QueryType.LIST, QueryBudget::check) { query, schema, entry, trusted, _ ->
            QueryAdmission.Trusted.list(query, schema, entry, trusted)
        }

        @JvmField
        val PAGED =
            QueryOperation<PagedQuery>(QueryType.PAGED, QueryBudget::check) { query, schema, entry, trusted, _ ->
                QueryAdmission.Trusted.paged(query, schema, entry, trusted)
            }

        @JvmField
        val CURSOR =
            QueryOperation<CursorQuery>(QueryType.CURSOR, QueryBudget::check) { query, schema, entry, trusted, _ ->
                QueryAdmission.Trusted.cursor(query, schema, entry, trusted)
            }

        /**
         * A list streamed as keyset chunks: a cursor query of the chunk size, admitted as a cursor (its
         * unique tie-breaker appended) under the stream's budget, and observed as a list.
         */
        @JvmField
        val STREAM = QueryOperation<CursorQuery>(QueryType.LIST, QueryBudget::checkStream) {
                query, schema, entry, trusted, _ ->
            QueryAdmission.Trusted.cursor(query, schema, entry, trusted)
        }

        /** An unbudgeted entry counts as the query asks, exactly without a limit; a budgeted one at most its limit. */
        @JvmField
        val COUNT =
            QueryOperation<CountQuery>(QueryType.COUNT, QueryBudget::check) { query, schema, entry, trusted, budget ->
                QueryAdmission.Trusted.count(query, schema, entry, trusted, budget?.countLimit(query) ?: query.limit)
            }

        @JvmField
        val AGGREGATION = QueryOperation<AggregationQuery>(QueryType.AGGREGATION, QueryBudget::check) {
                query, schema, entry, trusted, _ ->
            QueryAdmission.Trusted.aggregate(query, schema, entry, trusted)
        }
    }
}
