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

package me.ahoo.wow.spi.peer

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.store.StoreResources
import java.net.URI

/**
 * Where the internal ports of a peer service's nodes are (decision D305): the seeds a node calls a peer at, its sagas'
 * synchronous calls and its typed clients alike. Wow brings `dns` (the default: DNS SRV records, as Elasticsearch
 * finds its seeds) and `static` (`wow.peers.<context>.url` alone); a community provider (Consul, the Kubernetes API)
 * is found as a [me.ahoo.wow.spi.transport.TransportProvider] is: on the classpath (`ServiceLoader`, from
 * `META-INF/services/me.ahoo.wow.spi.peer.PeerDiscovery`) and among the application's beans, configured by the keys
 * under `wow.peer.<type>`.
 *
 * Experimental: owner-direct routing (calling the node that owns an aggregate, a 10.x candidate) may need the peers'
 * membership and partition owners from it.
 */
@WowSpi
@ExperimentalWowApi
interface PeerDiscovery {
    /** The type `wow.peer.discovery` names: lowercase, unique among the providers of a node. */
    val type: String

    /** Opens the discovery [config] configures. */
    fun open(config: PeerDiscoveryConfig): PeerTargets
}

/**
 * One open discovery: the internal ports of a peer's nodes, in no particular order, which the client rotates through
 * and fails over between (decision D305). A target is the base URL of a node's internal port, `http://host:port`; a
 * peer whose nodes advertise their pod IPs (decision D319) may be found by names that resolve to them, so a target is
 * compared with a member's address by its resolved IP, never by its text.
 */
@WowSpi
@ExperimentalWowApi
interface PeerTargets : AutoCloseable {
    /**
     * The targets of the peer [service], which serves [context]; empty when none is found now, which fails the call.
     * Asked on every request, so an answer may be cached, but not for longer than the discovery's own refresh.
     */
    suspend fun targetsOf(service: String, context: String): List<URI>

    /** Hears that every target of [service] failed to connect, so the discovery may look again before it would. */
    fun unreachable(service: String, context: String) = Unit

    override fun close() = Unit
}

/**
 * What a [PeerDiscovery] opens its targets from: its [type], its own settings ([options], each key relative to
 * `wow.peer.<type>`, `name-template` say) and the application's [resources].
 *
 * Fields may be added (decision D216): the host builds it, so the constructor is internal to Wow; a provider's test
 * builds one with [of].
 */
@WowSpi
@ExperimentalWowApi
class PeerDiscoveryConfig @InternalWowApi constructor(
    val type: String,
    val options: Map<String, String> = emptyMap(),
    val resources: StoreResources = StoreResources.NONE,
) {
    init {
        require(type.isNotBlank()) { "A peer discovery needs a type." }
    }

    /** The configuration key of the discovery's settings, for a provider's failures to name. */
    val key: String get() = "wow.peer.$type"

    /** The setting [name] of [options], or null when it is not set or blank. */
    fun option(name: String): String? = options[name]?.takeIf { it.isNotBlank() }

    override fun toString(): String = "PeerDiscoveryConfig(type=$type, options=${options.keys})"

    companion object {
        /** A configuration of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            type: String,
            options: Map<String, String> = emptyMap(),
            resources: StoreResources = StoreResources.NONE,
        ): PeerDiscoveryConfig = PeerDiscoveryConfig(type, options, resources)
    }
}
