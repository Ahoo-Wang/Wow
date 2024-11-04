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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.SimpleDynamicDocument
import me.ahoo.wow.elasticsearch.DefaultSnapshotIndexNameConverter
import me.ahoo.wow.elasticsearch.SnapshotIndexNameConverter
import me.ahoo.wow.elasticsearch.query.ElasticsearchConditionConverter.toQuery
import me.ahoo.wow.elasticsearch.query.ElasticsearchProjectionConverter.toSourceFilter
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.core.document.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchSnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val snapshotIndexNameConverter: SnapshotIndexNameConverter = DefaultSnapshotIndexNameConverter
) : SnapshotQueryService<S> {
    private val snapshotIndexName = snapshotIndexNameConverter.convert(namedAggregate)
    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>> {
        TODO()
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        val searchRequest = SearchRequest.of {
            it.index(snapshotIndexName)
                .query(singleQuery.condition.toQuery())
                .sort(singleQuery.sort.toSortOptions())
                .source {
                    it.filter(singleQuery.projection.toSourceFilter())
                }
                .size(1)
        }
        return elasticsearchClient.search(searchRequest, Document::class.java)
            .mapNotNull<DynamicDocument> { result ->
                result.hits()?.hits()?.firstOrNull()?.source()?.let {
                    SimpleDynamicDocument(it)
                }
            }
    }

    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<S>> {
        TODO("Not yet implemented")
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        TODO("Not yet implemented")
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<S>>> {
        TODO("Not yet implemented")
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        TODO("Not yet implemented")
    }

    override fun count(condition: Condition): Mono<Long> {
        TODO("Not yet implemented")
    }
}