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

import me.ahoo.wow.api.annotation.InternalWowApi
import java.security.MessageDigest

/**
 * The cluster tokens of a service (decision D226, review A3-1): the secret its nodes share, which opens the cluster
 * channel ([InternalHttp.FORWARD_PATH]) and the wait signals ([InternalHttp.SIGNALS_PATH]) of the internal port. A node
 * sends the [current] one, the first, as [InternalHttp.CLUSTER_TOKEN]; a node takes any of them, so a token is rotated
 * by adding the new one after the current, then making it the first on every node, then removing the old one.
 *
 * A service's tokens for its synchronous calls (`Authorization: Bearer`, decision D208) are another secret: they open
 * the call routes, never the cluster channel, and a cluster token opens no call route. Tokens are compared as SHA-256
 * digests in constant time, every token each time; each is at least [MIN_TOKEN_LENGTH] characters. Without tokens
 * ([isEmpty], a development setup) the routes take every request.
 */
@InternalWowApi
class ClusterTokens(tokens: Collection<String>) {
    private val digests: List<ByteArray> = tokens.map { token ->
        require(token.length >= MIN_TOKEN_LENGTH) {
            "A cluster token (wow.internal.token) has at least $MIN_TOKEN_LENGTH characters."
        }
        digest(token)
    }

    /** The token this node sends: the first one; null without tokens. */
    val current: String? = tokens.firstOrNull()

    /** Whether the service has no cluster token: its routes take every request. */
    val isEmpty: Boolean get() = digests.isEmpty()

    /** Whether [token] is one of these tokens. */
    fun accepts(token: String): Boolean {
        val presented = digest(token)
        var found = false
        digests.forEach { digest ->
            if (MessageDigest.isEqual(presented, digest)) {
                found = true
            }
        }
        return found
    }

    @InternalWowApi
    companion object {
        /** The fewest characters of a cluster token: 32, as many as 192 random bits in base64. */
        const val MIN_TOKEN_LENGTH: Int = 32

        /** No cluster token: the cluster channel and the signals take every request (development only). */
        @JvmField
        val NONE: ClusterTokens = ClusterTokens(emptyList())

        private fun digest(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    }
}
