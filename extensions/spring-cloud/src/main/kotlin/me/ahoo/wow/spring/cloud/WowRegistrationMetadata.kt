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

import com.netflix.appinfo.ApplicationInfoManager
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent
import org.springframework.cloud.client.serviceregistry.Registration
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration
import org.springframework.cloud.zookeeper.serviceregistry.ZookeeperRegistration
import org.springframework.context.ApplicationListener
import org.springframework.util.ClassUtils

/**
 * Gives the registration of this node in the application's service registry its [WowInstanceMetadata]: its service and
 * the address it advertises to its members ([ServiceNode.address]), so a peer that looks the service up there reaches
 * its internal port. Spring Cloud announces the registration ([InstancePreRegisteredEvent]) just before it registers
 * it, once the public server is up, so after the node has bound its internal port, a port the system chose included.
 *
 * Consul, Nacos and Zookeeper send the registration's metadata as it is when they register it, so it is written there;
 * Eureka copies it when its instance's bean is built, so it is given to Eureka's [ApplicationInfoManager] too, and
 * Zookeeper builds its instance on first use, so it is built first. A registration whose metadata takes no entry fails
 * the start: its peers would never find the node.
 */
@ExperimentalWowApi
class WowRegistrationMetadata(
    /** The node, absent in an application that runs none. */
    private val node: () -> ServiceNode?,
    classLoader: ClassLoader? = WowRegistrationMetadata::class.java.classLoader,
) : ApplicationListener<InstancePreRegisteredEvent> {
    private val eureka = ClassUtils.isPresent(EUREKA_REGISTRATION, classLoader)
    private val zookeeper = ClassUtils.isPresent(ZOOKEEPER_REGISTRATION, classLoader)

    override fun onApplicationEvent(event: InstancePreRegisteredEvent) {
        val node = node() ?: return
        val registration = event.registration
        if (!node.hasAddress) {
            logger.warn {
                "Registration ${registration.serviceId} is made before node ${node.node} has its internal address: " +
                    "its peers will not find it in the registry."
            }
            return
        }
        val metadata = WowInstanceMetadata.of(node.service, node.address)
        val registered = metadataOf(registration) ?: throw noMetadata(registration, node, metadata, null)
        try {
            registered.putAll(metadata)
        } catch (refused: UnsupportedOperationException) {
            throw noMetadata(registration, node, metadata, refused)
        }
        if (eureka) Eureka.register(registration, metadata)
        logger.info { "Node ${node.node} registers as ${registration.serviceId} with $metadata." }
    }

    private fun noMetadata(
        registration: Registration,
        node: ServiceNode,
        metadata: Map<String, String>,
        refused: UnsupportedOperationException?,
    ) = IllegalStateException(
        "The registration ${registration.serviceId} (${registration.javaClass.name}) takes no metadata, so its peers " +
            "would not find node ${node.node} in the registry: register it with the metadata $metadata, or have its " +
            "peers call it at wow.peers.<context>.url.",
        refused,
    )

    /** The map the registry sends of [registration]; null when it has none. */
    private fun metadataOf(registration: Registration): MutableMap<String, String>? =
        (if (zookeeper) Zookeeper.metadataOf(registration) else null) ?: registration.metadata

    /** Eureka's registration, apart so that the class is only loaded with Eureka on the class path. */
    private object Eureka {
        fun register(registration: Registration, metadata: Map<String, String>) {
            if (registration is EurekaRegistration) registration.applicationInfoManager.registerAppMetadata(metadata)
        }
    }

    /** Zookeeper's registration, apart so that the class is only loaded with Zookeeper on the class path. */
    private object Zookeeper {
        /** The metadata of the instance [registration] registers, which it builds on first use. */
        fun metadataOf(registration: Registration): MutableMap<String, String>? =
            (registration as? ZookeeperRegistration)?.serviceInstance?.payload?.metadata
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
        private const val EUREKA_REGISTRATION =
            "org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration"
        private const val ZOOKEEPER_REGISTRATION =
            "org.springframework.cloud.zookeeper.serviceregistry.ZookeeperRegistration"
    }
}
