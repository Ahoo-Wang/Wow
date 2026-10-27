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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.OWNER
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * The lifecycle routes the Manifest lists for every aggregate (decision D113), on the ordering's orders, which belong
 * to the buyers who submitted them (decision D189): an order deleted and restored, an order moved to another buyer's
 * account, and an order tagged and moved into a fulfilment space.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderLifecycleTest {
    private val context = SpringApplicationBuilder(OrderingServer::class.java, InMemoryOrderingTopics::class.java).run(
        "--spring.main.banner-mode=off",
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.internal.host=127.0.0.1",
        "--wow.cluster.drain-timeout=1s",
        "--wow.subscription.stop-timeout=2s",
        "--wow.wait.max-timeout=15s",
    )
    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = JsonMapper.builder().build()

    @AfterAll
    fun close() {
        context.close()
        http.shutdownNow()
    }

    @Test
    fun `a deleted order should refuse its next command until it is restored`() {
        val alice = buyer()
        val id = submitted(alice)
        val deleted = send("DELETE", "/eshop-ordering/order/$id", alice)
        deleted.first.assert().describedAs(deleted.second.toString()).isEqualTo(OK)
        deleted.second["version"].asInt().assert().isEqualTo(VERSION_AFTER_VALIDATION + 1)
        read(id, alice).second["deleted"].asBoolean().assert().isTrue()

        val refused = confirm(id, "mug", alice)
        refused.first.assert().isEqualTo(GONE)
        refused.second["code"].asString().assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)

        lifecycle(id, "restore-aggregate", alice).first.assert().isEqualTo(OK)
        read(id, alice).second["deleted"].asBoolean().assert().isFalse()
        confirm(id, "mug", alice).first.assert().isEqualTo(OK)
    }

    @Test
    fun `an order moved to another buyer's account should be that buyer's alone`() {
        val second = buyer()
        val main = buyer()
        val id = submitted(second)
        lifecycle(id, "transfer-owner", second, """{"ownerId":"${main[USER]}"}""").first.assert().isEqualTo(OK)
        read(id, main).let { (status, view) ->
            status.assert().isEqualTo(OK)
            view["scope"]["ownerId"].asString().assert().isEqualTo(main[USER])
        }

        // The former owner neither reads nor changes it: its state is not found for it (decision D254 ④), and the
        // kernel's scope guard refuses its commands (decision D94).
        read(id, second).let { (status, problem) ->
            status.assert().isEqualTo(NOT_FOUND)
            problem["code"].asString().assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        }
        confirm(id, "mug", second).second["code"].asString().assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        confirm(id, "mug", main).first.assert().isEqualTo(OK)
    }

    @Test
    fun `an employee should tag an order and move it into a fulfilment space`() {
        val alice = buyer()
        val id = submitted(alice)
        lifecycle(id, "apply-tags", EMPLOYEE, """{"tags":{"channel":["web"]}}""").first.assert().isEqualTo(OK)
        lifecycle(id, "transfer-space", EMPLOYEE, """{"spaceId":"warehouse-east"}""").first.assert().isEqualTo(OK)
        val view = read(id, EMPLOYEE).second
        view["tags"]["channel"][0].asString().assert().isEqualTo("web")
        view["scope"]["spaceId"].asString().assert().isEqualTo("warehouse-east")
        // The order is still its buyer's.
        view["scope"]["ownerId"].asString().assert().isEqualTo(alice[USER])
    }

    /** A buyer of this test only. */
    private fun buyer(): Map<String, String> =
        ("buyer-" + UUID.randomUUID().toString().take(8)).let { mapOf(USER to it, OWNER to it) }

    /** Submits an order as [buyer] and waits until the order process has it await its stock validation; its id. */
    private fun submitted(buyer: Map<String, String>): String {
        val id = "order-" + UUID.randomUUID()
        val submitted = send(
            "POST",
            "/eshop-ordering/order/$id/submit-order",
            buyer,
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2},{"productId":"cap","unitPrice":12.00,"quantity":1}]}""",
        )
        submitted.first.assert().describedAs(submitted.second.toString()).isEqualTo(OK)
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (read(id, buyer).second["version"]?.asInt() != VERSION_AFTER_VALIDATION) {
            check(System.nanoTime() < deadline) { "The order $id did not await its validation in $DEADLINE." }
            Thread.sleep(POLL.toMillis())
        }
        return id
    }

    /** Sends the lifecycle command [kebab] (`wow.lifecycle.<kebab>`) to the order [id]. */
    private fun lifecycle(id: String, kebab: String, caller: Map<String, String>, body: String = "{}") =
        send("POST", "/eshop-ordering/order/$id/wow.lifecycle.$kebab", caller, body)

    private fun confirm(id: String, product: String, caller: Map<String, String>): Pair<Int, JsonNode> =
        send("POST", "/eshop-ordering/order/$id/confirm-order-stock", caller, """{"productId":"$product"}""")

    private fun read(id: String, caller: Map<String, String>): Pair<Int, JsonNode> =
        send("GET", "/eshop-ordering/order/$id/state", caller)

    private fun send(method: String, path: String, headers: Map<String, String>, body: String? = null): Pair<Int, JsonNode> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(TIMEOUT)
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            )
        body?.let { request.header("Content-Type", "application/json") }
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val GONE = 410

        /** The order's version once the order process had it await its validation: submitted, then awaiting. */
        const val VERSION_AFTER_VALIDATION = 2
        val EMPLOYEE = mapOf(USER to "erin", ROLES to "employee")
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        val DEADLINE: Duration = Duration.ofSeconds(20)
        val POLL: Duration = Duration.ofMillis(100)
    }
}
