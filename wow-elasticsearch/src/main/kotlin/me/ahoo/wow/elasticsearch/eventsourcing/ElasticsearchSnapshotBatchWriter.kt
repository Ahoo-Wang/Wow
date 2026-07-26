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
import co.elastic.clients.elasticsearch._types.VersionType
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import me.ahoo.wow.infra.batch.BatchItemResult
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

internal data class ElasticsearchSnapshotSave(
    val index: String,
    val id: String,
    val document: Map<String, Any?>,
    val version: Int,
)

internal class ElasticsearchSnapshotBatchWriter(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val refreshPolicy: Refresh,
) {
    private val versionConflictResolver = ElasticsearchSnapshotVersionConflictResolver(
        elasticsearchClient = elasticsearchClient,
        refreshPolicy = refreshPolicy,
    )

    private data class SnapshotKey(
        val index: String,
        val id: String,
    )

    fun write(batch: List<ElasticsearchSnapshotSave>): Mono<List<BatchItemResult>> {
        require(batch.isNotEmpty()) { "Elasticsearch snapshot batch must not be empty." }
        val coalesced = coalesce(batch)
        val request = BulkRequest.of { bulk ->
            bulk.refresh(refreshPolicy)
                .operations(coalesced.map(::toIndexOperation))
        }
        return elasticsearchClient.bulk(request)
            .flatMap { response ->
                val responseItems = response.items()
                validateElasticsearchBulkResponse(
                    expectedItems = coalesced.map {
                        ElasticsearchBulkItemExpectation(
                            operationType = OperationType.Index,
                            indexExpression = it.index,
                            id = it.id,
                        )
                    },
                    responseItems = responseItems,
                    responseErrors = response.errors(),
                )
                val resultsByKey = mutableMapOf<SnapshotKey, BatchItemResult>()
                val versionConflicts = mutableListOf<ElasticsearchSnapshotSave>()
                coalesced.zip(responseItems).forEach { (save, item) ->
                    if (item.status() == VERSION_CONFLICT_STATUS) {
                        versionConflicts += save
                    } else {
                        resultsByKey[save.toKey()] = if (item.isSuccessfulResponse()) {
                            BatchItemResult.Success
                        } else {
                            BatchItemResult.Failure(
                                ElasticsearchBulkItemException(
                                    operationType = item.operationType(),
                                    index = item.index(),
                                    id = item.id(),
                                    status = item.status(),
                                    error = item.error(),
                                )
                            )
                        }
                    }
                }

                resolveVersionConflicts(versionConflicts)
                    .map { conflictResults ->
                        versionConflicts.zip(conflictResults).forEach { (save, result) ->
                            resultsByKey[save.toKey()] = result
                        }
                        batch.map { save ->
                            checkNotNull(resultsByKey[save.toKey()]) {
                                "Elasticsearch snapshot batch writer did not produce a result for " +
                                    "${save.index}/${save.id}."
                            }
                        }
                    }
            }
    }

    private fun resolveVersionConflicts(
        versionConflicts: List<ElasticsearchSnapshotSave>,
    ): Mono<List<BatchItemResult>> {
        if (versionConflicts.isEmpty()) {
            return Mono.just(emptyList())
        }
        return versionConflictResolver.resolve(versionConflicts)
            .onErrorResume { error ->
                Mono.just(
                    versionConflicts.map {
                        BatchItemResult.Failure(error)
                    }
                )
            }
    }

    private fun coalesce(
        batch: List<ElasticsearchSnapshotSave>,
    ): List<ElasticsearchSnapshotSave> {
        return batch.groupBy { it.toKey() }
            .values
            .map { sameAggregate ->
                sameAggregate.reduce { selected, candidate ->
                    if (candidate.version > selected.version) {
                        candidate
                    } else {
                        selected
                    }
                }
            }
    }

    private fun toIndexOperation(
        save: ElasticsearchSnapshotSave,
    ): BulkOperation {
        return BulkOperation.of { operation ->
            operation.index<Map<String, Any?>> { index ->
                index.index(save.index)
                    .id(save.id)
                    .document(save.document)
                    .version(save.version.toLong())
                    .versionType(VersionType.External)
            }
        }
    }

    private fun ElasticsearchSnapshotSave.toKey(): SnapshotKey {
        return SnapshotKey(index = index, id = id)
    }

    private companion object {
        const val VERSION_CONFLICT_STATUS = 409
    }
}
