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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.call.ServiceCredential
import me.ahoo.wow.http.command.CallHeader
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.call.CommandCaller
import me.ahoo.wow.runtime.call.OutgoingCall
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/**
 * The host's synchronous calls (decision D193): `wow.calls.*` gives the node its client of the other contexts'
 * gateways and its service token, `wow.http.services.*` the service identities its public port trusts. The host here
 * calls its own gateway, as another service would.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostCallsTest {
    private val port = freePort()
    private val host = startHost(
        "server.port" to port,
        "wow.calls.contexts.bank.url" to "http://127.0.0.1:$port",
        "wow.calls.token" to TOKEN,
        "wow.calls.timeout" to "5s",
        "wow.http.services.ledger-service.tokens[0]" to TOKEN,
    )

    @AfterAll
    fun close() {
        host.close()
    }

    private val root = Causation("root-1", "ledger/(0)/l-1@1")

    @Test
    fun `the node's client should call a gateway with the service's token, trusted there`(): Unit = runBlocking {
        val caller = host.context.getBean(CommandCaller::class.java)
        val command = Command(
            id = "call-1",
            requestId = "request-1",
            ref = AggregateType(BANK, "account").unassigned(Caller.DEFAULT_TENANT),
            body = OpenAccount("dave"),
            scope = Scope("dave"),
            causation = root,
            operator = Operator("dave", Caller.DEFAULT_TENANT),
        )
        val result = caller.call(OutgoingCall("bank.account.OpenAccount", command))
        result.status.assert().describedAs(result.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
        result.commandId.assert().isEqualTo("call-1")
        // Called again, the same request is the same account (decision D191).
        val again = caller.call(OutgoingCall("bank.account.OpenAccount", command))
        again.ref.assert().isEqualTo(result.ref)
        again.duplicate.assert().isTrue()
        host.context.getBean(ServiceCredential::class.java).authorization().assert().isEqualTo("Bearer $TOKEN")
    }

    @Test
    fun `a caller without the service's token should not send Wow-Call`() {
        val call = CallHeader.format(CallContext("call-2", Caller.DEFAULT_TENANT, Scope("dave"), root))
        val answer = host.post("/bank/account/open-account", """{"owner":"mallory"}""", mapOf(WowHeaders.CALL to call))
        answer.status.assert().isEqualTo(403)
    }

    @Test
    fun `a context without a URL should be refused`() {
        val properties = WowProperties().apply { calls.contexts["mail"] = WowProperties.Calls.CallTarget() }
        assertThrows<IllegalStateException> {
            WowAutoConfiguration().wowCommandCaller(properties, ServiceCredential.NONE)
        }.message.assert().contains("wow.calls.contexts.mail.url")
    }

    @Test
    fun `no token should present no credential`(): Unit = runBlocking {
        WowAutoConfiguration().wowServiceCredential(WowProperties()).authorization().assert().isNull()
    }

    private companion object {
        const val TOKEN = "ledger-service-token-0123456789abcdef"
    }
}
