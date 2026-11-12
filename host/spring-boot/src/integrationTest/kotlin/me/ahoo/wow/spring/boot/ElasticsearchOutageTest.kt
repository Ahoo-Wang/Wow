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
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.observability.WowMetrics
import me.ahoo.wow.runtime.health.HealthStatus
import me.ahoo.wow.runtime.health.NodeHealth
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.util.Timeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/** A meter registry to read, for an outage's counted fallbacks. */
@Configuration(proxyBeanMethods = false)
class OutageMeters {
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/** The application's Elasticsearch client for an outage: a server that does not answer fails a call within seconds. */
@Configuration(proxyBeanMethods = false)
class OutageElasticsearchClient {
    @Bean(destroyMethod = "close")
    fun elasticsearchClient(): Rest5Client = Rest5Client.builder(
        HttpHost.create("http://" + ElasticsearchOutageTest.address)
    )
        .setConnectionConfigCallback {
            it.setConnectTimeout(Timeout.ofSeconds(1)).setSocketTimeout(Timeout.ofSeconds(2))
        }
        .setRequestConfigCallback {
            it.setConnectionRequestTimeout(Timeout.ofSeconds(1)).setResponseTimeout(Timeout.ofSeconds(2))
        }
        .build()
}

/**
 * Elasticsearch, the account's only snapshot store, stopping mid-run, the account's log on MongoDB: the same outage as
 * a MongoDB or PostgreSQL snapshot store's, its policy theirs. The store is on the write path, so the node is not ready
 * once it has kept failing. A command that still reaches the node commits, its load replaying the log (each failed
 * load counted, not logged), but its default wait times out; a state read fails, retryable; and once the store is
 * back the node is ready again and the snapshot subscription catches up by itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElasticsearchOutageTest {
    private val run: String = UUID.randomUUID().toString().take(SUFFIX)

    private val host: Host by lazy {
        startHost(
            "wow.log.partitions" to 2,
            "wow.state-cache.capacity" to 0,
            "wow.stores.mongo.type" to "mongo",
            "wow.stores.mongo.database" to "outage_log_$run",
            "test.mongo.url" to WowContainers.mongoUrl,
            "wow.log.store" to "mongo",
            "wow.stores.es.type" to "elasticsearch",
            "wow.stores.es.index-prefix" to "outage$run",
            "wow.aggregates.[$BANK.account].snapshot.store" to "es",
            "wow.health.stores.down-after" to "PT2S",
            "wow.node.sample-interval" to "PT1S",
            "wow.wait.default-timeout" to "PT3S",
            sources = listOf(
                HostTestApp::class.java,
                OutageElasticsearchClient::class.java,
                OutageMeters::class.java,
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
    fun `a type's own snapshot store stopping should take the node out of service until it is back`() {
        val opened = host.post("/bank/account/open-account", """{"owner":"olga"}""")
        opened.status.assert().isEqualTo(OK)
        val id = opened.body!!["ref"]["id"].asString()
        host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(OK)
        readiness().assert().isEqualTo(HealthStatus.UP)

        elasticsearch.dockerClient.pauseContainerCmd(elasticsearch.containerId).exec()
        try {
            // 1. A command still commits: its load replays the log. Its default wait, for the snapshot, times out.
            val deposited = host.post("/bank/account/$id/deposit", """{"amount":2}""")
            deposited.status.assert().describedAs(deposited.body.toString()).isEqualTo(TIMED_OUT)
            deposited.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.WAIT_TIMEOUT)
            deposited.body!!["result"]["version"].asInt().assert().describedAs(deposited.body.toString()).isEqualTo(3)
            deposited.body!!["result"]["hop"]["stage"].asString().assert().isEqualTo("committed")
            // Its load's failure is counted, not logged at every miss.
            host.context.getBean(MeterRegistry::class.java).find(WowMetrics.STORE_FALLBACKS.name)
                .tags(WowMetrics.CAPABILITY, ObservedEvent.StoreFellBack.Capability.SNAPSHOT_LOAD.value)
                .counters().sumOf { it.count() }.assert().isPositive()
            // 2. The store is on the write path: once it has kept failing, the node is not ready.
            eventually { readiness() == HealthStatus.DOWN }
            host.context.getBean(NodeHealth::class.java).readiness().components
                .single { it.name == "stores" }.components.single { it.name == "store:es" }.status
                .assert().isEqualTo(HealthStatus.DOWN)
            // 3. A state read fails with the store, retryable.
            val state = host.get("/bank/account/$id/state")
            state.status.assert().isEqualTo(UNAVAILABLE)
            state.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        } finally {
            elasticsearch.dockerClient.unpauseContainerCmd(elasticsearch.containerId).exec()
        }

        // 4. Back, the node is ready again, the snapshot subscription catches up, and the default wait ends there.
        eventually { readiness() == HealthStatus.UP }
        eventually { host.get("/bank/account/$id/state").body?.get("state")?.get("balance")?.asLong() == 3L }
        val after = host.post("/bank/account/$id/deposit", """{"amount":1}""")
        after.status.assert().isEqualTo(OK)
        after.body!!["hop"]["subscription"].asString().assert().isEqualTo("wow.snapshot")
        host.get("/bank/account/$id/state").body!!["state"]["balance"].asLong().assert().isEqualTo(4L)
    }

    private fun readiness(): HealthStatus = host.context.getBean(NodeHealth::class.java).readiness().status

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
        private const val UNAVAILABLE = 503
        private const val TIMED_OUT = 504
        private const val SUFFIX = 8
        private const val TIMEOUT_NANOS = 60_000_000_000L
        private const val POLL_MILLIS = 250L
    }
}
