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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.PeerMode
import me.ahoo.wow.client.Port
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spi.peer.PeerDiscoveryConfig
import me.ahoo.wow.spi.peer.PeerTargets
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Hashtable
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The peer discoveries a node can open: Wow's `dns` and `static`, then those found on the classpath
 * (`ServiceLoader`) and among the application's beans, a bean taking the place of a provider of its type.
 */
internal class PeerDiscoveries(val discoveries: Map<String, PeerDiscovery>) {
    /** The `wow.<prefix>.<key>` of each option the discoveries declare, for the host to know them at startup. */
    val declared: List<Pair<String, List<ProviderOption<*>>>>
        get() = discoveries.values.map { "$PREFIX.${it.type}" to it.options }

    /** The discovery of [type]; refused, naming the types there are, when none is. */
    fun of(type: String): PeerDiscovery = configNotNull(
        discoveries[type.trim().lowercase()],
        "wow.peer.discovery",
        "Set it to one of ${discoveries.keys}, or add the provider of $type.",
    ) { "wow.peer.discovery is $type, which no peer discovery provides." }

    companion object {
        /** The key under `wow` a discovery's settings are under, `wow.peer.<type>`. */
        const val PREFIX: String = "peer"

        /** Wow's discoveries, the classpath's and [beans], by type. */
        fun load(classLoader: ClassLoader?, beans: List<PeerDiscovery> = emptyList()): PeerDiscoveries {
            val loader = classLoader ?: PeerDiscoveries::class.java.classLoader
            val found = listOf(DnsPeerDiscovery(), StaticPeerDiscovery) +
                ServiceLoader.load(PeerDiscovery::class.java, loader).toList() + beans
            return PeerDiscoveries(found.associateBy { it.type })
        }
    }
}

/** `wow.peer.discovery=static`: the peers' `wow.peers.<context>.url` alone, no other target. */
internal object StaticPeerDiscovery : PeerDiscovery {
    override val type: String = "static"

    override fun open(config: PeerDiscoveryConfig): PeerTargets = object : PeerTargets {
        override suspend fun targetsOf(service: String, context: String): List<URI> = emptyList()
    }
}

/**
 * `wow.peer.discovery=dns`, the default: a peer service's nodes are the targets of the DNS SRV records
 * `name-template` names after it, `_internal._tcp.{service}-cluster` by default, each `http://<target>:<port>`.
 * Kubernetes publishes one such record per pod behind a headless Service, whose targets resolve to the pods' IPs, the
 * addresses their nodes advertise.
 */
@InternalWowApi
class DnsPeerDiscovery(private val lookup: SrvLookup = JndiSrvLookup()) : PeerDiscovery {
    override val type: String = TYPE

    override val options: List<ProviderOption<*>> = listOf(NAME_TEMPLATE)

    override fun open(config: PeerDiscoveryConfig): PeerTargets = DnsPeerTargets(config.option(NAME_TEMPLATE), lookup)

    @InternalWowApi
    companion object {
        const val TYPE: String = "dns"
        const val DEFAULT_NAME_TEMPLATE: String = "_internal._tcp.{service}-cluster"

        /** The SRV name of a peer service's internal port. */
        @JvmField
        val NAME_TEMPLATE: ProviderOption.Defaulted<String> = ProviderOption.string(
            "name-template",
            "the SRV name of a peer service's internal port, {service} its name (spring.application.name): by " +
                "default the headless Service <service>-cluster and its port named internal, the records Kubernetes " +
                "publishes for them. A name without a trailing dot is tried as given, then under each search domain " +
                "of the node's /etc/resolv.conf, as a resolver does; the records' targets are the nodes and their ports.",
            DEFAULT_NAME_TEMPLATE,
        )
    }
}

/** A DNS SRV lookup: the records of [name], as a resolver finds them. */
@InternalWowApi
fun interface SrvLookup {
    fun srv(name: String): List<SrvRecord>
}

/** One SRV record: its [priority], [weight], [port] and [target], the host name of a node. */
@InternalWowApi
data class SrvRecord(val priority: Int, val weight: Int, val port: Int, val target: String)

/**
 * The SRV records of a peer service, cached for [REFRESH] (the TTL Kubernetes' DNS gives them): looked up on the
 * service's first request, again once that is past, the last records found kept while a lookup finds none or fails,
 * and again at once when every target failed to connect ([unreachable]), at most once per [REFRESH], so a rollout that
 * moves every node heals without waiting.
 */
internal class DnsPeerTargets(
    private val template: String,
    private val lookup: SrvLookup,
    private val clock: TimeSource = TimeSource.Monotonic,
) : PeerTargets {
    private val peers = ConcurrentHashMap<String, Peer>()

    /** One peer service's records, and when they were looked up. */
    private class Peer {
        val lock = Mutex()

        @Volatile
        var targets: List<URI> = emptyList()

        @Volatile
        var looked: TimeMark? = null

        @Volatile
        var forced: TimeMark? = null

        @Volatile
        var warned: Boolean = false

        fun fresh(): Boolean = looked?.let { it.elapsedNow() < REFRESH } == true
    }

    override suspend fun targetsOf(service: String, context: String): List<URI> {
        val peer = peers.computeIfAbsent(service) { Peer() }
        if (peer.fresh()) return peer.targets
        peer.lock.withLock {
            if (peer.fresh()) return peer.targets
            val name = nameOf(service)
            val found = try {
                lookup.srv(name)
            } catch (failure: NamingException) {
                logger.debug(failure) { "The SRV lookup of $name failed." }
                emptyList()
            }
            val targets = found.sortedWith(compareBy<SrvRecord> { it.priority }.thenByDescending { it.weight })
                .map { URI.create("http://${it.target.trimEnd('.')}:${it.port}") }
            if (targets.isNotEmpty()) {
                peer.targets = targets
            } else if (!peer.warned) {
                peer.warned = true
                logger.warn {
                    "No SRV record of $name, the internal port of peer $service (context $context): set " +
                        "wow.peers.$context.url, or wow.peer.dns.name-template to the records' name."
                }
            }
            peer.looked = clock.markNow()
            return peer.targets
        }
    }

    override fun unreachable(service: String, context: String) {
        val peer = peers[service] ?: return
        if (peer.forced?.let { it.elapsedNow() < REFRESH } == true) return
        peer.forced = clock.markNow()
        peer.looked = null
    }

    /** The SRV name of [service]. */
    fun nameOf(service: String): String = template.replace(SERVICE, service)

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val SERVICE = "{service}"

        /** How long a peer's records are kept before they are looked up again. */
        val REFRESH: Duration = 30.seconds
    }
}

/**
 * The SRV lookup of the JDK's DNS provider (JNDI), with the search domains of the node's resolver: a [name] without a
 * trailing dot is tried as given, then under each `search` domain of [resolvConf] (read once; none when the file is
 * absent, as on a developer's machine), the first that has records answering; a name with one is absolute.
 */
@InternalWowApi
class JndiSrvLookup(
    resolvConf: Path = RESOLV_CONF,
    private val query: (name: String) -> List<SrvRecord> = Companion::records,
) : SrvLookup {
    private val search: List<String> = searchDomains(resolvConf)

    override fun srv(name: String): List<SrvRecord> {
        for (candidate in candidates(name)) {
            val records = query(candidate)
            if (records.isNotEmpty()) return records
        }
        return emptyList()
    }

    /** The names [name] is tried as. */
    fun candidates(name: String): List<String> =
        if (name.endsWith('.')) listOf(name) else listOf(name) + search.map { "$name.$it" }

    @InternalWowApi
    companion object {
        private const val DNS_FACTORY = "com.sun.jndi.dns.DnsContextFactory"
        private const val SRV = "SRV"
        private const val SRV_FIELDS = 4

        @JvmField
        val RESOLV_CONF: Path = Path.of("/etc/resolv.conf")

        /**
         * The SRV records of the absolute [name], as the JDK's DNS provider finds them, from the node's DNS servers
         * unless [settings] names others (`java.naming.provider.url`); none when it finds none.
         */
        @JvmOverloads
        fun records(name: String, settings: Map<String, String> = emptyMap()): List<SrvRecord> = try {
            val context = InitialDirContext(Hashtable(mapOf("java.naming.factory.initial" to DNS_FACTORY) + settings))
            try {
                val values = context.getAttributes(name, arrayOf(SRV)).get(SRV) ?: return emptyList()
                (0 until values.size()).mapNotNull { parse(values.get(it).toString()) }
            } finally {
                context.close()
            }
        } catch (_: NamingException) {
            emptyList()
        }

        /** One SRV record's text, `priority weight port target`; null when it is not one. */
        fun parse(text: String): SrvRecord? {
            val fields = text.trim().split(Regex("\\s+"))
            if (fields.size != SRV_FIELDS) return null
            val (priority, weight, port) = fields.take(SRV_FIELDS - 1).map { it.toIntOrNull() ?: return null }
            return SrvRecord(priority, weight, port, fields[SRV_FIELDS - 1])
        }

        /** The `search` domains of [resolvConf], in order; none when the file is absent or unreadable. */
        fun searchDomains(resolvConf: Path): List<String> = try {
            if (!Files.isReadable(resolvConf)) {
                emptyList()
            } else {
                Files.readAllLines(resolvConf).map { it.substringBefore('#').trim() }
                    .filter { it.startsWith("search ") || it.startsWith("domain ") }
                    .flatMap { it.split(Regex("\\s+")).drop(1) }
                    .filter { it.isNotBlank() }
                    .map { it.trimEnd('.') }
                    .distinct()
            }
        } catch (_: IOException) {
            emptyList()
        }
    }
}

/**
 * Where the node finds its peers: a context's public routes at its `wow.peers.<context>.public-url`;
 * its internal port at its `wow.peers.<context>.url` when set, else at the [targets] of its service, [serviceOf] the
 * context, as the peer's contract or Manifest names it. A context it can find either way [reaches], without a lookup.
 */
internal class PeerEndpoints(
    private val publicUrls: Map<String, URI>,
    private val internalUrls: Map<String, URI>,
    private val serviceOf: (context: String) -> String?,
    private val targets: PeerTargets,
    private val discovers: Boolean,
) : EndpointResolver, AutoCloseable {
    private val checked = EndpointResolver.of(publicUrls, internalUrls)

    override suspend fun resolve(context: String, port: Port): URI? = resolveAll(context, port).firstOrNull()

    override suspend fun resolveAll(context: String, port: Port): List<URI> {
        checked.resolve(context, port)?.let { return listOf(it) }
        if (port == Port.PUBLIC || !discovers) return emptyList()
        return serviceOf(context)?.let { targets.targetsOf(it, context) }.orEmpty()
    }

    override suspend fun reaches(context: String, port: Port): Boolean =
        checked.resolve(context, port) != null || port == Port.INTERNAL && discovers && serviceOf(context) != null

    override fun unreachable(context: String, port: Port) {
        if (port == Port.INTERNAL && context !in internalUrls) {
            serviceOf(context)?.let { targets.unreachable(it, context) }
        }
    }

    override fun close() = targets.close()

    companion object {
        /**
         * The endpoints of [properties]' peers, their internal ports found as `wow.peer.discovery` says among
         * [discoveries], [serviceOf] each context. A peer of `wow.peers` called in service mode that nothing can find
         * (no `url`, and either the `static` discovery or no service name for it) refuses the start.
         */
        fun of(
            properties: WowProperties,
            discoveries: PeerDiscoveries,
            options: (type: String) -> Map<String, String>,
            serviceOf: (context: String) -> String?,
        ): PeerEndpoints {
            val type = properties.peer.discovery.trim().lowercase()
            val discovery = discoveries.of(type)
            val discovers = discovery !== StaticPeerDiscovery
            // A peer called in user mode goes to its public routes, whose own URL it has.
            val unlisted = properties.peers.filterValues {
                it.url == null && (it.mode ?: properties.peer.mode) == PeerMode.SERVICE
            }.keys
            unlisted.forEach { context ->
                configCheck(
                    discovers && serviceOf(context) != null,
                    "wow.peers.$context.url",
                    null,
                    "Set it to the peer's internal port, http://host:port.",
                ) {
                    if (discovers) {
                        "Peer $context has no url, and no contract on the class path names its service to look up."
                    } else {
                        "Peer $context has no url, and wow.peer.discovery is static."
                    }
                }
            }
            val config = PeerDiscoveryConfig.of(type, PortProviders.declared(options(type), discovery.options))
            return PeerEndpoints(
                properties.peers.mapNotNull { (context, target) -> target.publicUrl?.let { context to it } }.toMap(),
                properties.peers.mapNotNull { (context, target) -> target.url?.let { context to it } }.toMap(),
                serviceOf,
                PortProviders.configured(config.key) { discovery.open(config) },
                discovers,
            )
        }
    }
}
