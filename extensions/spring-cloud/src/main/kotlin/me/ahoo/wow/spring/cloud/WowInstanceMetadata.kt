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

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import org.springframework.cloud.client.ServiceInstance
import java.net.URI

/**
 * The metadata a Wow node's instance carries in a Spring Cloud service registry: the [SERVICE] it is a
 * node of, and the [INTERNAL_HOST] and [INTERNAL_PORT] of the address it advertises to the nodes of its service, where
 * its internal port takes its peers' calls, so the registry and the node's membership name one address. The keys have
 * dashes, not dots: Consul takes no dot in a metadata key.
 *
 * A node of a service with wow-spring-cloud on its class path registers them itself; an instance registered otherwise
 * names them in its registry's own metadata configuration (`spring.cloud.consul.discovery.metadata.*`,
 * `eureka.instance.metadata-map.*`, `spring.cloud.nacos.discovery.metadata.*`, ...).
 */
@ExperimentalWowApi
object WowInstanceMetadata {
    /** The service the instance is a node of, its `wow.service` (else `spring.application.name`). */
    const val SERVICE: String = "wow-service"

    /** The host of the node's internal address, `wow.node.address`'s or its default; the instance's host when absent. */
    const val INTERNAL_HOST: String = "wow-internal-host"

    /** The port of the node's internal address. */
    const val INTERNAL_PORT: String = "wow-internal-port"

    private const val MAX_PORT = 65535

    /** The metadata of a node of [service] whose internal [address] is `host:port` or `[ipv6]:port`. */
    @JvmStatic
    fun of(service: String, address: String): Map<String, String> {
        val separator = address.lastIndexOf(':')
        require(separator > 0) { "A node's internal address is host:port: $address" }
        val host = address.substring(0, separator).removePrefix("[").removeSuffix("]")
        val port = address.substring(separator + 1)
        require(portOf(port) != null) { "A node's internal address has a port: $address" }
        return linkedMapOf(SERVICE to service, INTERNAL_HOST to host, INTERNAL_PORT to port)
    }

    /**
     * The base URL of the internal port of [instance], `http://host:port`: the host its metadata names, else the
     * instance's own, and the port its metadata names; null when its metadata names no port.
     */
    @JvmStatic
    fun internalUrlOf(instance: ServiceInstance): URI? {
        val metadata = instance.metadata.orEmpty()
        val port = metadata[INTERNAL_PORT]?.let(::portOf) ?: return null
        val host = metadata[INTERNAL_HOST]?.takeIf { it.isNotBlank() } ?: instance.host
        // The constructor brackets an IPv6 literal.
        return URI("http", null, host.removePrefix("[").removeSuffix("]"), port, null, null, null)
    }

    private fun portOf(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_PORT }
}
