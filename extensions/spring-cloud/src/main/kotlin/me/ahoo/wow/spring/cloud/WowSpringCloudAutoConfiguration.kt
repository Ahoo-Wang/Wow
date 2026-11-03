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
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.util.ClassUtils

/**
 * Wires a Wow service to the application's Spring Cloud service registry (decision D396), when it runs a node (the
 * runtime is on the class path): its registration carries the node's internal address ([WowRegistrationMetadata]),
 * and `wow.peer.discovery=spring-cloud` finds its peers there ([SpringCloudPeerDiscovery]), the host taking every
 * [me.ahoo.wow.spi.peer.PeerDiscovery] bean. A node that keeps another discovery is told once, at startup, how to
 * switch. This module lists it in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
 * and imports no Spring Boot type. An application that is no Wow service takes [LoadBalancedEndpoints] alone.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(WowSpringCloudAutoConfiguration.RunsNode::class)
@ExperimentalWowApi
class WowSpringCloudAutoConfiguration {
    @Bean
    fun wowSpringCloudPeerDiscovery(clients: ObjectProvider<ReactiveDiscoveryClient>): SpringCloudPeerDiscovery =
        SpringCloudPeerDiscovery { clients.getIfAvailable() }

    @Bean
    fun wowRegistrationMetadata(nodes: ObjectProvider<ServiceNode>): WowRegistrationMetadata =
        WowRegistrationMetadata({ nodes.getIfAvailable() })

    /** Tells, once the node's beans are built, how to find its peers in the registry when it does not. */
    @Bean
    fun wowSpringCloudDiscoveryHint(
        discovery: SpringCloudPeerDiscovery,
        nodes: ObjectProvider<ServiceNode>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val node = nodes.getIfAvailable()
        if (node != null && !discovery.isOpened) {
            logger.info {
                "wow-spring-cloud registers node ${node.node} of ${node.service} in the service registry, but the " +
                    "node does not find its peers there: set wow.peer.discovery=${SpringCloudPeerDiscovery.TYPE} " +
                    "to find them through Spring Cloud."
            }
        }
    }

    /** The application runs a Wow node: the runtime is on the class path. */
    internal class RunsNode : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            ClassUtils.isPresent(SERVICE_NODE, context.classLoader)
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
        private const val SERVICE_NODE = "me.ahoo.wow.runtime.node.ServiceNode"
    }
}
