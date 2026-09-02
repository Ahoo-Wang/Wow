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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.toObjectNode
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.ResolvedAggregationQuery
import me.ahoo.wow.query.withUniqueSort
import org.bson.Document
import org.bson.types.Decimal128
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import tools.jackson.databind.node.ObjectNode

abstract class AbstractMongoQueryBackend : QueryBackend {
    abstract val collection: MongoCollection<Document>
    abstract val converter: AbstractMongoFilterConverter
    abstract val projectionConverter: MongoProjectionConverter
    abstract val sortConverter: MongoSortConverter
    protected abstract fun toObjectNode(document: Document): ObjectNode

    protected abstract val cursorUniqueField: QueryField

    protected open fun resolve(query: ISingleQuery): Mono<ISingleQuery> = Mono.just(query)

    protected open fun resolve(query: IListQuery): Mono<IListQuery> = Mono.just(query)

    protected open fun resolve(query: IPagedQuery): Mono<IPagedQuery> = Mono.just(query)

    protected open fun resolve(query: ICursorQuery): Mono<ICursorQuery> = Mono.just(query)

    protected open fun resolve(filter: FilterExpression): Mono<FilterExpression> = Mono.just(filter)

    protected fun findDocument(queryable: Queryable<*>): FindPublisher<Document> {
        return collection.findDocument(converter, queryable, projectionConverter, sortConverter)
    }

    internal fun findDocument(queryable: Queryable<*>, schema: QueryModelSchema?): FindPublisher<Document> {
        return collection.findDocument(converter, queryable, projectionConverter, sortConverter, schema)
    }

    internal fun executeSingle(singleQuery: ISingleQuery, schema: QueryModelSchema?): Mono<ObjectNode> =
        findDocument(singleQuery, schema)
            .limit(1)
            .first()
            .toMono()
            .map(::toObjectNode)

    override fun single(query: ISingleQuery): Mono<ObjectNode> =
        resolve(query).flatMap { executeSingle(it, null) }

    internal fun executeList(listQuery: IListQuery, schema: QueryModelSchema?): Flux<ObjectNode> {
        return findDocument(listQuery, schema)
            .limit(listQuery.limit)
            .toFlux()
            .map(::toObjectNode)
    }

    override fun list(query: IListQuery): Flux<ObjectNode> {
        require(query.limit >= 0) { "limit must be greater than or equal to 0." }
        return resolve(query).flatMapMany { executeList(it, null) }
    }

    internal fun executePaged(pagedQuery: IPagedQuery, schema: QueryModelSchema?): Mono<PagedList<ObjectNode>> {
        val projectionBson = projectionConverter.convert(pagedQuery.projection, schema)
        val filter = converter.convert(pagedQuery.filter)
        val sort = sortConverter.convert(pagedQuery.sort)

        val totalPublisher = collection.countDocuments(filter).toMono()
        val listPublisher = collection.find(filter)
            .projection(projectionBson)
            .sort(sort)
            .skip(pagedQuery.pagination.offset())
            .limit(pagedQuery.pagination.size)
            .batchSize(pagedQuery.pagination.size)
            .toFlux()

        val listMappedPublisher = listPublisher.map(::toObjectNode).collectList()
        return Mono.zip(totalPublisher, listMappedPublisher)
            .map { result ->
                PagedList(result.t1, result.t2)
            }
    }

    override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> =
        resolve(query).flatMap { executePaged(it, null) }

    internal fun executeCursor(query: ICursorQuery, schema: QueryModelSchema?): Mono<CursorPage<ObjectNode>> {
        val uniqueField = cursorUniqueField
        val physicalSort = query.sort.map { it.copy(field = QueryField(sortConverter.convertField(it.field.path))) }
        val filter = query.cursor?.let {
            MongoCursorFilterCompiler.compile(physicalSort, MongoCursorCodec.decode(it, query.sort.size))
        }?.let { Filters.and(converter.convert(query.filter), it) }
            ?: converter.convert(query.filter)
        val projection = projectionConverter.cursorProjection(
            query.projection,
            physicalSort.map { it.field.path },
            schema,
        )
        val deferredInternalFields = setOf(Documents.ID_FIELD).intersect(projection.internalFields)
        return collection.find(filter)
            .projection(projectionConverter.convertCursor(projection))
            .sort(sortConverter.convert(query.sort))
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
                        if (deferredInternalFields.isNotEmpty()) {
                            result.remove(Documents.ID_FIELD)
                            result.remove(uniqueField.path)
                        }
                    }
                }
            }
    }

    override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
        resolve(query.withUniqueSort(cursorUniqueField)).flatMap { executeCursor(it, null) }

    internal fun executeCount(filter: FilterExpression): Mono<Long> =
        collection.countDocuments(converter.convert(filter)).toMono()

    override fun count(filter: FilterExpression): Mono<Long> = resolve(filter).flatMap(::executeCount)

    protected fun executeAggregation(resolved: ResolvedAggregationQuery): Flux<ObjectNode> {
        val query = resolved.query
        val result = collection.aggregate(
            MongoAggregationCompiler(converter).compile(query, resolved.schema),
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
            }
        }
        return this
    }

    private fun Any?.toTermsValue(alias: String): Any? =
        if (this is Decimal128) toFiniteDouble(alias) else this

    private fun AggregationQuery.emptySummary(): Document = metrics.associateTo(Document()) { metric ->
        metric.alias to if (metric is AggregationMetric.Count) 0L else null
    }

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
