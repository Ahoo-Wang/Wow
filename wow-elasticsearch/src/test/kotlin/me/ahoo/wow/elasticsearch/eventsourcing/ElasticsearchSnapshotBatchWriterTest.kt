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
import co.elastic.clients.elasticsearch.core.BulkResponse
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.batch.BatchItemResult
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ElasticsearchSnapshotBatchWriterTest {
    private val client = mockk<ReactiveElasticsearchClient>()

    @Test
    fun `should bulk index with strict external version and preserve cross index identity`() {
        val request = slot<BulkRequest>()
        every { client.bulk(capture(request)) } returns Mono.just(
            bulkResponse(
                errors = false,
                responseItem(index = "order.snapshot", id = "order-1", status = 200),
                responseItem(index = "cart.snapshot", id = "cart-1", status = 201),
            )
        )
        val batch = listOf(
            save(index = "order.snapshot", id = "order-1", version = 7),
            save(index = "cart.snapshot", id = "cart-1", version = 3),
        )

        ElasticsearchSnapshotBatchWriter(client, Refresh.WaitFor)
            .write(batch)
            .test()
            .assertNext { results ->
                results.assert().hasSize(2)
                results.all { it === BatchItemResult.Success }.assert().isTrue()
            }
            .verifyComplete()

        request.captured.refresh().assert().isEqualTo(Refresh.WaitFor)
        request.captured.operations().zip(batch).forEach { (operation, expected) ->
            operation.isIndex.assert().isTrue()
            operation.index<Map<String, Any?>>().let { index ->
                index.index().assert().isEqualTo(expected.index)
                index.id().assert().isEqualTo(expected.id)
                index.version().assert().isEqualTo(expected.version.toLong())
                index.versionType().assert().isEqualTo(VersionType.External)
                index.document().assert().isEqualTo(expected.document)
            }
        }
    }

    @Test
    fun `same aggregate writes should coalesce to the highest version`() {
        val request = slot<BulkRequest>()
        every { client.bulk(capture(request)) } returns Mono.just(
            bulkResponse(
                errors = false,
                responseItem(index = "order.snapshot", id = "order-1", status = 200),
            )
        )

        ElasticsearchSnapshotBatchWriter(client, Refresh.False)
            .write(
                listOf(
                    save(id = "order-1", version = 8),
                    save(id = "order-1", version = 6),
                    save(id = "order-1", version = 9),
                )
            )
            .test()
            .assertNext { results ->
                results.assert().hasSize(3)
                results.all { it === BatchItemResult.Success }.assert().isTrue()
            }
            .verifyComplete()

        request.captured.operations().assert().hasSize(1)
        request.captured.operations().single()
            .index<Map<String, Any?>>()
            .version()
            .assert()
            .isEqualTo(9L)
    }

    @Test
    fun `same aggregate and version should preserve the first submitted snapshot`() {
        val request = slot<BulkRequest>()
        every { client.bulk(capture(request)) } returns Mono.just(
            bulkResponse(
                errors = false,
                responseItem(index = "order.snapshot", id = "order-1", status = 200),
            )
        )

        ElasticsearchSnapshotBatchWriter(client, Refresh.False)
            .write(
                listOf(
                    save(version = 7, marker = "first"),
                    save(version = 7, marker = "second"),
                )
            )
            .test()
            .expectNext(listOf(BatchItemResult.Success, BatchItemResult.Success))
            .verifyComplete()

        request.captured.operations().single()
            .index<Map<String, Any?>>()
            .document()["marker"]
            .assert()
            .isEqualTo("first")
    }

    @Test
    fun `stale external version conflict should be treated as successful no-op`() {
        every { client.bulk(any<BulkRequest>()) } returns Mono.just(
            bulkResponse(
                errors = true,
                responseItem(
                    index = "order.snapshot",
                    id = "order-1",
                    status = 409,
                    errorType = "version_conflict_engine_exception",
                ),
            )
        )

        ElasticsearchSnapshotBatchWriter(client, Refresh.False)
            .write(listOf(save(version = 5)))
            .test()
            .expectNext(listOf(BatchItemResult.Success))
            .verifyComplete()
    }

    @Test
    fun `partial failure should be isolated and expanded only to matching aggregate callers`() {
        every { client.bulk(any<BulkRequest>()) } returns Mono.just(
            bulkResponse(
                errors = true,
                responseItem(
                    index = "order.snapshot",
                    id = "order-1",
                    status = 429,
                    errorType = "es_rejected_execution_exception",
                ),
                responseItem(index = "order.snapshot", id = "order-2", status = 200),
            )
        )

        ElasticsearchSnapshotBatchWriter(client, Refresh.False)
            .write(
                listOf(
                    save(id = "order-1", version = 5),
                    save(id = "order-2", version = 4),
                    save(id = "order-1", version = 6),
                )
            )
            .test()
            .assertNext { results ->
                results.assert().hasSize(3)
                val firstFailure = (results[0] as BatchItemResult.Failure).error
                firstFailure.assert().isInstanceOf(ElasticsearchBulkItemException::class.java)
                results[1].assert().isSameAs(BatchItemResult.Success)
                (results[2] as BatchItemResult.Failure).error.assert().isSameAs(firstFailure)
            }
            .verifyComplete()
    }

    @Test
    fun `batch request failure should remain terminal for the whole writer call`() {
        val failure = IllegalStateException("bulk unavailable")
        every { client.bulk(any<BulkRequest>()) } returns Mono.error(failure)

        ElasticsearchSnapshotBatchWriter(client, Refresh.False)
            .write(listOf(save()))
            .test()
            .expectErrorMatches { it === failure }
            .verify()
    }

    private fun save(
        index: String = "order.snapshot",
        id: String = "order-1",
        version: Int = 1,
        marker: String = version.toString(),
    ): ElasticsearchSnapshotSave {
        return ElasticsearchSnapshotSave(
            index = index,
            id = id,
            document = linkedMapOf(
                "version" to version,
                "marker" to marker,
            ),
            version = version,
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
            item.operationType(OperationType.Index)
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
