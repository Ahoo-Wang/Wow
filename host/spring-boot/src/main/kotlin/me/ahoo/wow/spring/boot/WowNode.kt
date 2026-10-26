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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck
import org.springframework.core.env.Environment
import java.net.InetAddress
import java.util.UUID

/**
 * Who this node is (decisions D72, D110): the [service] it belongs to, its [node] id and the [address] of its
 * internal port, null while the port is not bound yet when the system chooses it (`wow.internal.port=0`): the node
 * then learns it as [host] and the bound port ([ServiceNode.bind]).
 *
 * [bindHost] is the interface the internal server binds (decisions D173, D195): `wow.internal.host` when set
 * (`0.0.0.0` or `::` for every interface), else the host the node advertises to its members, that of
 * `wow.node.address` or [host], so the unauthenticated internal port is not open on every interface by default.
 */
@InternalWowApi
data class NodeIdentity(
    val service: String,
    val node: String,
    val address: String?,
    val host: String,
    val bindHost: String,
) {
    /** The address of the internal port bound at [port]: [address] when configured, else [host] and [port]. */
    fun addressAt(port: Int): String = address ?: "$host:$port"

    @InternalWowApi
    companion object {
        private const val SUFFIX_LENGTH = 8

        /**
         * The identity [properties] configure: `wow.service`, else `spring.application.name`; `wow.node.id`, else
         * the host name with a random suffix, so a restarted node is a new member; `wow.node.address`, else the
         * internal server's bind address (this host's when it binds every interface) and `wow.internal.port`, or,
         * when that port is 0, the port the internal server is bound at, known once it is; the [bindHost],
         * `wow.internal.host`, else the host of that member address.
         */
        fun of(properties: WowProperties, environment: Environment): NodeIdentity {
            val service = properties.service?.takeIf { it.isNotBlank() }
                ?: environment.getProperty("spring.application.name")?.takeIf { it.isNotBlank() }
                ?: throw WowConfigurationException(
                    "wow.service",
                    null,
                    "The service has no name.",
                    "Set wow.service or spring.application.name.",
                )
            // The name it states on the internal port (`Wow-Service`, decision D300), which a peer lists it by.
            configCheck(
                DeclaredServices.isName(service),
                "wow.service",
                service,
                "Name it with letters, digits, '.', '_' and '-' alone, such as ordering-service.",
            ) { "The service's name [$service] is no name its nodes can state on the internal port." }
            val local = InetAddress.getLocalHost()
            val node = properties.node.id?.takeIf { it.isNotBlank() }
                ?: "${local.hostName}-${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}"
            val host = properties.internal.host?.takeUnless { it.isBlank() || it == "0.0.0.0" || it == "::" }
                ?: local.hostAddress
            val port = properties.internal.port
            val configured = properties.node.address?.takeIf { it.isNotBlank() }
            val address = configured ?: "$host:$port".takeIf { port != 0 }
            val bindHost = properties.internal.host?.takeIf { it.isNotBlank() } ?: configured?.let(::hostOf) ?: host
            return NodeIdentity(service, node, address, host, bindHost)
        }

        /** The host of [address], `host:port` or `[ipv6]:port`, without the brackets of an IPv6 literal. */
        internal fun hostOf(address: String): String =
            if (address.startsWith("[")) {
                address.substringAfter('[').substringBefore(']')
            } else {
                address.substringBeforeLast(':')
            }
    }
}

/**
 * The aggregate models the node serves and the [providers] they come from (the binding index, decision D107), with
 * their factories' parameters from the application's beans.
 */
@InternalWowApi
class WowModels(val providers: List<ModelProvider>, val models: List<AggregateModel<*>>)

/** Hears each step of [ServiceNode.stop] once it is done, in order: a bean of the application's. */
fun interface ServiceNodeListener {
    fun onStep(step: ServiceNode.Step)
}
