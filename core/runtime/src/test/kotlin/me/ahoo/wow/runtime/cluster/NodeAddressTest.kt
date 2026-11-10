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

package me.ahoo.wow.runtime.cluster

import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI

/** A node's address is `host:port`, an IPv6 literal in brackets, read back to the host it was built of (RFC 3986). */
class NodeAddressTest {
    @ParameterizedTest
    @CsvSource(
        "::1, 9191, [::1]:9191",
        "fd00:0:0:0:0:0:0:7, 41234, [fd00:0:0:0:0:0:0:7]:41234",
        "[::1], 9191, [::1]:9191",
        "127.0.0.1, 9191, 127.0.0.1:9191",
        "ordering-0.ordering.shop.svc, 9191, ordering-0.ordering.shop.svc:9191",
    )
    fun `an address built of a host and a port should round-trip, and be a URI's authority`(
        host: String,
        port: Int,
        address: String,
    ) {
        NodeAddress.of(host, port).assert().isEqualTo(address)
        NodeAddress.hostOf(address).assert().isEqualTo(host.removePrefix("[").removeSuffix("]"))
        NodeAddress.isValid(address).assert().isTrue()
        val uri = URI("http://$address/wow/internal/forward")
        uri.port.assert().isEqualTo(port)
        uri.host.removePrefix("[").removeSuffix("]").assert().isEqualTo(NodeAddress.hostOf(address))
    }

    @ParameterizedTest
    @ValueSource(strings = ["::1:9191", "fd00::7:41234", "a b:9191"])
    fun `an address whose host a URI cannot tell from its port should not be valid`(address: String) {
        NodeAddress.isValid(address).assert().isFalse()
    }
}
