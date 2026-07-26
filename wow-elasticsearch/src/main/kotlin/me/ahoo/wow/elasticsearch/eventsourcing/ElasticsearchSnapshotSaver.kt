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
import co.elastic.clients.elasticsearch._types.VersionType
import co.elastic.clients.elasticsearch.core.IndexRequest
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.serialization.toLinkedHashMap
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

internal interface ElasticsearchSnapshotSaver : AutoCloseable {
    fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void>

    override fun close() = Unit
}

internal class DirectElasticsearchSnapshotSaver(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val refreshPolicy: Refresh,
) : ElasticsearchSnapshotSaver {
    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        val request = IndexRequest.of<Map<String, Any?>> {
            it.index(snapshot.aggregateId.toSnapshotIndexName())
                .id(snapshot.aggregateId.id)
                .document(snapshot.toLinkedHashMap())
                .version(snapshot.version.toLong())
                .versionType(VersionType.External)
                .refresh(refreshPolicy)
        }
        return elasticsearchClient.index(request)
            .onErrorResume { error ->
                if (
                    error is ElasticsearchException &&
                    error.status() == VERSION_CONFLICT_STATUS
                ) {
                    Mono.empty()
                } else {
                    Mono.error(error)
                }
            }
            .then()
    }

    private companion object {
        const val VERSION_CONFLICT_STATUS = 409
    }
}
