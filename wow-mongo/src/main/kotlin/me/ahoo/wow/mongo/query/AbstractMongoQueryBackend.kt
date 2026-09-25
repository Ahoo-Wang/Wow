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
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyTo
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.toObjectNode
import me.ahoo.wow.query.AdmittedQuery
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

    internal fun findDocument(admitted: AdmittedQuery<Queryable<*>>): FindPublisher<Document> {
        return collection.findDocument(filterCompiler, admitted)
    }

    override fun single(admitted: AdmittedQuery<ISingleQuery>): Mono<ObjectNode> {
        return findDocument(admitted)
            .limit(1)
            .first()
            .toMono()
            .map(::toObjectNode)
    }

    override fun list(admitted: AdmittedQuery<IListQuery>): Flux<ObjectNode> {
        val query = admitted.query
        require(query.limit >= 0) { "limit must be greater than or equal to 0." }
        return findDocument(admitted)
            .limit(query.limit)
            .toFlux()
            .map(::toObjectNode)
    }

    override fun paged(admitted: AdmittedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> {
        val query = admitted.query
        val projectionBson = MongoProjectionCompiler.compile(query.projection, admitted)
        val filter = filterCompiler.compile(query.filter, admitted)
        val sort = MongoSortCompiler.compile(query.sort, admitted)

        val totalPublisher = collection.countDocuments(filter).toMono()
        val listPublisher = collection.find(filter)
            .projection(projectionBson)
            .sort(sort)
            .skip(query.pagination.offset())
            .limit(query.pagination.size)
            .batchSize(query.pagination.size)
            .toFlux()

        val listMappedPublisher = listPublisher.map(::toObjectNode).collectList()
        return Mono.zip(totalPublisher, listMappedPublisher)
            .map { result ->
                PagedList(result.t1, result.t2)
            }
    }

    override fun cursor(admitted: AdmittedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>> {
        val query = admitted.query
        val resolvedSort = query.sort.map { admitted.field(it.field) }
        val physicalSort = query.sort.zip(resolvedSort) { sort, field -> sort.copy(field = field.physicalField) }
        val filter = query.cursor?.let {
            MongoCursorFilterCompiler.compile(physicalSort, MongoCursorCodec.decode(it, query.sort.size))
        }?.let { Filters.and(filterCompiler.compile(query.filter, admitted), it) }
            ?: filterCompiler.compile(query.filter, admitted)
        val projection = MongoProjectionCompiler.cursorProjection(
            query.projection,
            physicalSort.map { it.field.path },
            admitted,
        )
        val deferredInternalFields = setOf(Documents.ID_FIELD).intersect(projection.internalFields)
        val deferredResponseFields = resolvedSort
            .filter { it.physicalField.path in deferredInternalFields }
            .map { it.definition.responseField?.path ?: it.logicalField.path }
        return collection.find(filter)
            .projection(MongoProjectionCompiler.compile(projection))
            .sort(MongoSortCompiler.compilePhysical(physicalSort))
            .limit(query.size + 1)
            .toFlux()
            .collectList()
            .map { documents ->
                documents.toCursorPage(
                    query,
                    projection,
                    physicalSort.map { it.field.path },
                    deferredInternalFields,
                ) { document ->
                    toObjectNode(document).also { result ->
                        deferredInternalFields.forEach(result::remove)
                        deferredResponseFields.forEach(result::remove)
                    }
                }
            }
    }

    override fun count(admitted: AdmittedQuery<FilterExpression>): Mono<Long> {
        return collection.countDocuments(filterCompiler.compile(admitted)).toMono()
    }

    override fun aggregate(admitted: AdmittedQuery<AggregationQuery>): Flux<ObjectNode> {
        val query = admitted.query
        val result = collection.aggregate(
            MongoAggregationCompiler(filterCompiler).compile(admitted),
        ).toFlux().map { it.toAggregationResult(query).toObjectNode() }
        return if (query.groupBy.isEmpty()) {
            result.switchIfEmpty(Flux.defer { Flux.just(query.emptySummary().toObjectNode()) })
        } else {
            result
        }
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

    private fun AggregationQuery.emptySummary(): Document = Document(EmptyAggregationValues.values(metrics))

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
