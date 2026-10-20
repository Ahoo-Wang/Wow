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

package me.ahoo.wow.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.client.wire.CallHeader
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.runtime.call.OutgoingCall
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.TraceContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The client of another context's command gateway (decision D193): its request, and the answers it reads. */
class HttpCommandCallerTest {
    /** A request the fake gateway took: its path, its headers and its body. */
    data class Taken(val path: String, val headers: Map<String, String>, val body: String)

    private val taken = CopyOnWriteArrayList<Taken>()

    /** How the gateway answers: its status, its content type and its body. */
    @Volatile
    private var answer: Triple<Int, String, ByteArray> = Triple(200, "application/json", ByteArray(0))

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                taken += Taken(
                    exchange.requestURI.rawPath,
                    exchange.requestHeaders.mapValuesTo(sortedMapOf(String.CASE_INSENSITIVE_ORDER)) {
                        it.value.joinToString()
                    },
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                val (status, type, body) = answer
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) exchange.responseBody.write(body)
            }
        }
        start()
    }
    private val base = URI.create("http://127.0.0.1:${server.address.port}")

    @AfterEach
    fun close() {
        server.stop(0)
    }

    private val box = AggregateType("mail", "box")

    private fun call(id: String = "a b/c", expectedVersion: Int? = null): OutgoingCall {
        val ref = if (id.isEmpty()) box.unassigned("t") else box.ref("t", id)
        return OutgoingCall(
            "mail.box.DeliverItems",
            Command(
                "c-1",
                "r-1",
                ref,
                mapOf("items" to listOf("mug")),
                expectedVersion,
                Scope("alice"),
                causation = ROOT
            ),
        )
    }

    private fun result(
        call: OutgoingCall,
        status: CommandResult.Status = CommandResult.Status.SUCCEEDED,
        error: ErrorInfo? = null,
        results: Map<String, Any> = emptyMap(),
    ) = CommandResult(
        commandId = call.command.id,
        requestId = call.command.requestId,
        correlationId = "root",
        ref = call.command.ref,
        hop = Hop.committed("mail"),
        function = FunctionInfo(FunctionKind.COMMAND, "mail", "box", "DeliverItems"),
        status = status,
        version = 1,
        error = error,
        signalTime = 1L,
        results = results,
    )

    private fun caller(credential: ServiceCredential = ServiceCredential.bearer(TOKEN), timeout: Duration = 5.seconds) =
        HttpCommandCaller(mapOf("mail" to base), credential, timeout = timeout)

    @Test
    fun `a call should be the command's route with its request id, its call and the service's credential`(): Unit =
        runBlocking {
            val call = call(expectedVersion = 3)
            answer = Triple(200, "application/json", WowJson.mapper.writeValueAsBytes(result(call)))
            val trace = object : TraceContext() {
                override val fields: Map<String, String> = mapOf("traceparent" to "00-trace-span-01")
            }
            val result = withContext(trace) { caller().call(call) }
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val request = taken.single()
            request.path.assert().isEqualTo("/mail/box/a%20b%2Fc/deliver-items")
            request.headers["Authorization"].assert().isEqualTo("Bearer $TOKEN")
            request.headers[WowHeaders.REQUEST_ID].assert().isEqualTo("r-1")
            request.headers[WowHeaders.IF_MATCH].assert().isEqualTo("\"3\"")
            request.headers["traceparent"].assert().isEqualTo("00-trace-span-01")
            WowJson.mapper.readTree(request.body)["items"][0].asString().assert().isEqualTo("mug")
            val context = CallHeader.parse(request.headers.getValue(WowHeaders.CALL))
            context.commandId.assert().isEqualTo("c-1")
            context.scope.assert().isEqualTo(Scope("alice"))
            context.causation.assert().isEqualTo(ROOT)
        }

    @Test
    fun `a creation that leaves its id to the target should take the route without an id`(): Unit = runBlocking {
        val call = call(id = "")
        answer = Triple(200, "application/json", WowJson.mapper.writeValueAsBytes(result(call)))
        caller(ServiceCredential.NONE).call(call)
        val request = taken.single()
        request.path.assert().isEqualTo("/mail/box/deliver-items")
        request.headers.containsKey("Authorization").assert().isFalse()
        request.headers.containsKey(WowHeaders.IF_MATCH).assert().isFalse()
    }

    @Test
    fun `a problem with a result should answer the result`(): Unit = runBlocking {
        val call = call()
        val rejected = result(call, CommandResult.Status.REJECTED, ErrorInfo("mail.box.full", "Full."))
        answer = Triple(422, "application/problem+json", WowJson.mapper.writeValueAsBytes(mapOf("result" to rejected)))
        caller().call(call).error!!.code.assert().isEqualTo("mail.box.full")
    }

    @Test
    fun `decimals in the results and in a rejection's extensions should keep their exact value (D122)`(): Unit =
        runBlocking {
            val call = call()
            val total = BigDecimal("2.50")
            answer = Triple(
                200,
                "application/json",
                WowJson.mapper.writeValueAsBytes(result(call, results = mapOf("total" to total))),
            )
            caller().call(call).results["total"].assert().isEqualTo(total)

            val rejected = result(
                call,
                CommandResult.Status.REJECTED,
                ErrorInfo("mail.box.full", "Full.", extensions = mapOf("limit" to BigDecimal("10.00"))),
            )
            answer = Triple(422, "application/problem+json", WowJson.mapper.writeValueAsBytes(mapOf("result" to rejected)))
            caller().call(call).error!!.extensions["limit"].assert().isEqualTo(BigDecimal("10.00"))
        }

    @Test
    fun `an answer without a result should fail the call`() {
        answer = Triple(503, "text/plain", "down".toByteArray())
        assertThrownBy<CallFailedException> { runBlocking { caller().call(call()) } }
        answer = Triple(401, "application/problem+json", """{"status":401}""".toByteArray())
        assertThrownBy<CallFailedException> { runBlocking { caller().call(call()) } }
        answer = Triple(200, "application/json", "not json".toByteArray())
        assertThrownBy<CallFailedException> { runBlocking { caller().call(call()) } }
    }

    @Test
    fun `a target that is down or unknown should fail the call`() {
        withRefusingPort { closed ->
            val down = HttpCommandCaller(
                mapOf("mail" to URI.create("http://127.0.0.1:$closed")),
                timeout = 500.milliseconds
            )
            assertThrownBy<CallFailedException> { runBlocking { down.call(call()) } }
        }
        val unknown = HttpCommandCaller(emptyMap())
        assertThrownBy<CallFailedException> { runBlocking { unknown.call(call()) } }
    }

    @Test
    fun `a caller should reach only the contexts its endpoints give an internal URL`() {
        val fixed = HttpCommandCaller(mapOf("mail" to base))
        fixed.reaches("mail").assert().isTrue()
        fixed.reaches("box").assert().isFalse()
        val publicOnly = HttpCommandCaller(
            EndpointResolver.of(mapOf("box" to base)),
            ServiceCredential.NONE,
            HttpCommandCaller.client(),
            5.seconds,
        )
        publicOnly.reaches("box").assert().isFalse()
        val discovered = HttpCommandCaller(
            EndpointResolver { context, port -> base.takeIf { context == "mail" && port == Port.INTERNAL } },
            ServiceCredential.NONE,
            HttpCommandCaller.client(),
            5.seconds,
        )
        discovered.reaches("mail").assert().isTrue()
        discovered.reaches("box").assert().isFalse()
    }

    @Test
    fun `a caller should take http URLs and a positive timeout, and a credential a token`(): Unit = runBlocking {
        assertThrownBy<IllegalArgumentException> { HttpCommandCaller(mapOf("mail" to URI.create("ftp://host"))) }
        assertThrownBy<IllegalArgumentException> { HttpCommandCaller(mapOf("mail" to base), timeout = Duration.ZERO) }
        assertThrownBy<IllegalArgumentException> { ServiceCredential.bearer(" ") }
        ServiceCredential.NONE.authorization().assert().isNull()
        HttpCommandCaller(mapOf("mail" to URI.create("https://mail.example"))).assert().isNotNull()
    }

    private companion object {
        const val TOKEN = "a-service-token-of-at-least-32-chars"
        val ROOT = Causation("root", "basket/t/alice@2")
    }
}
