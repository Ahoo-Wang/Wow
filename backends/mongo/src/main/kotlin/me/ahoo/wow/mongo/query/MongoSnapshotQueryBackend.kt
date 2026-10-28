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

package me.ahoo.wow.mongo.query

import com.mongodb.MongoException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.client.AggregateIterable
import com.mongodb.client.FindIterable
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.Filters
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoFailures
import me.ahoo.wow.mongo.query.pipeline.MongoAggregationCompiler
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.spi.StorageTimeoutException
import org.bson.Document
import org.bson.conversions.Bson
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A query backend of one aggregate type on MongoDB: the four primitives over one of the aggregate type's own
 * collections, compiled by the 9.x filter, sort and aggregation compilers ([MongoMatchCompiler],
 * [MongoAggregationCompiler]) on the bindings of [MongoQuerySchemaAdapter], its documents read as [documents] says: its
 * snapshot collection, which `MongoSnapshotStore` writes, records of the shape of
 * `SnapshotRecord.toQueryRecord()` ([MongoSnapshotDocuments]); or its log collection, one record
 * per stream ([MongoEventDocuments]). Records are projected as [MongoProjections] says.
 *
 * Every command carries `maxTimeMS` of the admitted timeout, and MongoDB cancelling it there is a
 * [StorageTimeoutException]. A bounded count is `countDocuments` with `limit = n + 1`. Lists and offset pages end their
 * sort with the documents' tie-breakers (a snapshot's tenant and aggregate ID), so their order is total; a keyset page orders by the cursor's sort alone,
 * which admission ended with the identity. Driver calls run on [dispatcher], virtual threads by default.
 * A collection not created yet holds no documents, so every primitive answers as over an empty collection.
 */
@InternalWowApi
class MongoSnapshotQueryBackend internal constructor(
    override val aggregateType: AggregateType,
    private val collection: MongoCollection<Document>,
    private val options: MongoQueryOptions,
    private val dispatcher: CoroutineDispatcher,
    private val documents: MongoQueryDocuments = MongoSnapshotDocuments,
) : QueryBackend {
    override val cursorPositions: CursorPositionCodec = MongoCursorCodec

    override fun stream(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> = flow {
        val find = collection.find(match(query.query.filter, query))
            .projection(MongoProjections.native(query))
            .sort(totalSort(query))
            .batchSize(options.fetchSize)
            .maxTime(query.timeout)
        val limited = query.query.limit?.let(find::limit) ?: find
        timed(query.timeout) {
            limited.cursor().use { cursor ->
                while (cursor.hasNext()) emit(row(query, cursor.next()))
            }
        }
    }.flowOn(dispatcher)

    override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
        withContext(dispatcher) {
            timed(query.timeout) {
                when (window) {
                    is PageWindow.Offset -> offset(query, window)
                    is PageWindow.Keyset -> keyset(query, window)
                }
            }
        }

    private fun offset(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Offset): BackendPage {
        val filter = match(query.query.filter, query)
        val rows = if (window.limit == 0) {
            emptyList()
        } else {
            collection.find(filter)
                .projection(MongoProjections.native(query))
                .sort(totalSort(query))
                .skip(Math.toIntExact(window.offset))
                .limit(window.limit)
                .batchSize(window.limit)
                .maxTime(query.timeout)
                .map { row(query, it) }
                .toList()
        }
        val total = if (window.withTotal) count(filter, window.totalLimit?.let { it + 1L }, query.timeout) else null
        return BackendPage(rows, total)
    }

    /**
     * One keyset window after [PageWindow.Keyset.after], ordered by the cursor sort's physical fields. Each row's
     * position is the BSON values at those fields of the native document, read before the record is projected.
     */
    private fun keyset(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset): BackendPage {
        val sort = physicalSort(query, query.query.sort)
        val paths = sort.map { it.field.path }
        val match = match(query.query.filter, query)
        val filter = window.after?.let { Filters.and(match, MongoKeyset.after(sort, it.values)) } ?: match
        val positions = ArrayList<CursorPosition>()
        val rows = collection.find(filter)
            .projection(MongoProjections.native(query, paths))
            .sort(MongoKeyset.sort(sort))
            .limit(window.limit)
            .batchSize(window.limit)
            .maxTime(query.timeout)
            .map { document ->
                positions += CursorPosition(paths.map { MongoKeyset.valueAt(document, it) })
                row(query, document)
            }
            .toList()
        return BackendPage(rows, positions = positions)
    }

    override suspend fun count(query: AdmittedQuery<CountQuery>): Long = withContext(dispatcher) {
        timed(query.timeout) {
            count(
                match(query.query.filter, query),
                query.query.limit?.let { it + 1L },
                query.timeout,
            )
        }
    }

    /** [filter], of [query], within the documents' [MongoQueryDocuments.scope]. */
    private fun match(filter: FilterExpression, query: AdmittedQuery<*>): Bson =
        scoped(MongoMatchCompiler.compile(filter, query), scopeOf(filter))

    /**
     * The documents' [MongoQueryDocuments.scope] and, when [filter] holds one tenant, that tenant's keys
     * ([MongoQueryDocuments.keysOf]).
     */
    private fun scopeOf(filter: FilterExpression): Bson? {
        val keys = tenantOf(filter)?.let(documents::keysOf)
        return listOfNotNull(documents.scope, keys).let { if (it.size > 1) Filters.and(it) else it.singleOrNull() }
    }

    /** The tenant [filter] requires, as admission adds it: a tenant filter, alone or among the operands of an AND. */
    private fun tenantOf(filter: FilterExpression): String? = when (filter) {
        is TenantIdFilter -> filter.value
        is AndFilter -> filter.operands.firstNotNullOfOrNull(::tenantOf)
        else -> null
    }

    /** The documents matching [filter]; with a [limit], at most `limit` of them. */
    private fun count(filter: Bson, limit: Long?, timeout: Duration?): Long {
        val options = CountOptions()
        limit?.let { options.limit(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        timeout?.let { options.maxTime(maxTimeMillis(it), TimeUnit.MILLISECONDS) }
        return collection.countDocuments(filter, options)
    }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
        val limit = (window as? GroupWindow.First)?.limit
        // Without groups, `$group` with a null id emits nothing over no documents: the core emits the empty summary.
        val pipeline = collection.aggregate(
            MongoAggregationCompiler.compile(query, limit, documents.edgeTieBreakers, scopeOf(query.query.filter)),
        )
            .allowDiskUse(true)
            .maxTime(query.timeout)
        timed(query.timeout) {
            pipeline.cursor().use { cursor ->
                while (cursor.hasNext()) emit(group(query.query, cursor.next()))
            }
        }
    }.flowOn(dispatcher)

    /** The query record of [document], projected. */
    private fun row(query: AdmittedQuery<Queryable<*>>, document: Document): ObjectNode =
        MongoProjections.project(query, documents.record(document))

    /**
     * The sort of a list or an offset page: the query's, then the tenant and the aggregate ID where the query does not
     * name them, the unique key, so a page boundary never splits tied records nondeterministically.
     */
    private fun totalSort(query: AdmittedQuery<Queryable<*>>): Bson? {
        val sort = physicalSort(query, query.query.sort)
        val named = sort.map { it.field.path }.toSet()
        val keys = documents.tieBreakers
            .filterNot(named::contains)
            .map { Sort(QueryField(it), Sort.Direction.ASC) }
        return MongoKeyset.sort(sort + keys)
    }

    private fun physicalSort(query: AdmittedQuery<*>, sort: List<Sort>): List<Sort> = sort.map {
        it.copy(field = QueryField(MongoSnapshotDocuments.checked(query.physicalPath(it.field))))
    }

    /** One group's row: its keys under their aliases, then each metric, in the query's order. */
    private fun group(query: AggregationQuery, document: Document): ObjectNode = nodes.objectNode().apply {
        query.groupBy.forEach { set(it.alias, ExactBson.node(document[it.alias])) }
        query.metrics.forEach { metric ->
            val value = document[metric.alias]
            set(
                metric.alias,
                when (metric) {
                    is AggregationMetric.Count, is AggregationMetric.DistinctCount ->
                        nodes.numberNode((value as Number).toLong())
                    else -> ExactBson.node(value)
                },
            )
        }
    }

    private fun <T> FindIterable<T>.maxTime(timeout: Duration?) =
        timeout?.let { maxTime(maxTimeMillis(it), TimeUnit.MILLISECONDS) } ?: this

    private fun <T> AggregateIterable<T>.maxTime(timeout: Duration?) =
        timeout?.let { maxTime(maxTimeMillis(it), TimeUnit.MILLISECONDS) } ?: this

    internal companion object {
        /** MongoDB's error code of an operation that exceeded its `maxTimeMS`. */
        const val MAX_TIME_MS_EXPIRED = 50

        private val nodes: JsonNodeFactory = JsonNodeFactory.instance

        /** [timeout] in whole milliseconds, rounded up and at least 1, since `0` would disable the limit. */
        fun maxTimeMillis(timeout: Duration): Long {
            val millis = timeout.toMillis()
            val rounded = if (timeout > Duration.ofMillis(millis)) millis + 1 else millis
            return rounded.coerceAtLeast(1)
        }

        /** Runs [block]: MongoDB stopping it at its `maxTimeMS` fails as a [StorageTimeoutException]. */
        inline fun <T> timed(timeout: Duration?, block: () -> T): T = try {
            block()
        } catch (error: MongoExecutionTimeoutException) {
            throw expired(timeout, error)
        } catch (error: MongoException) {
            throw if (error.code == MAX_TIME_MS_EXPIRED) expired(timeout, error) else MongoFailures.translate(error)
        }

        /** [error] as a [StorageTimeoutException] of [timeout]; as it is when the query had no timeout. */
        fun expired(timeout: Duration?, error: MongoException): RuntimeException =
            timeout?.let { StorageTimeoutException(it, error) } ?: error
    }
}
