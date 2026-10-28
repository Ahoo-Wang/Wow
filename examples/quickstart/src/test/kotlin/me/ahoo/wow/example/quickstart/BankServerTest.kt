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
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/**
 * What the quickstart page does with the running service: its `application.yaml`, on random ports. Each request is the
 * one a `curl` of the page sends.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BankServerTest {
    private val server: WowServer = wowServer<BankServer>()

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `the page's commands and queries should answer as it says`() {
        post("/bank/account/alice/open-account", """{"owner":"Alice"}""").status(OK)
        post("/bank/account/alice/deposit", """{"amount":100}""").status(OK)
        post("/bank/account/alice/withdraw", """{"amount":150}""").status(CONFLICT).apply {
            body!!["type"].asString().assert().isEqualTo("urn:wow:error:bank.account.insufficient-balance")
        }

        val state = get("/bank/account/alice/state").status(OK).body!!
        state["state"]["owner"].asString().assert().isEqualTo("Alice")
        state["state"]["balance"].asLong().assert().isEqualTo(100L)

        val rich = post(
            "/bank/account/snapshot/query",
            """{"kind":"list","filter":{"op":"gte","field":"state.balance","value":100}}""",
        ).status(OK).body!!
        rich.values().map { it["aggregateId"].asString() }.assert().containsExactly("alice")
    }

    @Test
    fun `an amount that is not positive should be invalid before the account decides`() {
        post("/bank/account/carol/open-account", """{"owner":"Carol"}""").status(OK)
        for (command in listOf("deposit", "withdraw")) {
            post("/bank/account/carol/$command", """{"amount":0}""").status(BAD_REQUEST).body!!.apply {
                this["type"].asString().assert().isEqualTo("urn:wow:error:wow.command.invalid")
                this["violations"].single()["path"].asString().assert().isEqualTo("amount")
                this["violations"].single()["code"].asString().assert().isEqualTo("positive")
            }
        }
    }

    @Test
    fun `the teller should deposit in process through the CommandClient`() {
        post("/bank/account/bob/open-account", """{"owner":"Bob"}""").status(OK)
        val teller = server.bean<Teller>()
        val result = runBlocking { teller.deposit("bob", 40) }
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.version.assert().isEqualTo(2)
        get("/bank/account/bob/state").status(OK).body!!["state"]["balance"].asLong().assert().isEqualTo(40L)
        // The gateway checks an in-process command's constraints too.
        val invalid = assertThrows<CommandRejectedException> { runBlocking { teller.deposit("bob", 0) } }
        invalid.result.error?.code.assert().isEqualTo("wow.command.invalid")
    }

    private fun Answer.status(expected: Int): Answer = apply { status.assert().describedAs(text).isEqualTo(expected) }

    private fun post(path: String, body: String): Answer = server.http.post(path, body)

    private fun get(path: String): Answer = server.http.get(path)

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
    }
}
