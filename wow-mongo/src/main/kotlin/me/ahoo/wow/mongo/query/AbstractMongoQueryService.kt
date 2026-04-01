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

import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.ConditionConverter
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

abstract class AbstractMongoQueryService<R : Any> : QueryService<R> {
    abstract val collection: MongoCollection<Document>
    abstract val converter: ConditionConverter<Bson>
    abstract val projectionConverter: MongoProjectionConverter
    abstract val sortConverter: MongoSortConverter
    abstract fun toTypedResult(document: Document): R
    abstract fun toDynamicDocument(document: Document): DynamicDocument

    protected fun findDocument(queryable: Queryable<*>): FindPublisher<Document> {
        return collection.findDocument(converter, queryable, projectionConverter, sortConverter)
    }

    private fun singleDocument(singleQuery: ISingleQuery): Mono<Document> {
        return findDocument(singleQuery)
            .limit(1)
            .first()
            .toMono()
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
        return findDocument(listQuery)
            .limit(listQuery.limit)
            .toFlux()
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
        val projectionBson = projectionConverter.convert(pagedQuery.projection)
        val filter = converter.convert(pagedQuery.condition)
        val sort = sortConverter.convert(pagedQuery.sort)

        val totalPublisher = collection.countDocuments(filter).toMono()
        val listPublisher = collection.find(filter)
            .projection(projectionBson)
            .sort(sort)
            .skip(pagedQuery.pagination.offset())
            .limit(pagedQuery.pagination.size)
            .batchSize(pagedQuery.pagination.size)
            .toFlux()

        val listMappedPublisher = listPublisher.map { documentMapper(it) }.collectList()
        return Mono.zip(totalPublisher, listMappedPublisher)
            .map { result ->
                PagedList(result.t1, result.t2)
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

    override fun count(condition: Condition): Mono<Long> {
        val filter = converter.convert(condition)
        return collection.countDocuments(filter).toMono()
    }
}
