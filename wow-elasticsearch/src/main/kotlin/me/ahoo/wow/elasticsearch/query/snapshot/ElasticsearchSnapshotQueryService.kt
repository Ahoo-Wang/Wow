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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.elasticsearch.core.SearchRequest
import com.fasterxml.jackson.databind.type.TypeFactory
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.query.ElasticsearchConditionConverter.toQuery
import me.ahoo.wow.elasticsearch.query.ElasticsearchProjectionConverter.toSourceFilter
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchSnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    private val elasticsearchClient: ReactiveElasticsearchClient
) : SnapshotQueryService<S> {
    private val snapshotIndexName = namedAggregate.toSnapshotIndexName()
    private val snapshotType = TypeFactory.defaultInstance()
        .constructParametricType(
            MaterializedSnapshot::class.java,
            namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType
        )

    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>> {
        return dynamicSingle(singleQuery).map { doc -> doc.toJsonString().toObject(snapshotType) }
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

    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<S>> {
        return dynamicList(listQuery).map { doc -> doc.toJsonString().toObject(snapshotType) }
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

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<S>>> {
        return dynamicPaged(pagedQuery).map {
            PagedList(
                total = it.total,
                list = it.list.map { doc ->
                    doc.toJsonString().toObject(snapshotType)
                }
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        val searchRequest = SearchRequest.of {
            it.index(snapshotIndexName)
                .query(pagedQuery.condition.toQuery())
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
