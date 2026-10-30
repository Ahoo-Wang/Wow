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

package me.ahoo.wow.example.eshop.ordering.server

import me.ahoo.cosec.runtime.identity.IdentityCodec
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The ordering service as it is deployed, behind the CoSec gateway (the `cosec` profile): CoSec's gateway mode reads
 * the principal the gateway verified from its `CoSec-Identity` header, and wow-cosec's resolver makes it the caller. A
 * buyer owns the orders it submits and reads only its own; an employee, a tenant-level caller, reads every order; a
 * request without the header is anonymous and answered 401, whatever else it carries, a token included, since the
 * service verifies none. Outside development, a node without CoSec does not start: it would take its callers from
 * headers any client sets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderingCoSecTest {
    private val server = wowServer<OrderingServer>("cosec", sources = listOf(InMemoryOrderingTopics::class.java))

    @AfterAll
    fun close() {
        server.close()
    }

    /** The header the gateway forwards for [id] of the default tenant, with [roles], after verifying its token. */
    private fun signedIn(id: String, vararg roles: String): Map<String, String> {
        val now = Instant.now()
        val identity = Identity(
            tenant = DEFAULT_TENANT,
            subject = id,
            app = "",
            space = "",
            roles = roles.toSet(),
            credential = Identity.Credential(Identity.Method.JWT, UUID.randomUUID().toString(), now.plus(TOKEN_TTL)),
            issuedAt = now,
            requestId = UUID.randomUUID().toString(),
        )
        return mapOf(IdentityCodec.HEADER to IdentityCodec.encode(identity))
    }

    @Test
    @OptIn(ExperimentalWowApi::class) // wow-cosec is experimental in 10.0
    fun `the caller should be CoSec's`() {
        server.bean<CallerResolver>().assert().isInstanceOf(CoSecCallerResolver::class.java)
    }

    @Test
    fun `a buyer should own the order it submits, and an employee read it`() {
        val alice = signedIn("alice")
        submit("cosec-alice-1", alice).status.assert().isEqualTo(OK)
        val answer = read("cosec-alice-1", alice)
        answer.status.assert().describedAs(answer.text).isEqualTo(OK)
        answer.body!!["ownerId"].asString().assert().isEqualTo("alice")
        read("cosec-alice-1", signedIn("bob")).status.assert().isEqualTo(NOT_FOUND)
        read("cosec-alice-1", signedIn("erin", "employee")).status.assert().isEqualTo(OK)
    }

    @Test
    fun `a request without the gateway's identity should be answered 401, whatever headers it names`() {
        val headers = mapOf(USER to "mallory", ROLES to "employee", "Authorization" to "Bearer not-verified-here")
        submit("cosec-mallory-1", headers).status.assert().isEqualTo(UNAUTHORIZED)
        read("cosec-mallory-1", headers).status.assert().isEqualTo(UNAUTHORIZED)
    }

    @Test
    fun `a node outside development without CoSec should not start`() {
        val failure = assertThrows<Exception> {
            wowServer<OrderingServer>(
                properties = mapOf("wow.internal.host" to "0.0.0.0"),
                sources = listOf(InMemoryOrderingTopics::class.java),
            ).close()
        }
        generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            .assert().contains("No durable backend", "A development profile")
    }

    private fun submit(id: String, headers: Map<String, String>): Answer = post(
        "/eshop-ordering/order/$id/submit-order",
        """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
        headers,
    )

    private fun read(id: String, headers: Map<String, String>): Answer =
        server.http.get("/eshop-ordering/order/$id/state", headers)

    private fun post(path: String, body: String, headers: Map<String, String>): Answer =
        server.http.post(path, body, headers)

    private companion object {
        const val DEFAULT_TENANT = "(0)"
        val TOKEN_TTL: Duration = Duration.ofHours(1)
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
    }
}
