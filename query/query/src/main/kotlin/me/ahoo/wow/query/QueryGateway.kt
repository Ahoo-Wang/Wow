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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.describe
import tools.jackson.databind.node.ObjectNode
import kotlin.coroutines.cancellation.CancellationException

/**
 * The queries of one aggregate type's read model (decision D126), each run for an explicit [QueryCaller]: admitted
 * ([QueryAdmission]), executed on the backend's four primitives (`BackendQueries.kt`) within the timeout the caller's
 * budget gives, and observed. Records are JSON trees as the model stores them; a caller that wants a type reads them
 * with `WowJson`.
 */
interface QueryGateway {
    val aggregateType: AggregateType

    /** The entry policy this gateway admits every query under: the one source of each entry's budget. */
    val entryPolicy: QueryEntryPolicy

    /** The first record [query] matches, or `null` when none does. */
    suspend fun single(query: ISingleQuery, caller: QueryCaller): ObjectNode?

    /** The records [query] matches, streamed. */
    fun list(query: IListQuery, caller: QueryCaller): Flow<ObjectNode>

    /** One page of the records [query] matches, with its total as [IPagedQuery.total] asks (decision D150). */
    suspend fun paged(query: IPagedQuery, caller: QueryCaller): PagedList<ObjectNode>

    /** One keyset page of the records [query] matches, with the cursor of the next one. */
    suspend fun cursor(query: ICursorQuery, caller: QueryCaller): CursorPage<ObjectNode>

    /** How many records [query] matches, bounded by its limit or its budget's (decision D150). */
    suspend fun count(query: ICountQuery, caller: QueryCaller): CountResult

    /** The groups of [query], streamed. */
    fun aggregate(query: AggregationQuery, caller: QueryCaller): Flow<ObjectNode>

    /**
     * How this model can be queried on [entry] (decision D127, the `GET` of the query route): the capability
     * descriptor of the schema this gateway admits against, under the budget [entryPolicy] gives [entry].
     * [defaultListSize] is the list size the entry's adapter applies when a list query sends none, or `null`. Its
     * [version][QueryModelDescriptor.version] is the route's `ETag`.
     */
    suspend fun describe(entry: QueryEntry, defaultListSize: Int? = null): QueryModelDescriptor
}

/**
 * The [QueryGateway] over one [backend]: [schemaProvider] supplies the schema every query is admitted against,
 * [filters] and [policies] run at admission steps 1 and 2, and [observer] sees every outcome. One instance serves
 * every caller.
 */
class DefaultQueryGateway(
    override val aggregateType: AggregateType,
    private val backend: QueryBackend,
    private val schemaProvider: QueryModelSchemaProvider,
    filters: List<QueryFilter> = emptyList(),
    policies: List<QueryPolicy> = emptyList(),
    private val observer: QueryObserver = QueryObserver.NONE,
    override val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) : QueryGateway {
    private val admission = QueryAdmission(aggregateType, filters, policies, entryPolicy)

    override suspend fun single(query: ISingleQuery, caller: QueryCaller): ObjectNode? =
        run(QueryOperation.SINGLE, query, caller, rows = { if (it == null) 0 else 1 }) { admitted ->
            backend.single(admitted)?.let(admitted.reader())
        }

    override fun list(query: IListQuery, caller: QueryCaller): Flow<ObjectNode> =
        stream(QueryOperation.LIST, query, caller) { admitted -> backend.list(admitted).map(admitted.reader()) }

    override suspend fun paged(query: IPagedQuery, caller: QueryCaller): PagedList<ObjectNode> =
        run(QueryOperation.PAGED, query, caller, rows = { it.list.size.toLong() }) { admitted ->
            val page = backend.paged(admitted, totalLimit(admitted.entry))
            page.copy(list = page.list.map(admitted.reader()))
        }

    override suspend fun cursor(query: ICursorQuery, caller: QueryCaller): CursorPage<ObjectNode> =
        run(QueryOperation.CURSOR, query, caller, rows = { it.list.size.toLong() }) { admitted ->
            val page = backend.cursor(admitted)
            page.copy(list = page.list.map(admitted.reader()))
        }

    override suspend fun count(query: ICountQuery, caller: QueryCaller): CountResult =
        run(QueryOperation.COUNT, query, caller, rows = { 1 }) { backend.countBounded(it) }

    override fun aggregate(query: AggregationQuery, caller: QueryCaller): Flow<ObjectNode> =
        stream(QueryOperation.AGGREGATION, query, caller) { admitted ->
            backend.aggregate(admitted, entryPolicy.budget(admitted.entry))
        }

    override suspend fun describe(entry: QueryEntry, defaultListSize: Int?): QueryModelDescriptor =
        schemaProvider.schema().describe(entryPolicy.budget(entryPolicy.admit(entry)), defaultListSize)

    /** Masks each backend record by the schema's response masks (every caller sees masked values). */
    private fun AdmittedQuery<*>.reader(): (ObjectNode) -> ObjectNode {
        val masker = SchemaMasker.create(schema) ?: return { it }
        return masker::mask
    }

    /**
     * The bound of a [BOUNDED][me.ahoo.wow.api.query.TotalMode.BOUNDED] total on [entry]: its budget's count limit,
     * or the default limit for an entry without a budget (decision D150).
     */
    private fun totalLimit(entry: QueryEntry): Int? {
        val budget = entryPolicy.budget(entry) ?: return QueryBudget.DEFAULT_COUNT_LIMIT
        return budget.totalLimit
    }

    /** Runs one single-valued query: admitted once, executed, and its outcome observed and audited. */
    @Suppress("TooGenericExceptionCaught") // Every failure is observed, then propagates.
    private suspend fun <Q : RewritableFilter<Q>, T> run(
        operation: QueryOperation<Q>,
        query: Q,
        caller: QueryCaller,
        rows: (T) -> Long,
        execute: suspend (AdmittedQuery<Q>) -> T,
    ): T {
        val trail = trail(operation.queryType, query, caller)
        try {
            val admitted = admission.admit(operation, query, caller, schemaProvider::schema, trail)
            trail?.admitted(admitted.query)
            return execute(admitted).also { result ->
                trail?.rows(rows(result))
                finished(operation.queryType, trail, null)
            }
        } catch (error: Throwable) {
            finished(operation.queryType, trail, error)
            throw error
        }
    }

    /** Runs one streaming query: admitted when collected, executed, and its outcome observed and audited. */
    @Suppress("TooGenericExceptionCaught") // Every failure is observed, then propagates.
    private fun <Q : RewritableFilter<Q>> stream(
        operation: QueryOperation<Q>,
        query: Q,
        caller: QueryCaller,
        execute: (AdmittedQuery<Q>) -> Flow<ObjectNode>,
    ): Flow<ObjectNode> = flow {
        val trail = trail(operation.queryType, query, caller)
        try {
            val admitted = admission.admit(operation, query, caller, schemaProvider::schema, trail)
            trail?.admitted(admitted.query)
            execute(admitted).collect { row ->
                trail?.rows(1)
                emit(row)
            }
            finished(operation.queryType, trail, null)
        } catch (error: Throwable) {
            finished(operation.queryType, trail, error)
            throw error
        }
    }

    private fun trail(queryType: QueryType, query: Any, caller: QueryCaller): QueryAuditTrail? =
        if (observer.audits) QueryAuditTrail(aggregateType, queryType, query, caller) else null

    /** The terminal signal: a completion, a cancellation (the collector stopped early) or an error. */
    private fun finished(queryType: QueryType, trail: QueryAuditTrail?, error: Throwable?) {
        val outcome = when (error) {
            null -> {
                observer.isolated { onComplete(aggregateType, queryType) }
                QueryAudit.Outcome.COMPLETE
            }
            is CancellationException -> {
                observer.isolated { onCancel(aggregateType, queryType) }
                QueryAudit.Outcome.CANCEL
            }
            else -> {
                trail?.error(error)
                observer.isolated { onError(aggregateType, queryType, error) }
                QueryAudit.Outcome.ERROR
            }
        }
        trail?.let { observer.isolated { onAudit(it.audit(outcome)) } }
    }
}
