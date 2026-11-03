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

package me.ahoo.wow.test.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.client.Problems
import me.ahoo.wow.example.quickstart.AccountState
import me.ahoo.wow.example.quickstart.BankServer
import me.ahoo.wow.example.quickstart.Deposit
import me.ahoo.wow.example.quickstart.OpenAccount
import me.ahoo.wow.example.quickstart.Withdraw
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpHeaders
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The typed clients of a test server, and the problems its answers carry, on the quickstart's bank service. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypedClientsTest {
    private val server = wowServer<BankServer>("dev")

    @AfterAll
    fun close() {
        server.close()
    }

    // region typed-clients
    @Test
    fun `the typed clients should send commands and read states as the caller`() {
        val alice = Caller.of(subject = "alice", scope = Scope(ownerId = "alice"))
        val commands = server.commands(alice)
        runBlocking {
            commands.wait(OpenAccount("Alice"), "alice").status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            commands.wait(Deposit(100), "alice").version.assert().isEqualTo(2)

            val account = server.queries(alice).snapshots(AccountState::class.java).state("alice")!!
            account.state.balance.assert().isEqualTo(100L)
            account.operator!!.id.assert().isEqualTo("alice")

            val rejected = assertThrows<CommandRejectedException> { commands.wait(Withdraw(150), "alice") }
            rejected.code.assert().isEqualTo("bank.account.insufficient-balance")
        }
    }
    // endregion typed-clients

    @Test
    fun `a command refused at admission should be a rejection with its code`() {
        val bob = Caller.of(subject = "bob")
        runBlocking {
            server.commands(bob).wait(OpenAccount("Bob"), "bob")
            val invalid = assertThrows<CommandRejectedException> { server.commands(bob).wait(Deposit(0), "bob") }
            invalid.code.assert().isEqualTo("wow.command.invalid")
            invalid.result.error!!.violations.map { it.path }.assert().containsExactly("amount")
        }
    }

    @Test
    fun `the clients should be the same for every caller, each sending as its own`() {
        runBlocking {
            server.commands(Caller.ANONYMOUS).wait(OpenAccount("Erin"), "erin")
            val read = server.queries(Caller.of(subject = "frank")).snapshots(AccountState::class.java).state("erin")
            read!!.state.owner.assert().isEqualTo("Erin")
            read.operator.assert().isNull()
        }
    }

    // region problem
    @Test
    fun `a problem the server answered should be typed`() {
        server.http.post("/bank/account/carol/open-account", """{"owner":"Carol"}""").status.assert().isEqualTo(200)
        val invalid = server.http.post("/bank/account/carol/deposit", """{"amount":0}""").problem!!
        invalid.status.assert().isEqualTo(400)
        invalid.code.assert().isEqualTo("wow.command.invalid")
        val violation = invalid.violations.single()
        violation.path.assert().isEqualTo("amount")
        violation.code.assert().isEqualTo("positive")

        val rejected = server.http.post("/bank/account/carol/withdraw", """{"amount":1}""").problem!!
        rejected.status.assert().isEqualTo(409)
        rejected.code.assert().isEqualTo("bank.account.insufficient-balance")
        rejected.result!!.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        rejected.version.assert().isEqualTo(1)
        rejected.retry.assert().isEqualTo(Retry.NONE)
    }
    // endregion problem

    @Test
    fun `an answer that is no problem should have none`() {
        server.http.post("/bank/account/dave/open-account", """{"owner":"Dave"}""").problem.assert().isNull()
        server.http.get("/bank/account/nobody/state").apply {
            status.assert().isEqualTo(404)
            problem!!.status.assert().isEqualTo(404)
        }
    }

    @Test
    fun `a problem's other members should be its extensions, and Retry-After its retry after`() {
        val headers = mapOf(
            "content-type" to listOf("application/problem+json; charset=utf-8"),
            "Retry-After" to listOf(" 3 "),
        )
        val text = """{"type":"urn:wow:error:wow.node.closing","title":"Service Unavailable","code":"wow.node.closing",""" +
            """"retry":"same_request","balance":100,"limits":{"daily":5},"violations":[{"path":"amount"}]}"""
        val problem = Answer(503, headers, text).problem!!
        problem.status.assert().isEqualTo(503)
        problem.title.assert().isEqualTo("Service Unavailable")
        problem.detail.assert().isNull()
        problem.retry.assert().isEqualTo(Retry.SAME_REQUEST)
        problem.retryAfter.assert().isEqualTo(3L)
        problem.extensions.assert().isEqualTo(mapOf("balance" to 100, "limits" to mapOf("daily" to 5)))
        problem.violations.single().assert().isEqualTo(Violation.of("amount", "", ""))
        problem.result.assert().isNull()
        problem.version.assert().isNull()

        Answer(400, mapOf("Content-Type" to listOf("application/json")), """{"status":400}""").problem.assert().isNull()
        Answer(400, headers, """["not","an","object"]""").problem.assert().isNull()
        Answer(400, headers, "").problem.assert().isNull()
        Answer(500, headers, """{"title":5}""").problem!!.title.assert().isEmpty()
    }

    @Test
    fun `a problem's members of another type should be left out`() {
        val problemJson = mapOf("Content-Type" to listOf("application/problem+json"))
        val text = """{"status":"bad","title":"Bad Request","violations":{"path":"amount"},"result":"none",""" +
            """"version":"2"}"""
        val problem = Answer(400, problemJson, text).problem!!
        problem.status.assert().isEqualTo(400)
        problem.violations.assert().isEmpty()
        problem.result.assert().isNull()
        problem.version.assert().isNull()
        Answer(400, problemJson, """{"violations":[{"code":"positive"}]}""").problem!!.violations.single().assert()
            .isEqualTo(Violation.of("", "positive", ""))
        // Without a content type, a JSON object is no problem.
        Answer(400, emptyMap(), """{"status":400}""").problem.assert().isNull()
    }

    @Test
    fun `a problem should read as the client reads a peer's, Retry-After in seconds or as an HTTP date`() {
        val text = """{"type":"urn:wow:error:wow.node.closing","title":"Service Unavailable","status":503,""" +
            """"detail":"The node is closing.","code":"wow.node.closing","retry":"same_request","node":"n-1",""" +
            """"violations":[{"path":"a","code":"c","message":"m"}]}"""
        // A date already past: both readings clamp it to no wait, whenever each reads the clock. A date ahead would
        // be read as the seconds left until it, which two readings a second boundary apart tell differently.
        val date = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(1).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        val read = listOf("7", date).map { retryAfter ->
            val headers = mapOf(
                "Content-Type" to listOf("application/problem+json"),
                "Retry-After" to listOf(retryAfter)
            )
            val problem = Answer(503, headers, text).problem!!
            val client = Problems.read(503, HttpHeaders.of(headers) { _, _ -> true }, text.toByteArray())
            problem.status.assert().isEqualTo(client.status)
            problem.title.assert().isEqualTo(client.title)
            problem.detail.assert().isEqualTo(client.detail).isEqualTo("The node is closing.")
            problem.code.assert().isEqualTo(client.code)
            problem.violations.assert().isEqualTo(client.violations).isNotEmpty()
            problem.extensions.assert().isEqualTo(client.extensions).isEqualTo(mapOf("node" to "n-1"))
            problem.retry.assert().isEqualTo(client.retry)
            problem.version.assert().isEqualTo(client.version)
            problem.result.assert().isEqualTo(client.result)
            problem.retryAfter.assert().isEqualTo(client.retryAfter!!.inWholeSeconds)
            problem.retryAfter!!
        }
        read[0].assert().isEqualTo(7L)
        read[1].assert().isEqualTo(0L)
    }

    @Test
    fun `a closed server should close its typed clients`() {
        val closed = wowServer<BankServer>("dev")
        val commands = closed.commands(Caller.ANONYMOUS)
        closed.close()
        assertThrows<Exception> { runBlocking { commands.wait(OpenAccount("Gina"), "gina") } }
    }
}
