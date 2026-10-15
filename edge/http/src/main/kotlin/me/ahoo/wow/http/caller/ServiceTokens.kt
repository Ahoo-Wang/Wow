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

package me.ahoo.wow.http.caller

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import org.springframework.web.reactive.function.server.ServerRequest
import java.security.MessageDigest

/**
 * The service identities a service trusts by their static tokens (decision D193), for a deployment without CoSec: each
 * calling service's name with the tokens it may present as `Authorization: Bearer <token>`, several while one is
 * rotated (the caller switches to the new one, then the old one is removed). With CoSec, a service identity is a CoSec
 * principal of a service role instead (`wow.cosec.service.roles`).
 *
 * A request whose bearer token is one of them is the service's [Caller]: its subject `service:<name>`, in the
 * [tenantId], without scope, a [service][Caller.service] identity, so the edge takes its `Wow-Call`. Any other request
 * is the [delegate]'s, which never makes a service identity of a user's credentials. Tokens are compared as SHA-256
 * digests in constant time, every token each time, so the comparison tells nothing of a token by its timing; a token
 * is at least [MIN_TOKEN_LENGTH] characters.
 */
@InternalWowApi
class ServiceTokens(tokens: Map<String, Collection<String>>, private val tenantId: String = Caller.DEFAULT_TENANT) {
    private val digests: List<Pair<String, ByteArray>> = tokens.flatMap { (service, serviceTokens) ->
        require(service.isNotBlank()) { "A trusted service is named." }
        serviceTokens.map { token ->
            require(token.length >= MIN_TOKEN_LENGTH) {
                "A token of service $service has fewer than $MIN_TOKEN_LENGTH characters."
            }
            service to digest(token)
        }
    }

    init {
        require(tenantId.isNotBlank()) { "A service identity's tenant is not blank." }
        val shared = tokens.values.flatten().groupBy { it }.filterValues { it.size > 1 }
        require(shared.isEmpty()) { "A service token is given more than once: each names one service." }
    }

    /** Whether no service is trusted. */
    val isEmpty: Boolean get() = digests.isEmpty()

    /** The service whose token [token] is, or null. */
    fun serviceOf(token: String): String? {
        val presented = digest(token)
        var found: String? = null
        digests.forEach { (service, digest) ->
            if (MessageDigest.isEqual(presented, digest)) {
                found = service
            }
        }
        return found
    }

    /** The service identity of [request]'s bearer token, or null when it presents none of the trusted tokens. */
    fun callerOf(request: ServerRequest): Caller? {
        val authorization = request.headers().firstHeader(AUTHORIZATION) ?: return null
        if (!authorization.startsWith(BEARER, ignoreCase = true)) {
            return null
        }
        val name = serviceOf(authorization.substring(BEARER.length).trim()) ?: return null
        return Caller(tenantId = tenantId, subject = SUBJECT_PREFIX + name, service = true)
    }

    /** The service identities of these tokens, and [delegate]'s callers for every other request. */
    fun resolver(delegate: CallerResolver): CallerResolver =
        if (isEmpty) delegate else CallerResolver { request -> callerOf(request) ?: delegate.resolve(request) }

    @InternalWowApi
    companion object {
        /** The prefix of a service identity's subject, and of the operator it would record on a command of its own. */
        const val SUBJECT_PREFIX: String = "service:"

        /** The fewest characters of a token: 32, as many as 192 random bits in base64. */
        const val MIN_TOKEN_LENGTH: Int = 32

        private const val AUTHORIZATION = "Authorization"
        private const val BEARER = "Bearer "

        private fun digest(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    }
}
