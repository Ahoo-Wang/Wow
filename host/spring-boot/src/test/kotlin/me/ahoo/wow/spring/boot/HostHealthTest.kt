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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.runtime.health.ComponentHealth
import me.ahoo.wow.runtime.health.HealthStatus
import me.ahoo.wow.runtime.health.NodeHealth
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.health.contributor.Status
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The node's health in Actuator (review R3.5-3): `/actuator/health/readiness` fails while a store or the lease store
 * is down and recovers with it, `/actuator/health/liveness` stays up through a storage outage, and `wow` reports
 * every component.
 */
class HostHealthTest {
    @Test
    fun `the probe groups should include the node's readiness and liveness`() {
        startHost(*DETAILS).use { host ->
            val readiness = host.eventually("/actuator/health/readiness", 200)
            readiness.body!!["status"].asString().assert().isEqualTo("UP")
            readiness.body["components"].propertyNames().assert().containsExactlyInAnyOrder(
                "readinessState",
                "wowReadiness"
            )
            readiness.body["components"]["wowReadiness"]["details"]["stores"]["status"].asString().assert()
                .isEqualTo("UP")
            val liveness = host.get("/actuator/health/liveness")
            liveness.status.assert().isEqualTo(200)
            liveness.body!!["components"].propertyNames().assert().containsExactlyInAnyOrder(
                "livenessState",
                "wowLiveness"
            )
            val wow = host.get("/actuator/health/wow")
            wow.status.assert().isEqualTo(200)
            wow.body!!["components"].propertyNames().assert().containsExactlyInAnyOrder(
                "node",
                "ownership",
                "stores",
                "topics",
                "subscriptions",
                "executor",
                "liveness",
                "queries",
            )
            val queries = wow.body["components"]["queries"]
            queries["status"].asString().assert().isEqualTo("UP")
            val store = queries["details"][ContextBackends.DEFAULT_STORE]
            store["limit"].asInt().assert().isEqualTo(StoreQueryProvider.DEFAULT_MAX_CONCURRENT)
            store["adaptive"].asBoolean().assert().isTrue()
            store["pressure"].asBoolean().assert().isFalse()
            store["decreases"].asLong().assert().isZero()
            host.context.getBean(NodeHealth::class.java).options.storeDownAfter.assert().isEqualTo(Duration.ZERO)
        }
    }

    @Test
    fun `a probe should show only the status unless the application asks for the details`() {
        startHost().use { host ->
            val readiness = host.eventually("/actuator/health/readiness", 200)
            readiness.body!!.propertyNames().assert().containsExactly("status")
        }
    }

    @Test
    fun `a store that is down should fail the readiness and leave the liveness up, until it recovers`() {
        startHost(
            *DETAILS,
            sources = listOf(HostTestApp::class.java, FlakyStoresConfiguration::class.java)
        ).use { host ->
            host.context.getBean(FlakyStores::class.java).assert().isSameAs(FlakyStoresConfiguration.STORES)
            val flaky = FlakyStoresConfiguration.STORES
            host.eventually("/actuator/health/readiness", 200)

            flaky.logDown = true
            val down = host.eventually("/actuator/health/readiness", 503)
            down.body!!["components"]["wowReadiness"]["details"]["stores"]["status"].asString().assert()
                .isEqualTo("DOWN")
            host.get("/actuator/health/liveness").status.assert().isEqualTo(200)
            flaky.logDown = false
            host.eventually("/actuator/health/readiness", 200)

            flaky.leasesDown = true
            host.eventually("/actuator/health/readiness", 503).body!!["components"]["wowReadiness"]["details"]
                .get("ownership")["status"].asString().assert().isEqualTo("DOWN")
            host.get("/actuator/health/liveness").status.assert().isEqualTo(200)
            flaky.leasesDown = false
            host.eventually("/actuator/health/readiness", 200)
        }
    }

    @Test
    fun `a host without the node's health should keep Actuator's own groups`() {
        startHost("wow.health.enabled" to false, *DETAILS).use { host ->
            host.context.getBeansOfType(NodeHealth::class.java).assert().isEmpty()
            host.eventually("/actuator/health/readiness", 200)
            host.get("/actuator/health").body!!["components"].propertyNames().assert()
                .doesNotContain("wow", "wowReadiness", "wowLiveness")
        }
    }

    @Test
    fun `the groups should be defaults the application replaces`() {
        val processor = HealthGroupsEnvironmentPostProcessor()
        val application = SpringApplication()
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            org.springframework.core.env.MapPropertySource(
                "app",
                mapOf("management.endpoint.health.group.readiness.include" to "readinessState,db"),
            ),
        )
        processor.postProcessEnvironment(environment, application)
        environment.getProperty("management.endpoint.health.group.readiness.include").assert()
            .isEqualTo("readinessState,db")
        environment.getProperty("management.endpoint.health.group.liveness.include").assert()
            .isEqualTo("livenessState,wowLiveness")

        listOf(
            HealthGroupsEnvironmentPostProcessor.ENABLED to "false",
            HealthGroupsEnvironmentPostProcessor.PROBES to "false",
        ).forEach { (key, value) ->
            val off = StandardEnvironment()
            off.propertySources.addFirst(org.springframework.core.env.MapPropertySource("app", mapOf(key to value)))
            processor.postProcessEnvironment(off, application)
            off.propertySources.contains(HealthGroupsEnvironmentPostProcessor.SOURCE).assert().isFalse()
        }
        val all = StandardEnvironment()
        all.propertySources.addFirst(
            org.springframework.core.env.MapPropertySource("app", HealthGroupsEnvironmentPostProcessor.DEFAULTS),
        )
        processor.postProcessEnvironment(all, application)
        all.propertySources.contains(HealthGroupsEnvironmentPostProcessor.SOURCE).assert().isFalse()
    }

    @Test
    fun `a degraded component should be Actuator's DEGRADED, its parts in its details`() {
        val health = HealthAutoConfiguration.toHealth(
            ComponentHealth.of(
                "stores",
                listOf(
                    ComponentHealth("bank", HealthStatus.DEGRADED, mapOf("failures" to 1, "lastError" to null)),
                    ComponentHealth.of("group", listOf(ComponentHealth("inner", HealthStatus.UP))),
                ),
                mapOf("kept" to 1, "gone" to null),
            ),
        )
        health.details["kept"].assert().isEqualTo(1)
        health.details.assert().doesNotContainKey("gone")
        health.status.assert().isEqualTo(HealthAutoConfiguration.DEGRADED)
        health.status.code.assert().isEqualTo("DEGRADED")
        @Suppress("UNCHECKED_CAST")
        val bank = health.details["bank"] as Map<String, Any?>
        bank.assert().containsEntry("status", "DEGRADED").containsEntry("failures", 1).doesNotContainKey("lastError")
        @Suppress("UNCHECKED_CAST")
        ((health.details["group"] as Map<String, Any?>)["inner"] as Map<String, Any?>)["status"].assert()
            .isEqualTo("UP")
        HealthAutoConfiguration.statusOf(HealthStatus.UP).assert().isEqualTo(Status.UP)
        HealthAutoConfiguration.statusOf(HealthStatus.DOWN).assert().isEqualTo(Status.DOWN)
    }

    /** Polls [path] until it answers [status], at most ten seconds. */
    private fun Host.eventually(path: String, status: Int): Answer {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val answer = get(path)
            if (answer.status == status || System.nanoTime() > deadline) {
                answer.status.assert().describedAs("$path: ${answer.body}").isEqualTo(status)
                return answer
            }
            Thread.sleep(50)
        }
    }

    /** The node's stores in memory, whose log and lease store fail as unreachable while told to. */
    class FlakyStores : BackendFactory {
        @Volatile
        var logDown = false

        @Volatile
        var leasesDown = false

        private val opened = ConcurrentHashMap<String, ContextBackends>()

        override fun backends(context: String): ContextBackends = opened.computeIfAbsent(context) {
            ContextBackends(
                log = FlakyLog(InMemoryEventLog(4)),
                checkpoints = InMemoryCheckpointStore(),
                leases = FlakyLeases(InMemoryLeaseStore()),
                ledger = InMemoryFailureLedger(),
                snapshots = InMemorySnapshotStore(),
            )
        }

        private fun checkLog() {
            if (logDown) {
                throw StorageUnavailableException("The log is down.")
            }
        }

        private fun checkLeases() {
            if (leasesDown) {
                throw StorageUnavailableException("The lease store is down.")
            }
        }

        private inner class FlakyLog(private val log: EventLog) : EventLog by log {
            override suspend fun head(partition: Int): Long? {
                checkLog()
                return log.head(partition)
            }

            override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
                checkLog()
                log.read(ref, fromVersion, toVersion).collect { emit(it) }
            }

            override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = flow {
                checkLog()
                log.readEntries(ref, fromVersion, toVersion).collect { emit(it) }
            }

            override suspend fun findByRequestId(
                type: AggregateType,
                tenantId: String,
                requestId: String,
            ): EventStreamRecord? {
                checkLog()
                return log.findByRequestId(type, tenantId, requestId)
            }
        }

        private inner class FlakyLeases(private val leases: LeaseStore) : LeaseStore {
            override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
                checkLeases()
                return leases.acquire(group, partition, owner, ttl)
            }

            override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
                checkLeases()
                return leases.renew(group, partition, owner, ttl)
            }

            override suspend fun release(group: String, partition: Int, owner: String): Boolean {
                checkLeases()
                return leases.release(group, partition, owner)
            }

            override suspend fun owners(group: String): List<Lease> {
                checkLeases()
                return leases.owners(group)
            }

            override suspend fun heartbeat(group: String, node: String, address: String, ttl: Duration): Member {
                checkLeases()
                return leases.heartbeat(group, node, address, ttl)
            }

            override suspend fun members(group: String): List<Member> {
                checkLeases()
                return leases.members(group)
            }

            override suspend fun leave(group: String, node: String) {
                checkLeases()
                leases.leave(group, node)
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class FlakyStoresConfiguration {
        @Bean
        fun flakyStores(): FlakyStores = STORES

        companion object {
            val STORES = FlakyStores()
        }
    }

    private companion object {
        /** The details shown, a store down at its first failure, and a sample every 100 ms. */
        val DETAILS: Array<Pair<String, Any>> = arrayOf(
            "management.endpoint.health.show-details" to "always",
            "management.endpoint.health.show-components" to "always",
            "wow.health.stores.down-after" to "0s",
            "wow.health.sample-interval" to "100ms",
        )
    }
}
