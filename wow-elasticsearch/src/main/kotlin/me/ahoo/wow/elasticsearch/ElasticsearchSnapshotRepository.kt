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
package me.ahoo.wow.elasticsearch

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.serialization.asJsonString
import me.ahoo.wow.serialization.asObject
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.xcontent.XContentType
import org.springframework.data.elasticsearch.RestStatusException
import org.springframework.data.elasticsearch.client.reactive.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchSnapshotRepository(
    private val snapshotIndexNameConverter: SnapshotIndexNameConverter = DefaultSnapshotIndexNameConverter,
    private val elasticsearchClient: ReactiveElasticsearchClient,
) : SnapshotRepository {

    private fun AggregateId.asIndexName(): String {
        return snapshotIndexNameConverter.convert(aggregateId = this)
    }

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        val getRequest = GetRequest(aggregateId.asIndexName(), aggregateId.id)
        return elasticsearchClient.get(getRequest)
            .map {
                it.sourceAsString().asObject<Snapshot<S>>()
            }.onErrorResume {
                if (it is RestStatusException && it.status == 404) {
                    return@onErrorResume Mono.empty()
                }
                Mono.error(it)
            }
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        val indexRequest = IndexRequest(snapshot.aggregateId.asIndexName())
        indexRequest.id(snapshot.aggregateId.id)
        indexRequest.source(snapshot.asJsonString(), XContentType.JSON)
        return elasticsearchClient.index(indexRequest)
            .then()
    }

    override fun findAggregateId(namedAggregate: NamedAggregate, cursorId: String, limit: Int): Flux<AggregateId> {
        throw UnsupportedOperationException()
    }
}
