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
package me.ahoo.wow.elasticsearch.eventsourcing

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.SortOrder
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords
import org.springframework.data.elasticsearch.RestStatusException
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchSnapshotRepository(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val refreshPolicy: Refresh = Refresh.True
) : SnapshotRepository {
    companion object {
        private const val NOT_FOUND_CODE = 404
    }

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        return elasticsearchClient.get({
            it.index(aggregateId.toSnapshotIndexName())
                .id(aggregateId.id)
        }, Snapshot::class.java)
            .mapNotNull<Snapshot<S>> {
                it.source() as Snapshot<S>?
            }
            .onErrorResume {
                if (it is RestStatusException && it.status == NOT_FOUND_CODE) {
                    return@onErrorResume Mono.empty()
                }
                if (it is ElasticsearchException && it.response().status() == NOT_FOUND_CODE) {
                    return@onErrorResume Mono.empty()
                }
                Mono.error(it)
            }
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return elasticsearchClient.index {
            it.index(snapshot.aggregateId.toSnapshotIndexName())
                .id(snapshot.aggregateId.id)
                .document(snapshot)
                .refresh(refreshPolicy)
        }.then()
    }

    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> {
        return elasticsearchClient.search({
            it.index(namedAggregate.toSnapshotIndexName())
                .query {
                    it.range {
                        it.term {
                            it.field(MessageRecords.AGGREGATE_ID)
                                .gt(afterId)
                        }
                    }
                }
                .source {
                    it.filter {
                        it.includes(MessageRecords.AGGREGATE_ID, MessageRecords.TENANT_ID, MessageRecords.OWNER_ID)
                    }
                }
                .size(limit)
                .sort {
                    it.field {
                        it.field(MessageRecords.AGGREGATE_ID).order(SortOrder.Asc)
                    }
                }
        }, Map::class.java).flatMapIterable<AggregateId> {
            it.hits().hits().map { hit ->
                val source = requireNotNull(hit.source())
                val aggregateId = checkNotNull(source[MessageRecords.AGGREGATE_ID] as String)
                val tenantId = checkNotNull(source[MessageRecords.TENANT_ID] as String)
                val ownerId = source[MessageRecords.OWNER_ID]?.toString() ?: OwnerId.DEFAULT_OWNER_ID
                namedAggregate.aggregateId(aggregateId, tenantId, ownerId)
            }
        }
    }
}
