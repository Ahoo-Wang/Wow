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
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.query.CursorTokenCodec
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.schema.ResolvedAggregationQuery
import me.ahoo.wow.query.withUniqueSort
import org.bson.Document
import org.bson.types.Decimal128
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

abstract class AbstractMongoQueryService<R : Any> : QueryService<R> {
    abstract val collection: MongoCollection<Document>
    abstract val converter: AbstractMongoFilterConverter
    abstract val projectionConverter: MongoProjectionConverter
    abstract val sortConverter: MongoSortConverter
    abstract fun toTypedResult(document: Document): R
    abstract fun toDynamicDocument(document: Document): DynamicDocument

    protected open val cursorUniqueField: String? = null
    protected open val cursorTokenCodec: CursorTokenCodec? = null

    protected open fun resolve(query: ISingleQuery): Mono<ISingleQuery> = Mono.just(query)

    protected open fun resolve(query: IListQuery): Mono<IListQuery> = Mono.just(query)

    protected open fun resolve(query: IPagedQuery): Mono<IPagedQuery> = Mono.just(query)

    protected open fun resolve(query: ICursorQuery): Mono<ICursorQuery> = Mono.just(query)

    protected open fun resolve(filter: FilterExpression): Mono<FilterExpression> = Mono.just(filter)

    protected fun findDocument(queryable: Queryable<*>): FindPublisher<Document> {
        return collection.findDocument(converter, queryable, projectionConverter, sortConverter)
    }

    private fun singleDocument(singleQuery: ISingleQuery): Mono<Document> {
        return resolve(singleQuery).flatMap { resolved ->
            findDocument(resolved)
                .limit(1)
                .first()
                .toMono()
        }
    }

    override fun single(singleQuery: ISingleQuery): Mono<R> {
        return singleDocument(singleQuery).map {
            toTypedResult(it)
        }
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        return singleDocument(singleQuery).map {
            toDynamicDocument(it)
        }
    }

    private fun listDocument(listQuery: IListQuery): Flux<Document> {
        require(listQuery.limit >= 0) { "limit must be greater than or equal to 0." }
        return resolve(listQuery).flatMapMany { resolved ->
            findDocument(resolved)
                .limit(resolved.limit)
                .toFlux()
        }
    }

    override fun list(listQuery: IListQuery): Flux<R> {
        return listDocument(listQuery).map {
            toTypedResult(it)
        }
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return listDocument(listQuery).map {
            toDynamicDocument(it)
        }
    }

    private fun <T : Any> pagedDocument(
        pagedQuery: IPagedQuery,
        documentMapper: (Document) -> T
    ): Mono<PagedList<T>> {
        return resolve(pagedQuery).flatMap { resolved ->
            val projectionBson = projectionConverter.convert(resolved.projection)
            val filter = converter.convert(resolved.filter)
            val sort = sortConverter.convert(resolved.sort)

            val totalPublisher = collection.countDocuments(filter).toMono()
            val listPublisher = collection.find(filter)
                .projection(projectionBson)
                .sort(sort)
                .skip(resolved.pagination.offset())
                .limit(resolved.pagination.size)
                .batchSize(resolved.pagination.size)
                .toFlux()

            val listMappedPublisher = listPublisher.map { documentMapper(it) }.collectList()
            Mono.zip(totalPublisher, listMappedPublisher)
                .map { result ->
                    PagedList(result.t1, result.t2)
                }
        }
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<R>> {
        return pagedDocument(pagedQuery) {
            toTypedResult(it)
        }
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        return pagedDocument(pagedQuery) { toDynamicDocument(it) }
    }

    private fun <T : Any> cursorDocument(
        query: ICursorQuery,
        mapper: (Document) -> T,
    ): Mono<CursorPage<T>> {
        val uniqueField = cursorUniqueField
            ?: return Mono.error(UnsupportedOperationException("Cursor query is not supported."))
        val tokenCodec = cursorTokenCodec
            ?: return Mono.error(UnsupportedOperationException("Cursor query is not supported."))
        val effective = query.withUniqueSort(uniqueField)
        return resolve(effective).flatMap { resolved ->
            val physicalSort = resolved.sort.map { it.copy(field = sortConverter.convertField(it.field)) }
            val filter = resolved.cursor?.let {
                MongoCursorCodec.decode(tokenCodec, it, resolved.sort.size)
            }?.let { values ->
                Filters.and(
                    converter.convert(resolved.filter),
                    MongoCursorFilterCompiler.compile(physicalSort, values),
                )
            } ?: converter.convert(resolved.filter)
            val projection = projectionConverter.cursorProjection(
                resolved.projection,
                physicalSort.map { it.field },
            )
            collection.find(filter)
                .projection(projectionConverter.convertCursor(projection))
                .sort(sortConverter.convert(resolved.sort))
                .limit(resolved.size + 1)
                .toFlux()
                .collectList()
                .map { documents ->
                    documents.toCursorPage(
                        resolved,
                        projection,
                        physicalSort.map { it.field },
                        tokenCodec,
                        mapper,
                    )
                }
        }
    }

    override fun cursor(query: ICursorQuery): Mono<CursorPage<R>> = cursorDocument(query, ::toTypedResult)

    override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<DynamicDocument>> =
        cursorDocument(query, ::toDynamicDocument)

    override fun count(filter: FilterExpression): Mono<Long> {
        return resolve(filter).flatMap { resolved ->
            collection.countDocuments(converter.convert(resolved)).toMono()
        }
    }

    protected fun executeAggregation(resolved: ResolvedAggregationQuery): Flux<DynamicDocument> {
        val query = resolved.query
        val result = collection.aggregate(
            MongoAggregationCompiler(converter).compile(query, resolved.schema),
        ).toFlux().map { it.toAggregationResult(query) }
        return if (query.groupBy.isEmpty()) {
            result.switchIfEmpty(Flux.just(query.emptySummary()))
        } else {
            result
        }
    }

    private fun Document.toAggregationResult(query: AggregationQuery): DynamicDocument {
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
        return toDynamicDocument()
    }

    private fun Any?.toTermsValue(alias: String): Any? =
        if (this is Decimal128) toFiniteDouble(alias) else this

    private fun AggregationQuery.emptySummary(): DynamicDocument = metrics.associateTo(Document()) { metric ->
        metric.alias to if (metric is AggregationMetric.Count) 0L else null
    }.toDynamicDocument()

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
