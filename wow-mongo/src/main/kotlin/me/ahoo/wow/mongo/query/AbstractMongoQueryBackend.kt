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
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyTo
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.toObjectNode
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.schema.QueryModelSchema
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

    internal fun findDocument(queryable: Queryable<*>, schema: QueryModelSchema): FindPublisher<Document> {
        return collection.findDocument(filterCompiler, queryable, schema)
    }

    internal fun executeSingle(singleQuery: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode> =
        findDocument(singleQuery, schema)
            .limit(1)
            .first()
            .toMono()
            .map(::toObjectNode)

    override fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode> =
        executeSingle(query.query, query.schema)

    internal fun executeList(listQuery: IListQuery, schema: QueryModelSchema): Flux<ObjectNode> {
        return findDocument(listQuery, schema)
            .limit(listQuery.limit)
            .toFlux()
            .map(::toObjectNode)
    }

    override fun list(query: ResolvedQuery<IListQuery>): Flux<ObjectNode> {
        require(query.query.limit >= 0) { "limit must be greater than or equal to 0." }
        return executeList(query.query, query.schema)
    }

    internal fun executePaged(pagedQuery: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>> {
        val projectionBson = MongoProjectionCompiler.compile(pagedQuery.projection, schema)
        val filter = filterCompiler.compile(pagedQuery.filter, schema)
        val sort = MongoSortCompiler.compile(pagedQuery.sort, schema)

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

    override fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> =
        executePaged(query.query, query.schema)

    internal fun executeCursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>> {
        val physicalSort = query.sort.map { it.copy(field = MongoSortCompiler.physicalField(it.field, schema)) }
        val filter = query.cursor?.let {
            MongoCursorFilterCompiler.compile(physicalSort, MongoCursorCodec.decode(it, query.sort.size))
        }?.let { Filters.and(filterCompiler.compile(query.filter, schema), it) }
            ?: filterCompiler.compile(query.filter, schema)
        val projection = MongoProjectionCompiler.cursorProjection(
            query.projection,
            physicalSort.map { it.field.path },
            schema,
        )
        val deferredInternalFields = setOf(Documents.ID_FIELD).intersect(projection.internalFields)
        val deferredResponseFields = physicalSort.zip(query.sort)
            .filter { (physical) -> physical.field.path in deferredInternalFields }
            .map { (_, logical) ->
                schema.resolveFieldSchema(logical.field, QueryCapability.SORT)
                    ?.responseField?.path ?: logical.field.path
            }
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

    override fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>> =
        executeCursor(query.query, query.schema)

    internal fun executeCount(filter: FilterExpression, schema: QueryModelSchema): Mono<Long> =
        collection.countDocuments(filterCompiler.compile(filter, schema)).toMono()

    override fun count(query: ResolvedQuery<FilterExpression>): Mono<Long> = executeCount(query.query, query.schema)

    internal fun executeAggregation(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode> {
        val result = collection.aggregate(
            MongoAggregationCompiler(filterCompiler).compile(query, schema),
        ).toFlux().map { it.toAggregationResult(query).toObjectNode() }
        return if (query.groupBy.isEmpty()) {
            result.switchIfEmpty(Flux.defer { Flux.just(query.emptySummary().toObjectNode()) })
        } else {
            result
        }
    }

    override fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode> =
        executeAggregation(query.query, query.schema)

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
