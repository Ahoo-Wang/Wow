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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorResponse
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.Hit
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CompletableFuture

/**
 * A missing index answers no rows only when the search itself finds no index: a failure of whoever collects the
 * rows, an `index_not_found_exception` of another index included, reaches that collector unchanged.
 */
internal class ElasticsearchMissingIndexTest {
    private fun failure(type: String = "index_not_found_exception") = ElasticsearchException(
        "other-index",
        ErrorResponse.of { it.status(404).error { error -> error.type(type).reason("no such index [other-index]") } },
    )

    /** A backend over an index of two documents, or whose every search fails with [upstream]. */
    private class Fixture(upstream: Throwable? = null) {
        val client = mockk<ElasticsearchAsyncClient>()
        private val documents = object : ElasticsearchQueryDocuments {
            override val tieBreakers = emptyList<String>()
            override val storeFields = emptyList<String>()
            override fun record(source: ObjectNode): ObjectNode = source
        }
        private val backend =
            ElasticsearchQueryBackend(AggregateType("shop", "cart"), client, "source-index", documents)

        init {
            val hits = listOf("a", "b").map { id ->
                Hit.of<ObjectNode> {
                    it.index("source-index").id(id).source(WowJson.mapper.createObjectNode().put("id", id))
                }
            }
            val response = SearchResponse.of<ObjectNode> {
                it.took(0).timedOut(false).pitId("pit-2")
                    .shards { shards -> shards.total(1).successful(1).failed(0) }.hits { page -> page.hits(hits) }
            }
            every { client.search(any<SearchRequest>(), ObjectNode::class.java) } returns
                if (upstream == null) {
                    CompletableFuture.completedFuture(response)
                } else {
                    CompletableFuture.failedFuture(upstream)
                }
            every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns CompletableFuture.completedFuture(
                OpenPointInTimeResponse.of { it.id("pit-1").shards { shards -> shards.total(1).successful(1).failed(0) } },
            )
            every { client.closePointInTime(any<ClosePointInTimeRequest>()) } returns
                CompletableFuture.completedFuture(ClosePointInTimeResponse.of { it.succeeded(true).numFreed(1) })
        }

        /** A bounded list is one search; an unbounded one pages over a point in time. */
        fun rows(pit: Boolean = false, provisional: Boolean = false): Flow<ObjectNode> {
            val query = ListQuery(limit = if (pit) null else 2)
            return backend.stream(QueryAdmission.Trusted.list(query, nativeSchema(provisional = provisional)))
        }
    }

    private fun collectorFails(failure: Throwable, pit: Boolean = false) = runBlocking {
        val fixture = Fixture()
        var read = 0
        val caught = runCatching {
            fixture.rows(pit).collect {
                read++
                throw failure
            }
        }.exceptionOrNull()
        read.assert().isEqualTo(1)
        caught.assert().isSameAs(failure)
        if (pit) {
            verify(exactly = 1) {
                fixture.client.closePointInTime(match<ClosePointInTimeRequest> { it.id() == "pit-2" })
            }
        }
    }

    @Test
    fun `a bounded list passes on its collector's missing-index failure`() {
        collectorFails(failure())
    }

    @Test
    fun `a list over a point in time passes on its collector's missing-index failure and closes the point in time`() {
        collectorFails(failure(), pit = true)
    }

    @Test
    fun `a list passes on its collector's other failures`() {
        collectorFails(failure("document_missing_exception"))
        collectorFails(IllegalStateException("collector failed"))
    }

    @Test
    fun `a missing source index answers no rows`(): Unit = runBlocking {
        Fixture(failure()).rows().toList().assert().isEmpty()
        Fixture(failure()).rows(pit = true).toList().assert().isEmpty()
    }

    @Test
    fun `a search failure other than a missing index fails the list`(): Unit = runBlocking {
        val shards = failure("search_phase_execution_exception")
        runCatching { Fixture(shards).rows().toList() }.exceptionOrNull().assert().isSameAs(shards)
        val unreachable = IllegalStateException("connection refused")
        runCatching { Fixture(unreachable).rows().toList() }.exceptionOrNull().assert().isSameAs(unreachable)
    }

    @Test
    fun `a list admitted against the provisional schema of a missing index answers no rows while the index is missing`(): Unit =
        runBlocking {
            Fixture(failure()).rows(provisional = true).toList().assert().isEmpty()
        }

    @Test
    fun `a list admitted against the provisional schema of a missing index is refused once the index exists`(): Unit =
        runBlocking {
            runCatching { Fixture().rows(provisional = true).toList() }.exceptionOrNull()
                .assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
        }

    @Test
    fun `an index that answers yields every row`(): Unit = runBlocking {
        Fixture().rows().toList().map { it.path("id").stringValue() }.assert().containsExactly("a", "b")
    }
}
