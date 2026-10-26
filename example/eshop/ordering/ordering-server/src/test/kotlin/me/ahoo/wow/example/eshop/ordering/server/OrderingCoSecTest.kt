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

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosid.jvm.UuidGenerator
import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.builder.SpringApplicationBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The ordering service as it is deployed, behind the CoSec gateway (the `cosec` profile, decisions D295, D296, D301):
 * CoSec's inject mode reads the principal from the token the gateway verified, and wow-cosec's resolver makes it the
 * caller. A buyer owns the orders it submits and reads only its own; an employee, a tenant-level caller, reads every
 * order; a request without a token is answered 401, whatever headers it carries. Outside development, a node without
 * CoSec does not start: it would take its callers from headers any client sets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderingCoSecTest {
    private val context = SpringApplicationBuilder(OrderingServer::class.java, InMemoryOrderingTopics::class.java)
        .profiles("cosec")
        .run(*NODE)

    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = JsonMapper.builder().build()

    /** The gateway's tokens: the service holds no secret and verifies none (decision D296). */
    private val tokens = JwtTokenConverter(UuidGenerator.INSTANCE, Algorithm.HMAC256(GATEWAY_SECRET))

    @AfterAll
    fun close() {
        context.close()
        http.shutdownNow()
    }

    private fun signedIn(id: String, vararg roles: String): Map<String, String> {
        val principal = SimpleTenantPrincipal(SimplePrincipal(id, roles = roles.toSet()), SimpleTenant(DEFAULT_TENANT))
        return mapOf("Authorization" to "Bearer ${tokens.toToken(principal).accessToken}")
    }

    @Test
    fun `the caller should be CoSec's`() {
        context.getBean(CallerResolver::class.java).assert().isInstanceOf(CoSecCallerResolver::class.java)
    }

    @Test
    fun `a buyer should own the order it submits, and an employee read it`() {
        val alice = signedIn("alice")
        submit("cosec-alice-1", alice).first.assert().isEqualTo(OK)
        val (status, view) = read("cosec-alice-1", alice)
        status.assert().describedAs(view.toString()).isEqualTo(OK)
        view["scope"]["ownerId"].asString().assert().isEqualTo("alice")
        read("cosec-alice-1", signedIn("bob")).first.assert().isEqualTo(NOT_FOUND)
        read("cosec-alice-1", signedIn("erin", OrderingCallers.EMPLOYEE)).first.assert().isEqualTo(OK)
    }

    @Test
    fun `a request without a token should be answered 401, whatever headers it names`() {
        val headers = mapOf(OrderingCallers.USER to "mallory", OrderingCallers.ROLE to OrderingCallers.EMPLOYEE)
        submit("cosec-mallory-1", headers).first.assert().isEqualTo(UNAUTHORIZED)
        read("cosec-mallory-1", headers).first.assert().isEqualTo(UNAUTHORIZED)
    }

    @Test
    fun `a node outside development without CoSec should not start`() {
        val failure = assertThrows<Exception> {
            SpringApplicationBuilder(OrderingServer::class.java, InMemoryOrderingTopics::class.java)
                .run(
                    *(NODE.filterNot { it.startsWith("--wow.internal.host=") } + "--wow.internal.host=0.0.0.0").toTypedArray()
                )
                .close()
        }
        generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            .assert().contains(OrderingCallers.COSEC_ENABLED)
    }

    private fun submit(id: String, headers: Map<String, String>): Pair<Int, JsonNode> = post(
        "/eshop-ordering/order/$id/submit-order",
        """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
        headers,
    )

    private fun read(id: String, headers: Map<String, String>): Pair<Int, JsonNode> {
        val request = HttpRequest.newBuilder(uri("/eshop-ordering/order/$id/state")).timeout(TIMEOUT).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
    }

    private fun post(path: String, body: String, headers: Map<String, String>): Pair<Int, JsonNode> {
        val request = HttpRequest.newBuilder(uri(path))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    private companion object {
        const val GATEWAY_SECRET = "the-gateway-test-secret-of-32-characters"
        const val DEFAULT_TENANT = "(0)"
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        val NODE = arrayOf(
            "--spring.main.banner-mode=off",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--wow.internal.port=0",
            "--wow.internal.host=127.0.0.1",
            "--wow.cluster.drain-timeout=1s",
            "--wow.subscription.stop-timeout=2s",
        )
    }
}
