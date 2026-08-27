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

import co.elastic.clients.elasticsearch._types.ErrorCause
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.infra.batch.BatchItemResult
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

internal data class ElasticsearchEventStreamAppend(
    val eventStream: DomainEventStream,
    val index: String,
    val id: String,
    val document: Map<String, Any?>,
    val routing: String,
)

class ElasticsearchBulkItemException(
    val operationType: OperationType,
    val index: String,
    val id: String?,
    val status: Int,
    val error: ErrorCause?,
) : RuntimeException(
    "Elasticsearch bulk item failed: operation[$operationType], " +
        "index[$index], id[$id], status[$status], " +
        "type[${error?.type()}], reason[${error?.reason()}]."
)

class ElasticsearchBulkResponseException(
    message: String,
) : RuntimeException(message)

internal class ElasticsearchEventStreamBatchWriter(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val refreshPolicy: Refresh,
) {
    fun write(batch: List<ElasticsearchEventStreamAppend>): Mono<List<BatchItemResult>> {
        require(batch.isNotEmpty()) { "Elasticsearch event stream batch must not be empty." }
        val request = BulkRequest.of { bulk ->
            bulk.refresh(refreshPolicy)
                .operations(batch.map(::toCreateOperation))
        }
        return elasticsearchClient.bulk(request)
            .map { response ->
                val responseItems = response.items()
                validateElasticsearchBulkResponse(
                    expectedItems = batch.map {
                        ElasticsearchBulkItemExpectation(
                            operationType = OperationType.Create,
                            indexExpression = it.index,
                            id = it.id,
                        )
                    },
                    responseItems = responseItems,
                    responseErrors = response.errors(),
                )
                batch.zip(responseItems).map { (append, item) ->
                    item.toBatchItemResult(append.eventStream)
                }
            }
    }

    private fun toCreateOperation(
        append: ElasticsearchEventStreamAppend,
    ): BulkOperation {
        return BulkOperation.of { operation ->
            operation.create<Map<String, Any?>> { create ->
                create.index(append.index)
                    .id(append.id)
                    .routing(append.routing)
                    .document(append.document)
            }
        }
    }

    private fun BulkResponseItem.toBatchItemResult(
        eventStream: DomainEventStream,
    ): BatchItemResult {
        if (isSuccessfulResponse()) {
            return BatchItemResult.Success
        }
        val itemException = ElasticsearchBulkItemException(
            operationType = operationType(),
            index = index(),
            id = id(),
            status = status(),
            error = error(),
        )
        val resultError = if (status() == VERSION_CONFLICT_STATUS) {
            EventVersionConflictException(
                eventStream = eventStream,
                cause = itemException,
            )
        } else {
            itemException
        }
        return BatchItemResult.Failure(resultError)
    }

    private companion object {
        const val VERSION_CONFLICT_STATUS = 409
    }
}
