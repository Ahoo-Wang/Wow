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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/** What a peer discovery is opened from, and an open discovery's defaults (decision D305). */
class PeerDiscoveryConfigTest {
    @Test
    fun `a configuration should hand out its settings by name`() {
        val config = PeerDiscoveryConfig.of("consul", mapOf("address" to "http://consul:8500", "blank" to " "))
        config.key.assert().isEqualTo("wow.peer.consul")
        config.option("address").assert().isEqualTo("http://consul:8500")
        config.option("blank").assert().isNull()
        config.option("missing").assert().isNull()
        config.toString().assert().contains("consul", "address").doesNotContain("8500")
        PeerDiscoveryConfig.of("dns").options.assert().isEmpty()
        assertThrows<IllegalArgumentException> { PeerDiscoveryConfig.of(" ") }
    }

    @Test
    fun `an open discovery should hear of an unreachable peer and close, doing nothing by default`() {
        val targets = object : PeerTargets {
            override suspend fun targetsOf(service: String, context: String): List<URI> =
                listOf(URI.create("http://$service:9090"))
        }
        targets.use {
            it.unreachable("mail-service", "mail")
            runBlocking { it.targetsOf("mail-service", "mail") }.assert()
                .containsExactly(URI.create("http://mail-service:9090"))
        }
    }
}
