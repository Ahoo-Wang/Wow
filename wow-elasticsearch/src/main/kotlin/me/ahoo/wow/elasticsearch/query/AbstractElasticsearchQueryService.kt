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
import co.elastic.clients.elasticsearch.core.SearchRequest
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.elasticsearch.query.ElasticsearchProjectionConverter.toSourceFilter
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.ConditionConverter
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

abstract class AbstractElasticsearchQueryService<R : Any> : QueryService<R> {
    abstract val elasticsearchClient: ReactiveElasticsearchClient
    abstract val conditionConverter: ConditionConverter<Query>
    abstract val indexName: String
    abstract fun toTypedResult(document: DynamicDocument): R

    override fun single(singleQuery: ISingleQuery): Mono<R> {
        return dynamicSingle(singleQuery).map { toTypedResult(it) }
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        val listQuery = ListQuery(
            condition = singleQuery.condition,
            projection = singleQuery.projection,
            limit = 1,
            sort = singleQuery.sort
        )
        return dynamicList(listQuery).single()
    }

    override fun list(listQuery: IListQuery): Flux<R> {
        return dynamicList(listQuery).map { toTypedResult(it) }
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        val pagedQuery =
            PagedQuery(
                condition = listQuery.condition,
                projection = listQuery.projection,
                sort = listQuery.sort,
                pagination = Pagination(index = 1, size = listQuery.limit)
            )
        return dynamicPaged(pagedQuery).flatMapIterable { it.list }
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<R>> {
        return dynamicPaged(pagedQuery).map {
            PagedList(
                total = it.total,
                list = it.list.map { doc ->
                    toTypedResult(doc)
                }
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        val searchRequest = SearchRequest.of {
            it.index(indexName)
                .query(conditionConverter.convert(pagedQuery.condition))
                .from(pagedQuery.pagination.offset())
                .size(pagedQuery.pagination.size)

            if (pagedQuery.sort.isNotEmpty()) {
                it.sort(pagedQuery.sort.toSortOptions())
            }
            if (!pagedQuery.projection.isEmpty()) {
                it.source {
                    it.filter(pagedQuery.projection.toSourceFilter())
                }
            }
            it
        }
        return elasticsearchClient.search(searchRequest, Map::class.java)
            .mapNotNull<PagedList<DynamicDocument>> { result ->
                val list = result.hits()?.hits()?.map { hit ->
                    hit.source()?.let {
                        (it as MutableMap<String, Any>).toDynamicDocument()
                    }
                } as List<DynamicDocument>? ?: emptyList()
                PagedList(result.hits()?.total()?.value() ?: 0, list)
            }
    }

    override fun count(condition: Condition): Mono<Long> {
        val pagedQuery = PagedQuery(condition = condition, pagination = Pagination(index = 1, size = 0))
        return dynamicPaged(pagedQuery).map { it.total }
    }
}
