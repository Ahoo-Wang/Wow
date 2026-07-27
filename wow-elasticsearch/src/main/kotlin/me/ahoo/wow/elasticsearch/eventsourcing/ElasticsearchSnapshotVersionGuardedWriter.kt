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
import co.elastic.clients.elasticsearch._types.Script
import co.elastic.clients.elasticsearch._types.ScriptLanguage
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.UpdateRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import co.elastic.clients.json.JsonData
import me.ahoo.wow.infra.batch.BatchItemResult
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

/**
 * Persists snapshots with an atomic aggregate-version guard against `_source`.
 *
 * Elasticsearch internal `_version` is only a write counter for legacy
 * documents and therefore cannot safely order aggregate snapshots. Applying
 * the same guarded update to direct and Bulk writes protects both legacy and
 * current documents without a client-side get/update race.
 */
internal class ElasticsearchSnapshotVersionGuardedWriter(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val refreshPolicy: Refresh,
) {
    @Suppress("UNCHECKED_CAST")
    private val documentClass = Map::class.java as Class<Map<String, Any?>>

    fun write(save: ElasticsearchSnapshotWrite): Mono<Void> {
        val request = UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { update ->
            update.index(save.index)
                .id(save.id)
                .script(save.toVersionGuardedScript())
                .upsert(save.document)
                .retryOnConflict(RETRY_ON_CONFLICT)
                .refresh(refreshPolicy)
        }
        return elasticsearchClient.update(request, documentClass).then()
    }

    fun write(batch: List<ElasticsearchSnapshotWrite>): Mono<List<BatchItemResult>> {
        val request = BulkRequest.of { bulk ->
            bulk.refresh(refreshPolicy)
                .operations(batch.map(::toUpdateOperation))
        }
        return elasticsearchClient.bulk(request)
            .map { response ->
                val responseItems = response.items()
                validateElasticsearchBulkResponse(
                    expectedItems = batch.map {
                        ElasticsearchBulkItemExpectation(
                            operationType = OperationType.Update,
                            indexExpression = it.index,
                            id = it.id,
                        )
                    },
                    responseItems = responseItems,
                    responseErrors = response.errors(),
                )
                responseItems.map { item ->
                    if (item.isSuccessfulResponse()) {
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
    }

    private fun toUpdateOperation(save: ElasticsearchSnapshotWrite): BulkOperation {
        return BulkOperation.of { operation ->
            operation.update<Map<String, Any?>, Map<String, Any?>> { update ->
                update.index(save.index)
                    .id(save.id)
                    .retryOnConflict(RETRY_ON_CONFLICT)
                    .action { action ->
                        action.script(save.toVersionGuardedScript())
                            .upsert(save.document)
                    }
            }
        }
    }

    private fun ElasticsearchSnapshotWrite.toVersionGuardedScript(): Script {
        return Script.of { script ->
            script.lang(ScriptLanguage.Painless)
                .source { source -> source.scriptString(VERSION_GUARDED_REPLACE_SCRIPT) }
                .params(VERSION_PARAMETER, JsonData.of(version))
                .params(SNAPSHOT_PARAMETER, JsonData.of(document))
        }
    }

    private companion object {
        const val RETRY_ON_CONFLICT = 10
        const val VERSION_PARAMETER = "version"
        const val SNAPSHOT_PARAMETER = "snapshot"
        const val VERSION_GUARDED_REPLACE_SCRIPT =
            "if (!ctx._source.containsKey('version')) { " +
                "throw new IllegalStateException('Stored Wow snapshot has no version.'); " +
                "} " +
                "if (ctx._source.version < params.version) { " +
                "ctx._source = params.snapshot; " +
                "} else { " +
                "ctx.op = 'noop'; " +
                "}"
    }
}
