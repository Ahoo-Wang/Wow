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
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.example.quickstart.BankServer
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.Environment
import tools.jackson.databind.JsonNode
import kotlin.time.Duration.Companion.milliseconds

data class ParcelState(val address: String = "")

data class ShipParcel(val address: String)

data class ScheduleParcel(val parcelId: String, val day: String)

/** A fake peer of the shipping context, called by a service's client as a saga's synchronous call does. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FakePeerTest {
    private val shipping = fakePeer("shipping")
    private val client = WowClient.builder()
        .endpoints(EndpointResolver.of(emptyMap(), mapOf("shipping" to shipping.url)))
        .service("shop-service")
        .build()

    @AfterAll
    fun close() {
        client.close()
        shipping.close()
    }

    private fun commands(caller: Caller): CommandClient = client.commands.of(caller)

    @Test
    fun `a call should be recorded as the peer decodes it, and answered committed`() {
        val alice = Caller.of(tenantId = "acme", scope = Scope(ownerId = "alice"), subject = "alice")
        val result = runBlocking { commands(alice).wait(ShipParcel("1 Main St"), "parcel-1") }
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.ref.id.assert().isEqualTo("parcel-1")
        result.version.assert().isEqualTo(1)
        val call = shipping.called<ShipParcel>()
        call.command.assert().isEqualTo(ShipParcel("1 Main St"))
        call.commandType.assert().isEqualTo("shipping.parcel.ShipParcel")
        call.aggregateId.assert().isEqualTo("parcel-1")
        call.service.assert().isEqualTo("shop-service")
        call.tenantId.assert().isEqualTo("acme")
        call.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        call.operator!!.id.assert().isEqualTo("alice")
        call.requestId.assert().isEqualTo(result.requestId)
        call.toString().assert().contains("shipping.parcel.ShipParcel", "parcel-1")
        shipping.calls.assert().contains(call)
    }

    @Test
    fun `an answer should be the rule's, the aggregate a creation names none of given`() {
        shipping.answer { PeerAnswer.succeeded(id = "SO-1", version = 3) }
        try {
            val result = runBlocking { commands(Caller.ANONYMOUS).wait(ShipParcel("2 High St")) }
            result.ref.id.assert().isEqualTo("SO-1")
            result.version.assert().isEqualTo(3)
            shipping.called<ShipParcel>().aggregateId.assert().isNull()
        } finally {
            shipping.answer { PeerAnswer.succeeded() }
        }
        runBlocking { commands(Caller.ANONYMOUS).wait(ShipParcel("3 Low St")) }.ref.id.assert().isNotBlank()
        shipping.called<ShipParcel>()
    }

    @Test
    fun `a body that names its aggregate should be the call's aggregate`() {
        val request = CommandRequest.of(ScheduleParcel("parcel-9", "Monday"))
        runBlocking { commands(Caller.ANONYMOUS).wait(request) }.ref.id.assert().isEqualTo("parcel-9")
        shipping.called<ScheduleParcel>().aggregateId.assert().isEqualTo("parcel-9")
    }

    @Test
    fun `a rejection should reach the caller as a rejection with its code`() {
        shipping.answer { PeerAnswer.rejected("shipping.parcel.no-address", "No such address.") }
        try {
            val rejected = assertThrows<CommandRejectedException> {
                runBlocking { commands(Caller.ANONYMOUS).wait(ShipParcel("nowhere"), "parcel-2") }
            }
            rejected.code.assert().isEqualTo("shipping.parcel.no-address")
            rejected.result.error!!.message.assert().isEqualTo("No such address.")
            rejected.result.ref.id.assert().isEqualTo("parcel-2")
        } finally {
            shipping.answer { PeerAnswer.succeeded() }
        }
        shipping.called<ShipParcel>()
    }

    @Test
    fun `a rejection's status should be the one the peer's contract gives its code`() {
        val headers = mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to callOf("acme"))
        fun rejectedAs(answer: PeerAnswer): Answer {
            shipping.answer { answer }
            return WowHttp(
                shipping.url
            ).use { it.post("/shipping/parcel/parcel-6/ship-parcel", """{"address":"x"}""", headers) }
        }
        try {
            rejectedAs(PeerAnswer.rejected("shipping.parcel.no-address")).problem!!.apply {
                status.assert().isEqualTo(404)
                title.assert().isEqualTo("The address is unknown.")
                result!!.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            }
            rejectedAs(PeerAnswer.rejected("shipping.parcel.lost")).status.assert().isEqualTo(422)
            rejectedAs(PeerAnswer.rejected("shipping.parcel.lost", status = 409)).status.assert().isEqualTo(409)
            rejectedAs(PeerAnswer.timedOut()).apply {
                status.assert().isEqualTo(504)
                problem!!.retry.assert().isEqualTo(Retry.SAME_REQUEST)
            }
        } finally {
            shipping.answer { PeerAnswer.succeeded() }
        }
    }

    @Test
    fun `a timed out call should reach the caller as a wait timeout it may send again`() {
        shipping.answer { PeerAnswer.timedOut() }
        try {
            val timedOut = assertThrows<WowProblemException> {
                runBlocking { commands(Caller.ANONYMOUS).wait(ShipParcel("4 Slow St"), "parcel-3") }
            }
            timedOut.code.assert().isEqualTo("wow.wait.timeout")
            timedOut.retry.assert().isEqualTo(Retry.SAME_REQUEST)
        } finally {
            shipping.answer { PeerAnswer.succeeded() }
        }
        shipping.called<ShipParcel>()
    }

    @Test
    fun `a command whose class the class path lacks should be its JSON`() {
        val answer = WowHttp(shipping.url).post(
            "/shipping/parcel/parcel-4/return-parcel",
            """{"reason":"damaged"}""",
            mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to callOf("acme")),
        )
        answer.status.assert().describedAs(answer.text).isEqualTo(200)
        val call = shipping.calls.single { it.commandType == "shipping.parcel.ReturnParcel" }
        (call.command as JsonNode)["reason"].asString().assert().isEqualTo("damaged")
    }

    @Test
    fun `a request that names no service or no root command should be refused and not recorded`() {
        val before = shipping.calls.size
        WowHttp(shipping.url).use { http ->
            val anonymous = http.post("/shipping/parcel/parcel-5/ship-parcel", """{"address":"x"}""")
            anonymous.status.assert().isEqualTo(403)
            anonymous.problem!!.code.assert().isEqualTo("wow.auth.forbidden")
            val noCall = http.post("/shipping/parcel/parcel-5/ship-parcel", headers = mapOf(WowHeaders.SERVICE to "s"))
            noCall.status.assert().isEqualTo(403)
        }
        shipping.calls.size.assert().isEqualTo(before)
    }

    @Test
    fun `no call within the timeout should fail saying what was not called`() {
        val failure = assertThrows<AssertionError> { shipping.called<ScheduleParcel>(timeout = 50.milliseconds) }
        failure.message.assert().contains("ScheduleParcel", "shipping")
    }

    @Test
    fun `a context no contract declares should be refused`() {
        assertThrows<IllegalStateException> { fakePeer("nowhere") }.message.assert().contains("nowhere")
    }

    @Test
    fun `a server given a fake peer should call it at its URL`() {
        wowServer<BankServer>("dev", peers = listOf(shipping)).use { server ->
            server.bean<Environment>().getProperty("wow.peers.shipping.url").assert()
                .isEqualTo(shipping.url.toString())
        }
        shipping.toString().assert().contains("shipping", shipping.url.toString())
    }

    /** The `Wow-Call` of a root command in [tenantId], as a calling service writes it. */
    private fun callOf(tenantId: String): String = CallHeaders.format(
        CallContext("command-1", tenantId, Scope.NONE, Causation("command-1", "command-1")),
    )
}
