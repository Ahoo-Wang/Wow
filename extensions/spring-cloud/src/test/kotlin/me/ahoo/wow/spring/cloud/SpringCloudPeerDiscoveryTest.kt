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

package me.ahoo.wow.spring.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.peer.PeerDiscoveryConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.cloud.client.DefaultServiceInstance
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
import reactor.core.publisher.Flux
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TestTimeSource

/**
 * Peer discovery through a Spring Cloud registry (decision D396): an instance's internal port from its metadata, the
 * instances that are passed over, and the cache, kept as the `dns` discovery keeps its records (decision D325 ②).
 */
class SpringCloudPeerDiscoveryTest {
    private val registry = Registry()
    private val clock = TestTimeSource()
    private val targets = SpringCloudPeerTargets(registry, clock)

    @Test
    fun `a service's targets should be the internal ports its instances name`(): Unit = runBlocking {
        registry.instances = listOf(
            node("bank-1", "10.0.0.5:9090"),
            node("bank-2", "10.0.0.6:9090"),
        )
        targets.targetsOf("bank-service", "bank").assert().containsExactly(
            URI.create("http://10.0.0.5:9090"),
            URI.create("http://10.0.0.6:9090"),
        )
        registry.asked.assert().containsExactly("bank-service")
    }

    @Test
    fun `an instance that names no port, or another service or none, should be passed over`(): Unit = runBlocking {
        registry.instances = listOf(
            node("bank-1", "10.0.0.5:9090"),
            instance("bank-2", mapOf(WowInstanceMetadata.SERVICE to "bank-service")),
            instance("bank-3", WowInstanceMetadata.of("teller-service", "10.0.0.7:9090")),
            instance("bank-4", mapOf(WowInstanceMetadata.INTERNAL_PORT to "9090")),
            node("bank-5", "10.0.0.8:9090"),
        )
        targets.targetsOf("bank-service", "bank").assert().containsExactly(
            URI.create("http://10.0.0.5:9090"),
            URI.create("http://10.0.0.8:9090"),
        )
        // The service is compared as written: a registry that upper-cases names (Eureka) still finds its instances.
        targets.targetsOf("BANK-SERVICE", "bank").assert().isEmpty()
    }

    @Test
    fun `the targets should be cached, read again after the refresh, and kept when a read finds none or fails`(): Unit =
        runBlocking {
            registry.instances = listOf(node("bank-1", "10.0.0.5:9090"))
            val first = targets.targetsOf("bank-service", "bank")
            targets.targetsOf("bank-service", "bank").assert().isEqualTo(first)
            registry.reads.get().assert().isEqualTo(1)

            clock += SpringCloudPeerTargets.REFRESH
            registry.instances = listOf(node("bank-2", "10.0.0.6:9090"))
            val moved = targets.targetsOf("bank-service", "bank")
            moved.assert().containsExactly(URI.create("http://10.0.0.6:9090"))
            registry.reads.get().assert().isEqualTo(2)

            clock += SpringCloudPeerTargets.REFRESH
            registry.instances = emptyList()
            targets.targetsOf("bank-service", "bank").assert().isEqualTo(moved)

            clock += SpringCloudPeerTargets.REFRESH
            registry.failure = IllegalStateException("the registry is down")
            targets.targetsOf("bank-service", "bank").assert().isEqualTo(moved)
            registry.reads.get().assert().isEqualTo(4)
        }

    @Test
    fun `every target failing should read again at once, at most once per refresh`(): Unit = runBlocking {
        registry.instances = listOf(node("bank-1", "10.0.0.5:9090"))
        targets.targetsOf("bank-service", "bank")
        targets.unreachable("bank-service", "bank")
        targets.targetsOf("bank-service", "bank")
        registry.reads.get().assert().isEqualTo(2)
        targets.unreachable("bank-service", "bank")
        targets.targetsOf("bank-service", "bank")
        registry.reads.get().assert().isEqualTo(2)
        clock += SpringCloudPeerTargets.REFRESH
        targets.unreachable("bank-service", "bank")
        targets.targetsOf("bank-service", "bank")
        registry.reads.get().assert().isEqualTo(3)
        // A service never asked for has nothing to read again.
        targets.unreachable("teller-service", "teller")
    }

    @Test
    fun `a read that does not answer should time out and keep the last targets`() = runTest {
        registry.instances = listOf(node("bank-1", "10.0.0.5:9090"))
        val found = targets.targetsOf("bank-service", "bank")
        clock += SpringCloudPeerTargets.REFRESH
        registry.answers = false
        targets.targetsOf("bank-service", "bank").assert().isEqualTo(found)
        testScheduler.currentTime.assert().isEqualTo(SpringCloudPeerTargets.READ_TIMEOUT.inWholeMilliseconds)
    }

    @Test
    fun `a caller cancelled while the registry reads should stay cancelled`() = runTest {
        registry.answers = false
        val read = async { targets.targetsOf("bank-service", "bank") }
        yield()
        read.cancel()
        assertThrows<CancellationException> { read.await() }
    }

    @Test
    fun `the discovery should open on the application's reactive client, and refuse the start without one`() {
        val discovery = SpringCloudPeerDiscovery { registry }
        discovery.type.assert().isEqualTo("spring-cloud")
        discovery.options.assert().isEmpty()
        discovery.isOpened.assert().isFalse()
        discovery.open(PeerDiscoveryConfig.of(SpringCloudPeerDiscovery.TYPE)).assert()
            .isInstanceOf(SpringCloudPeerTargets::class.java)
        discovery.isOpened.assert().isTrue()

        val refused = assertThrows<WowConfigurationException> {
            SpringCloudPeerDiscovery { null }.open(PeerDiscoveryConfig.of(SpringCloudPeerDiscovery.TYPE))
        }
        refused.key.assert().isEqualTo("wow.peer.discovery")
        refused.value.assert().isEqualTo("spring-cloud")
        refused.fix.assert().contains("spring.cloud.discovery.reactive.enabled")
    }

    private fun node(id: String, address: String) = instance(id, WowInstanceMetadata.of("bank-service", address))

    private fun instance(id: String, metadata: Map<String, String>): ServiceInstance =
        DefaultServiceInstance(id, "bank-service", "10.0.1.1", 8080, false, metadata)

    /** A registry of [instances], which fails with [failure] when set and does not answer unless it [answers]. */
    private class Registry : ReactiveDiscoveryClient {
        @Volatile
        var instances: List<ServiceInstance> = emptyList()

        @Volatile
        var failure: RuntimeException? = null

        @Volatile
        var answers: Boolean = true

        val reads = AtomicInteger()
        val asked: MutableList<String> = mutableListOf()

        override fun description(): String = "a test registry"

        override fun getInstances(serviceId: String): Flux<ServiceInstance> {
            reads.incrementAndGet()
            asked += serviceId
            failure?.let { return Flux.error(it) }
            if (!answers) return Flux.never()
            return Flux.fromIterable(instances)
        }

        override fun getServices(): Flux<String> = Flux.just("bank-service")
    }
}
