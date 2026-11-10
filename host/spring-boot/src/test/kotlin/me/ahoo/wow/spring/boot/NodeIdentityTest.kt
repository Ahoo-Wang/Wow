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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment
import java.net.http.HttpRequest

/** The node's internal address as the host builds it: an IPv6 host in brackets, as its peers' clients need it. */
class NodeIdentityTest {
    private val environment = MockEnvironment().withProperty("spring.application.name", "shop")

    private fun identity(host: String? = null, port: Int = 9191, address: String? = null): NodeIdentity =
        NodeIdentity.of(
            WowProperties().apply {
                internal.host = host
                internal.port = port
                node.address = address
            },
            environment,
        )

    @Test
    fun `an IPv6 internal host should be advertised in brackets, and bound bare`() {
        val identity = identity(host = "::1")
        identity.address.assert().isEqualTo("[::1]:9191")
        identity.bindHost.assert().isEqualTo("::1")
        // The address the peers forward commands and send wait signals to.
        val uri = InternalHttp.uri(identity.address!!, InternalHttp.FORWARD_PATH)
        HttpRequest.newBuilder(uri).build().uri().port.assert().isEqualTo(9191)
    }

    @Test
    fun `a port the system chooses should give an IPv6 host's address in brackets too`() {
        val identity = identity(host = "::1", port = 0)
        identity.address.assert().isNull()
        identity.addressAt(41234).assert().isEqualTo("[::1]:41234")
        identity(host = "127.0.0.1", port = 0).addressAt(41234).assert().isEqualTo("127.0.0.1:41234")
    }

    @Test
    fun `an IPv4 host and a configured address should be advertised as they are`() {
        identity(host = "127.0.0.1").address.assert().isEqualTo("127.0.0.1:9191")
        val named = identity(address = "ordering-0.ordering.shop.svc:9191")
        named.address.assert().isEqualTo("ordering-0.ordering.shop.svc:9191")
        named.bindHost.assert().isEqualTo("ordering-0.ordering.shop.svc")
        identity(address = "[::1]:9191").bindHost.assert().isEqualTo("::1")
    }

    @Test
    fun `a configured address that is a bare IPv6 literal should fail, naming the bracketed form`() {
        val failure = assertThrows<WowConfigurationException> { identity(address = "::1:9191") }
        failure.key.assert().isEqualTo("wow.node.address")
        failure.fix.assert().contains("[::1]:9191")
    }
}
