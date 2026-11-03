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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spi.peer.PeerDiscoveryConfig
import me.ahoo.wow.spi.peer.PeerTargets
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * `wow.peer.discovery=spring-cloud` (decision D396): a peer service's nodes are the instances the application's
 * Spring Cloud service registry (Consul, Eureka, Nacos, Zookeeper, through their starters) has of the service, each at
 * the internal address its [WowInstanceMetadata] names. It reads them through the application's
 * [ReactiveDiscoveryClient], Spring Cloud's composite one by default; it declares no setting of its own.
 *
 * Experimental, as [PeerDiscovery] is.
 */
@ExperimentalWowApi
class SpringCloudPeerDiscovery(
    /** The application's reactive discovery client, null when it has none. */
    private val client: () -> ReactiveDiscoveryClient?,
) : PeerDiscovery {
    private val opened = AtomicBoolean()

    override val type: String = TYPE

    /** Opens the targets on the application's discovery client; refused when it has none. */
    override fun open(config: PeerDiscoveryConfig): PeerTargets {
        val client = configNotNull(
            client(),
            "wow.peer.discovery",
            "Add the starter of the service registry (spring-cloud-starter-consul-discovery, say), and keep " +
                "spring.cloud.discovery.reactive.enabled true, its default.",
            TYPE,
        ) { "wow.peer.discovery is $TYPE, and the application has no ReactiveDiscoveryClient." }
        opened.set(true)
        return SpringCloudPeerTargets(client)
    }

    /** Whether a node opened it: whether it finds its peers through the registry. */
    val isOpened: Boolean get() = opened.get()

    companion object {
        /** The type `wow.peer.discovery` names. */
        const val TYPE: String = "spring-cloud"
    }
}

/**
 * The instances of each peer service in the registry, read through [client] on the service's first request, again
 * once [REFRESH] is past, and again at once when every target failed to connect ([unreachable]), at most once per
 * [REFRESH]; a read that fails, takes longer than [READ_TIMEOUT] or finds no instance keeps the last targets found, as
 * the `dns` discovery keeps its records (decision D325 ②). Each instance is the base URL of its internal port
 * ([WowInstanceMetadata.internalUrlOf]); one whose metadata names no internal port, or a service other than the one
 * looked up, is passed over, with one warning per service.
 */
internal class SpringCloudPeerTargets(
    private val client: ReactiveDiscoveryClient,
    private val clock: TimeSource = TimeSource.Monotonic,
) : PeerTargets {
    private val peers = ConcurrentHashMap<String, Peer>()

    /** One peer service's targets, and when they were read. */
    private class Peer {
        val lock = Mutex()

        @Volatile
        var targets: List<URI> = emptyList()

        @Volatile
        var read: TimeMark? = null

        @Volatile
        var forced: TimeMark? = null

        val warned: MutableSet<Warning> = ConcurrentHashMap.newKeySet()

        fun fresh(): Boolean = read?.let { it.elapsedNow() < REFRESH } == true
    }

    /** What a peer is warned of, once. */
    private enum class Warning { NONE, NO_PORT, OTHER_SERVICE }

    override suspend fun targetsOf(service: String, context: String): List<URI> {
        val peer = peers.computeIfAbsent(service) { Peer() }
        if (peer.fresh()) return peer.targets
        peer.lock.withLock {
            if (peer.fresh()) return peer.targets
            val instances = instancesOf(service)
            val targets = instances?.let { targetsOf(peer, service, context, it) }.orEmpty()
            if (targets.isNotEmpty()) {
                peer.targets = targets
            } else if (instances != null && instances.isEmpty() && peer.warned.add(Warning.NONE)) {
                logger.warn {
                    "The service registry has no instance of $service, peer $context: register its nodes there, or " +
                        "set wow.peers.$context.url."
                }
            }
            peer.read = clock.markNow()
            return peer.targets
        }
    }

    override fun unreachable(service: String, context: String) {
        val peer = peers[service] ?: return
        if (peer.forced?.let { it.elapsedNow() < REFRESH } == true) return
        peer.forced = clock.markNow()
        peer.read = null
    }

    /** The instances of [service] in the registry; null when the read failed or timed out. */
    @Suppress("TooGenericExceptionCaught") // Whatever the registry's client fails with keeps the last targets.
    private suspend fun instancesOf(service: String): List<ServiceInstance>? = try {
        withTimeoutOrNull(READ_TIMEOUT) { client.getInstances(service).asFlow().toList() }
            ?: null.also { logger.debug { "Reading the instances of $service from ${client.description()} timed out." } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: RuntimeException) {
        logger.debug(failure) { "Reading the instances of $service from ${client.description()} failed." }
        null
    }

    /** The internal ports of [instances], those that name one and the [service] they are nodes of. */
    private fun targetsOf(peer: Peer, service: String, context: String, instances: List<ServiceInstance>): List<URI> =
        instances.mapNotNull { instance ->
            val named = instance.metadata.orEmpty()[WowInstanceMetadata.SERVICE]
            val target = WowInstanceMetadata.internalUrlOf(instance)
            when {
                named != service -> null.also {
                    if (peer.warned.add(Warning.OTHER_SERVICE)) {
                        logger.warn {
                            "Instance ${instance.instanceId} of $service in the service registry names " +
                                (named?.let { "service $it" } ?: "no service") +
                                " (metadata ${WowInstanceMetadata.SERVICE}), not $service: it is passed over for peer " +
                                "$context. Register the nodes of $service with wow-spring-cloud on their class path."
                        }
                    }
                }
                target == null -> null.also {
                    if (peer.warned.add(Warning.NO_PORT)) {
                        logger.warn {
                            "Instance ${instance.instanceId} of $service in the service registry names no internal " +
                                "port (metadata ${WowInstanceMetadata.INTERNAL_PORT}): it is passed over for peer " +
                                "$context. Register the nodes of $service with wow-spring-cloud on their class path."
                        }
                    }
                }
                else -> target
            }
        }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** How long a peer's targets are kept before they are read again, as the `dns` discovery keeps its records. */
        val REFRESH: Duration = 30.seconds

        /** How long a read of the registry may take before the last targets are kept. */
        val READ_TIMEOUT: Duration = 5.seconds
    }
}
