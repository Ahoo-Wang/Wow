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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.client.Port
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spi.peer.PeerDiscoveryConfig
import me.ahoo.wow.spi.peer.PeerTargets
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.support.StaticApplicationContext
import org.springframework.mock.env.MockEnvironment
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.naming.NamingException
import kotlin.io.path.writeText
import kotlin.time.TestTimeSource

/**
 * Peer discovery by DNS SRV (decision D305), over the answers Kubernetes' DNS gave in the GA end-to-end run 6
 * (`peer/dns-run6.txt`): the search domains of the pods' resolver, the records' targets as the nodes, their cache and
 * its refresh, and the endpoints a node calls its peers at.
 */
class DnsPeerDiscoveryTest {
    /** The SRV records of run 6, by their absolute name. */
    private val records: Map<String, List<SrvRecord>> = javaClass.getResource("/peer/dns-run6.txt")!!.readText()
        .lines().map { it.trim().split(Regex("\\s+")) }
        .filter { it.size == 8 && it[3] == "SRV" }
        .groupBy({ it[0].trimEnd('.') }, { SrvRecord(it[4].toInt(), it[5].toInt(), it[6].toInt(), it[7]) })

    private val queries = AtomicInteger()

    @Volatile
    private var failing = false

    private fun query(name: String): List<SrvRecord> {
        queries.incrementAndGet()
        if (failing) throw NamingException("DNS is down")
        return records[name.trimEnd('.')].orEmpty()
    }

    private fun lookup(dir: Path): JndiSrvLookup {
        val resolvConf = dir.resolve("resolv.conf").apply {
            writeText("# a pod's\nsearch default.svc.cluster.local svc.cluster.local cluster.local\noptions ndots:5\n")
        }
        return JndiSrvLookup(resolvConf) { failing.let { _ -> query(it) } }
    }

    @Test
    fun `a pod's search domains should find the records, a trailing dot should not search`(@TempDir dir: Path) {
        val lookup = lookup(dir)
        lookup.candidates("_internal._tcp.ops-cluster").assert().containsExactly(
            "_internal._tcp.ops-cluster",
            "_internal._tcp.ops-cluster.default.svc.cluster.local",
            "_internal._tcp.ops-cluster.svc.cluster.local",
            "_internal._tcp.ops-cluster.cluster.local",
        )
        lookup.srv("_internal._tcp.ops-cluster").map { it.port }.assert().containsExactly(9098, 9098)
        lookup.candidates("_internal._tcp.ops-cluster.").assert().containsExactly("_internal._tcp.ops-cluster.")
        lookup.srv("_internal._tcp.ops-cluster.").assert().isEmpty()
        JndiSrvLookup(dir.resolve("absent")).candidates("x").assert().containsExactly("x")
        JndiSrvLookup.searchDomains(dir.resolve("absent")).assert().isEmpty()
        JndiSrvLookup.parse("0 50 9093 a.b.").assert().isEqualTo(SrvRecord(0, 50, 9093, "a.b."))
        JndiSrvLookup.parse("0 50 x a.b.").assert().isNull()
        JndiSrvLookup.parse("0 50 9093").assert().isNull()
    }

    @Test
    fun `a service's targets should be its records' targets, cached and refreshed`(
        @TempDir dir: Path
    ): Unit = runBlocking {
        val clock = TestTimeSource()
        val targets = DnsPeerTargets(DnsPeerDiscovery.DEFAULT_NAME_TEMPLATE, lookup(dir), clock)
        // Run 6's headless Services are named after the context: the service name stands in for it.
        val ordering = targets.targetsOf("eshop-ordering", "eshop-ordering")
        ordering.assert().containsExactlyInAnyOrder(
            URI.create("http://192-168-131-224.eshop-ordering-cluster.default.svc.cluster.local:9093"),
            URI.create("http://192-168-131-225.eshop-ordering-cluster.default.svc.cluster.local:9093"),
        )
        val looked = queries.get()
        targets.targetsOf("eshop-ordering", "eshop-ordering").assert().isEqualTo(ordering)
        queries.get().assert().isEqualTo(looked)
        clock += DnsPeerTargets.REFRESH
        failing = true
        // A lookup that fails keeps the records found last.
        targets.targetsOf("eshop-ordering", "eshop-ordering").assert().isEqualTo(ordering)
        queries.get().assert().isGreaterThan(looked)
        failing = false
        // Every node failed to connect: looked up again at once, at most once per refresh.
        targets.unreachable("eshop-ordering", "eshop-ordering")
        val forced = queries.get()
        targets.targetsOf("eshop-ordering", "eshop-ordering")
        queries.get().assert().isGreaterThan(forced)
        targets.unreachable("eshop-ordering", "eshop-ordering")
        val again = queries.get()
        targets.targetsOf("eshop-ordering", "eshop-ordering")
        queries.get().assert().isEqualTo(again)
        targets.unreachable("unknown", "unknown")
        targets.targetsOf("payment", "payment").assert().isEmpty()
        targets.nameOf("eshop-ordering-service").assert().isEqualTo("_internal._tcp.eshop-ordering-service-cluster")
    }

    @Test
    fun `the endpoints should take a peer's url first, else its service's targets`(
        @TempDir dir: Path
    ): Unit = runBlocking {
        val properties = WowProperties().apply {
            peers["mail"] = WowProperties.PeerTarget().apply {
                url = URI.create("http://mail:9090")
                publicUrl = URI.create("http://mail:8080")
            }
            peers["eshop-ordering"] = WowProperties.PeerTarget()
        }
        val discoveries = PeerDiscoveries(
            mapOf("dns" to DnsPeerDiscovery(lookup(dir)), "static" to StaticPeerDiscovery)
        )
        val services = mapOf("eshop-ordering" to "eshop-ordering", "eshop-payment" to "eshop-payment")
        PeerEndpoints.of(properties, discoveries, { emptyMap() }, services::get).use { endpoints ->
            endpoints.resolve("mail", Port.INTERNAL).assert().isEqualTo(URI.create("http://mail:9090"))
            endpoints.resolve("mail", Port.PUBLIC).assert().isEqualTo(URI.create("http://mail:8080"))
            endpoints.resolveAll("eshop-ordering", Port.INTERNAL).assert().hasSize(2)
            endpoints.resolveAll("eshop-ordering", Port.PUBLIC).assert().isEmpty()
            // A saga's call needs no listing: its contract names the service.
            endpoints.reaches("eshop-payment", Port.INTERNAL).assert().isTrue()
            endpoints.reaches("unknown", Port.INTERNAL).assert().isFalse()
            endpoints.reaches("mail", Port.PUBLIC).assert().isTrue()
            endpoints.unreachable("eshop-ordering", Port.INTERNAL)
            endpoints.unreachable("mail", Port.INTERNAL)
        }
        val static = WowProperties().apply {
            peer.discovery = "static"
            peers["mail"] = WowProperties.PeerTarget().apply { url = URI.create("http://mail:9090") }
        }
        PeerEndpoints.of(static, discoveries, { emptyMap() }, services::get).use { endpoints ->
            endpoints.reaches("eshop-payment", Port.INTERNAL).assert().isFalse()
            endpoints.resolveAll("eshop-payment", Port.INTERNAL).assert().isEmpty()
        }
    }

    @Test
    fun `the JDK's DNS should find no record of a name that does not exist`() {
        // The `.invalid` top-level domain never resolves (RFC 6761); without a DNS server the lookup fails alike.
        JndiSrvLookup.records("_internal._tcp.nothing.invalid.").assert().isEmpty()
        JndiSrvLookup().srv("_internal._tcp.nothing.invalid.").assert().isEmpty()
    }

    @Test
    fun `the host's resolver should find a peer's service by its contract, and a provider by its type`(): Unit =
        runBlocking {
            val contracts = ContractDirectory(
                Manifest(service = ServiceInfo("bank-service"), contexts = listOf(ContextManifest(BANK))),
                listOf(
                    ManifestFragment(
                        "mail-api",
                        Manifest(service = ServiceInfo("mail-service"), contexts = listOf(ContextManifest("mail"))),
                    ),
                ),
            )
            WowAutoConfiguration.serviceOf(contracts, "mail").assert().isEqualTo("mail-service")
            WowAutoConfiguration.serviceOf(contracts, "box").assert().isNull()
            val custom = object : PeerDiscovery {
                override val type: String = "custom"

                override fun open(config: PeerDiscoveryConfig): PeerTargets = object : PeerTargets {
                    override suspend fun targetsOf(service: String, context: String): List<URI> =
                        listOf(URI.create("http://$service.${config.option("domain")}:9090"))
                }
            }
            val beans = StaticListableBeanFactory().apply { addBean("custom", custom) }
            val environment = MockEnvironment().withProperty("wow.peer.custom.domain", "internal")
            val properties = WowProperties().apply { peer.discovery = "custom" }
            val resolver = WowAutoConfiguration().wowEndpointResolver(
                properties,
                contracts,
                beans.getBeanProvider(PeerDiscovery::class.java),
                environment,
                StaticApplicationContext(),
            )
            resolver.resolveAll("mail", Port.INTERNAL).assert()
                .containsExactly(URI.create("http://mail-service.internal:9090"))
            resolver.reaches("mail", Port.INTERNAL).assert().isTrue()
            (resolver as AutoCloseable).close()
            val dns = WowAutoConfiguration().wowEndpointResolver(
                WowProperties(),
                contracts,
                StaticListableBeanFactory().getBeanProvider(PeerDiscovery::class.java),
                environment,
                StaticApplicationContext(),
            )
            dns.reaches("mail", Port.INTERNAL).assert().isTrue()
        }

    @Test
    fun `a listed peer nothing can find, or an unknown discovery, should refuse the start`(@TempDir dir: Path) {
        val discoveries = PeerDiscoveries(
            mapOf("dns" to DnsPeerDiscovery(lookup(dir)), "static" to StaticPeerDiscovery)
        )
        fun refused(properties: WowProperties) = assertThrows<WowConfigurationException> {
            PeerEndpoints.of(properties, discoveries, { emptyMap() }) { null }
        }
        refused(WowProperties().apply { peers["mail"] = WowProperties.PeerTarget() }).message.assert()
            .contains("no contract on the class path names its service")
        refused(
            WowProperties().apply {
                peer.discovery = "static"
                peers["mail"] = WowProperties.PeerTarget()
            },
        ).message.assert().contains("static")
        refused(WowProperties().apply { peer.discovery = "consul" }).key.assert().isEqualTo("wow.peer.discovery")
        PeerDiscoveries.load(javaClass.classLoader).discoveries.keys.assert().contains("dns", "static")
        StaticPeerDiscovery.open(PeerDiscoveryConfig.of("static")).use { runBlocking { it.targetsOf("a", "a") } }
            .assert().isEmpty()
    }
}
