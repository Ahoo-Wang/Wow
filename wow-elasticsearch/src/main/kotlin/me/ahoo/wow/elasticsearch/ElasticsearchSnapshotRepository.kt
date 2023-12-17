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

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.Refresh
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import org.springframework.data.elasticsearch.RestStatusException
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

class ElasticsearchSnapshotRepository(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val snapshotIndexNameConverter: SnapshotIndexNameConverter = DefaultSnapshotIndexNameConverter,
    private val refreshPolicy: Refresh = Refresh.WaitFor
) : SnapshotRepository {
    companion object {
        private const val NOT_FOUND_STATUS = 404
    }

    private fun NamedAggregate.toIndexName(): String {
        return snapshotIndexNameConverter.convert(namedAggregate = this)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        return elasticsearchClient.get({
            it.index(aggregateId.toIndexName())
                .id(aggregateId.id)
        }, Snapshot::class.java)
            .mapNotNull<Snapshot<S>> {
                it.source() as Snapshot<S>?
            }
            .onErrorResume {
                if (it is RestStatusException && it.status == NOT_FOUND_STATUS) {
                    return@onErrorResume Mono.empty()
                }
                if (it is ElasticsearchException && it.response().status() == NOT_FOUND_STATUS) {
                    return@onErrorResume Mono.empty()
                }
                Mono.error(it)
            }
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return elasticsearchClient.index {
            it.index(snapshot.aggregateId.toIndexName())
                .id(snapshot.aggregateId.id)
                .document(snapshot)
                .refresh(refreshPolicy)
        }.then()
    }
}
