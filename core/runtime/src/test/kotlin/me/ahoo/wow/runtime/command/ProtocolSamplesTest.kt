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

package me.ahoo.wow.runtime.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.PublishedState
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.io.File

/**
 * The wire forms the protocols reference page shows (`documentation/docs/{en,zh}/reference/protocols.md`, design 08):
 * each sample in `src/test/resources/protocols/` is what Wow writes for the values below, so the page's examples are
 * the protocol as it is. A change of a wire form fails here; rerun with `WOW_UPDATE_PROTOCOL_SAMPLES=true` to rewrite
 * the samples, and review the page with them.
 */
class ProtocolSamplesTest {
    private val account = AggregateType("bank", "account")
    private val ref = account.ref("t1", "alice")
    private val operator = Operator("u-42", "t1")
    private val wait = WaitContext(
        "w-7",
        Origin("bank-1", "10.0.0.5:9090", "bank-service"),
        listOf(Hop.snapshot("bank")),
        1760950810000,
    )

    @Test
    fun `a published state event`() {
        val record = StateEventRecord(
            ref = ref,
            version = 2,
            commandId = "c-2",
            requestId = "r-2",
            commandType = "bank.account.Deposit",
            commitTime = 1760950800000,
            events = listOf(StateEventPayload("bank.account.Deposited", 1, tree("""{"amount":100}"""))),
            state = PublishedState(2, FINGERPRINT, tree("""{"owner":"Alice","balance":100}""")),
            scope = Scope("u-42"),
            headers = mapOf(RequestFacts.APP_ID to "teller-app"),
            operator = operator,
        )
        check("state-event", EnvelopeCodec.message(record).value)
    }

    @Test
    fun `a command sent to another context`() {
        val execution = ExecutionId("TransferSaga.onRequested", AggregateType("bank", "transfer").ref("t1", "tr-9"), 1)
        val wire = ClusterCodec.wire(
            "bank.account.Withdraw",
            Command(
                "c-3",
                "tr-9@1#0",
                ref,
                mapOf("amount" to 30),
                causation = Causation("c-1", "bank.transfer/t1/tr-9@1"),
                operator = operator,
            ),
            ReplyTo.of("bank", execution),
        )
        check("command", CommandMessages.command(wire, 5, wait).value)
    }

    @Test
    fun `a rejection receipt`() {
        val rejected = CommandRejected<Any>(
            "c-3",
            "bank.account.Withdraw",
            ref,
            ErrorInfo("bank.account.insufficient-balance", "The balance is 20."),
            Causation("c-1", "bank.transfer/t1/tr-9@1"),
            ExecutionId("TransferSaga.onRequested", AggregateType("bank", "transfer").ref("t1", "tr-9"), 1),
            operator,
            Scope("u-42"),
            version = 4,
        )
        check("command-receipt", CommandMessages.receipt(rejected).value)
    }

    @Test
    fun `a wait signal`() {
        val result = CommandResult.succeeded(
            commandId = "c-2",
            requestId = "r-2",
            correlationId = "c-2",
            ref = ref,
            hop = Hop.snapshot("bank"),
            function = FunctionInfo(FunctionKind.SNAPSHOT, "bank", "snapshot", "snapshot"),
            version = 2,
            signalTime = 1760950800042,
        )
        check("wait-signal", ClusterCodec.encode(WaitSignal(waitId = "w-7", result = result)))
    }

    private fun check(name: String, bytes: ByteArray) {
        val actual = pretty(WowJson.mapper.readTree(bytes)) + "\n"
        val file = File(SAMPLES, "$name.json")
        if (System.getenv(UPDATE) == "true") {
            file.parentFile.mkdirs()
            file.writeText(actual)
        }
        actual.assert().describedAs("$file, rewritten with $UPDATE=true").isEqualTo(file.readText())
    }

    private fun tree(json: String): JsonNode = WowJson.mapper.readTree(json)

    private companion object {
        const val UPDATE = "WOW_UPDATE_PROTOCOL_SAMPLES"

        /** A state schema fingerprint: the SHA-256 the kernel computes, in hex. */
        const val FINGERPRINT = "43bfa906ccc015f9dc85b5ad142a24dcb99668d69f98ebd9d1ae73471c97be4d"
        val SAMPLES = File("src/test/resources/protocols")

        /** [node] with two spaces of indentation and `"name": value`, as the page shows it. */
        fun pretty(node: JsonNode, indent: String = ""): String {
            val inner = "$indent  "
            return when {
                node.isObject && node.isEmpty -> "{}"
                node.isArray && node.isEmpty -> "[]"
                node.isObject -> node.properties().joinToString(",\n", "{\n", "\n$indent}") { (key, value) ->
                    "$inner${WowJson.mapper.writeValueAsString(key)}: ${pretty(value, inner)}"
                }

                node.isArray -> node.joinToString(",\n", "[\n", "\n$indent]") { "$inner${pretty(it, inner)}" }
                else -> WowJson.mapper.writeValueAsString(node)
            }
        }
    }
}
