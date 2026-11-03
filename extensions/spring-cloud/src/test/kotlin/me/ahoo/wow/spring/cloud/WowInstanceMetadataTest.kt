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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.cloud.client.DefaultServiceInstance
import java.net.URI

/** The metadata of a node's instance in the registry, and the internal port it gives a peer. */
class WowInstanceMetadataTest {
    @Test
    fun `a node's metadata should be its service and the host and port of its internal address`() {
        WowInstanceMetadata.of("bank-service", "10.0.0.5:9090").assert().containsExactlyEntriesOf(
            linkedMapOf(
                "wow-service" to "bank-service",
                "wow-internal-host" to "10.0.0.5",
                "wow-internal-port" to "9090",
            ),
        )
        WowInstanceMetadata.of("bank-service", "[fd00::5]:9090")[WowInstanceMetadata.INTERNAL_HOST].assert()
            .isEqualTo("fd00::5")
        assertThrows<IllegalArgumentException> { WowInstanceMetadata.of("bank-service", "10.0.0.5") }
        assertThrows<IllegalArgumentException> { WowInstanceMetadata.of("bank-service", "10.0.0.5:http") }
    }

    @Test
    fun `an instance's internal port should be at the host and port its metadata names`() {
        WowInstanceMetadata.internalUrlOf(instance("10.0.0.9", WowInstanceMetadata.of("bank-service", "10.0.0.5:9090")))
            .assert().isEqualTo(URI.create("http://10.0.0.5:9090"))
        // An instance registered by hand may name the port alone: its own host then.
        WowInstanceMetadata.internalUrlOf(instance("10.0.0.9", mapOf(WowInstanceMetadata.INTERNAL_PORT to "9090")))
            .assert().isEqualTo(URI.create("http://10.0.0.9:9090"))
        WowInstanceMetadata.internalUrlOf(instance("fd00::9", mapOf(WowInstanceMetadata.INTERNAL_PORT to "9090")))
            .assert().isEqualTo(URI.create("http://[fd00::9]:9090"))
        WowInstanceMetadata.internalUrlOf(
            instance("10.0.0.9", WowInstanceMetadata.of("bank-service", "[fd00::5]:9090"))
        )
            .assert().isEqualTo(URI.create("http://[fd00::5]:9090"))
    }

    @Test
    fun `an instance whose metadata names no port should have no internal port`() {
        WowInstanceMetadata.internalUrlOf(instance("10.0.0.9", emptyMap())).assert().isNull()
        WowInstanceMetadata.internalUrlOf(instance("10.0.0.9", mapOf(WowInstanceMetadata.INTERNAL_PORT to "x")))
            .assert().isNull()
        WowInstanceMetadata.internalUrlOf(instance("10.0.0.9", mapOf(WowInstanceMetadata.INTERNAL_PORT to "0")))
            .assert().isNull()
        WowInstanceMetadata.internalUrlOf(instance("10.0.0.9", mapOf(WowInstanceMetadata.INTERNAL_PORT to "65536")))
            .assert().isNull()
    }

    private fun instance(host: String, metadata: Map<String, String>) =
        DefaultServiceInstance("bank-1", "bank-service", host, 8080, false, metadata)
}
