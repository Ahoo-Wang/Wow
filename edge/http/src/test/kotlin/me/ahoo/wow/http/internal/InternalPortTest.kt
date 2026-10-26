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

package me.ahoo.wow.http.internal

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * The internal port's security floor (decision D226, reviews A3-1, R5.C-2), held where the port is built, so every
 * host gets it: its cluster routes take the cluster tokens alone, and a port without one runs only for development or
 * on the loopback interface alone.
 */
class InternalPortTest {
    private var built: ClusterTokens? = null

    private fun port(
        bindHost: String,
        vararg tokens: String,
        serviceToken: String? = null,
        development: List<String> = emptyList(),
    ): InternalPort = InternalPort.of(
        InternalPortSettings(
            port = 0,
            bindHost = bindHost,
            clusterTokens = tokens.toList(),
            serviceTokens = setOfNotNull(serviceToken),
            development = development,
            devProfiles = setOf("dev", "test", "local"),
        ),
    ) { tokens -> routes(tokens) }

    private fun routes(tokens: ClusterTokens): RouterFunction<ServerResponse> {
        built = tokens
        return coRouter { GET("/ping") { ServerResponse.ok().buildAndAwait() } }
    }

    @Test
    fun `a port without a cluster token should not be built where it is reachable outside development`() {
        assertThrownBy<WowConfigurationException> { port("10.0.0.7") }.hasMessageContaining("wow.internal.token")
        assertThrownBy<WowConfigurationException> { port("0.0.0.0") }
        // A host that does not resolve is not known to be the loopback interface.
        assertThrownBy<WowConfigurationException> { port("no-such-host.invalid") }
        // A blank value, as an unset environment variable gives, is no token.
        assertThrownBy<WowConfigurationException> { port("10.0.0.7", " ") }
        // No routes are built for a port that is refused.
        built.assert().isNull()
    }

    @Test
    fun `a refused port should name the key and the fix`() {
        val refused = runCatching { port("10.0.0.7") }.exceptionOrNull() as WowConfigurationException
        refused.key.assert().isEqualTo("wow.internal.token")
        refused.fix.assert().contains("wow.internal.dev-profiles")
    }

    @Test
    fun `a port without a cluster token should be built for development or on the loopback interface`() {
        port("10.0.0.7", development = listOf("dev"))
        built!!.isEmpty.assert().isTrue()
        port("0.0.0.0", development = listOf("test"))
        port("127.0.0.1").isRunning.assert().isFalse()
        port("localhost")
        built!!.isEmpty.assert().isTrue()
    }

    @Test
    fun `a port with cluster tokens should take each and send the first`() {
        port("10.0.0.7", CURRENT, " $PREVIOUS ")
        val tokens = built!!
        tokens.accepts(CURRENT).assert().isTrue()
        tokens.accepts(PREVIOUS).assert().isTrue()
        tokens.current.assert().isEqualTo(CURRENT)
    }

    @Test
    fun `a cluster token should not be a service token too`() {
        assertThrownBy<WowConfigurationException> { port("10.0.0.7", CURRENT, serviceToken = CURRENT) }
            .hasMessageContaining("wow.http.services")
        assertThrownBy<IllegalArgumentException> { port("10.0.0.7", "too-short") }
    }

    @Test
    fun `a built port should bind and unbind`() {
        val port = port("127.0.0.1")
        port.start()
        try {
            port.isRunning.assert().isTrue()
            port.boundPort.assert().isGreaterThan(0)
            port.start()
        } finally {
            port.stop()
        }
        port.isRunning.assert().isFalse()
    }

    private companion object {
        const val CURRENT = "cluster-token-current-0123456789abcdef"
        const val PREVIOUS = "cluster-token-previous-0123456789abcdef"
    }
}
