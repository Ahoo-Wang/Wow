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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.internal.InternalServer
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * The default request facts of the public port (09 §17 G-10): each command records its request's `User-Agent` as
 * `user_agent` and the server's remote address as `remote_ip`, as 9.x's header appenders did; no request sets either.
 */
class RequestFactsRoutesTest {
    @Test
    fun `a command over a real connection records its user agent and the connection's address`() {
        val node = HttpNode()
        InternalServer(node.routes).use { server ->
            val client = WebTestClient.bindToServer().baseUrl("http://${server.address}").build()
            client.post().uri("/shop/cart/c-1/add-item")
                .header("X-User", "alice")
                .header("User-Agent", "shop-app/1.0")
                // A client's forwarded address is not taken: only a proxy the server trusts rewrites the address.
                .header("X-Forwarded-For", "203.0.113.9")
                .header("Content-Type", "application/json")
                .bodyValue("""{"sku":"apple"}""")
                .answer().status.assert().isEqualTo(200)
        }
        node.commands.single().headers.assert()
            .isEqualTo(mapOf("user_agent" to "shop-app/1.0", "remote_ip" to "127.0.0.1"))
    }

    @Test
    fun `a host that turns client facts off records neither the user agent nor the address (D267)`() {
        val node = HttpNode(options = WowHttpOptions(clientFacts = false))
        InternalServer(node.routes).use { server ->
            WebTestClient.bindToServer().baseUrl("http://${server.address}").build()
                .post().uri("/shop/cart/c-1/add-item")
                .header("X-User", "alice")
                .header("User-Agent", "shop-app/1.0")
                .header("Content-Type", "application/json")
                .bodyValue("""{"sku":"apple"}""")
                .answer().status.assert().isEqualTo(200)
        }
        node.commands.single().headers.assert().isEmpty()
    }

    @Test
    fun `a request cannot set a default fact, even one the host lists`() {
        val node = HttpNode(options = WowHttpOptions(requestFacts = setOf("X-App-Id", "remote_ip", "User_Agent")))
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf(
                "X-App-Id" to "app-1",
                "remote_ip" to "203.0.113.9",
                "User_Agent" to "forged",
                "User-Agent" to "shop-app/1.0",
            ),
        ).status.assert().isEqualTo(200)
        // The in-memory exchange has no remote address: no `remote_ip` at all rather than the forged one.
        node.commands.single().headers.assert()
            .isEqualTo(mapOf("X-App-Id" to "app-1", "user_agent" to "shop-app/1.0"))
    }

    @Test
    fun `a command records the application its caller's resolver authenticated, never one a request names`() {
        // The resolver authenticated the application: the `X-Authenticated-App` stands for its credential here.
        val callers = CallerResolver { request ->
            Caller.of(
                subject = "alice",
                attributes = request.headers().firstHeader("X-Authenticated-App")
                    ?.let { mapOf(Caller.APP to listOf(it)) }.orEmpty(),
            )
        }
        val node = HttpNode(callers = callers, options = WowHttpOptions(requestFacts = setOf("app_id")))
        node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf("X-Authenticated-App" to "console", "app_id" to "forged"),
        ).status.assert().isEqualTo(200)
        node.post("/shop/cart/c-2/add-item", """{"sku":"apple"}""", headers = mapOf("app_id" to "forged"))
            .status.assert().isEqualTo(200)
        node.commands.map { it.headers[RequestFacts.APP_ID] }.assert().containsExactly("console", null)
    }
}
