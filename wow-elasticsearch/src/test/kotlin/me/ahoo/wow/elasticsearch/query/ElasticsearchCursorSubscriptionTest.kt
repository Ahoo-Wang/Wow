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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackend
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.requireAccepted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CompletableFuture

class ElasticsearchCursorSubscriptionTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val backend = ElasticsearchSnapshotQueryBackend(MaterializedNamedAggregate("test", "cursor"), client)
    private val id = QueryField("aggregateId")
    private val schema = QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        mapOf(
            id to QueryFieldSchema(
                title = null,
                description = null,
                enumValues = null,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                required = true,
                cardinality = QueryCardinality.SINGLE,
                semanticType = null,
                dynamicChildren = false,
                bindings = mapOf(QueryCapability.SORT to QueryFieldBinding(id, id, null)),
                rewriteMode = QueryRewriteMode.NONE,
            ),
        ),
    )

    @Test
    fun `cursor should defer search and create fresh results when repeated`() {
        every { client.search(any<SearchRequest>(), ObjectNode::class.java) } answers {
            Mono.fromFuture(CompletableFuture.completedFuture(response()))
        }
        val publisher = backend.cursor(resolved())
        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }

        val nodes = publisher.map { it.list.single() }
            .repeat(1)
            .index()
            .doOnNext { indexed -> if (indexed.t1 == 0L) indexed.t2.put("mutated", true) }
            .map { it.t2 }
            .collectList().block()!!
        nodes.assert().hasSize(2)
        nodes[1].assert().isNotSameAs(nodes[0])
        nodes[1].has("mutated").assert().isFalse()
        nodes.map { it.path("aggregateId").asString() }.assert().containsExactly("id-1", "id-1")
        verify(exactly = 2) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `cursor retry should issue a new request after request failure`() {
        val failure = IllegalStateException("first-request")
        var calls = 0
        every { client.search(any<SearchRequest>(), ObjectNode::class.java) } answers {
            val future = CompletableFuture<ResponseBody<ObjectNode>>()
            if (calls++ == 0) future.completeExceptionally(failure) else future.complete(response())
            Mono.fromFuture(future)
        }
        backend.cursor(resolved()).retry(1).test()
            .assertNext { it.list.single().path("aggregateId").asString().assert().isEqualTo("id-1") }
            .verifyComplete()
        calls.assert().isEqualTo(2)
    }

    @Test
    fun `cursor retry should discard a downstream mutation`() {
        every { client.search(any<SearchRequest>(), ObjectNode::class.java) } answers {
            Mono.fromFuture(CompletableFuture.completedFuture(response()))
        }
        val seen = mutableListOf<ObjectNode>()
        backend.cursor(resolved()).map { it.list.single() }
            .doOnNext { node ->
                seen += node
                if (seen.size == 1) {
                    node.put("mutated", true)
                    error("retry-once")
                }
            }.retry(1).test()
            .assertNext { node ->
                node.assert().isNotSameAs(seen.first())
                node.has("mutated").assert().isFalse()
            }.verifyComplete()
        verify(exactly = 2) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    @Test
    fun `cancelling one concurrent cursor subscription should not cancel another`() {
        val futures = mutableListOf<CompletableFuture<ResponseBody<ObjectNode>>>()
        every { client.search(any<SearchRequest>(), ObjectNode::class.java) } answers {
            val future = CompletableFuture<ResponseBody<ObjectNode>>().also(futures::add)
            Mono.fromFuture(future)
        }
        val publisher = backend.cursor(resolved())
        val first = publisher.subscribe()
        val nodes = mutableListOf<ObjectNode>()
        val failures = mutableListOf<Throwable>()
        publisher.subscribe({ nodes += it.list.single() }, failures::add)
        futures.assert().hasSize(2)
        first.dispose()
        futures[0].isCancelled.assert().isTrue()
        futures[1].isCancelled.assert().isFalse()
        futures[1].complete(response())
        failures.assert().isEmpty()
        nodes.single().path("aggregateId").asString().assert().isEqualTo("id-1")
    }

    @Test
    fun `invalid cursor should still fail while assembling the request`() {
        assertThrows<IllegalArgumentException> { backend.cursor(resolved("invalid!")) }
            .message.assert().isEqualTo("Invalid cursor.")
        verify(exactly = 0) { client.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    private fun resolved(cursor: String? = null): ResolvedQuery<ICursorQuery> = ResolvedQuery(
        schema.resolve(CursorQuery(MatchAllFilter, size = 1, cursor = cursor))
            .requireAccepted(QuerySchemaValidationMode.STRICT),
        schema,
    )

    private fun response(): ResponseBody<ObjectNode> = SearchResponse.of<ObjectNode> { response ->
        response.took(1).timedOut(false)
            .shards { it.failed(0).successful(1).total(1) }
            .hits { hits ->
                hits.hits { hit ->
                    hit.index(backend.indexName).id("id-1")
                        .source(JsonNodeFactory.instance.objectNode().put("aggregateId", "id-1"))
                        .sort(listOf(FieldValue.of("id-1")))
                }
            }
    }
}
