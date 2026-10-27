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
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.http.caller.CallAuthorizer
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.call.OutgoingCall
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The host's synchronous calls (decision D193): `wow.calls.*` gives the node its client of the other contexts'
 * gateways, at their internal ports, naming its service (`Wow-Service`, decision D300); `wow.callers.*` what
 * each calling service may call there. The [host] here calls the internal port of a [target], as another service
 * would, at the port the target bound; the target's public port refuses calls.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostCallsTest {
    private val target = startHost()
    private val host = startHost(
        "wow.peers.bank.url" to "http://127.0.0.1:${target.internalPort}",
        "wow.peer.timeout" to "5s",
    )

    /** A target whose list narrows the service's calls (decision D226). */
    private val narrowed = startHost(
        "wow.callers.ledger-service.commands[0]" to "wow.lifecycle.*",
        "wow.callers.ledger-service.commands[1]" to "bank.account.Deposit",
    )

    /** A target with a call authorizer bean (CoSec's, say) that denies every call (decision D226). */
    private val denying = startHost(
        sources = listOf(HostTestApp::class.java, DenyingCalls::class.java),
    )

    @AfterAll
    fun close() {
        denying.close()
        narrowed.close()
        host.close()
        target.close()
    }

    private val root = Causation("root-1", "ledger/(0)/l-1@1")

    @Test
    fun `the node's client should call a gateway as its service`(): Unit = runBlocking {
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
    }

    @Test
    fun `the public port should refuse Wow-Call, even naming a service`() {
        val call = CallHeaders.format(CallContext("call-2", Caller.DEFAULT_TENANT, Scope("dave"), root))
        target.post("/bank/account/open-account", """{"owner":"mallory"}""", mapOf(WowHeaders.CALL to call))
            .status.assert().isEqualTo(403)
        target.post(
            "/bank/account/open-account",
            """{"owner":"mallory"}""",
            mapOf(WowHeaders.CALL to call, WowHeaders.SERVICE to SERVICE),
        ).status.assert().isEqualTo(403)
    }

    @Test
    fun `the internal port should refuse a call that names no service`(): Unit = runBlocking {
        val forged = HttpCommandCaller(mapOf(BANK to URI.create("http://127.0.0.1:${target.internalPort}")), null)
        val command = Command(
            "call-3",
            "request-3",
            AggregateType(BANK, "account").unassigned(Caller.DEFAULT_TENANT),
            OpenAccount("mallory"),
            causation = root,
        )
        assertThrows<WowProblemException> {
            runBlocking { forged.call(OutgoingCall("bank.account.OpenAccount", command)) }
        }.message.assert().contains("403")
    }

    /** A call of [path] on [host]'s internal port, from the ledger service: its status and its problem's code. */
    private fun internalCall(host: Host, path: String, body: String): Pair<Int, String?> {
        val call = CallHeaders.format(
            CallContext("call-${path.hashCode()}", Caller.DEFAULT_TENANT, Scope("dave"), root)
        )
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${host.internalPort}$path"))
            .header("Content-Type", "application/json")
            .header(WowHeaders.SERVICE, SERVICE)
            .header(WowHeaders.CALL, call)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        val code = response.body().takeIf { it.isNotBlank() }?.let { JsonMapper.builder().build().readTree(it)["code"] }
        return response.statusCode() to code?.asString()
    }

    @Test
    fun `a service's commands should narrow the calls it may make (D226)`() {
        // Without a list, a domain command is called (the bank's account has no lifecycle routes; the edge's tests
        // call those).
        internalCall(target, "/bank/account/open-account", """{"owner":"olga"}""").second.assert()
            .isNotEqualTo(CallPermits.COMMAND_FORBIDDEN)
        // Listed, the gateway decides it: the account does not exist.
        internalCall(narrowed, "/bank/account/acc-1/deposit", """{"amount":1}""").second.assert()
            .isEqualTo("wow.aggregate.not-found")
        // The list narrows the domain commands to those it names.
        internalCall(narrowed, "/bank/account/open-account", """{"owner":"mallory"}""").assert()
            .isEqualTo(403 to CallPermits.COMMAND_FORBIDDEN)
    }

    @Test
    fun `a call authorizer should authorize the services' commands, the host's authorizer every other`() {
        val base = CommandAuthorizer.ALLOW_ALL
        WowAutoConfiguration.authorizerOf(base, null).assert().isSameAs(base)
        WowAutoConfiguration.authorizerOf(base, CallAuthorizer { _, _ -> Authorization.Denied("No.") }).assert()
            .isNotSameAs(base)
        // With a call authorizer bean (CoSec's, say), a service's call is that authorizer's.
        internalCall(denying, "/bank/account/open-account", """{"owner":"mallory"}""").assert()
            .isEqualTo(403 to "wow.auth.forbidden")
        // A caller that is no service is the host's authorizer's, which allows all.
        denying.post("/bank/account/open-account", """{"owner":"olga"}""").status.assert().isEqualTo(200)
    }

    @Test
    fun `a listed peer without a URL or a service to look up should refuse the start`() {
        val properties = WowProperties().apply { peers["mail"] = WowProperties.PeerTarget() }
        assertThrows<IllegalStateException> {
            PeerEndpoints.of(properties, PeerDiscoveries.load(javaClass.classLoader), { emptyMap() }) { null }
        }.message.assert().contains("Peer mail has no url")
    }

    private companion object {
        const val SERVICE = "ledger-service"
    }
}

@Configuration(proxyBeanMethods = false)
class DenyingCalls {
    @Bean
    fun callAuthorizer(): CallAuthorizer = CallAuthorizer { _, _ -> Authorization.Denied("No call is allowed here.") }
}
