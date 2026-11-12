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

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.observability.WowMetrics
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import java.time.Duration
import java.util.UUID
import kotlin.concurrent.thread

/** The snapshot store's `MongoClient`, with the driver's defaults, on a server of its own; and a meter registry. */
@Configuration(proxyBeanMethods = false)
class HeldSnapshotsClient {
    @Bean(destroyMethod = "close")
    fun snapshotMongoClient(): MongoClient = MongoClients.create(SnapshotLoadTimeoutTest.url)

    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * A command's cache-miss load waits at most `wow.snapshot.load-timeout`: with its snapshot store paused, on a server
 * of its own and over a client with the driver's defaults (whose reads the store bounds at 30 seconds), the command
 * commits from the log after the timeout and its replay, and gives its slot of the node's bound back then, so a
 * paused snapshot store refuses no command for the 30 seconds the store would hold its read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotLoadTimeoutTest {
    private val run: String = UUID.randomUUID().toString().take(SUFFIX)

    private val host: Host by lazy {
        startHost(
            "wow.log.partitions" to 2,
            "wow.state-cache.capacity" to 0,
            "wow.executor.max-in-flight" to 1,
            "wow.snapshot.load-timeout" to "PT1S",
            "wow.stores.mongo.type" to "mongo",
            "wow.stores.mongo.client" to "mongoClient",
            "wow.stores.mongo.database" to "load_timeout_log_$run",
            "test.mongo.url" to WowContainers.mongoUrl,
            "wow.log.store" to "mongo",
            "wow.stores.snap.type" to "mongo",
            "wow.stores.snap.client" to "snapshotMongoClient",
            "wow.stores.snap.database" to "load_timeout_snapshots_$run",
            "wow.aggregates.[$BANK.account].snapshot.store" to "snap",
            sources = listOf(
                HostTestApp::class.java,
                MongoClientConfiguration::class.java,
                HeldSnapshotsClient::class.java
            ),
        )
    }

    private val long by lazy { host.client.mutate().responseTimeout(Duration.ofSeconds(LONG_SECONDS)).build() }

    @AfterAll
    fun close() {
        host.close()
        mongo.stop()
    }

    private fun deposit(id: String): Answer = long.post().uri("/bank/account/$id/deposit")
        .header(WowHeaders.WAIT, "committed").header(WowHeaders.WAIT_TIMEOUT, "${LONG_SECONDS}s")
        .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"amount":1}""")
        .answer()

    private fun open(): String {
        val opened = host.post("/bank/account/open-account", """{"owner":"olga"}""")
        opened.status.assert().isEqualTo(OK)
        return opened.body!!["ref"]["id"].asString()
    }

    @Test
    fun `a held load should fail at its timeout, and give the node's only slot back then`() {
        val first = open()
        val second = open()
        val third = open()
        mongo.dockerClient.pauseContainerCmd(mongo.containerId).exec()
        try {
            // 1. The first deposit takes the node's only slot, its load held by the paused store.
            val started = System.nanoTime()
            var held: Answer? = null
            var heldFor = Duration.ZERO
            val sending = thread {
                held = deposit(first)
                heldFor = Duration.ofNanos(System.nanoTime() - started)
            }
            Thread.sleep(BUSY_AFTER_MILLIS)
            // 2. Meanwhile the node is full: the bound is real.
            val refused = host.post("/bank/account/$second/deposit", """{"amount":1}""")
            refused.status.assert().describedAs(refused.body.toString()).isEqualTo(BUSY)
            refused.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_BUSY)
            sending.join()
            // 3. Its load failed at the timeout, a second, and it committed from the log right after.
            held!!.status.assert().describedAs(held.body.toString()).isEqualTo(OK)
            held!!.body!!["version"].asInt().assert().isEqualTo(2)
            heldFor.assert().isLessThan(Duration.ofSeconds(BOUND_SECONDS))
            // 4. Its slot is free: the next command is taken, not refused, and commits the same way.
            val next = deposit(third)
            next.status.assert().describedAs(next.body.toString()).isEqualTo(OK)
            timedOut().assert().isGreaterThanOrEqualTo(2.0)
        } finally {
            mongo.dockerClient.unpauseContainerCmd(mongo.containerId).exec()
        }
    }

    private fun timedOut(): Double = host.context.getBean(MeterRegistry::class.java)
        .find(WowMetrics.STORE_FALLBACKS.name)
        .tag(WowMetrics.CAPABILITY, ObservedEvent.StoreFellBack.Capability.SNAPSHOT_LOAD.value)
        .tag(WowMetrics.REASON, ObservedEvent.StoreFellBack.Reason.TIMED_OUT.value)
        .counters().sumOf { it.count() }

    companion object {
        private val mongo = WowContainers.newMongo().also { it.start() }

        /** The address of the test's own MongoDB, which it pauses. */
        val url: String get() = mongo.replicaSetUrl

        private const val OK = 200
        private const val BUSY = 429
        private const val SUFFIX = 8
        private const val LONG_SECONDS = 60L
        private const val BOUND_SECONDS = 4L
        private const val BUSY_AFTER_MILLIS = 300L
    }
}
