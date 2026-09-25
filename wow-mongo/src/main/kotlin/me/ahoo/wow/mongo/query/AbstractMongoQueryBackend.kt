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

import com.mongodb.client.model.Filters
import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyTo
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.toObjectNode
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.aggregation.EmptyAggregationValues
import org.bson.Document
import org.bson.types.Decimal128
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import tools.jackson.databind.node.ObjectNode

internal fun Document.toQueryObjectNode(idField: String): ObjectNode {
    if (containsKey(Documents.ID_FIELD)) {
        replacePrimaryKeyTo(idField)
    }
    return toObjectNode()
}

abstract class AbstractMongoQueryBackend : QueryBackend {
    abstract val collection: MongoCollection<Document>
    abstract val filterCompiler: AbstractMongoFilterCompiler
    protected abstract fun toObjectNode(document: Document): ObjectNode

    override val cursorPositions: CursorPositionCodec = MongoCursorCodec

    internal fun findDocument(admitted: AdmittedQuery<Queryable<*>>): FindPublisher<Document> {
        return collection.findDocument(filterCompiler, admitted)
    }

    override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> {
        val limit = query.query.limit
        require(limit >= 0) { "limit must be greater than or equal to 0." }
        return findDocument(query)
            .limit(limit)
            .toFlux()
            .map(::toObjectNode)
    }

    override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> = when (window) {
        is PageWindow.Offset -> offsetPage(query, window)
        is PageWindow.Keyset -> keysetPage(query, window)
    }

    /** One offset window; the total, when asked for, is counted in parallel with the find. */
    private fun offsetPage(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Offset): Mono<BackendPage> {
        val queryable = query.query
        val projection = MongoProjectionCompiler.compile(queryable.projection, query)
        val filter = filterCompiler.compile(queryable.filter, query)
        val sort = MongoSortCompiler.compile(queryable.sort, query)
        val rows = collection.find(filter)
            .projection(projection)
            .sort(sort)
            .skip(window.offset)
            .limit(window.limit)
            .batchSize(window.limit)
            .toFlux()
            .map(::toObjectNode)
            .collectList()
        if (!window.withTotal) {
            return rows.map { BackendPage(it) }
        }
        return Mono.zip(collection.countDocuments(filter).toMono(), rows).map { BackendPage(it.t2, it.t1) }
    }

    /**
     * One keyset window after [PageWindow.Keyset.after], ordered by the cursor sort's physical fields. Each row's
     * position is the BSON values at those fields, read before any field only the cursor needed is stripped.
     */
    private fun keysetPage(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset): Mono<BackendPage> {
        val queryable = query.query
        val resolvedSort = queryable.sort.map { query.field(it.field) }
        val physicalSort = queryable.sort.zip(resolvedSort) { sort, field -> sort.copy(field = field.physicalField) }
        val filter = window.after?.let {
            Filters.and(
                filterCompiler.compile(queryable.filter, query),
                MongoCursorFilterCompiler.compile(physicalSort, it.values),
            )
        } ?: filterCompiler.compile(queryable.filter, query)
        val sortFields = physicalSort.map { it.field.path }
        val projection = MongoProjectionCompiler.cursorProjection(queryable.projection, sortFields, query)
        val deferredInternalFields = setOf(Documents.ID_FIELD).intersect(projection.internalFields)
        val deferredResponseFields = resolvedSort
            .filter { it.physicalField.path in deferredInternalFields }
            .map { it.definition.responseField?.path ?: it.logicalField.path }
        return collection.find(filter)
            .projection(MongoProjectionCompiler.compile(projection))
            .sort(MongoSortCompiler.compilePhysical(physicalSort))
            .limit(window.limit)
            .toFlux()
            .collectList()
            .map { documents ->
                val keyset = documents.toKeysetRows(projection, sortFields, deferredInternalFields) { document ->
                    toObjectNode(document).also { result ->
                        deferredInternalFields.forEach(result::remove)
                        deferredResponseFields.forEach(result::remove)
                    }
                }
                BackendPage(keyset.rows, positions = keyset.positions)
            }
    }

    override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> {
        return collection.countDocuments(filterCompiler.compile(query)).toMono()
    }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> {
        val aggregation = query.query
        val limit = (window as? GroupWindow.First)?.limit
        val result = collection.aggregate(MongoAggregationCompiler(filterCompiler).compile(query, limit))
            .toFlux()
            .map { it.toAggregationResult(aggregation).toObjectNode() }
        // `$group` with a null id emits nothing over no documents; an ungrouped aggregation still has its summary.
        if (aggregation.groupBy.isNotEmpty()) {
            return result
        }
        val summary = Flux.defer {
            Flux.just(
                Document(EmptyAggregationValues.values(aggregation.metrics)).toObjectNode()
            )
        }
        return result.switchIfEmpty(summary)
    }

    private fun Document.toAggregationResult(query: AggregationQuery): Document {
        query.groupBy.forEach { group ->
            this[group.alias] = get(group.alias).toTermsValue(group.alias)
        }
        query.metrics.forEach { metric ->
            this[metric.alias] = when (metric) {
                is AggregationMetric.Count -> (get(metric.alias) as Number).toLong()
                is AggregationMetric.Any -> get(metric.alias).toTermsValue(metric.alias)
                is AggregationMetric.Numeric -> get(metric.alias).toFiniteDouble(metric.alias)
                is AggregationMetric.Percentile -> get(metric.alias).toFiniteDouble(metric.alias)
                is AggregationMetric.DistinctCount -> (get(metric.alias) as Number).toLong()
                is AggregationMetric.Derived -> get(metric.alias).toFiniteDouble(metric.alias)
            }
        }
        return this
    }

    private fun Any?.toTermsValue(alias: String): Any? =
        if (this is Decimal128) toFiniteDouble(alias) else this

    private fun Any?.toFiniteDouble(alias: String): Double? {
        val value = when (this) {
            null -> return null
            is Decimal128 -> bigDecimalValue().toDouble()
            is Number -> toDouble()
            else -> error("Aggregation metric [$alias] must be numeric, but was [${this::class.java.name}].")
        }
        require(value.isFinite()) { "Aggregation metric [$alias] must be finite." }
        return value
    }
}
