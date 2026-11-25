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
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.example.quickstart.BankServer
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.env.Environment
import tools.jackson.databind.JsonNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

data class ParcelState(val address: String = "")

data class ShipParcel(val address: String)

data class ScheduleParcel(val parcelId: String, val day: String)

/**
 * A fake peer of the shipping context, called by a service's client as a saga's synchronous call does; and one of the
 * returns context, of whose classes the test holds none. The fixture's contract keeps `ReturnParcel` in a context of its
 * own: a consumer may hold a context's contract with none of its classes and address it by JSON, not with only some of
 * them, which is a contract and types of different versions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FakePeerTest {
    private val shipping = fakePeer("shipping")
    private val returns = fakePeer("returns")
    private val client = WowClient.builder()
        .endpoints(EndpointResolver.of(emptyMap(), mapOf("shipping" to shipping.url)))
        .service("shop-service")
        .build()

    @AfterAll
    fun close() {
        client.close()
        shipping.close()
        returns.close()
    }

    private fun commands(caller: Caller): CommandClient = client.commands.of(caller)

    @Test
    fun `a call should be recorded as the peer decodes it, and answered committed`() {
        val alice = Caller.owner("alice", "acme")
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
            val rejected = assertRejected { commands(Caller.ANONYMOUS).wait(ShipParcel("nowhere"), "parcel-2") }
            rejected.code.assert().isEqualTo("shipping.parcel.no-address")
            rejected.error.message.assert().isEqualTo("No such address.")
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
    fun `an answer should give the caller the values, the violations and the problem it reads`() {
        val alice = Caller.owner("alice", "acme")
        try {
            // region answers
            shipping.answer { PeerAnswer.succeeded(results = mapOf("trackingNo" to "TN-1")) }
            runBlocking { commands(alice).wait(ShipParcel("1 Main St"), "parcel-1") }
                .results["trackingNo"].assert().isEqualTo("TN-1")

            shipping.answer {
                PeerAnswer.rejected(
                    "shipping.parcel.no-address",
                    violations = listOf(Violation.of("address", "unknown", "No such street.")),
                )
            }
            assertRejected { commands(alice).wait(ShipParcel("9 Nowhere"), "parcel-2") }
                .violations.single().path.assert().isEqualTo("address")

            shipping.answer { PeerAnswer.failed(FrameworkCodes.STORAGE_UNAVAILABLE) }
            val failed = assertThrows<WowProblemException> {
                runBlocking { commands(alice).wait(ShipParcel("1 Main St"), "parcel-3") }
            }
            failed.retryable.assert().isTrue()
            // endregion
        } finally {
            shipping.answer { PeerAnswer.succeeded() }
        }
        repeat(3) { shipping.called<ShipParcel>() }
    }

    @Test
    fun `a rejection should carry its extensions, and a failure its catalog status`() {
        try {
            shipping.answer {
                PeerAnswer.rejected("shipping.parcel.lost", "Lost.", extensions = mapOf("depot" to "D-4"))
            }
            val rejected = assertRejected { commands(Caller.ANONYMOUS).wait(ShipParcel("5 Far St"), "parcel-7") }
            rejected.error.extensions.assert().isEqualTo(mapOf("depot" to "D-4"))
            rejected.error.message.assert().isEqualTo("Lost.")
            shipping.answer { PeerAnswer.failed(FrameworkCodes.COMMAND_BUSY, "Too many commands.") }
            val busy = assertThrows<WowProblemException> {
                runBlocking { commands(Caller.ANONYMOUS).wait(ShipParcel("6 Far St"), "parcel-8") }
            }
            busy.code.assert().isEqualTo(FrameworkCodes.COMMAND_BUSY)
            busy.status.assert().isEqualTo(429)
            busy.result.assert().isNull()
        } finally {
            shipping.answer { PeerAnswer.succeeded() }
        }
        repeat(2) { shipping.called<ShipParcel>() }
        assertThrows<IllegalArgumentException> { PeerAnswer.failed("shipping.parcel.lost") }
            .message.assert().contains("no code of the error catalog")
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
        val answer = WowHttp(returns.url).post(
            "/returns/parcel/parcel-4/return-parcel",
            """{"reason":"damaged"}""",
            mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to callOf("acme")),
        )
        answer.status.assert().describedAs(answer.text).isEqualTo(200)
        val call = returns.calls.single { it.commandType == "returns.parcel.ReturnParcel" }
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
    fun `a request whose service is no service name should be refused as one that names none`() {
        val answer = WowHttp(shipping.url).use { http ->
            http.post(
                "/shipping/parcel/parcel-5/ship-parcel",
                """{"address":"x"}""",
                mapOf(WowHeaders.SERVICE to "shop service", WowHeaders.CALL to callOf("acme")),
            )
        }
        answer.status.assert().describedAs(answer.text).isEqualTo(403)
        answer.problem!!.code.assert().isEqualTo("wow.auth.forbidden")
    }

    @Test
    fun `a system command the callee's permits do not list should be refused as the real peer refuses it`(
        @TempDir contracts: Path,
    ) {
        // A real peer's call route checks the caller's permits (CommandHandler, CallPermits): with no
        // wow.callers.<service>.commands, a system command (wow.*) is 403 wow.call.command-forbidden.
        val (real, fake) = bankAnswers(contracts, RESTORE, "{}")
        for ((peer, answer) in listOf("the real bank-service" to real, "the fake bank-service" to fake)) {
            answer.status.assert().describedAs("$peer answered ${answer.text}").isEqualTo(403)
            answer.problem!!.code.assert().isEqualTo("wow.call.command-forbidden")
        }
        fake.problem!!.detail.assert().isEqualTo(real.problem!!.detail)
    }

    @Test
    fun `a system command the callee's callers list should be taken, by the real peer and the fake alike`(
        @TempDir contracts: Path,
    ) {
        val permit = mapOf("shop-service" to listOf("wow.lifecycle.RestoreAggregate"))
        val (real, fake) = bankAnswers(contracts, RESTORE, "{}", permit)
        // The real bank has no account acc-1 to restore: what it answers is no refusal of the call.
        real.problem?.code.assert().describedAs(real.text).isNotEqualTo("wow.call.command-forbidden")
        fake.status.assert().describedAs(fake.text).isEqualTo(200)
    }

    @Test
    fun `a domain command the callee's callers do not list should be refused, and one they list taken`() {
        fakePeer("shipping", callers = mapOf("shop-service" to listOf("ScheduleParcel"))).use { narrowed ->
            WowHttp(narrowed.url).use { http ->
                val headers = mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to callOf("acme"))
                val ship = http.post("/shipping/parcel/parcel-6/ship-parcel", """{"address":"x"}""", headers)
                ship.status.assert().describedAs(ship.text).isEqualTo(403)
                ship.problem!!.detail.assert().contains("shipping.parcel.ShipParcel")
                val schedule = http.post(
                    "/shipping/parcel/schedule-parcel",
                    """{"parcelId":"parcel-6","day":"monday"}""",
                    headers,
                )
                schedule.status.assert().describedAs(schedule.text).isEqualTo(200)
            }
            narrowed.calls.map { it.commandType }.assert().containsExactly("shipping.parcel.ScheduleParcel")
        }
    }

    @Test
    fun `a body that does not read as its command should be refused as the real peer refuses it`(
        @TempDir contracts: Path,
    ) {
        for (body in listOf("not json", """{"amount":1,"note":"x"}""", """{"amount":1,"amount":2}""")) {
            val (real, fake) = bankAnswers(contracts, DEPOSIT, body)
            for ((peer, answer) in listOf("the real bank-service" to real, "the fake bank-service" to fake)) {
                answer.status.assert().describedAs("$peer answered ${answer.text} to $body").isEqualTo(400)
                answer.problem!!.code.assert().isEqualTo("wow.command.malformed")
            }
            fake.problem!!.detail.assert().isEqualTo("The body is not a bank.account.Deposit.")
        }
    }

    @Test
    fun `a body its command's constraints refuse should be taken by the fake, which checks none`(
        @TempDir contracts: Path,
    ) {
        // The one difference the fake's KDoc states: Deposit's amount is @Positive, which only the real peer checks.
        val (real, fake) = bankAnswers(contracts, DEPOSIT, """{"amount":0}""")
        real.problem!!.code.assert().describedAs(real.text).isEqualTo("wow.command.invalid")
        fake.status.assert().describedAs(fake.text).isEqualTo(200)
    }

    @Test
    fun `an empty body should read as an empty object, as the peer reads it`() {
        WowHttp(shipping.url).use { http ->
            val headers = mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to callOf("acme"))
            // ShipParcel needs its address: {} does not read as one.
            // No body at all: WowHttp sends `{}` unless told none.
            val ship = http.post("/shipping/parcel/parcel-8/ship-parcel", json = null, headers = headers)
            ship.status.assert().describedAs(ship.text).isEqualTo(400)
            ship.problem!!.detail.assert().isEqualTo("The body is not a shipping.parcel.ShipParcel.")
        }
        // A fake of its own: the returns fixture's calls are another case's.
        fakePeer("returns").use { returns ->
            val answer = WowHttp(returns.url).use { http ->
                http.post(
                    "/returns/parcel/parcel-8/return-parcel",
                    json = null,
                    headers = mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to callOf("acme")),
                )
            }
            answer.status.assert().describedAs(answer.text).isEqualTo(200)
            (returns.calls.single().command as JsonNode).isEmpty.assert().isTrue()
        }
    }

    @Test
    fun `a body that is not JSON should be refused for a command whose class the class path lacks`() {
        val answer = WowHttp(returns.url).use { http ->
            http.post(
                "/returns/parcel/parcel-7/return-parcel",
                "not json",
                mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to callOf("acme")),
            )
        }
        answer.status.assert().describedAs(answer.text).isEqualTo(400)
        answer.problem!!.detail.assert().isEqualTo("The body is not a returns.parcel.ReturnParcel.")
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

    /**
     * What the real bank-service and a fake of it answer [body] posted to [path] by shop-service: the real one with
     * [callers] as its `wow.callers.<service>.commands`, the fake given the same, built from the real bank's own
     * Manifest, as the bank's api jar would carry it, under [contracts].
     */
    private fun bankAnswers(
        contracts: Path,
        path: String,
        body: String,
        callers: Map<String, List<String>> = emptyMap(),
    ): Pair<Answer, Answer> {
        val headers = mapOf(WowHeaders.SERVICE to "shop-service", WowHeaders.CALL to callOf("acme"))
        val permits = callers.flatMap { (service, commands) ->
            commands.mapIndexed { index, command -> "wow.callers.$service.commands[$index]" to command }
        }.toMap()
        val (real, manifest) = wowServer<BankServer>("dev", properties = permits).use { bank ->
            bank.internal.post(path, body, headers) to bank.bean<Manifest>()
        }
        val resource = contracts.resolve(PeerContracts.RESOURCE)
        Files.createDirectories(resource.parent)
        Files.write(resource, WowJson.mapper.writeValueAsBytes(ManifestTree.write(manifest)))
        val fake = withContracts(contracts) { fakePeer("bank", callers) }.use { bank ->
            WowHttp(bank.url).use { it.post(path, body, headers) }
        }
        return real to fake
    }

    /** What [block] makes with the contracts under [directory] on the thread's class path, as a test's are. */
    private fun <T> withContracts(directory: Path, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        return URLClassLoader(arrayOf(directory.toUri().toURL()), previous).use { loader ->
            thread.contextClassLoader = loader
            try {
                block()
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    /** The `Wow-Call` of a root command in [tenantId], as a calling service writes it. */
    private fun callOf(tenantId: String): String = CallHeaders.format(
        CallContext("command-1", tenantId, Scope.NONE, Causation("command-1", "command-1")),
    )

    private companion object {
        const val RESTORE = "/bank/account/acc-1/wow.lifecycle.restore-aggregate"
        const val DEPOSIT = "/bank/account/acc-1/deposit"
    }
}
