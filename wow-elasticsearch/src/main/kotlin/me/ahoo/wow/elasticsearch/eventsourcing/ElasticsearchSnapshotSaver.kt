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

import co.elastic.clients.elasticsearch._types.Refresh
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

internal interface ElasticsearchSnapshotSaver : AutoCloseable {
    fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void>

    override fun close() = Unit
}

internal class DirectElasticsearchSnapshotSaver(
    elasticsearchClient: ReactiveElasticsearchClient,
    refreshPolicy: Refresh,
) : ElasticsearchSnapshotSaver {
    private val versionGuardedWriter = ElasticsearchSnapshotVersionGuardedWriter(
        elasticsearchClient = elasticsearchClient,
        refreshPolicy = refreshPolicy,
    )

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> =
        versionGuardedWriter.write(snapshot.toElasticsearchSnapshotWrite())
}
