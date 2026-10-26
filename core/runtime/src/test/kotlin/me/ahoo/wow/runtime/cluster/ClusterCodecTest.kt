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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import me.ahoo.wow.spi.wait.toContext
import me.ahoo.wow.spi.wait.toWire
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.ToStringSerializer
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal

/** Turns [ClusterCodecTest.Deposit] into its `toString`; registered for discovery by `ServiceLoader` in tests. */
class DiscoverableModule : SimpleModule("discoverable") {
    init {
        @Suppress("UNCHECKED_CAST")
        val serializer = ToStringSerializer.instance as ValueSerializer<ClusterCodecTest.Deposit>
        addSerializer(ClusterCodecTest.Deposit::class.java, serializer)
    }
}

/** The wire form of the cluster channel's forward (design 08 §3, §9; decisions D26, D48). */
class ClusterCodecTest {
    data class Deposit(val amount: Int, val memo: String? = null)
    data class Deposited(val amount: Int)
    data class Pay(val amount: BigDecimal, val extra: Map<String, Any?> = emptyMap())
    class Account {
        var balance = 0
    }

    private val model = aggregate<Account>("bank", "account") {
        initial { Account() }
        decide<Deposit>(Creation.ALLOWED) { _, body -> Decision.events(Deposited(body.amount)) }
        decide<Pay>(Creation.ALLOWED) { _, _ -> Decision.events(Deposited(0)) }
        evolve<Deposited> { balance += it.amount }
    }
    private val ref = model.type.ref("t1", "acc-1")
    private val command = Command(
        id = "c-1",
        requestId = "r-1",
        ref = ref,
        body = Deposit(5, "salary"),
        expectedVersion = 3,
        scope = Scope("owner-1", "space-1"),
        headers = mapOf("app" to "web"),
        causation = Causation("root", "bank.account/t1/acc-0@2"),
        operator = Operator("alice", "t0", crossScope = true),
    )
    private val wait = WaitContext(
        "w-1",
        Origin("node-1", "10.0.0.1:7070", "bank-service"),
        listOf(Hop.handled("bank.projector", "bank")),
        1_000L,
    )
    private val result = CommandResult(
        commandId = "c-1",
        requestId = "r-1",
        correlationId = "root",
        ref = ref,
        hop = Hop.committed("bank"),
        function = FunctionInfo(FunctionKind.COMMAND, "bank", "account", "Deposit"),
        status = CommandResult.Status.SUCCEEDED,
        version = 4,
        results = mapOf("receipt" to "x-1"),
        signalTime = 99L,
    )

    @Test
    fun `a forwarded command round-trips its wire form`() {
        val request =
            ForwardCommand(command = ClusterCodec.wire(model, command), wait = wait.toWire(), deadline = 500L)
        request.command.type.assert().isEqualTo("bank.account.Deposit")
        val read = ClusterCodec.decodeCommand(ClusterCodec.encode(request))
        read.assert().isEqualTo(request)
        read.wait!!.toContext().assert().isEqualTo(wait)
        val decoded = ClusterCodec.command(model, read.command)!!
        decoded.assert().isEqualTo(command.copyWith())
    }

    @Test
    fun `the codec registers the Kotlin module only, whatever else is on the class path`() {
        ClusterCodec.mapper.registeredModules().map { it.javaClass.name }
            .assert().containsExactly(KotlinModule::class.java.name)
        // DiscoverableModule is registered for discovery in this test class path, and would change the body.
        val discovering = JsonMapper.builder().findAndAddModules().build()
        discovering.registeredModules().map { it.javaClass.name }.assert().contains(DiscoverableModule::class.java.name)
        discovering.valueToTree<JsonNode>(command.body).isString.assert().isTrue()
        val body = ClusterCodec.wire(model, command).body
        body.isObject.assert().isTrue()
        body["amount"].asInt().assert().isEqualTo(5)
    }

    @Test
    fun `a forwarded command body keeps its numbers exact`() {
        val pay = Command("c-2", "r-2", ref, Pay(BigDecimal("2.50"), mapOf("fee" to BigDecimal("0.10"))))
        val request = ForwardCommand(command = ClusterCodec.wire(model, pay), deadline = 1L)
        val bytes = ClusterCodec.encode(request)
        bytes.decodeToString().assert().contains(""""amount":2.50""", """"fee":0.10""")
        val read = ClusterCodec.decodeCommand(bytes)
        read.command.body["amount"].decimalValue().assert().isEqualTo(BigDecimal("2.50"))
        // The untyped extra reads its decimal back as a BigDecimal of the same scale, never a Double.
        ClusterCodec.command(model, read.command).assert().isEqualTo(pay)
    }

    @Test
    fun `a command whose type the model does not know is not read`() {
        val wire = ClusterCodec.wire(model, command).copy(type = "bank.account.Withdraw")
        ClusterCodec.command(model, wire).assert().isNull()
    }

    @Test
    fun `a reply round-trips its wire form`() {
        val reply = ForwardReply(result = result, reached = listOf(result.copyWith(hop = Hop.handled("p", "bank"))))
        ClusterCodec.decodeReply(ClusterCodec.encode(reply)).assert().isEqualTo(reply)
        val rejected = result.copyWith(status = CommandResult.Status.REJECTED, error = ErrorInfo("bank.no", "No."))
        ClusterCodec.decodeReply(
            ClusterCodec.encode(ForwardReply(result = rejected))
        ).result.assert().isEqualTo(rejected)
        ClusterCodec.decodeReply(ClusterCodec.encode(ForwardReply.refused(Refusal.NOT_OWNER)))
            .refused.assert().isEqualTo(Refusal.NOT_OWNER)
    }

    @Test
    fun `a wait signal round-trips its wire form, its numbers exact (D122)`() {
        val signal = WaitSignal(
            waitId = "w-1",
            result = result.copyWith(results = mapOf("price" to BigDecimal("2.50"), "count" to Long.MAX_VALUE)),
        )
        val decoded = ClusterCodec.decodeSignal(ClusterCodec.encode(signal))
        decoded.assert().isEqualTo(signal)
        (decoded.result.results["price"] as BigDecimal).scale().assert().isEqualTo(2)
        val newer = String(ClusterCodec.encode(signal)).replaceFirst("{", """{"next":true,""")
        ClusterCodec.decodeSignal(newer.toByteArray()).assert().isEqualTo(signal)
    }

    @Test
    fun `a message of the next minor version is read, its unknown fields ignored`() {
        val json = """
            {
              "v": 2, "priority": "high",
              "command": {
                "type": "bank.account.Deposit", "id": "c-1", "requestId": "r-1", "trace": {"a": 1},
                "ref": {"type": {"context": "bank", "name": "account", "kind": "x"}, "tenantId": "t1", "id": "acc-1"},
                "body": {"amount": 5, "memo": "salary", "currency": "EUR"}
              },
              "wait": {
                "waitId": "w-1", "origin": {"node": "node-1", "address": "10.0.0.1:7070", "service": "bank-service",
                "zone": "z"}, "remaining": [{"stage": "HANDLED", "context": "bank", "subscription": "bank.projector",
                "group": "g"}], "deadline": 1000, "hops": 3
              },
              "deadline": 500
            }
        """.trimIndent()
        val read = ClusterCodec.decodeCommand(json.encodeToByteArray())
        read.v.assert().isEqualTo(2)
        read.wait!!.toContext().assert().isEqualTo(wait)
        ClusterCodec.command(model, read.command).assert().isEqualTo(
            Command("c-1", "r-1", ref, Deposit(5, "salary")),
        )
        val reply = """
            {"v": 2, "extra": true, "result": {"format": 1, "commandId": "c-1", "requestId": "r-1",
             "correlationId": "root", "ref": {"type": {"context": "bank", "name": "account"}, "tenantId": "t1",
             "id": "acc-1"}, "hop": {"stage": "COMMITTED", "context": "bank"}, "function": {"kind": "COMMAND",
             "context": "bank", "processor": "account", "name": "Deposit", "module": "m"}, "status": "SUCCEEDED",
             "version": 4, "results": {"receipt": "x-1"}, "signalTime": 99, "latency": 3}}
        """.trimIndent()
        ClusterCodec.decodeReply(reply.encodeToByteArray()).result.assert().isEqualTo(result)
    }

    @Test
    fun `the loopback channel goes through the wire form`(): Unit = runBlocking {
        val channel = LoopbackClusterChannel()
        val seen = ArrayList<ForwardCommand>()
        channel.bind("node-2:7070") { request ->
            seen += request
            ForwardReply(result = result)
        }
        val request = ForwardCommand(command = ClusterCodec.wire(model, command), deadline = 1L)
        channel.forward("node-2:7070", request).result.assert().isEqualTo(result)
        seen.single().assert().isEqualTo(request)
        channel.unbind("node-2:7070")
        runCatching { channel.forward("node-2:7070", request) }.exceptionOrNull()
            .assert().isInstanceOf(ClusterChannelException::class.java)
    }
}
