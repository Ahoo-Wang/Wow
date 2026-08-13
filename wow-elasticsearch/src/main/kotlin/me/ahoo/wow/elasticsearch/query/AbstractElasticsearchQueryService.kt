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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.query.event.GatewayEventStreamQueryService
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

abstract class AbstractElasticsearchQueryService<R : Any> private constructor(
    private val queryService: QueryService<R>?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : QueryService<R> {
    @Deprecated("Use the constructor that requires NamedAggregate and QueryGateway.")
    constructor() : this(null, Unit)

    @Suppress("UNCHECKED_CAST")
    protected constructor(
        namedAggregate: NamedAggregate,
        queryGateway: QueryGateway,
        documentKind: QueryDocumentKind,
    ) : this(
        when (documentKind) {
            QueryDocumentKind.SNAPSHOT -> GatewaySnapshotQueryService<Any>(namedAggregate, queryGateway)
            QueryDocumentKind.EVENT_STREAM -> GatewayEventStreamQueryService(namedAggregate, queryGateway)
        } as QueryService<R>,
        Unit,
    )

    abstract val elasticsearchClient: ReactiveElasticsearchClient
    abstract val conditionConverter: ConditionConverter<Query>
    abstract val indexName: String
    abstract fun toTypedResult(document: DynamicDocument): R

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
