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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.WowProperties
import me.ahoo.wow.spring.boot.config.DevelopmentMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment
import java.net.InetAddress

/**
 * Where the internal port binds when the operator names no address (#6662, #6667): the loopback interface in a
 * development profile, otherwise the address this host's name resolves to, refused when that is public; an address the
 * operator names is taken as written, and only an address the operator names makes a node a development one.
 */
class NodeIdentityDefaultsTest {
    private fun environment(vararg profiles: String) = MockEnvironment()
        .withProperty("spring.application.name", "app")
        .apply { setActiveProfiles(*profiles) }

    private fun resolved(name: String, vararg bytes: Int): InetAddress =
        InetAddress.getByAddress(name, bytes.map(Int::toByte).toByteArray())

    private val lan = resolved("laptop.local", 192, 168, 71, 40)
    private val public = resolved("vm-1.example", 203, 0, 113, 7)
    private val debian = resolved("debian", 127, 0, 1, 1)

    private fun properties(block: WowProperties.() -> Unit = {}) =
        WowProperties().apply { internal.port = 9300 }.apply(block)

    @Test
    fun `in a development profile the internal port binds and advertises the loopback address`() {
        val identity = NodeIdentity.of(properties(), environment("dev"), lan)
        identity.bindHost.assert().isEqualTo("127.0.0.1")
        identity.address.assert().isEqualTo("127.0.0.1:9300")
        // Spring's default profiles count when none is active, as for every development safeguard.
        val defaulted = MockEnvironment().withProperty("spring.application.name", "app")
            .apply { setDefaultProfiles("local") }
        NodeIdentity.of(properties(), defaulted, lan).bindHost.assert().isEqualTo("127.0.0.1")
        // A public address this host resolves to is not used there, so nothing is refused.
        NodeIdentity.of(properties(), environment("dev"), public).bindHost.assert().isEqualTo("127.0.0.1")
    }

    @Test
    fun `in a development profile an address the operator names is taken as written`() {
        NodeIdentity.of(properties { internal.host = "0.0.0.0" }, environment("dev"), lan).let {
            it.bindHost.assert().isEqualTo("0.0.0.0")
            it.address.assert().isEqualTo("192.168.71.40:9300")
        }
        NodeIdentity.of(properties { internal.host = "::" }, environment("dev"), lan).let {
            it.bindHost.assert().isEqualTo("::")
            it.address.assert().isEqualTo("192.168.71.40:9300")
        }
        NodeIdentity.of(properties { node.address = "10.0.0.1:9191" }, environment("dev"), lan).let {
            it.bindHost.assert().isEqualTo("10.0.0.1")
            it.address.assert().isEqualTo("10.0.0.1:9191")
        }
        NodeIdentity.of(properties { internal.host = "192.168.71.40" }, environment("dev"), lan)
            .bindHost.assert().isEqualTo("192.168.71.40")
    }

    @Test
    fun `outside a development profile the internal port binds the address this host's name resolves to`() {
        val identity = NodeIdentity.of(properties(), environment("prod"), lan)
        identity.bindHost.assert().isEqualTo("192.168.71.40")
        identity.address.assert().isEqualTo("192.168.71.40:9300")
        NodeIdentity.of(properties(), environment(), lan).bindHost.assert().isEqualTo("192.168.71.40")
    }

    @Test
    fun `a public address this host's name resolves to refuses the start, naming the key and the address`() {
        val failure = assertThrows<WowConfigurationException> {
            NodeIdentity.of(properties(), environment("prod"), public)
        }
        failure.key.assert().isEqualTo("wow.internal.host")
        failure.value.assert().isEqualTo("203.0.113.7")
        failure.reason.assert().contains("203.0.113.7").contains("vm-1.example").contains("no credential")
        failure.fix.assert().contains("wow.internal.host")
        // Named, it is taken as written: the operator answers for the firewall.
        NodeIdentity.of(properties { internal.host = "203.0.113.7" }, environment(), public)
            .bindHost.assert().isEqualTo("203.0.113.7")
        NodeIdentity.of(properties { internal.host = "0.0.0.0" }, environment(), public)
            .address.assert().isEqualTo("203.0.113.7:9300")
        NodeIdentity.of(properties { node.address = "10.0.0.1:9191" }, environment(), public)
            .bindHost.assert().isEqualTo("10.0.0.1")
    }

    @Test
    fun `a loopback address is bound but makes no development node, resolved or named`() {
        // Debian's /etc/hosts gives the host name 127.0.1.1 (#6667).
        val properties = properties()
        val identity = NodeIdentity.of(properties, environment(), debian)
        identity.bindHost.assert().isEqualTo("127.0.1.1")
        DevelopmentMode.of(properties, environment()).active.assert().isFalse()
        // Named by the operator, it states nothing either (#6972): development is a profile of wow.dev-profiles alone.
        properties.internal.host = "127.0.1.1"
        NodeIdentity.of(properties, environment(), debian).bindHost.assert().isEqualTo("127.0.1.1")
        DevelopmentMode.of(properties, environment()).active.assert().isFalse()
        properties.node.address = "127.0.0.1:9300"
        DevelopmentMode.of(properties, environment()).active.assert().isFalse()
        DevelopmentMode.of(properties, environment("dev")).reason.assert().isEqualTo("profile dev")
    }

    @Test
    fun `an address is public unless it is loopback, private, link-local, shared or unique-local`() {
        listOf(
            "127.0.0.1", "127.0.1.1", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.0.1", "169.254.1.1",
            "100.64.0.1", "100.127.255.254", "0.0.0.0", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::",
        ).forEach { NodeIdentity.isPublic(InetAddress.getByName(it)).assert().describedAs(it).isFalse() }
        listOf(
            "8.8.8.8",
            "203.0.113.7",
            "172.32.0.1",
            "172.15.255.255",
            "100.63.255.255",
            "100.128.0.1",
            "2001:db8::1",
            "2400:cb00::1",
        ).forEach { NodeIdentity.isPublic(InetAddress.getByName(it)).assert().describedAs(it).isTrue() }
    }
}
