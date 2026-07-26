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
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.BulkResponse
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.infra.batch.BatchItemResult
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ElasticsearchEventStreamBatchWriterTest {
    private val client = mockk<ReactiveElasticsearchClient>()

    @Test
    fun `should use bulk create and preserve index routing and input order`() {
        val request = slot<BulkRequest>()
        every { client.bulk(capture(request)) } returns Mono.just(
            bulkResponse(
                errors = false,
                responseItem(index = "event-order", id = "order-1-1", status = 201),
                responseItem(index = "event-cart", id = "cart-1-2", status = 201),
            )
        )
        val batch = listOf(
            append(index = "event-order", id = "order-1-1", routing = "order-1"),
            append(index = "event-cart", id = "cart-1-2", routing = "cart-1"),
        )

        ElasticsearchEventStreamBatchWriter(client, Refresh.WaitFor)
            .write(batch)
            .test()
            .assertNext { results ->
                results.assert().hasSize(2)
                results.all { it === BatchItemResult.Success }.assert().isTrue()
            }
            .verifyComplete()

        request.captured.refresh().assert().isEqualTo(Refresh.WaitFor)
        request.captured.operations().assert().hasSize(2)
        request.captured.operations().zip(batch).forEach { (operation, expected) ->
            operation.isCreate.assert().isTrue()
            operation.create<Map<String, Any?>>().let { create ->
                create.index().assert().isEqualTo(expected.index)
                create.id().assert().isEqualTo(expected.id)
                create.routing().assert().isEqualTo(expected.routing)
                create.document().assert().isEqualTo(expected.document)
            }
        }
    }

    @Test
    fun `version conflict should fail only its corresponding append`() {
        every { client.bulk(any<BulkRequest>()) } returns Mono.just(
            bulkResponse(
                errors = true,
                responseItem(
                    index = "event-order",
                    id = "order-1-1",
                    status = 409,
                    errorType = "version_conflict_engine_exception",
                ),
                responseItem(index = "event-order", id = "order-2-1", status = 201),
            )
        )
        val conflictedStream = mockk<DomainEventStream> {
            every { version } returns 1
        }
        val successfulStream = mockk<DomainEventStream>()

        ElasticsearchEventStreamBatchWriter(client, Refresh.False)
            .write(
                listOf(
                    append(eventStream = conflictedStream, id = "order-1-1"),
                    append(eventStream = successfulStream, id = "order-2-1"),
                )
            )
            .test()
            .assertNext { results ->
                results.assert().hasSize(2)
                val conflict = (results[0] as BatchItemResult.Failure).error
                conflict.assert().isInstanceOf(EventVersionConflictException::class.java)
                (conflict as EventVersionConflictException).eventStream.assert().isSameAs(conflictedStream)
                conflict.cause.assert().isInstanceOf(ElasticsearchBulkItemException::class.java)
                results[1].assert().isSameAs(BatchItemResult.Success)
            }
            .verifyComplete()
    }

    @Test
    fun `non conflict item failure should be isolated`() {
        every { client.bulk(any<BulkRequest>()) } returns Mono.just(
            bulkResponse(
                errors = true,
                responseItem(index = "event-order", id = "order-1-1", status = 201),
                responseItem(
                    index = "event-order",
                    id = "order-2-1",
                    status = 429,
                    errorType = "es_rejected_execution_exception",
                ),
            )
        )

        ElasticsearchEventStreamBatchWriter(client, Refresh.False)
            .write(listOf(append(id = "order-1-1"), append(id = "order-2-1")))
            .test()
            .assertNext { results ->
                results[0].assert().isSameAs(BatchItemResult.Success)
                val failure = (results[1] as BatchItemResult.Failure).error
                failure.assert().isInstanceOf(ElasticsearchBulkItemException::class.java)
                (failure as ElasticsearchBulkItemException).status.assert().isEqualTo(429)
            }
            .verifyComplete()
    }

    @Test
    fun `batch request failure should remain terminal for the whole writer call`() {
        val failure = IllegalStateException("bulk unavailable")
        every { client.bulk(any<BulkRequest>()) } returns Mono.error(failure)

        ElasticsearchEventStreamBatchWriter(client, Refresh.False)
            .write(listOf(append()))
            .test()
            .expectErrorMatches { it === failure }
            .verify()
    }

    @Test
    fun `response cardinality mismatch should fail the whole writer call`() {
        every { client.bulk(any<BulkRequest>()) } returns Mono.just(
            bulkResponse(
                errors = false,
                responseItem(index = "event-order", id = "order-1-1", status = 201),
            )
        )

        ElasticsearchEventStreamBatchWriter(client, Refresh.False)
            .write(listOf(append(id = "order-1-1"), append(id = "order-2-1")))
            .test()
            .expectError(ElasticsearchBulkResponseException::class.java)
            .verify()
    }

    @Test
    fun `inconsistent errors flag should fail the whole writer call`() {
        every { client.bulk(any<BulkRequest>()) } returns Mono.just(
            bulkResponse(
                errors = false,
                responseItem(
                    index = "event-order",
                    id = "order-1-1",
                    status = 500,
                    errorType = "internal_server_error",
                ),
            )
        )

        ElasticsearchEventStreamBatchWriter(client, Refresh.False)
            .write(listOf(append()))
            .test()
            .expectError(ElasticsearchBulkResponseException::class.java)
            .verify()
    }

    private fun append(
        eventStream: DomainEventStream = mockk(),
        index: String = "event-order",
        id: String = "order-1-1",
        routing: String = "order-1",
    ): ElasticsearchEventStreamAppend {
        return ElasticsearchEventStreamAppend(
            eventStream = eventStream,
            index = index,
            id = id,
            document = linkedMapOf("id" to id),
            routing = routing,
        )
    }

    private fun bulkResponse(
        errors: Boolean,
        vararg items: BulkResponseItem,
    ): BulkResponse {
        return BulkResponse.of {
            it.errors(errors)
                .items(items.toList())
                .took(1)
        }
    }

    private fun responseItem(
        index: String,
        id: String,
        status: Int,
        errorType: String? = null,
    ): BulkResponseItem {
        return BulkResponseItem.of { item ->
            item.operationType(OperationType.Create)
                .index(index)
                .id(id)
                .status(status)
            errorType?.let {
                item.error { error ->
                    error.type(errorType)
                        .reason("$errorType for $id")
                }
            }
            item
        }
    }
}
