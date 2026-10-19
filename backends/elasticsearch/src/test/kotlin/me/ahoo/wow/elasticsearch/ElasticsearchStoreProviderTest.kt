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
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.query.ElasticsearchQueryProvider
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.ServiceLoader
import java.util.concurrent.CompletableFuture

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
        ServiceLoader.load(FailureClassifier::class.java).any { it is ElasticsearchFailureClassifier }.assert().isTrue()
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
        assertThrownBy<UnsupportedOperationException> { store.timers("ctx") }
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
    fun `transient failures are a storage unavailable and systemic`() {
        val unavailable = elasticsearchException(503)
        val badRequest = elasticsearchException(400)
        ElasticsearchFailures.isTransient(unavailable).assert().isTrue()
        ElasticsearchFailures.isTransient(badRequest).assert().isFalse()
        ElasticsearchFailures.isTransient(IOException("reset")).assert().isTrue()
        ElasticsearchFailures.isTransient(IllegalStateException()).assert().isFalse()
        ElasticsearchFailures.translate(badRequest).assert().isSameAs(badRequest)
        ElasticsearchFailures.hasStatus(badRequest, 400).assert().isTrue()
        ElasticsearchFailures.hasStatus(IOException(), 400).assert().isFalse()
        val classifier = ElasticsearchFailureClassifier()
        classifier.classify(unavailable).assert().isEqualTo(FailureClass.SYSTEMIC)
        classifier.classify(mockk<TransportException>()).assert().isEqualTo(FailureClass.SYSTEMIC)
        classifier.classify(badRequest).assert().isNull()
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

    private fun elasticsearchException(status: Int) = ElasticsearchException(
        "es/test",
        ErrorResponse.of { response ->
            response.status(status).error(ErrorCause.of { it.type("test_exception").reason("test") })
        },
    )
}
