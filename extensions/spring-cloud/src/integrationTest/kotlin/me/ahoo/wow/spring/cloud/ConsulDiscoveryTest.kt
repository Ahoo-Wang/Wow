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

import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.Port
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.example.eshop.catalog.api.brand.CreateBrand
import me.ahoo.wow.example.eshop.catalog.domain.brand.BrandState
import me.ahoo.wow.example.quickstart.BankServer
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.eventually
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer
import org.testcontainers.consul.ConsulContainer
import java.net.URI
import java.util.UUID

/**
 * A Wow service off Kubernetes finds its peer's nodes in Consul, with no `wow.peers.<context>.url` (decision D396): two
 * nodes of the eShop's catalog service register their internal addresses there, and a node of the quickstart's bank
 * service calls the catalog through `wow.peer.discovery=spring-cloud`, reaching either node and passing over one that
 * stopped. An application's `WowClient` reaches them as `lb://eshop-catalog-service` through Spring Cloud LoadBalancer.
 * Each node keeps its data in memory: discovery does not depend on the backend.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ConsulDiscoveryTest {
    private val consul = ConsulContainer(CONSUL_IMAGE).also { it.start() }

    /** Consul, and the registries the test class path also has turned off. */
    private val registry: Map<String, Any?> = mapOf(
        "spring.cloud.consul.host" to consul.host,
        "spring.cloud.consul.port" to consul.getMappedPort(CONSUL_PORT),
        // Consul's agent runs in a container, which reaches no loopback port of this machine to check.
        "spring.cloud.consul.discovery.register-health-check" to false,
        "spring.cloud.consul.discovery.prefer-ip-address" to true,
        "spring.cloud.consul.discovery.ip-address" to LOOPBACK,
        "eureka.client.enabled" to false,
        "spring.cloud.zookeeper.enabled" to false,
    )

    /** The catalog's nodes; the application class is the quickstart's, each serving the catalog alone. */
    private val catalogs = (1..2).map { node ->
        wowServer<BankServer>(
            "dev",
            properties = registry + mapOf(
                "spring.application.name" to CATALOG_SERVICE,
                "wow.contexts.include" to CATALOG,
                "wow.node.id" to "catalog-$node",
                // Consul's default instance id is the application's name and port, the same for two nodes on port 0.
                "spring.cloud.consul.discovery.instance-id" to "catalog-$node",
            ),
            // The catalog's service routes are built from an instance the host gives.
            sources = listOf(CatalogStorefront::class.java),
        )
    }

    private val bank = wowServer<BankServer>(
        "dev",
        properties = registry + mapOf(
            "wow.contexts.include" to "bank",
            "spring.cloud.consul.discovery.instance-id" to "bank-1",
            "wow.peer.discovery" to SpringCloudPeerDiscovery.TYPE,
            // The catalog is called over HTTP, in service mode, at the nodes the registry has: no url.
            "wow.peers.$CATALOG.mode" to "service",
        ),
    )

    private val alice = Caller.of(subject = "alice")

    @AfterAll
    fun close() {
        (catalogs + bank).forEach { runCatching { it.close() } }
        consul.stop()
    }

    @Test
    @Order(1)
    fun `each node should register its service and internal address`() {
        awaitRegistered()
        val instances = instancesOf(CATALOG_SERVICE)
        instances.map { instance -> instance.metadata.orEmpty().filterKeys { it.startsWith("wow-") } }.assert()
            .containsExactlyInAnyOrderElementsOf(
                catalogs.map { WowInstanceMetadata.of(CATALOG_SERVICE, "$LOOPBACK:${it.internalPort}") },
            )
        instances.map { it.port }.assert().containsExactlyInAnyOrderElementsOf(catalogs.map { it.publicPort })
    }

    @Test
    @Order(2)
    fun `a peer should reach either node through the registry`() {
        awaitRegistered()
        val created = (1..4).map { create(bank.bean<CommandClient>()) }
        catalogs.forEach { catalog ->
            created.count { id -> stateAt(catalog, id) != null }.assert().isPositive()
        }
        created.forEach { id -> catalogs.count { stateAt(it, id) != null }.assert().isEqualTo(1) }
    }

    @Test
    @Order(3)
    fun `an application's WowClient should reach the nodes as lb, in service and user mode`() {
        awaitRegistered()
        @Suppress("UNCHECKED_CAST")
        val loadBalancers = bank.bean<ReactiveLoadBalancer.Factory<*>>() as ReactiveLoadBalancer.Factory<ServiceInstance>
        val endpoints = LoadBalancedEndpoints.of(loadBalancers, mapOf(CATALOG to URI.create("lb://$CATALOG_SERVICE")))
        WowClient.builder().endpoints(endpoints).service("storefront-app").build().use { client ->
            val id = create(client.commands)
            catalogs.count { stateAt(it, id) != null }.assert().isEqualTo(1)
        }
        WowClient.builder().endpoints(endpoints).user(UserCredential.DEVELOPMENT).build().use { client ->
            val id = create(client.commands)
            catalogs.count { stateAt(it, id) != null }.assert().isEqualTo(1)
        }
    }

    @Test
    @Order(4)
    fun `a node that stopped should be passed over`() {
        awaitRegistered()
        val stopped = catalogs.first()
        val running = catalogs.last()
        val endpoints = bank.bean<EndpointResolver>()
        val stoppedAt = URI.create("http://$LOOPBACK:${stopped.internalPort}")
        runBlocking { endpoints.resolveAll(CATALOG, Port.INTERNAL) }.assert().contains(stoppedAt)
        stopped.close()
        // The peer keeps the nodes it read for its refresh: it still tries the stopped one, fails to connect and goes
        // on to the other, in the same attempt.
        runBlocking { endpoints.resolveAll(CATALOG, Port.INTERNAL) }.assert().contains(stoppedAt)
        val created = (1..4).map { create(bank.bean<CommandClient>()) }
        created.forEach { id -> stateAt(running, id).assert().isNotNull() }
    }

    @Test
    @Order(5)
    fun `a setting of the discovery should be refused, as it declares none`() {
        val refused = assertThrows<Exception> {
            wowServer<BankServer>(
                "dev",
                properties = registry + mapOf(
                    "wow.contexts.include" to "bank",
                    "spring.cloud.consul.discovery.instance-id" to "bank-2",
                    "wow.peer.discovery" to SpringCloudPeerDiscovery.TYPE,
                    "wow.peer.spring-cloud.refresh" to "1s",
                ),
            ).close()
        }
        generateSequence<Throwable>(refused) { it.cause }.filterIsInstance<WowConfigurationException>().first()
            .message.assert().contains("wow.peer.spring-cloud.refresh")
    }

    private fun instancesOf(service: String): List<ServiceInstance> = runBlocking {
        bank.bean<ReactiveDiscoveryClient>().getInstances(service).collectList().awaitFirst()
    }

    private fun awaitRegistered() {
        eventually("the running catalog nodes are in Consul") {
            instancesOf(CATALOG_SERVICE).size == catalogs.count { it.context.isActive }
        }
    }

    /** Creates a brand of a new id and name through [commands], as [alice]; its id. */
    private fun create(commands: CommandClient): String {
        val id = UUID.randomUUID().toString()
        runBlocking {
            commands.of(alice).wait(CreateBrand("brand $id"), id).status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
        }
        return id
    }

    /** The brand [id] as [catalog], which keeps its own brands in memory; null when it has none of that id. */
    private fun stateAt(catalog: WowServer, id: String): BrandState? = runBlocking {
        catalog.queries(alice).snapshots(BrandState::class.java).state(id)?.state
    }

    private companion object {
        const val CONSUL_IMAGE = "hashicorp/consul:1.22"
        const val CONSUL_PORT = 8500
        const val LOOPBACK = "127.0.0.1"
        const val CATALOG = "eshop-catalog"
        const val CATALOG_SERVICE = "eshop-catalog-service"
    }
}
