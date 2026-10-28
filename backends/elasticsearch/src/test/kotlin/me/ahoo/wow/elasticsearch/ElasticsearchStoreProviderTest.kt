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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorCause
import co.elastic.clients.elasticsearch._types.ErrorResponse
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.transport.TransportException
import co.elastic.clients.transport.rest5_client.low_level.Response
import co.elastic.clients.transport.rest5_client.low_level.ResponseException
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.query.ElasticsearchQueryProvider
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.ServiceLoader
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ElasticsearchStoreProviderTest {
    private val restClient: Rest5Client = Rest5Client.builder(HttpHost.create("http://localhost:1")).build()

    private fun config(options: Map<String, String> = emptyMap()) = StoreConfig.of(
        name = "search",
        type = ElasticsearchStoreProvider.TYPE,
        options = options,
        resources = StoreResources.of(mapOf("elasticsearch" to restClient)),
    )

    @Test
    fun `the providers are found on the classpath`() {
        ServiceLoader.load(StoreProvider::class.java).map { it.type }.assert().contains(ElasticsearchStoreProvider.TYPE)
        ServiceLoader.load(
            StoreQueryProvider::class.java
        ).map { it.type }.assert().contains(ElasticsearchStoreProvider.TYPE)
    }

    @Test
    fun `a store holds snapshots, never a log`() {
        val provider = ElasticsearchStoreProvider()
        provider.available(StoreResources.of(mapOf("elasticsearch" to restClient))).assert().isFalse()
        provider.requirement.assert().contains("Rest5Client")
        val store = provider.open(
            config(mapOf(ElasticsearchStoreProvider.INDEX_PREFIX to "Prod-A"))
        ) as ElasticsearchStore
        store.holdsLogs.assert().isFalse()
        store.holdsSnapshots.assert().isTrue()
        store.location.assert().isSameAs(restClient)
        store.refresh.assert().isEqualTo(Refresh.WaitFor)
        store.names.snapshots("ctx", "Order").assert().isEqualTo("prod_a_wow_snapshot__ctx__order")
        store.snapshots("ctx").assert().isNotNull()
        assertThrownBy<UnsupportedOperationException> { store.log("ctx", 4) }
        assertThrownBy<UnsupportedOperationException> { store.timers("ctx", 4) }
        assertThrownBy<UnsupportedOperationException> { store.checkpoints }
        assertThrownBy<UnsupportedOperationException> { store.leases }
        assertThrownBy<UnsupportedOperationException> { store.ledger }
        assertThrownBy<UnsupportedOperationException> { store.reservations }
        store.createSchemas()
        ElasticsearchQueryProvider().snapshots(store, StoreQueryOptions.DEFAULT)
            .binding(
                AggregateType("ctx", "order")
            ).backend.aggregateType.assert().isEqualTo(AggregateType("ctx", "order"))
    }

    @Test
    fun `the refresh option names a policy`() {
        ElasticsearchStoreProvider.refreshOf(null).assert().isEqualTo(Refresh.WaitFor)
        ElasticsearchStoreProvider.refreshOf("wait_for").assert().isEqualTo(Refresh.WaitFor)
        ElasticsearchStoreProvider.refreshOf("TRUE").assert().isEqualTo(Refresh.True)
        ElasticsearchStoreProvider.refreshOf("false").assert().isEqualTo(Refresh.False)
        assertThrownBy<IllegalArgumentException> { ElasticsearchStoreProvider.refreshOf("sometimes") }
        val store = ElasticsearchStoreProvider().open(config(mapOf(ElasticsearchStoreProvider.REFRESH to "true")))
        (store as ElasticsearchStore).refresh.assert().isEqualTo(Refresh.True)
    }

    @Test
    fun `names follow the rule of every backend`() {
        val names = ElasticsearchNames()
        names.snapshots("Order-Context", "Order").assert().isEqualTo("wow_snapshot__order_context__order")
        names.events("ctx", "order").assert().isEqualTo("wow_events__ctx__order")
        names.snapshotTables.assert().isEqualTo("wow_snapshot_table")
        val long = names.snapshots("c".repeat(40), "a".repeat(40))
        long.length.assert().isEqualTo(ElasticsearchNames.MAX_LENGTH)
        long.assert().isNotEqualTo(names.snapshots("c".repeat(40), "a".repeat(39) + "b"))
        ElasticsearchNames.documentId(AggregateType("ctx", "order").ref("t:1", "a")).assert().isEqualTo("3:t:1:a")
    }

    @Test
    fun `transient failures are a storage unavailable, a refused document a storage refusal`() {
        val unavailable = elasticsearchException(503)
        val badRequest = elasticsearchException(400)
        ElasticsearchFailures.isTransient(unavailable).assert().isTrue()
        ElasticsearchFailures.isTransient(badRequest).assert().isFalse()
        ElasticsearchFailures.isTransient(IOException("reset")).assert().isTrue()
        ElasticsearchFailures.isTransient(IllegalStateException()).assert().isFalse()
        ElasticsearchFailures.translate(badRequest).assert().isSameAs(badRequest)
        ElasticsearchFailures.hasStatus(badRequest, 400).assert().isTrue()
        ElasticsearchFailures.hasStatus(IOException(), 400).assert().isFalse()
        ElasticsearchFailures.isTransient(mockk<TransportException>()).assert().isTrue()
        ElasticsearchFailures.itemFailure(503, "the snapshot [s]", "no master").assert()
            .isInstanceOf(StorageUnavailableException::class.java)
        ElasticsearchFailures.itemFailure(400, "the snapshot [s]", "mapper_parsing_exception").assert()
            .isInstanceOf(StorageRefusedException::class.java)
            .hasMessage("Elasticsearch answered 400 for the snapshot [s]: mapper_parsing_exception")
        ElasticsearchFailures.itemFailure(500, "the snapshot [s]", "boom").assert()
            .isInstanceOf(IllegalStateException::class.java)
        assertThrownBy<StorageUnavailableException> {
            runBlocking { CompletableFuture.failedFuture<Unit>(unavailable).awaitElasticsearch() }
        }
        assertThrownBy<StorageUnavailableException> {
            runBlocking { CompletableFuture.failedFuture<Unit>(IOException("refused")).awaitElasticsearch() }
        }
        assertThrownBy<ElasticsearchException> {
            runBlocking { CompletableFuture.failedFuture<Unit>(badRequest).awaitElasticsearch() }
        }
    }

    @Test
    fun `a shard not ready yet is retried, any other failure is not`(): Unit = runBlocking {
        val recovering = StorageUnavailableException(
            "recovering",
            elasticsearchException(503, "no_shard_available_action_exception", "illegal_index_shard_state_exception"),
        )
        ElasticsearchFailures.isShardNotReady(recovering).assert().isTrue()
        ElasticsearchFailures.isShardNotReady(
            elasticsearchException(503, "unavailable_shards_exception")
        ).assert().isTrue()
        ElasticsearchFailures.isShardNotReady(elasticsearchException(503)).assert().isFalse()
        ElasticsearchFailures.isShardNotReady(
            elasticsearchException(503, "search_phase_execution_exception", "illegal_index_shard_state_exception"),
        ).assert().isTrue()
        ElasticsearchFailures.isShardNotReady(
            elasticsearchException(400, "no_shard_available_action_exception")
        ).assert().isFalse()
        // A real-time GET's answer reaches the client as the low-level ResponseException, its body in the message.
        ElasticsearchFailures.isShardNotReady(
            StorageUnavailableException("recovering", responseException(503, "no_shard_available_action_exception")),
        ).assert().isTrue()
        ElasticsearchFailures.isShardNotReady(responseException(503, "search_phase_execution_exception")).assert()
            .isFalse()
        ElasticsearchFailures.isShardNotReady(responseException(500, "illegal_index_shard_state_exception")).assert()
            .isFalse()
        var calls = 0
        whenShardReady(timeout = 10.seconds) {
            calls++
            if (calls < 3) throw recovering
            "ready"
        }.assert().isEqualTo("ready")
        calls.assert().isEqualTo(3)
        calls = 0
        assertThrownBy<StorageUnavailableException> {
            runBlocking {
                // Pauses of 100 ms then 200 ms: the second would end past the budget.
                whenShardReady(timeout = 250.milliseconds) {
                    calls++
                    throw recovering
                }
            }
        }
        calls.assert().isIn(1, 2)
        calls = 0
        val other = StorageUnavailableException("down", elasticsearchException(503))
        assertThrownBy<StorageUnavailableException> {
            runBlocking {
                whenShardReady(timeout = 10.seconds) {
                    calls++
                    throw other
                }
            }
        }
        calls.assert().isEqualTo(1)
    }

    private fun responseException(status: Int, type: String): ResponseException {
        val response = mockk<Response> { every { statusCode } returns status }
        return mockk<ResponseException> {
            every { this@mockk.response } returns response
            every { message } returns """status line [$status] {"error":{"root_cause":[{"type":"$type"}]},"status":$status}"""
            every { cause } returns null
        }
    }

    private fun elasticsearchException(status: Int, type: String = "test_exception", rootType: String? = null) =
        ElasticsearchException(
            "es/test",
            ErrorResponse.of { response ->
                response.status(status).error(
                    ErrorCause.of { cause ->
                        cause.type(type).reason("test")
                        rootType?.let { root -> cause.rootCause(ErrorCause.of { it.type(root).reason("root") }) }
                        cause
                    },
                )
            },
        )
}
