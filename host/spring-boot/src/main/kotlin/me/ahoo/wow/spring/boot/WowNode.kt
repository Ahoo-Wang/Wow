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

import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.core.env.Environment
import java.net.InetAddress
import java.util.UUID

/**
 * Who this node is (decisions D72, D110): the [service] it belongs to, its [node] id and the [address] of its
 * internal port.
 */
data class NodeIdentity(val service: String, val node: String, val address: String) {
    companion object {
        private const val SUFFIX_LENGTH = 8

        /**
         * The identity [properties] configure: `wow.service`, else `spring.application.name`; `wow.node.id`, else
         * the host name with a random suffix, so a restarted node is a new member; `wow.node.address`, else the
         * internal server's bind address (this host's when it binds every interface) and `wow.internal.port`.
         */
        fun of(properties: WowProperties, environment: Environment): NodeIdentity {
            val service = properties.service?.takeIf { it.isNotBlank() }
                ?: environment.getProperty("spring.application.name")?.takeIf { it.isNotBlank() }
                ?: error("Name the service: set wow.service or spring.application.name.")
            val local = InetAddress.getLocalHost()
            val node = properties.node.id?.takeIf { it.isNotBlank() }
                ?: "${local.hostName}-${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}"
            val host = properties.internal.host?.takeUnless { it.isBlank() || it == "0.0.0.0" || it == "::" }
                ?: local.hostAddress
            val address = properties.node.address?.takeIf { it.isNotBlank() } ?: "$host:${properties.internal.port}"
            return NodeIdentity(service, node, address)
        }
    }
}

/**
 * The aggregate models the node serves and the [providers] they come from (the binding index, decision D107), with
 * their factories' parameters from the application's beans.
 */
class WowModels(val providers: List<ModelProvider>, val models: List<AggregateModel<*>>)

/** Hears each step of [ServiceNode.stop] once it is done, in order: a bean of the application's. */
fun interface ServiceNodeListener {
    fun onStep(step: ServiceNode.Step)
}
