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

package me.ahoo.wow.http.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.http.problem.ErrorStatuses
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import java.net.InetAddress
import java.net.InetSocketAddress

/** The request facts a command keeps (09 §17 G-10): the listed headers and, on the public port, the default facts. */
class RequestFactsTest {
    private val statuses = ErrorStatuses(cartManifest(), emptyMap())
    private val publicPort = RequestHeaders(statuses, publicPort = true)
    private val internalPort = RequestHeaders(statuses)
    private val address = InetSocketAddress(InetAddress.getByAddress("client.example", byteArrayOf(10, 0, 0, 7)), 4711)

    private fun request(vararg headers: Pair<String, String>, remote: InetSocketAddress? = address) =
        MockServerRequest.builder().apply {
            headers.forEach { (name, value) -> header(name, value) }
            remote?.let { remoteAddress(it) }
        }.build()

    @Test
    fun `the public port records the user agent and the remote address literal, never a forwarded one`() {
        publicPort.facts(
            request("User-Agent" to " shop-app/1.0 ", "X-Forwarded-For" to "203.0.113.9", "X-App-Id" to "app-1"),
            setOf("X-App-Id"),
        ).assert().isEqualTo(mapOf("X-App-Id" to "app-1", "user_agent" to "shop-app/1.0", "remote_ip" to "10.0.0.7"))
    }

    @Test
    fun `a blank user agent or an unknown address records nothing`() {
        publicPort.facts(request("User-Agent" to "  ", remote = null), emptySet()).assert().isEmpty()
    }

    @Test
    fun `an unresolved remote address is recorded as it was given`() {
        publicPort.facts(request(remote = InetSocketAddress.createUnresolved("proxied-client", 80)), emptySet())
            .assert().isEqualTo(mapOf("remote_ip" to "proxied-client"))
    }

    @Test
    fun `no listed name sets a default fact, on either port`() {
        val forged = request("REMOTE_IP" to "203.0.113.9", "user_agent" to "forged", "X-App-Id" to "app-1")
        val names = setOf("REMOTE_IP", "user_agent", "X-App-Id")
        publicPort.facts(forged, names).assert().isEqualTo(mapOf("X-App-Id" to "app-1", "remote_ip" to "10.0.0.7"))
        internalPort.facts(forged, names).assert().isEqualTo(mapOf("X-App-Id" to "app-1"))
    }

    @Test
    fun `the internal port records no default fact`() {
        internalPort.facts(request("User-Agent" to "Java-http-client"), emptySet()).assert().isEmpty()
    }
}
