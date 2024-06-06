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

import com.fasterxml.jackson.databind.type.TypeFactory
import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.query.MongoProjectionConverter.toMongoProjection
import me.ahoo.wow.mongo.query.MongoSortConverter.toMongoSort
import me.ahoo.wow.mongo.toDynamicDocument
import me.ahoo.wow.mongo.toMaterializedSnapshot
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.query.converter.ConditionConverter
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class MongoSnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    private val collection: MongoCollection<Document>,
    private val converter: ConditionConverter<Bson> = MongoConditionConverter
) : SnapshotQueryService<S> {

    private val snapshotType = TypeFactory.defaultInstance()
        .constructParametricType(
            MaterializedSnapshot::class.java,
            namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType
        )

    private fun findDocument(queryable: Queryable<*>): FindPublisher<Document> {
        val projectionBson = queryable.projection.toMongoProjection()
        val filter = converter.convert(queryable.condition)
        val sort = queryable.sort.toMongoSort()
        return collection.find(filter)
            .projection(projectionBson)
            .sort(sort)
    }

    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>> {
        return findDocument(singleQuery)
            .limit(1)
            .first()
            .toMono()
            .toMaterializedSnapshot(snapshotType)
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        return findDocument(singleQuery)
            .limit(1)
            .first()
            .toMono()
            .toDynamicDocument()
    }

    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<S>> {
        return findDocument(listQuery)
            .limit(listQuery.limit)
            .toFlux()
            .toMaterializedSnapshot(snapshotType)
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return findDocument(listQuery)
            .limit(listQuery.limit)
            .toFlux()
            .toDynamicDocument()
    }

    private fun <T> pagedFindDocument(
        pagedQuery: IPagedQuery,
        documentMap: (Flux<Document>) -> Flux<T>
    ): Mono<PagedList<T>> {
        val projectionBson = pagedQuery.projection.toMongoProjection()
        val filter = converter.convert(pagedQuery.condition)
        val sort = pagedQuery.sort.toMongoSort()

        val totalPublisher = collection.countDocuments(filter).toMono()
        val listPublisher = collection.find(filter)
            .projection(projectionBson)
            .sort(sort)
            .skip(pagedQuery.pagination.offset())
            .limit(pagedQuery.pagination.size)
            .toFlux()
        val listMappedPublisher = documentMap(listPublisher).collectList()
        return Mono.zip(totalPublisher, listMappedPublisher)
            .map { result ->
                PagedList(result.t1, result.t2)
            }
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<S>>> {
        return pagedFindDocument(pagedQuery) { it.toMaterializedSnapshot(snapshotType) }
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        return pagedFindDocument(pagedQuery) { it.toDynamicDocument() }
    }

    override fun count(condition: Condition): Mono<Long> {
        val filter = converter.convert(condition)
        return collection.countDocuments(filter).toMono()
    }
}
