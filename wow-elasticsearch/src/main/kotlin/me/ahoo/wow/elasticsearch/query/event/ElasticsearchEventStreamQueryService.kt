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

package me.ahoo.wow.elasticsearch.query.event

import co.elastic.clients.elasticsearch.core.SearchRequest
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.query.ElasticsearchConditionConverter.toQuery
import me.ahoo.wow.elasticsearch.query.ElasticsearchProjectionConverter.toSourceFilter
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchEventStreamQueryService(
    override val namedAggregate: NamedAggregate,
    private val elasticsearchClient: ReactiveElasticsearchClient
) : EventStreamQueryService {
    private val eventStreamIndexName = namedAggregate.toEventStreamIndexName()
    override fun list(listQuery: IListQuery): Flux<DomainEventStream> {
        return dynamicList(listQuery)
            .map { it.toJsonString().toObject<DomainEventStream>() }
    }

    @Suppress("UNCHECKED_CAST")
    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        val searchRequest = SearchRequest.of {
            it.index(eventStreamIndexName)
                .query(listQuery.condition.toQuery())
                .size(listQuery.limit)

            if (listQuery.sort.isNotEmpty()) {
                it.sort(listQuery.sort.toSortOptions())
            }
            if (!listQuery.projection.isEmpty()) {
                it.source {
                    it.filter(listQuery.projection.toSourceFilter())
                }
            }
            it
        }
        return elasticsearchClient.search(searchRequest, Map::class.java)
            .flatMapIterable { result ->
                result.hits()?.hits()?.map { hit ->
                    hit.source()?.let {
                        (it as MutableMap<String, Any>).toDynamicDocument()
                    }
                } as List<DynamicDocument>? ?: emptyList()
            }
    }

    override fun count(condition: Condition): Mono<Long> {
        val searchRequest = SearchRequest.of {
            it.index(eventStreamIndexName)
                .query(condition.toQuery())
                .size(0)
            it
        }
        return elasticsearchClient.search(searchRequest, Map::class.java)
            .map { it.hits()?.total()?.value() ?: 0 }
    }
}
