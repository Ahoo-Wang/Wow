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
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The cluster token of the internal port (`wow.internal.token`, decision D226) on the host: its cluster routes take it
 * alone.
 */
class ClusterTokenTest {
    private fun properties(vararg tokens: String, serviceToken: String? = null) = WowProperties().apply {
        internal.token = tokens.toMutableList()
        serviceToken?.let { http.services["ledger-service"] = WowProperties.Http.Service().apply { this.tokens += it } }
    }

    @Test
    fun `the node should send its first cluster token`() {
        WowAutoConfiguration.clusterTokenOf(properties(CURRENT, " $PREVIOUS ")).assert().isEqualTo(CURRENT)
        WowAutoConfiguration.clusterTokenOf(properties(" ")).assert().isNull()
        WowAutoConfiguration.clusterTokenOf(properties()).assert().isNull()
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
