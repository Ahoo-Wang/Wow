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

package me.ahoo.wow.spring.boot.node

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.runtime.cluster.NodeAddress
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spring.boot.WowProperties
import me.ahoo.wow.spring.boot.config.DevelopmentMode
import org.springframework.core.env.Environment
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

/**
 * Who this node is: the [service] it belongs to, its [node] id and the [address] of its
 * internal port, null while the port is not bound yet when the system chooses it (`wow.internal.port=0`): the node
 * then learns it as [host] and the bound port ([ServiceNode.bind]).
 *
 * [bindHost] is the interface the internal server binds: `wow.internal.host` when set
 * (`0.0.0.0` or `::` for every interface), else the host the node advertises to its members, that of
 * `wow.node.address` or [host], so the unauthenticated internal port is not open on every interface by default. With
 * neither set, [host] is the loopback address in a profile of `wow.dev-profiles`, and otherwise the address this host's
 * name resolves to, refused when that is a public one.
 */
@InternalWowApi
data class NodeIdentity(
    val service: String,
    val node: String,
    val address: String?,
    val host: String,
    val bindHost: String,
    /** The property that gave [service]: `wow.service` when it is set, else `spring.application.name`. */
    val serviceKey: String = WOW_SERVICE,
) {
    /**
     * The address of the internal port bound at [port]: [address] when configured, else [host] and [port]
     * ([NodeAddress.of]: an IPv6 literal in brackets).
     */
    fun addressAt(port: Int): String = address ?: NodeAddress.of(host, port)

    @InternalWowApi
    companion object {
        private const val SUFFIX_LENGTH = 8

        /**
         * The identity [properties] configure: `wow.service`, else `spring.application.name`; `wow.node.id`, else
         * the host name with a random suffix, so a restarted node is a new member; `wow.node.address`, else the
         * internal server's bind address (this host's when it binds every interface) and `wow.internal.port`, or,
         * when that port is 0, the port the internal server is bound at, known once it is, written as
         * [NodeAddress.of] writes an address (`[::1]:9191` for an IPv6 host); the [bindHost], `wow.internal.host`,
         * else the host of that member address. A `wow.node.address` that is a bare IPv6 literal with a port fails.
         *
         * With neither `wow.internal.host` nor `wow.node.address`, the node in a profile of `wow.dev-profiles` binds
         * and advertises [LOOPBACK]; any other binds and advertises [local], the address this host's name resolves
         * to, and fails when that is a public address ([isPublic]): the internal port takes no credential. An address
         * the operator names is taken as written.
         */
        fun of(
            properties: WowProperties,
            environment: Environment,
            local: InetAddress = InetAddress.getLocalHost(),
        ): NodeIdentity {
            val own = properties.service?.takeIf { it.isNotBlank() }
            val service = own
                ?: environment.getProperty(APPLICATION_NAME)?.takeIf { it.isNotBlank() }
                ?: throw WowConfigurationException(
                    WOW_SERVICE,
                    null,
                    "The service has no name.",
                    "Set $WOW_SERVICE or $APPLICATION_NAME.",
                )
            val serviceKey = if (own != null) WOW_SERVICE else APPLICATION_NAME
            // The name it states on the internal port (`Wow-Service`), which a peer lists it by.
            configCheck(
                DeclaredServices.isName(service),
                serviceKey,
                service,
                "Name it with letters, digits, '.', '_' and '-' alone, such as ordering-service.",
            ) { "The service's name [$service] is no name its nodes can state on the internal port." }
            val node = properties.node.id?.takeIf { it.isNotBlank() }
                ?: "${local.hostName}-${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}"
            val named = properties.internal.host?.takeIf { it.isNotBlank() }
            val configured = properties.node.address?.takeIf { it.isNotBlank() }?.also(::checkAddress)
            val host = named?.takeUnless { it == "0.0.0.0" || it == "::" }
                ?: if (named == null && configured == null) {
                    defaultHost(properties, environment, local)
                } else {
                    local.hostAddress
                }
            val port = properties.internal.port
            val address = configured ?: NodeAddress.of(host, port).takeIf { port != 0 }
            val bindHost = named ?: configured?.let(NodeAddress::hostOf) ?: host
            return NodeIdentity(service, node, address, host, bindHost, serviceKey)
        }

        /** The property that names the service before Spring's application name. */
        const val WOW_SERVICE: String = "wow.service"

        /** Spring Boot's application name, the service's name when [WOW_SERVICE] is not set. */
        const val APPLICATION_NAME: String = "spring.application.name"

        /** The address the internal port binds and advertises by default in a profile of `wow.dev-profiles`. */
        const val LOOPBACK: String = "127.0.0.1"

        /**
         * Whether [address] is reachable from outside a private network: none of the loopback, private (`10/8`,
         * `172.16/12`, `192.168/16`), link-local, shared (`100.64/10`, carrier-grade NAT) or IPv6 unique-local
         * (`fc00::/7`) and site-local addresses, nor the wildcard.
         */
        fun isPublic(address: InetAddress): Boolean {
            val bytes = address.address
            val shared = address is Inet4Address &&
                bytes[0].toInt() and BYTE == SHARED_FIRST && bytes[1].toInt() and SHARED_MASK == SHARED_SECOND
            val uniqueLocal = address is Inet6Address && bytes[0].toInt() and ULA_MASK == ULA_PREFIX
            return !(
                address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress ||
                    address.isLinkLocalAddress || shared || uniqueLocal
                )
        }

        /**
         * The host of a node of [properties] whose operator names no address: [LOOPBACK] in a profile of
         * `wow.dev-profiles`, else [local], the address this host's name resolves to, refused when it is public.
         */
        private fun defaultHost(properties: WowProperties, environment: Environment, local: InetAddress): String {
            if (DevelopmentMode.of(properties, environment).byProfile) {
                logger.info {
                    "The internal port binds $LOOPBACK, in a development profile: nodes on other machines cannot " +
                        "reach it. Set wow.internal.host to run the service across machines."
                }
                return LOOPBACK
            }
            checkPrivate(local)
            return local.hostAddress
        }

        /**
         * Refuses [local], the address this host's name resolves to, as the default of a port that takes no credential
         * when it is public: the operator names the address instead.
         */
        private fun checkPrivate(local: InetAddress) {
            configCheck(
                !isPublic(local),
                "wow.internal.host",
                local.hostAddress,
                "Set wow.internal.host to the private address the other nodes reach this node at, or to this " +
                    "address itself where a firewall admits the platform's machines alone.",
            ) {
                "The internal port would listen on ${local.hostAddress}, a public address this host's name " +
                    "(${local.hostName}) resolves to; it takes no credential."
            }
        }

        /**
         * Refuses a `wow.node.address` the internal port's clients cannot reach ([NodeAddress.isValid]): a bare IPv6
         * literal, a host without its port, or a port outside 1 to 65535.
         */
        private fun checkAddress(address: String) {
            configCheck(
                NodeAddress.isValid(address),
                "wow.node.address",
                address,
                "Write host:port, the port the internal port listens on (1 to 65535), and an IPv6 host in brackets, " +
                    "such as [::1]:9191, as RFC 3986 does.",
            ) { "wow.node.address [$address] is no host and port the other nodes can reach." }
        }

        private const val BYTE = 0xFF
        private const val SHARED_FIRST = 100
        private const val SHARED_SECOND = 64
        private const val SHARED_MASK = 0xC0
        private const val ULA_PREFIX = 0xFC
        private const val ULA_MASK = 0xFE

        private val logger = KotlinLogging.logger {}
    }
}

/**
 * The aggregate models the node serves and the [providers] they come from (the binding index), with
 * their factories' parameters from the application's beans.
 */
@InternalWowApi
class WowModels(val providers: List<ModelProvider>, val models: List<AggregateModel<*>>)

/** Hears each step of [ServiceNode.stop] once it is done, in order: a bean of the application's. */
fun interface ServiceNodeListener {
    fun onStep(step: ServiceNode.Step)
}
