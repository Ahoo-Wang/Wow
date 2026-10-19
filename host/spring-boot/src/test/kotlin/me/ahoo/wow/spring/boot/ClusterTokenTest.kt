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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.http.internal.InternalHttp
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The cluster token of the internal port (`wow.internal.token`, decision D226): the host's cluster routes take it
 * alone, and a node without one starts only in a development profile or on a loopback-only internal port.
 */
class ClusterTokenTest {
    private fun properties(vararg tokens: String, serviceToken: String? = null) = WowProperties().apply {
        internal.token = tokens.toMutableList()
        serviceToken?.let { http.services["ledger-service"] = WowProperties.Http.Service().apply { this.tokens += it } }
    }

    private fun environment(vararg profiles: String) = StandardEnvironment().apply { setActiveProfiles(*profiles) }

    @Test
    fun `a node without a cluster token should not start on a reachable internal port outside development`() {
        val failure = assertThrownBy<IllegalStateException> {
            WowAutoConfiguration.clusterTokens(properties(), environment(), "10.0.0.7")
        }
        failure.hasMessageContaining("wow.internal.token")
        assertThrownBy<IllegalStateException> {
            WowAutoConfiguration.clusterTokens(properties(), environment("prod"), "0.0.0.0")
        }
        // A host that does not resolve is not known to be the loopback interface.
        assertThrownBy<IllegalStateException> {
            WowAutoConfiguration.clusterTokens(properties(), environment(), "no-such-host.invalid")
        }
        // A blank value, as an unset environment variable gives, is no token.
        assertThrownBy<IllegalStateException> {
            WowAutoConfiguration.clusterTokens(properties(" "), environment(), "10.0.0.7")
        }
    }

    @Test
    fun `a node without a cluster token should start in a development profile or on the loopback interface`() {
        WowAutoConfiguration.clusterTokens(properties(), environment("dev"), "10.0.0.7").isEmpty.assert().isTrue()
        WowAutoConfiguration.clusterTokens(properties(), environment("test", "kafka"), "0.0.0.0").isEmpty.assert()
            .isTrue()
        WowAutoConfiguration.clusterTokens(properties(), environment(), "127.0.0.1").isEmpty.assert().isTrue()
        WowAutoConfiguration.clusterTokens(properties(), environment(), "localhost").isEmpty.assert().isTrue()
        val custom = properties().apply { internal.devProfiles = mutableSetOf("sandbox") }
        WowAutoConfiguration.clusterTokens(custom, environment("sandbox"), "10.0.0.7").isEmpty.assert().isTrue()
        assertThrownBy<IllegalStateException> {
            WowAutoConfiguration.clusterTokens(custom, environment("dev"), "10.0.0.7")
        }
    }

    @Test
    fun `a node with cluster tokens should take each and send the first`() {
        val properties = properties(CURRENT, " $PREVIOUS ")
        val tokens = WowAutoConfiguration.clusterTokens(properties, environment(), "10.0.0.7")
        tokens.accepts(CURRENT).assert().isTrue()
        tokens.accepts(PREVIOUS).assert().isTrue()
        tokens.current.assert().isEqualTo(CURRENT)
        WowAutoConfiguration.clusterTokenOf(properties).assert().isEqualTo(CURRENT)
        WowAutoConfiguration.clusterTokenOf(properties()).assert().isNull()
    }

    @Test
    fun `a cluster token should not be a service token too`() {
        assertThrownBy<IllegalArgumentException> {
            WowAutoConfiguration.clusterTokens(properties(CURRENT, serviceToken = CURRENT), environment(), "10.0.0.7")
        }.hasMessageContaining("wow.http.services")
        assertThrownBy<IllegalArgumentException> {
            WowAutoConfiguration.clusterTokens(properties("too-short"), environment(), "10.0.0.7")
        }
    }

    @Test
    fun `a host on every interface without a cluster token should fail to start`() {
        // In memory on purpose: on every interface, auto would refuse the missing durable backend first (R3.5-1).
        assertThrownBy<Exception> { startHost("wow.internal.host" to "0.0.0.0", "wow.backend" to "memory").close() }
            .hasStackTraceContaining("wow.internal.token")
    }

    @Test
    fun `a host's cluster routes should take its cluster token alone`() {
        startHost("wow.internal.token" to "$CURRENT,$PREVIOUS").use { host ->
            fun signal(token: String?): Int {
                val request = HttpRequest.newBuilder(
                    InternalHttp.uri("127.0.0.1:${host.internalPort}", InternalHttp.SIGNALS_PATH),
                )
                    .header("Content-Type", InternalHttp.JSON)
                    .apply { token?.let { header(InternalHttp.CLUSTER_TOKEN, it) } }
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build()
                return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            }
            signal(null).assert().isEqualTo(401)
            signal("cluster-token-of-another-service-0123456789").assert().isEqualTo(403)
            // Taken, then read: this body is no signal.
            signal(PREVIOUS).assert().isEqualTo(400)
        }
    }

    @Test
    fun `a host's signals carry the cluster token to the members of its cluster alone (R2_3-1)`() {
        startHost("wow.internal.token" to CURRENT).use { host ->
            val node = host.node
            val deadline = System.currentTimeMillis() + 10_000
            fun members() = node.contexts.values.flatMap { it.ownership.routes.members }.map { it.address }
            while (members().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val members = NodeMembers { node }
            members().forEach { address -> members.contains(address).assert().isTrue() }
            members().assert().isNotEmpty()
            members.contains("attacker.example:80").assert().isFalse()
            NodeMembers { null }.contains(members().first()).assert().isFalse()
        }
    }

    private companion object {
        const val CURRENT = "cluster-token-current-0123456789abcdef"
        const val PREVIOUS = "cluster-token-previous-0123456789abcdef"
    }
}
