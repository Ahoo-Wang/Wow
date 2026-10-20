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


package me.ahoo.wow.elasticsearch.events

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.ErrorCause
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.BulkResponse
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CompletableFuture

class ElasticsearchEventSinkTest {
    private val type = AggregateType("shop", "order")
    private val client = mockk<ElasticsearchAsyncClient>()
    private val sink = ElasticsearchEventSink(client, ElasticsearchNames("p"), type, StoreDdl.NONE)

    private fun record(id: String, version: Int, operator: Operator? = null) = StateEventRecord.of(
        type.ref("tenant", id),
        version,
        commandId = "c-$id-$version",
        requestId = "r-$id-$version",
        commandType = "shop.order.Place",
        commitTime = 1_000L + version,
        events = listOf(
            StateEventPayload.of("shop.order.Placed", 1, WowJson.mapper.readTree("""{"amount":1.10}""")),
        ),
        scope = Scope("owner", null),
        operator = operator,
    )

    private fun item(id: String, status: Int, error: String? = null) = BulkResponseItem.of {
        it.operationType(OperationType.Create).index(sink.index).id(id).status(status)
        error?.let { type -> it.error(ErrorCause.of { cause -> cause.type(type).reason("$type reason") }) }
        it
    }

    private fun answer(vararg items: BulkResponseItem) {
        every { client.bulk(any<BulkRequest>()) } returns CompletableFuture.completedFuture(
            BulkResponse.of { it.errors(items.any { item -> item.error() != null }).took(1).items(items.toList()) },
        )
    }

    @Test
    fun `a batch is one bulk request of creates by the commit's identity`(): Unit = runBlocking {
        val request = slot<BulkRequest>()
        every { client.bulk(capture(request)) } returns CompletableFuture.completedFuture(
            BulkResponse.of { it.errors(false).took(1).items(emptyList()) },
        )
        sink.write(listOf(record("o1", 1), record("o1", 2)))
        sink.index.assert().isEqualTo("p_wow_events__shop__order")
        request.captured.index().assert().isEqualTo("p_wow_events__shop__order")
        request.captured.refresh().assert().isNull()
        request.captured.operations().map { it.create<ObjectNode>().id() }.assert()
            .containsExactly("6:tenant:o1@1", "6:tenant:o1@2")
        sink.toString().assert().contains("p_wow_events__shop__order")
    }

    @Test
    fun `an empty batch sends nothing`(): Unit = runBlocking {
        sink.write(emptyList())
        verify(exactly = 0) { client.bulk(any<BulkRequest>()) }
    }

    @Test
    fun `a record of another type is refused`() {
        val other = StateEventRecord.of(
            AggregateType("shop", "cart").ref("tenant", "c1"),
            1,
            commandId = "c",
            requestId = "r",
            commandType = "shop.cart.Add",
            commitTime = 1,
            events = listOf(StateEventPayload.of("shop.cart.Added", 1, WowJson.mapper.createObjectNode())),
        )
        assertThrownBy<IllegalArgumentException> { runBlocking { sink.write(listOf(other)) } }
    }

    @Test
    fun `a record written before counts as written`(): Unit = runBlocking {
        answer(item("6:tenant:o1@1", 409, "version_conflict_engine_exception"), item("6:tenant:o1@2", 201))
        sink.write(listOf(record("o1", 1), record("o1", 2)))
    }

    @Test
    fun `a record the cluster cannot take now fails the write as retryable`() {
        answer(item("6:tenant:o1@1", 201), item("6:tenant:o1@2", 429, "es_rejected_execution_exception"))
        assertThrownBy<StorageUnavailableException> {
            runBlocking { sink.write(listOf(record("o1", 1), record("o1", 2))) }
        }.hasMessageContaining("1 of 2").hasMessageContaining("es_rejected_execution_exception")
    }

    @Test
    fun `a record the cluster refuses fails the write`() {
        answer(item("6:tenant:o1@1", 400, "document_parsing_exception"))
        assertThrownBy<IllegalStateException> { runBlocking { sink.write(listOf(record("o1", 1))) } }
            .hasMessageContaining("document_parsing_exception")
    }

    @Test
    fun `a document is the events model's record`() {
        val document = ElasticsearchEventDocuments.document(record("o1", 1, Operator("u", "tenant", true)))
        document.path("aggregateId").stringValue().assert().isEqualTo("o1")
        document.path("spaceId").isNull.assert().isTrue()
        document.path("operator").path("crossScope").booleanValue().assert().isTrue()
        document.path("events")[0].path("body").path("amount").decimalValue().toPlainString().assert().isEqualTo("1.10")
        ElasticsearchEventDocuments.record(document).propertyNames().toList().assert()
            .isEqualTo(ElasticsearchEventDocuments.RECORD_FIELDS)
        val anonymous = ElasticsearchEventDocuments.document(record("o1", 2))
        anonymous.path("operator").isNull.assert().isTrue()
        anonymous.remove("events")
        ElasticsearchEventDocuments.record(anonymous).has("events").assert().isFalse()
    }

    @Test
    fun `the sinks of a store share its client and names`() {
        val restClient = Rest5Client.builder(HttpHost.create("http://localhost:1")).build()
        val store = ElasticsearchStoreProvider().open(
            StoreConfig.of(
                name = "search",
                type = ElasticsearchStoreProvider.TYPE,
                options = mapOf(ElasticsearchStoreProvider.INDEX_PREFIX to "x"),
                resources = StoreResources.of(mapOf("elasticsearch" to restClient)),
            ),
        )
        val sinks = ElasticsearchEventSinks.of(store)
        sinks.sinkOf("shop", "order").assert().isSameAs(sinks.sinkOf("shop", "order"))
        sinks.sinkOf("shop", "order").index.assert().isEqualTo("x_wow_events__shop__order")
        sinks.toString().assert().contains("search")
        assertThrownBy<IllegalArgumentException> { ElasticsearchEventSinks.of(mockk<Store>(relaxed = true)) }
    }

    @Test
    fun `a write waits for no refresh unless the store refreshes at once`() {
        ElasticsearchEventSinks.sinkRefresh(Refresh.WaitFor).assert().isNull()
        ElasticsearchEventSinks.sinkRefresh(Refresh.False).assert().isNull()
        ElasticsearchEventSinks.sinkRefresh(Refresh.True).assert().isEqualTo(Refresh.True)
    }
}
