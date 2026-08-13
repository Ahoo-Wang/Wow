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
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.ConditionConverter
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

abstract class AbstractMongoQueryService<R : Any> protected constructor(
    private val queryService: QueryService<R>?
) : QueryService<R> {
    constructor() : this(null)

    abstract val collection: MongoCollection<Document>
    abstract val converter: ConditionConverter<Bson>
    abstract val projectionConverter: MongoProjectionConverter
    abstract val sortConverter: MongoSortConverter
    abstract fun toTypedResult(document: Document): R
    abstract fun toDynamicDocument(document: Document): DynamicDocument

    protected fun findDocument(queryable: Queryable<*>): FindPublisher<Document> {
        return collection.findDocument(converter, queryable, projectionConverter, sortConverter)
    }

    override fun single(singleQuery: ISingleQuery): Mono<R> {
        return queryService?.single(singleQuery) ?: unavailableMono()
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        return queryService?.dynamicSingle(singleQuery) ?: unavailableMono()
    }

    override fun list(listQuery: IListQuery): Flux<R> {
        return queryService?.list(listQuery) ?: unavailableFlux()
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return queryService?.dynamicList(listQuery) ?: unavailableFlux()
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<R>> {
        return queryService?.paged(pagedQuery) ?: unavailableMono()
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        return queryService?.dynamicPaged(pagedQuery) ?: unavailableMono()
    }

    override fun count(condition: Condition): Mono<Long> {
        return queryService?.count(condition) ?: unavailableMono()
    }

    private fun <T : Any> unavailableMono(): Mono<T> = Mono.defer { Mono.error(backendUnavailable()) }

    private fun <T : Any> unavailableFlux(): Flux<T> = Flux.defer { Flux.error(backendUnavailable()) }

    private fun backendUnavailable(): QueryException = QueryException(
        QueryErrorCode.BACKEND_NOT_READY,
        QueryStage.BACKEND_RESOLUTION,
        QueryErrorReason.BACKEND_UNAVAILABLE
    )
}
