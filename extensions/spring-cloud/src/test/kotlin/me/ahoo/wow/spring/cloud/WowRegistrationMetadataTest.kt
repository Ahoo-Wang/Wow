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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.cloud.client.DefaultServiceInstance
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent
import org.springframework.cloud.client.serviceregistry.Registration
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration
import org.springframework.cloud.zookeeper.discovery.ZookeeperInstance
import org.springframework.cloud.zookeeper.serviceregistry.ServiceInstanceRegistration

/**
 * A node's registration carries the address it advertises to its members (decision D396), in whichever registry: the
 * registration's own metadata (Consul, Nacos), Eureka's instance and Zookeeper's payload.
 */
class WowRegistrationMetadataTest {
    private val node = mockk<ServiceNode> {
        every { service } returns "bank-service"
        every { node } returns "bank-1"
        every { hasAddress } returns true
        every { address } returns "10.0.0.5:9090"
    }

    private val expected = WowInstanceMetadata.of("bank-service", "10.0.0.5:9090")

    @Test
    fun `a registration should carry the node's service and internal address`() {
        val registration = registration(mutableMapOf("zone" to "a"))
        WowRegistrationMetadata({ node }).onApplicationEvent(InstancePreRegisteredEvent(this, registration))
        registration.metadata.assert().containsAllEntriesOf(expected).containsEntry("zone", "a")
    }

    @Test
    fun `an application without a node, or a node without its address yet, should register nothing of its own`() {
        val registration = registration(mutableMapOf())
        WowRegistrationMetadata({ null }).onApplicationEvent(InstancePreRegisteredEvent(this, registration))
        registration.metadata.assert().isEmpty()

        every { node.hasAddress } returns false
        WowRegistrationMetadata({ node }).onApplicationEvent(InstancePreRegisteredEvent(this, registration))
        registration.metadata.assert().isEmpty()
    }

    @Test
    fun `a registration that takes no metadata should fail the start, naming what to register`() {
        val fixed = assertThrows<IllegalStateException> {
            WowRegistrationMetadata({ node }).onApplicationEvent(
                InstancePreRegisteredEvent(this, registration(emptyMap())),
            )
        }
        fixed.message.assert().contains("bank-service").contains("wow-internal-port=9090")
        fixed.cause.assert().isInstanceOf(UnsupportedOperationException::class.java)

        val none = assertThrows<IllegalStateException> {
            WowRegistrationMetadata({ node }).onApplicationEvent(InstancePreRegisteredEvent(this, registration(null)))
        }
        none.message.assert().contains("takes no metadata")
    }

    @Test
    fun `an Eureka registration should give Eureka's instance the metadata it copied before`() {
        val metadata = mutableMapOf<String, String>()
        val infos = mockk<ApplicationInfoManager>(relaxed = true)
        val registration = mockk<EurekaRegistration> {
            every { serviceId } returns "BANK-SERVICE"
            every { getMetadata() } returns metadata
            every { applicationInfoManager } returns infos
        }
        WowRegistrationMetadata({ node }).onApplicationEvent(InstancePreRegisteredEvent(this, registration))
        metadata.assert().containsAllEntriesOf(expected)
        verify { infos.registerAppMetadata(expected) }
    }

    @Test
    fun `a Zookeeper registration should be built and its payload carry the metadata`() {
        val payload = ZookeeperInstance("bank-1", "bank-service", mutableMapOf())
        val registration = ServiceInstanceRegistration.builder()
            .defaultUriSpec()
            .address("10.0.1.1")
            .port(8080)
            .name("bank-service")
            .payload(payload)
            .build()
        // Zookeeper builds its instance on first use: its metadata is none before.
        assertThrows<UnsupportedOperationException> { registration.metadata!!.put("x", "y") }
        WowRegistrationMetadata({ node }).onApplicationEvent(InstancePreRegisteredEvent(this, registration))
        payload.metadata.assert().containsAllEntriesOf(expected)
        registration.metadata.assert().containsAllEntriesOf(expected)
        // Its port, set when it registers, builds the instance again from the same payload.
        registration.setPort(8081)
        registration.serviceInstance.payload.metadata.assert().containsAllEntriesOf(expected)
    }

    @Test
    fun `without Eureka or Zookeeper on the class path, a registration should take its own metadata alone`() {
        val registration = registration(mutableMapOf())
        WowRegistrationMetadata({ node }, Hiding(javaClass.classLoader))
            .onApplicationEvent(InstancePreRegisteredEvent(this, registration))
        registration.metadata.assert().containsAllEntriesOf(expected)
    }

    /** A registration, its [metadata] as the registry keeps it. */
    private fun registration(metadata: Map<String, String>?): Registration {
        val instance: ServiceInstance = DefaultServiceInstance("bank-1", "bank-service", "10.0.1.1", 8080, false)
        return object : Registration, ServiceInstance by instance {
            override fun getMetadata(): Map<String, String>? = metadata
        }
    }

    /** A class loader that has neither Eureka nor Zookeeper, as an application registering with Consul. */
    private class Hiding(parent: ClassLoader) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("org.springframework.cloud.netflix") || name.startsWith("org.springframework.cloud.zookeeper")) {
                throw ClassNotFoundException(name)
            }
            return super.loadClass(name, resolve)
        }
    }
}
