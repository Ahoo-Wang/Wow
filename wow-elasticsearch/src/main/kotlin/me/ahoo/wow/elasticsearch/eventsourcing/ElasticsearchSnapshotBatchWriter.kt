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
import me.ahoo.wow.infra.batch.BatchItemResult
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

internal class ElasticsearchSnapshotBatchWriter(
    elasticsearchClient: ReactiveElasticsearchClient,
    refreshPolicy: Refresh,
) {
    private val versionGuardedWriter = ElasticsearchSnapshotVersionGuardedWriter(
        elasticsearchClient = elasticsearchClient,
        refreshPolicy = refreshPolicy,
    )

    private data class SnapshotKey(
        val index: String,
        val id: String,
    )

    fun write(batch: List<ElasticsearchSnapshotWrite>): Mono<List<BatchItemResult>> {
        require(batch.isNotEmpty()) { "Elasticsearch snapshot batch must not be empty." }
        val coalesced = coalesce(batch)
        return versionGuardedWriter.write(coalesced)
            .map { results ->
                val resultsByKey = coalesced.zip(results)
                    .associate { (save, result) -> save.toKey() to result }
                batch.map { save ->
                    checkNotNull(resultsByKey[save.toKey()]) {
                        "Elasticsearch snapshot batch writer did not produce a result for " +
                            "${save.index}/${save.id}."
                    }
                }
            }
    }

    private fun coalesce(
        batch: List<ElasticsearchSnapshotWrite>,
    ): List<ElasticsearchSnapshotWrite> {
        return batch.groupBy { it.toKey() }
            .values
            .map { sameAggregate ->
                sameAggregate.reduce { selected, candidate ->
                    if (candidate.version >= selected.version) {
                        candidate
                    } else {
                        selected
                    }
                }
            }
    }

    private fun ElasticsearchSnapshotWrite.toKey(): SnapshotKey {
        return SnapshotKey(index = index, id = id)
    }
}
