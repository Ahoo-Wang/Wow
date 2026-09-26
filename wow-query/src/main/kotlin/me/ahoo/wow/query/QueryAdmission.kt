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

import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.query.schema.profile
import me.ahoo.wow.query.schema.requireIdentityField
import me.ahoo.wow.query.schema.withCanonicalFields
import reactor.core.publisher.Mono
import reactor.util.context.ContextView
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * The one admission pipeline of an aggregate's queries: it turns what a caller submitted into the [AdmittedQuery] a
 * [QueryBackend] may execute, in the fixed order of the design (§5.4):
 *
 * 0. the entry is accepted by the [entry policy][QueryEntryPolicy] and the submitted query plus the caller's scope fit
 *    the entry's budget; once the schema is known, an authenticated scope is required when the policy says so;
 * 1. the [QueryFilter]s rewrite the query, in order;
 * 2. the caller's [scope][queryScope], the route's [selection][querySelection] (an operation constraint, such as the
 *    aggregate id of a load route, not caller scope) and every [QueryPolicy] restriction are appended, after all
 *    rewrites, so no filter can see the selection or remove any of them;
 * 3. the model's [default scope][QueryModelProfile.defaultScope] is appended, judged on the query steps 1 and 2
 *    produced: a snapshot query already carrying any deletion scope, a policy's included, keeps it;
 * 4. to 6. the [operation][QueryOperation] finishes the query (a cursor's unique tie-breaker sort), then it is
 *    validated, normalized and resolved ([Trusted]).
 *
 * The caller's entry, scope and route selection are read once, from the subscriber context of the admission. Every rejection is an
 * error signal of the returned publisher.
 *
 * [admitRecord] runs steps 2 and 3 for a record read without a backend query (a state point read).
 *
 * Code that drives a backend directly and owns governance itself (backend conformance tests, tools, benchmarks)
 * admits through [Trusted] instead, whose name says it skips steps 0 to 3.
 */
class QueryAdmission(
    private val namedAggregate: NamedAggregate,
    filters: List<QueryFilter> = emptyList(),
    policies: List<QueryPolicy> = emptyList(),
    private val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) {
    private val filters = filters.sortedByOrder()
    private val policies = policies.sortedByOrder()

    /** Admits [query] for [operation] against the one schema [schema] emits, under the subscriber's entry and scope. */
    fun <Q : RewritableFilter<Q>> admit(
        operation: QueryOperation<Q>,
        query: Q,
        schema: Mono<QueryModelSchema>,
    ): Mono<AdmittedQuery<Q>> = Mono.deferContextual { identity -> admit(operation, query, schema, identity, null) }

    internal fun <Q : RewritableFilter<Q>> admit(
        operation: QueryOperation<Q>,
        query: Q,
        schema: Mono<QueryModelSchema>,
        identity: ContextView,
        trail: QueryAuditTrail?,
    ): Mono<AdmittedQuery<Q>> = Mono.defer {
        val entry = entryPolicy.admit(identity.queryEntry())
        val constraints = identity.queryScope().and(identity.querySelection())
        entryPolicy.budget(entry)?.let { operation.budget(it, query, constraints) }
        schema.switchIfEmpty(Mono.error { IllegalStateException("QueryModelSchemaProvider must emit one schema.") })
            .flatMap { model ->
                trail?.schema(model)
                entryPolicy.requireScope(entry, identity.authenticatedQueryScope(), model.profile)
                fun context(current: Q) = QueryContext(current, namedAggregate, model, operation.queryType, entry)
                val appended = mutableListOf<FilterExpression>()
                rewrite(query, ::context)
                    .flatMap { rewritten -> restrict(rewritten, identity, ::context, trail, appended) }
                    .map { restricted ->
                        val defaulted = restricted.withDefaultScope(model.profile, appended)
                        operation.finish(defaulted, model, entry, appended).also { trail?.admitted(it.query) }
                    }
            }
    }

    /**
     * Point-read admission of [record], the snapshot-shaped record of a state that [selection] chose (by id; a
     * tracing read also states its deletion scope), under the subscriber's entry and scope.
     *
     * Steps 2 and 3 run on a [QueryType.SINGLE] query of [selection]: the caller's scope and every [QueryPolicy]
     * restriction are appended, then the snapshot model's default scope. There is no submitted query to budget or
     * rewrite, so steps 0 and 1 do not apply. The resulting filter is put in canonical form (aliases replaced when
     * [schema] is given, `EQ`/`NE` of `null` lowered, logical nodes simplified) and evaluated on [record] in memory
     * with the semantics of the filter semantics matrix; an operator the evaluation does not support fails closed.
     *
     * Emits [record] masked by [schema]'s response masks, or nothing when the caller may not read it. Policies need
     * the schema, so a read with policies fails when [schema] is `null`.
     */
    fun admitRecord(
        selection: FilterExpression,
        record: ObjectNode,
        schema: QueryModelSchema?,
    ): Mono<ObjectNode> = Mono.deferContextual { identity ->
        val entry = identity.queryEntry()
        fun context(current: ISingleQuery): QueryContext<ISingleQuery> {
            checkNotNull(schema) { "Point-read admission needs the snapshot query schema to evaluate query policies." }
            return QueryContext(current, namedAggregate, schema, QueryType.SINGLE, entry)
        }
        val appended = mutableListOf<FilterExpression>()
        restrict(SingleQuery(selection) as ISingleQuery, identity, ::context, null, appended).mapNotNull { restricted ->
            val filter = restricted.withDefaultScope(schema?.profile ?: SnapshotQueryModelProfile, appended).filter
            val canonical = if (schema == null) filter else filter.withCanonicalFields(schema)
            record.takeIf { RecordFilter(normalizer.normalize(canonical)).admits(it) }
                ?.let { schema?.maskRecord(it) ?: it }
        }
    }

    /** Step 1: the [QueryFilter]s in order, each seeing the query the previous one produced. */
    private fun <Q : RewritableFilter<Q>> rewrite(query: Q, context: (Q) -> QueryContext<Q>): Mono<Q> =
        filters.fold(Mono.just(query)) { pending, filter ->
            pending.flatMap { current ->
                Mono.defer { filter.prepare(context(current)) }
                    .switchIfEmpty(
                        Mono.error { IllegalStateException("QueryFilter.prepare must emit exactly one query.") }
                    )
            }
        }

    /**
     * Step 2: the caller's scope and the route selection, then every policy's restriction of the scoped query,
     * appended. Each condition admission appends is recorded in [appended], so validation treats it as trusted.
     */
    private fun <Q : RewritableFilter<Q>> restrict(
        query: Q,
        identity: ContextView,
        context: (Q) -> QueryContext<Q>,
        trail: QueryAuditTrail?,
        appended: MutableList<FilterExpression>,
    ): Mono<Q> {
        val callerScope = identity.queryScope().also(appended::add)
        val selection = identity.querySelection().also(appended::add)
        val scoped = query.restrict(callerScope).restrict(selection)
        if (policies.isEmpty()) {
            return Mono.just(scoped)
        }
        val policyContext = context(scoped)
        return policies.fold(Mono.just<FilterExpression>(MatchAllFilter)) { pending, policy ->
            pending.flatMap { combined ->
                Mono.defer { policy.evaluate(identity, policyContext) }
                    .switchIfEmpty(Mono.error { IllegalStateException("QueryPolicy must emit one filter.") })
                    .doOnNext {
                        appended += it
                        if (it !== MatchAllFilter) trail?.policy(policy)
                    }
                    .map { combined.restrict(it) }
            }
        }.map { scoped.restrict(it) }
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

    /** The scope and the route selection, as step 0 budgets them; a missing part adds no node. */
    private fun FilterExpression.and(other: FilterExpression): FilterExpression =
        if (other === MatchAllFilter) this else appendFilter(other)

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
     * no model default scope. The caller gets exactly the query it wrote, validated, normalized and resolved. A
     * query from anyone but trusted code goes through a [QueryAdmission] instance, as the gateway does.
     */
    object Trusted {
        @JvmStatic
        @JvmOverloads
        fun single(query: ISingleQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            single(query, schema, entry, emptyList())

        @JvmStatic
        @JvmOverloads
        fun list(query: IListQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            list(query, schema, entry, emptyList())

        @JvmStatic
        @JvmOverloads
        fun paged(query: IPagedQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            paged(query, schema, entry, emptyList())

        /** Appends the model's identity field as the unique tie-breaker sort before resolving. */
        @JvmStatic
        @JvmOverloads
        fun cursor(query: ICursorQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            cursor(query, schema, entry, emptyList())

        @JvmStatic
        @JvmOverloads
        fun count(filter: FilterExpression, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            count(filter, schema, entry, emptyList())

        @JvmStatic
        @JvmOverloads
        fun aggregate(query: AggregationQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
            aggregate(query, schema, entry, emptyList())

        internal fun single(
            query: ISingleQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<ISingleQuery> = resolve(schema, entry, trusted) { single(query) }

        internal fun list(
            query: IListQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<IListQuery> = resolve(schema, entry, trusted) { list(query) }

        internal fun paged(
            query: IPagedQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<IPagedQuery> = resolve(schema, entry, trusted) { paged(query) }

        internal fun cursor(
            query: ICursorQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<ICursorQuery> = resolve(schema, entry, trusted) {
            cursor(query.withCanonicalSort(schema).withUniqueSort(schema.requireIdentityField()))
        }

        internal fun count(
            filter: FilterExpression,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<FilterExpression> = resolve(schema, entry, trusted) { filter(filter) }

        internal fun aggregate(
            query: AggregationQuery,
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
        ): AdmittedQuery<AggregationQuery> = resolve(schema, entry, trusted) { aggregate(query) }

        private inline fun <Q : Any> resolve(
            schema: QueryModelSchema,
            entry: QueryEntry,
            trusted: Collection<FilterExpression>,
            resolve: QueryResolver.() -> Q,
        ): AdmittedQuery<Q> {
            val resolver = QueryResolver(schema, Instant.now(), trusted)
            val query = resolver.resolve()
            return AdmittedQuery(query, schema, entry, resolver.fields)
        }

        /** The cursor's sort with aliases replaced, so the identity tie-breaker is found under any name. */
        private fun ICursorQuery.withCanonicalSort(schema: QueryModelSchema): ICursorQuery =
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
 * steps 4 to 6.
 */
class QueryOperation<Q : RewritableFilter<Q>> private constructor(
    val queryType: QueryType,
    private val budget: QueryBudget.(Q, FilterExpression) -> Unit,
    private val finish: (Q, QueryModelSchema, QueryEntry, Collection<FilterExpression>) -> AdmittedQuery<Q>,
) {
    internal fun budget(budget: QueryBudget, query: Q, scope: FilterExpression) = budget.budget(query, scope)

    internal fun finish(
        query: Q,
        schema: QueryModelSchema,
        entry: QueryEntry,
        trusted: Collection<FilterExpression>,
    ): AdmittedQuery<Q> = finish.invoke(query, schema, entry, trusted)

    override fun toString(): String = "QueryOperation($queryType)"

    companion object {
        @JvmField
        val SINGLE = QueryOperation<ISingleQuery>(QueryType.SINGLE, QueryBudget::check, QueryAdmission.Trusted::single)

        @JvmField
        val LIST = QueryOperation<IListQuery>(QueryType.LIST, QueryBudget::check, QueryAdmission.Trusted::list)

        @JvmField
        val PAGED = QueryOperation<IPagedQuery>(QueryType.PAGED, QueryBudget::check, QueryAdmission.Trusted::paged)

        @JvmField
        val CURSOR = QueryOperation<ICursorQuery>(QueryType.CURSOR, QueryBudget::check, QueryAdmission.Trusted::cursor)

        @JvmField
        val COUNT = QueryOperation<FilterExpression>(
            QueryType.COUNT,
            QueryBudget::checkCount,
            QueryAdmission.Trusted::count,
        )

        @JvmField
        val AGGREGATION = QueryOperation<AggregationQuery>(
            QueryType.AGGREGATION,
            QueryBudget::check,
            QueryAdmission.Trusted::aggregate,
        )
    }
}
