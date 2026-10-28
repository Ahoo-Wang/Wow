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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.observability.WowMetrics
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * The store combinations with Elasticsearch (DG-6): the log and the primary snapshots on MongoDB or PostgreSQL, a
 * copy of the snapshots and the events search on Elasticsearch. The events search Sink starts by itself, since
 * `query.events.store` names the Elasticsearch store; the snapshot queries (`query.snapshot.store`) and the events
 * queries are served from there; the commands load from the primary; and the Sink resumes from its checkpoint after a
 * restart.
 */
class ElasticsearchStoresTest {
    @ParameterizedTest
    @ValueSource(strings = [MONGO, POSTGRES])
    fun `a log beside Elasticsearch snapshots and events search should serve both, and resume its Sink`(log: String) {
        val prefix = "$log-${UUID.randomUUID().toString().take(SUFFIX)}"
        val id = startHost(*properties(log, prefix), sources = sources(log)).use { host ->
            // 1. Commands commit on the log.
            val opened = host.post("/bank/account/open-account", """{"owner":"es-$log"}""")
            opened.status.assert().isEqualTo(200)
            val id = opened.body!!["ref"]["id"].asString()
            host.post("/bank/account/$id/deposit", """{"amount":3}""").status.assert().isEqualTo(200)
            // 2. The snapshot query is served by Elasticsearch.
            host.eventually {
                host.post(
                    "/bank/account/snapshot/query",
                    """{"kind":"list","filter":{"op":"eq","field":"state.owner","value":"es-$log"}}""",
                ).body?.values()?.map { it["aggregateId"].asString() } == listOf(id)
            }
            // 3. The events query is served from the index the Sink writes, once it has written both commits.
            host.eventually { host.eventVersions(id) == listOf(1, 2) }
            // 4. The commands load from the primary, the log's store, and only the snapshot queries read
            // Elasticsearch. Without a state cache, each command reads the snapshot and its tail from the log's store:
            // PostgreSQL's log reads both in one round trip, and MongoDB's has no such read, so nothing falls back.
            host.context.getBean(HostBackends::class.java).snapshotStoreOf(ACCOUNT)!!.name.assert().isEqualTo(log)
            host.context.getBean(SnapshotQueryBackend::class.java).storeOf(ACCOUNT).assert().isEqualTo("es")
            host.context.getBean(MeterRegistry::class.java).find(WowMetrics.STORE_FALLBACKS.name)
                .tags(WowMetrics.CAPABILITY, ObservedEvent.StoreFellBack.Capability.SNAPSHOT_TAIL_READ.value)
                .counters().sumOf { it.count() }.assert().isZero()
            id
        }
        // 5. A new node resumes the Sink from its checkpoint and writes what it commits.
        startHost(*properties(log, prefix), sources = sources(log)).use { host ->
            host.post("/bank/account/$id/deposit", """{"amount":4}""").status.assert().isEqualTo(200)
            host.eventually { host.eventVersions(id) == listOf(1, 2, 3) }
        }
    }

    private fun Host.eventVersions(id: String): List<Int>? =
        post("/bank/account/events/query", """{"kind":"list","filter":{"op":"id","value":"$id"}}""")
            .takeIf { it.status == 200 }?.body?.values()?.map { it["version"].asInt() }?.sorted()

    private fun Host.eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in time." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun properties(log: String, prefix: String): Array<Pair<String, Any>> = arrayOf<Pair<String, Any>>(
        "wow.log.partitions" to 2,
        "wow.state-cache.capacity" to 0,
        "wow.stores.$log.type" to log,
        "wow.log.store" to log,
        "wow.stores.es.type" to "elasticsearch",
        "wow.stores.es.refresh" to "immediate",
        "wow.stores.es.index-prefix" to prefix,
        "wow.snapshot.stores" to "$log, es",
        "wow.aggregates.[$BANK.account].query.snapshot.store" to "es",
        "wow.aggregates.[$BANK.account].query.events.store" to "es",
    ) + when (log) {
        MONGO -> arrayOf<Pair<String, Any>>(
            "wow.stores.mongo.database" to "dg6_${prefix.replace('-', '_')}",
            "test.mongo.url" to WowContainers.mongoUrl,
        )
        else -> arrayOf<Pair<String, Any>>(
            "spring.datasource.url" to WowContainers.postgres.jdbcUrl,
            "spring.datasource.username" to WowContainers.postgres.username,
            "spring.datasource.password" to WowContainers.postgres.password,
            "spring.datasource.hikari.maximum-pool-size" to 8,
        )
    }

    private fun sources(log: String): List<Class<*>> = listOfNotNull(
        HostTestApp::class.java,
        ElasticsearchClientConfiguration::class.java,
        MongoClientConfiguration::class.java.takeIf { log == MONGO },
    )

    /** The application's Elasticsearch client, over the shared container, and a meter registry to read. */
    @Configuration(proxyBeanMethods = false)
    class ElasticsearchClientConfiguration {
        @Bean(destroyMethod = "close")
        fun elasticsearchClient(): Rest5Client =
            Rest5Client.builder(HttpHost.create("http://" + WowContainers.elasticsearch.httpHostAddress)).build()

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    private companion object {
        val ACCOUNT = AggregateType(BANK, "account")
        const val MONGO = "mongo"
        const val POSTGRES = "postgres"
        const val SUFFIX = 8
        const val TIMEOUT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 200L
    }
}
