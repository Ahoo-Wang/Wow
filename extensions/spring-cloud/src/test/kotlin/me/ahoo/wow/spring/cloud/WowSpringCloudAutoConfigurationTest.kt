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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spi.peer.PeerDiscoveryConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryClientAutoConfiguration
import org.springframework.cloud.commons.util.UtilAutoConfiguration
import org.springframework.context.ApplicationListener
import java.net.URI

/**
 * wow-spring-cloud wired into a node's application: its discovery and its registration's metadata, and nothing of it
 * in an application that runs no node. Spring Cloud's simple discovery client stands in for a registry.
 */
class WowSpringCloudAutoConfigurationTest {
    private val node = mockk<ServiceNode> {
        every { service } returns "teller-service"
        every { node } returns "teller-1"
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                UtilAutoConfiguration::class.java,
                SimpleReactiveDiscoveryClientAutoConfiguration::class.java,
                WowSpringCloudAutoConfiguration::class.java,
            ),
        )
        .withBean(ServiceNode::class.java, { node })
        .withPropertyValues(
            "spring.cloud.discovery.client.simple.instances.bank-service[0].uri=http://10.0.0.5:8080",
            "spring.cloud.discovery.client.simple.instances.bank-service[0].metadata.wow-service=bank-service",
            "spring.cloud.discovery.client.simple.instances.bank-service[0].metadata.wow-internal-port=9090",
        )

    @Test
    fun `the module should be one of the host's auto-configurations`() {
        ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates.assert()
            .contains(WowSpringCloudAutoConfiguration::class.java.name)
    }

    @Test
    fun `a node should find its peers through the registry, and register its own address`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val discovery = context.getBeansOfType(PeerDiscovery::class.java).values.single()
            discovery.type.assert().isEqualTo(SpringCloudPeerDiscovery.TYPE)
            val targets = discovery.open(PeerDiscoveryConfig.of(SpringCloudPeerDiscovery.TYPE))
            runBlocking { targets.targetsOf("bank-service", "bank") }.assert()
                .containsExactly(URI.create("http://10.0.0.5:9090"))
            context.getBeanNamesForType(ApplicationListener::class.java).toList().assert()
                .contains("wowRegistrationMetadata")
        }
    }

    @Test
    fun `a node that keeps another discovery should start, told how to switch`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(SpringCloudPeerDiscovery::class.java).isOpened.assert().isFalse()
        }
    }

    @Test
    fun `an application that runs no node should get nothing of the module`() {
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(ServiceNode::class.java))
            .withConfiguration(AutoConfigurations.of(WowSpringCloudAutoConfiguration::class.java))
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(PeerDiscovery::class.java).assert().isEmpty()
                context.getBeansOfType(WowRegistrationMetadata::class.java).assert().isEmpty()
            }
    }
}
