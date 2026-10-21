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

package me.ahoo.wow.example.quickstart

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * What the quickstart page does with the running service: its `application.yaml`, on random ports. Each request is the
 * one a `curl` of the page sends.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BankServerTest {
    private val context: ConfigurableApplicationContext = SpringApplicationBuilder(BankServer::class.java).run(
        "--spring.main.banner-mode=off",
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.internal.host=127.0.0.1",
    )

    private val client: WebTestClient = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:${context.environment.getRequiredProperty("local.server.port")}")
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    private val json: JsonMapper = JsonMapper.builder().build()

    @AfterAll
    fun close() {
        context.close()
    }

    @Test
    fun `the page's commands and queries should answer as it says`() {
        post("/bank/account/alice/open-account", """{"owner":"Alice"}""").status(OK)
        post("/bank/account/alice/deposit", """{"amount":100}""").status(OK)
        post("/bank/account/alice/withdraw", """{"amount":150}""").status(CONFLICT).apply {
            body["type"].asString().assert().isEqualTo("urn:wow:error:bank.account.insufficient-balance")
        }

        val state = get("/bank/account/alice/state").status(OK).body
        state["state"]["owner"].asString().assert().isEqualTo("Alice")
        state["state"]["balance"].asLong().assert().isEqualTo(100L)

        val rich = post(
            "/bank/account/snapshot/query",
            """{"kind":"list","filter":{"op":"GTE","field":"state.balance","value":100}}""",
        ).status(OK).body
        rich.values().map { it["aggregateId"].asString() }.assert().containsExactly("alice")
    }

    @Test
    fun `the teller should deposit in process through the CommandClient`() {
        post("/bank/account/bob/open-account", """{"owner":"Bob"}""").status(OK)
        val teller = context.getBean(Teller::class.java)
        val result = runBlocking { teller.deposit("bob", 40) }
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.version.assert().isEqualTo(2)
        get("/bank/account/bob/state").status(OK).body["state"]["balance"].asLong().assert().isEqualTo(40L)
    }

    private class Answer(val status: Int, val body: JsonNode) {
        fun status(expected: Int): Answer = apply { status.assert().describedAs(body.toString()).isEqualTo(expected) }
    }

    private fun post(path: String, body: String): Answer =
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body).answer()

    private fun get(path: String): Answer = client.get().uri(path).answer()

    private fun WebTestClient.RequestHeadersSpec<*>.answer(): Answer {
        val result = exchange().expectBody(String::class.java).returnResult()
        return Answer(result.status.value(), json.readTree(result.responseBody.orEmpty().ifBlank { "null" }))
    }

    private companion object {
        const val OK = 200
        const val CONFLICT = 409
    }
}
