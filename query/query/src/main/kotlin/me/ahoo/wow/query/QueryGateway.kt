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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.descriptor.ConstraintDescriptor
import me.ahoo.wow.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SnapshotFields
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.query.schema.withConstraints
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.observe
import tools.jackson.databind.node.ObjectNode
import kotlin.time.toJavaDuration

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

    /**
     * Whether [query] streams as a resumable stream ([stream], decision D253): its model's storage pages by keyset and
     * its sort, with the model's unique key appended, orders a cursor. A list that does not is streamed by [list].
     */
    suspend fun resumes(query: IListQuery): Boolean = false

    /**
     * The records [query] matches as a resumable stream (decision D253): keyset chunks of [chunkSize] records read one
     * after the other, each record with the cursor token of its position, from strictly after the position [after]
     * names when given. [after] is a token of this model and effective sort (a stream's or a `cursor` query's); any
     * other is `wow.query.invalid`. The query is admitted like a `cursor` query, so a stream resumed with the same
     * query from another node or after a restart reads on from the same place, under its caller's scope again; at most
     * [query]'s limit records are read (`0`: all of them), and its budget bounds neither the limit nor the chunks.
     * Each chunk runs within the query's timeout and under one bulkhead permit, given back before its records are
     * emitted. Chunks are not one snapshot: see [chunks]. Only for a query [resumes].
     */
    fun stream(
        query: IListQuery,
        caller: QueryCaller,
        after: String? = null,
        chunkSize: Int = DEFAULT_STREAM_CHUNK_SIZE,
    ): Flow<PositionedRecord> = flow {
        throw QueryViolation.StorageUnsupported("resumable streams").rejection()
    }

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

    /**
     * The single-aggregate visibility check of the policy chain (review A3-11): [record], the snapshot-shaped record of
     * one aggregate a point read chose by [selection] (a state read by id, its version or its trace, or the aggregate
     * of an events query), as [caller] may read it: admitted by the same caller scope and [QueryPolicy] restrictions
     * (ABAC's among them) and the model's default scope as a query, evaluated in memory, and masked; null when the
     * caller may not read it, so a row a query hides is hidden from a read by id too. A gateway without policies
     * admits every record.
     */
    suspend fun admitRecord(selection: FilterExpression, record: ObjectNode, caller: QueryCaller): ObjectNode? = record

    /**
     * The batch form of [admitRecord], the events model's pinned check (decisions D254, D259): the snapshot records of
     * the aggregates [ids] of [tenantId], read by one query of this gateway's backend without [caller]'s restrictions,
     * each admitted for [caller] in memory as [admitRecord] admits it. Null when this gateway queries no backend.
     */
    @InternalWowApi
    suspend fun admitAggregates(tenantId: String, ids: Collection<String>, caller: QueryCaller): AdmittedAggregates? =
        null

    companion object {
        /** The records a [stream] reads per chunk by default (decision D253). */
        const val DEFAULT_STREAM_CHUNK_SIZE: Int = 500
    }
}

/**
 * The [QueryGateway] over one [backend]: [schemaProvider] supplies the schema every query is admitted against,
 * [filters] and [policies] run at admission steps 1 and 2, and [observer] sees every outcome. One instance serves
 * every caller.
 *
 * With [counts], the counts and paged totals are cached ([QueryCountCache], decision D152): admitted as ever, then
 * served from the cache while the type's snapshot write checkpoint holds; a query served so ends its observation with
 * the outcome [QueryCountCache.CACHED_OUTCOME]. A cache is keyed by aggregate type, not by model: give each model's
 * gateways a cache of their own.
 *
 * Each record a backend returns is masked by the schema's response masks; with [records], it is read by [records]
 * instead (the `events` model reads each event as its aggregate reads it now and masks it by its own schema).
 * [constraints] are rules its [policies] add to the schema's, which [describe] lists after the schema's own (the
 * `events` model's [AGGREGATE_REQUIRED][me.ahoo.wow.query.events.UnpinnedEventsPolicy.AGGREGATE_REQUIRED]).
 */
@InternalWowApi
class DefaultQueryGateway(
    override val aggregateType: AggregateType,
    private val backend: QueryBackend,
    private val schemaProvider: QueryModelSchemaProvider,
    filters: List<QueryFilter> = emptyList(),
    policies: List<QueryPolicy> = emptyList(),
    private val observer: QueryObserver = QueryObserver.NONE,
    override val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
    private val counts: QueryCountCache? = null,
    private val records: QueryRecordReader? = null,
    private val constraints: List<ConstraintDescriptor> = emptyList(),
) : QueryGateway {
    private val admission = QueryAdmission(aggregateType, filters, policies, entryPolicy)

    override suspend fun single(query: ISingleQuery, caller: QueryCaller): ObjectNode? =
        run(QueryOperation.SINGLE, query, caller, rows = { if (it == null) 0 else 1 }) { admitted, _ ->
            backend.single(admitted)?.let(admitted.reader())
        }

    override fun list(query: IListQuery, caller: QueryCaller): Flow<ObjectNode> =
        stream(QueryOperation.LIST, query, caller) { admitted -> backend.list(admitted).map(admitted.reader()) }

    override suspend fun resumes(query: IListQuery): Boolean = schemaProvider.schema().streamsByKeyset(query.sort)

    override fun stream(
        query: IListQuery,
        caller: QueryCaller,
        after: String?,
        chunkSize: Int,
    ): Flow<PositionedRecord> {
        val chunks = CursorQuery(query.filter, query.projection, query.sort, chunkSize, after)
        return stream(QueryOperation.STREAM, chunks, caller) { admitted ->
            val reader = admitted.reader()
            backend.chunks(admitted, query.limit).map { PositionedRecord(reader(it.record), it.position) }
        }
    }

    override suspend fun paged(query: IPagedQuery, caller: QueryCaller): PagedList<ObjectNode> =
        run(QueryOperation.PAGED, query, caller, rows = { it.list.size.toLong() }) { admitted, observation ->
            val page = page(admitted, caller, observation)
            page.copy(list = page.list.map(admitted.reader()))
        }

    override suspend fun cursor(query: ICursorQuery, caller: QueryCaller): CursorPage<ObjectNode> =
        run(QueryOperation.CURSOR, query, caller, rows = { it.list.size.toLong() }) { admitted, _ ->
            val page = backend.cursor(admitted)
            page.copy(list = page.list.map(admitted.reader()))
        }

    override suspend fun count(query: ICountQuery, caller: QueryCaller): CountResult =
        run(QueryOperation.COUNT, query, caller, rows = { 1 }) { admitted, observation ->
            val count = admitted.query
            counted(count.filter, count.limit, caller, observation) { backend.countBounded(admitted) }
        }

    override fun aggregate(query: AggregationQuery, caller: QueryCaller): Flow<ObjectNode> =
        stream(QueryOperation.AGGREGATION, query, caller) { admitted ->
            backend.aggregate(admitted, entryPolicy.budget(admitted.entry))
        }

    override suspend fun admitRecord(selection: FilterExpression, record: ObjectNode, caller: QueryCaller): ObjectNode? =
        admission.admitRecord(selection, record, schemaProvider.schema(), caller)

    override suspend fun describe(entry: QueryEntry, defaultListSize: Int?): QueryModelDescriptor =
        schemaProvider.schema().describe(entryPolicy.budget(entryPolicy.admit(entry)), defaultListSize)
            .withConstraints(constraints)

    /**
     * One list query of the records `tenantId = ? AND aggregateId IN (?)`, of every deletion state, admitted as
     * trusted code (no policy restricts it) and run within the caller's timeout and this store's bulkhead; each record
     * is then admitted for [caller] by one condition built for all of them.
     */
    override suspend fun admitAggregates(
        tenantId: String,
        ids: Collection<String>,
        caller: QueryCaller,
    ): AdmittedAggregates {
        if (ids.isEmpty()) return AdmittedAggregates.NONE
        val schema = schemaProvider.schema()
        val selected = AggregateIdsFilter(ids.distinct())
        val query = ListQuery(
            AndFilter(listOf(TenantIdFilter(tenantId), selected, DeletionFilter(DeletionState.ALL))),
            limit = selected.values.size,
        )
        val entry = entryPolicy.admit(caller.entry)
        val admitted = QueryAdmission.Trusted.list(query, schema, entry)
            .withTimeout(entryPolicy.timeout(entry)?.toJavaDuration())
        val admits = admission.recordFilter(AndFilter(listOf(selected, DeletionFilter(DeletionState.ALL))), schema, caller)
        val stored = mutableSetOf<String>()
        val visible = mutableSetOf<String>()
        backend.list(admitted).collect { record ->
            val id = record.get(SnapshotFields.AGGREGATE_ID)?.stringValue() ?: return@collect
            stored += id
            if (admits(record)) visible += id
        }
        return AdmittedAggregates(stored, visible)
    }

    /**
     * Reads each backend record by [records], else masks it by the schema's response masks (every caller sees masked
     * values).
     */
    private fun AdmittedQuery<*>.reader(): (ObjectNode) -> ObjectNode {
        records?.let { return it::read }
        val masker = SchemaMasker.create(schema) ?: return { it }
        return masker::mask
    }

    /**
     * One page of [admitted] with its total, which [counts] gives while it holds a valid count of the page's filter
     * and bound: the page's rows are then read without counting.
     */
    private suspend fun page(
        admitted: AdmittedQuery<IPagedQuery>,
        caller: QueryCaller,
        observation: Observation,
    ): PagedList<ObjectNode> {
        val totalLimit = totalLimit(admitted.entry)
        val mode = admitted.query.total
        if (counts == null || mode == TotalMode.NONE) {
            return backend.paged(admitted, totalLimit)
        }
        var counted: PagedList<ObjectNode>? = null
        val bound = if (mode == TotalMode.BOUNDED) totalLimit else null
        val total = counted(admitted.query.filter, bound, caller, observation) {
            val page = backend.paged(admitted, totalLimit)
            counted = page
            CountResult(checkNotNull(page.total), page.exact)
        }
        return counted ?: backend.paged(admitted, totalLimit, known = total)
    }

    /** The count of [filter] bounded by [bound]: from [counts] while it holds a valid one, else [count]'s. */
    private suspend fun counted(
        filter: FilterExpression,
        bound: Int?,
        caller: QueryCaller,
        observation: Observation,
        count: suspend () -> CountResult,
    ): CountResult {
        val cache = counts ?: return count()
        val cached = { observation.outcome(QueryCountCache.CACHED_OUTCOME) }
        return cache.count(aggregateType, filter, bound, caller, cached, count)
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
        execute: suspend (AdmittedQuery<Q>, Observation) -> T,
    ): T {
        val observation = observer.started(aggregateType, operation.queryType)
        return observation.observe {
            val trail = trail(operation.queryType, query, caller)
            try {
                val admitted = admission.admit(operation, query, caller, schemaProvider::schema, trail)
                trail?.admitted(admitted.query)
                execute(admitted, observation).also { result ->
                    trail?.rows(rows(result))
                    finished(operation.queryType, trail, null)
                }
            } catch (error: Throwable) {
                finished(operation.queryType, trail, error)
                throw error
            }
        }
    }

    /** Runs one streaming query: admitted when collected, executed, and its outcome observed and audited. */
    @Suppress("TooGenericExceptionCaught") // Every failure is observed, then propagates.
    private fun <Q : RewritableFilter<Q>, T> stream(
        operation: QueryOperation<Q>,
        query: Q,
        caller: QueryCaller,
        execute: (AdmittedQuery<Q>) -> Flow<T>,
    ): Flow<T> = flow {
        // Its rows are emitted to the collector, so the observation times the stream without running it in its context.
        val observed = observer.started(aggregateType, operation.queryType)
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
            observed.failed(error)
            finished(operation.queryType, trail, error)
            throw error
        } finally {
            observed.end()
        }
    }

    private fun trail(queryType: QueryType, query: Any, caller: QueryCaller): QueryAuditTrail? =
        if (observer.audits) QueryAuditTrail(aggregateType, queryType, query, caller) else null

    private fun finished(queryType: QueryType, trail: QueryAuditTrail?, error: Throwable?) =
        observer.finished(aggregateType, queryType, trail, error)
}

/**
 * What [QueryGateway.admitAggregates] found: the aggregates that have a [stored] record, and the ones of them the
 * caller may see ([visible]).
 */
@InternalWowApi
class AdmittedAggregates(val stored: Set<String>, val visible: Set<String>) {
    @InternalWowApi
    companion object {
        /** No aggregate asked, none found. */
        @JvmField
        val NONE: AdmittedAggregates = AdmittedAggregates(emptySet(), emptySet())
    }
}

/**
 * How a gateway reads the records its backend returns, in place of the schema's response masks: a model whose records
 * the backend returns as stored and the gateway reads as the model shows them (the `events` model, decision D235).
 * The record is the backend's own, fresh instance; the reader may change it in place and return it.
 */
@InternalWowApi
fun interface QueryRecordReader {
    fun read(record: ObjectNode): ObjectNode
}
