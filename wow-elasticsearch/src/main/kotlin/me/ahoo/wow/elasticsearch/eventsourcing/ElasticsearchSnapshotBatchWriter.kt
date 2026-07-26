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
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem
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
            .map { response ->
                val responseItems = response.items()
                validateResponse(coalesced, responseItems, response.errors())
                val resultsByKey = coalesced.zip(responseItems).associate { (save, item) ->
                    save.toKey() to item.toBatchItemResult()
                }
                batch.map { save ->
                    checkNotNull(resultsByKey[save.toKey()]) {
                        "Elasticsearch snapshot batch writer did not produce a result for " +
                            "${save.index}/${save.id}."
                    }
                }
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

    private fun validateResponse(
        batch: List<ElasticsearchSnapshotSave>,
        responseItems: List<BulkResponseItem>,
        responseErrors: Boolean,
    ) {
        val mismatchMessage = when {
            responseItems.size != batch.size ->
                "Elasticsearch bulk response item count[${responseItems.size}] " +
                    "does not match request item count[${batch.size}]."

            else -> {
                val mismatchedItem = batch.zip(responseItems)
                    .withIndex()
                    .firstOrNull { (_, pair) ->
                        val (save, item) = pair
                        item.operationType() != OperationType.Index ||
                            item.index() != save.index ||
                            item.id() != save.id
                    }
                when {
                    mismatchedItem != null -> {
                        val (index, pair) = mismatchedItem
                        val (save, item) = pair
                        "Elasticsearch bulk response item[$index] does not match its request: " +
                            "expected[index ${save.index}/${save.id}], " +
                            "actual[${item.operationType()} ${item.index()}/${item.id()}]."
                    }

                    responseErrors != responseItems.any { !it.isSuccessfulResponse() } ->
                        "Elasticsearch bulk response errors[$responseErrors] is inconsistent " +
                            "with its item failures."

                    else -> null
                }
            }
        }
        if (mismatchMessage != null) {
            throw ElasticsearchBulkResponseException(mismatchMessage)
        }
    }

    private fun BulkResponseItem.toBatchItemResult(): BatchItemResult {
        if (isSuccessfulResponse() || status() == VERSION_CONFLICT_STATUS) {
            return BatchItemResult.Success
        }
        return BatchItemResult.Failure(
            ElasticsearchBulkItemException(
                operationType = operationType(),
                index = index(),
                id = id(),
                status = status(),
                error = error(),
            )
        )
    }

    private fun BulkResponseItem.isSuccessfulResponse(): Boolean {
        return status() in SUCCESS_STATUS_RANGE && error() == null
    }

    private fun ElasticsearchSnapshotSave.toKey(): SnapshotKey {
        return SnapshotKey(index = index, id = id)
    }

    private companion object {
        const val VERSION_CONFLICT_STATUS = 409
        val SUCCESS_STATUS_RANGE = 200..299
    }
}
