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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.serialization.toLinkedHashMap
import org.springframework.data.elasticsearch.RestStatusException
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

class ElasticsearchSnapshotStore(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val refreshPolicy: Refresh = Refresh.True
) : SnapshotStore {
    companion object {
        private const val NOT_FOUND_CODE = 404
        const val NAME = "elasticsearch"
    }

    override val name: String
        get() = NAME

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
                .document(snapshot.toLinkedHashMap())
                .refresh(refreshPolicy)
        }.then()
    }
}
