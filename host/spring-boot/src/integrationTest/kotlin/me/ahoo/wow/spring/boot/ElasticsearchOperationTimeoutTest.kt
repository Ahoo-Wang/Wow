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

package me.ahoo.wow.spring.boot

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.observability.WowMetrics
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import java.time.Duration
import java.util.UUID

/** The application's `Rest5Client`, built with the library's defaults: no response timeout of its own. */
@Configuration(proxyBeanMethods = false)
class UnboundedElasticsearchClient {
    @Bean(destroyMethod = "close")
    fun elasticsearchClient(): Rest5Client =
        Rest5Client.builder(HttpHost.create("http://" + ElasticsearchOperationTimeoutTest.address)).build()

    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * An Elasticsearch store bounds its requests itself (`operation-timeout`), whatever the application's client sets: with
 * the server paused, a command's snapshot load fails within the bound and the command commits from the log, and the
 * snapshot subscription's write fails, so the subscription pauses and retries it, until the server is back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElasticsearchOperationTimeoutTest {
    private val run: String = UUID.randomUUID().toString().take(SUFFIX)

    private val host: Host by lazy {
        startHost(
            "wow.log.partitions" to 2,
            "wow.state-cache.capacity" to 0,
            "wow.stores.mongo.type" to "mongo",
            "wow.stores.mongo.database" to "es_timeout_log_$run",
            "test.mongo.url" to WowContainers.mongoUrl,
            "wow.log.store" to "mongo",
            "wow.stores.es.type" to "elasticsearch",
            "wow.stores.es.index-prefix" to "timeout$run",
            "wow.stores.es.operation-timeout" to "PT2S",
            "wow.aggregates.[$BANK.account].snapshot.store" to "es",
            sources = listOf(
                HostTestApp::class.java,
                UnboundedElasticsearchClient::class.java,
                MongoClientConfiguration::class.java,
            ),
        )
    }

    @AfterAll
    fun close() {
        host.close()
        elasticsearch.stop()
    }

    @Test
    fun `a paused server should fail the store's requests within its operation timeout`() {
        val opened = host.post("/bank/account/open-account", """{"owner":"olga"}""")
        opened.status.assert().isEqualTo(OK)
        val id = opened.body!!["ref"]["id"].asString()
        host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(OK)

        elasticsearch.dockerClient.pauseContainerCmd(elasticsearch.containerId).exec()
        try {
            // 1. The command's load fails within the store's 2 seconds, and the command commits from the log.
            val started = System.nanoTime()
            val deposited = host.client.mutate().responseTimeout(Duration.ofSeconds(LONG_SECONDS)).build()
                .post().uri("/bank/account/$id/deposit")
                .header(WowHeaders.WAIT, "committed").header(WowHeaders.WAIT_TIMEOUT, "${LONG_SECONDS}s")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"amount":2}""")
                .answer()
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            deposited.status.assert().describedAs(deposited.body.toString()).isEqualTo(OK)
            deposited.body!!["version"].asInt().assert().isEqualTo(3)
            elapsed.assert().isLessThan(Duration.ofSeconds(BOUND_SECONDS))
            // 2. The snapshot subscription's write fails too, instead of waiting: it pauses its partition to retry.
            eventually { pausedNotices(WowMetrics.PAUSED_KIND) > 0 }
        } finally {
            elasticsearch.dockerClient.unpauseContainerCmd(elasticsearch.containerId).exec()
        }

        // 3. Back, the subscription's retry succeeds: it resumes and the store holds the commit.
        eventually { pausedNotices(WowMetrics.RESUMED_KIND) > 0 }
        eventually { host.get("/bank/account/$id/state").body?.get("state")?.get("balance")?.asLong() == 3L }
    }

    private fun pausedNotices(kind: String): Double =
        host.context.getBean(MeterRegistry::class.java).find(WowMetrics.PAUSED_NOTICES.name)
            .tag(WowMetrics.SUBSCRIPTION, "wow.snapshot:es").tag(WowMetrics.KIND, kind)
            .counters().sumOf { it.count() }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in time." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    companion object {
        private val elasticsearch = WowContainers.newElasticsearch().also { it.start() }

        /** The address of the test's own Elasticsearch, which it pauses. */
        val address: String get() = elasticsearch.httpHostAddress

        private const val OK = 200
        private const val SUFFIX = 8
        private const val LONG_SECONDS = 60L
        private const val BOUND_SECONDS = 6L
        private const val TIMEOUT_NANOS = 60_000_000_000L
        private const val POLL_MILLIS = 250L
    }
}
