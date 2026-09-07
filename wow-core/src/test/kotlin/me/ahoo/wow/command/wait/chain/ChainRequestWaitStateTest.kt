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

package me.ahoo.wow.command.wait.chain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandRequestId
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.completed
import me.ahoo.wow.command.wait.finalSignal
import me.ahoo.wow.command.wait.testAggregateId
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.command.wait.testSignal
import me.ahoo.wow.modeling.DefaultAggregateId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ChainRequestWaitStateTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mixed saga notifications still match commands without request metadata`(legacyFails: Boolean) {
        val state = chain()
        state.next(testSignal(CommandStage.PROCESSED, waitCommandId = "main"))
        state.next(testSignal(CommandStage.SAGA_HANDLED, waitCommandId = "main", commands = listOf("legacy")))
        state.next(
            testSignal(
                CommandStage.SAGA_HANDLED,
                waitCommandId = "main",
                commands = listOf("retry"),
                commandRequests = mapOf("retry" to CommandRequestId(testAggregateId(), "request")),
            )
        )
        state.next(
            testSignal(CommandStage.PROCESSED, waitCommandId = "main", commandId = "original", requestId = "request")
        ).completed.assert().isFalse()
        val completed = state.next(
            testSignal(
                CommandStage.PROCESSED,
                waitCommandId = "main",
                commandId = "legacy",
                requestId = "legacy-request",
                errorCode = if (legacyFails) "FAILED" else "Ok",
            )
        )
        completed.completed.assert().isTrue()
        completed.finalSignal!!.succeeded.assert().isEqualTo(!legacyFails)
    }

    @ParameterizedTest
    @ValueSource(strings = ["aggregate", "request"])
    fun `known request metadata prevents matching a conflicting command id`(difference: String) {
        val state = chain()
        state.next(testSignal(CommandStage.PROCESSED, waitCommandId = "main"))
        state.next(
            testSignal(
                CommandStage.SAGA_HANDLED,
                waitCommandId = "main",
                commands = listOf("retry"),
                commandRequests = mapOf("retry" to CommandRequestId(testAggregateId(), "request")),
            )
        )
        state.next(
            testSignal(
                CommandStage.PROCESSED,
                waitCommandId = "main",
                commandId = "retry",
                requestId = if (difference == "request") "other" else "request",
                aggregateId = if (difference == "aggregate") testAggregateId("other") else testAggregateId(),
            )
        ).completed.assert().isFalse()
        state.next(
            testSignal(CommandStage.PROCESSED, waitCommandId = "main", commandId = "original", requestId = "request")
        ).completed.assert().isTrue()
    }

    @ParameterizedTest
    @CsvSource("true, true", "true, false", "false, true", "false, false")
    fun `matches the original request outcome before or after the saga notification`(
        early: Boolean,
        succeeds: Boolean
    ) {
        val state = chain()
        state.next(testSignal(CommandStage.PROCESSED, waitCommandId = "main"))
        val original = testSignal(
            CommandStage.PROCESSED,
            waitCommandId = "main",
            commandId = "original",
            requestId = "request",
            errorCode = if (succeeds) "Ok" else "FAILED",
            result = mapOf("original" to true),
        )
        val saga = testSignal(
            CommandStage.SAGA_HANDLED,
            waitCommandId = "main",
            commands = listOf("retry"),
            commandRequests = mapOf("retry" to CommandRequestId(testAggregateId(), "request")),
        )
        state.next(if (early) original else saga).completed.assert().isFalse()
        val completed = state.next(if (early) saga else original)
        completed.completed.assert().isTrue()
        completed.finalSignal!!.succeeded.assert().isEqualTo(succeeds)
        completed.finalSignal!!.commandId.assert().isEqualTo("original")
        completed.finalSignal!!.result["original"].assert().isEqualTo(true)
    }

    @ParameterizedTest
    @ValueSource(strings = ["context", "aggregate", "tenant", "id", "request"])
    fun `request matching keeps each aggregate and request distinct`(difference: String) {
        val first = DefaultAggregateId(MaterializedNamedAggregate("context", "aggregate"), "id", "tenant")
        val second = when (difference) {
            "context" -> first.copy(namedAggregate = MaterializedNamedAggregate("other", "aggregate"))
            "aggregate" -> first.copy(namedAggregate = MaterializedNamedAggregate("context", "other"))
            "tenant" -> first.copy(tenantId = "other")
            "id" -> first.copy(id = "other")
            else -> first
        }
        val secondRequest = if (difference == "request") "other" else "request"
        val state = chain()
        state.next(testSignal(CommandStage.PROCESSED, waitCommandId = "main"))
        state.next(
            testSignal(
                CommandStage.SAGA_HANDLED,
                waitCommandId = "main",
                commands = listOf("retry-1", "retry-2"),
                commandRequests = mapOf(
                    "retry-1" to CommandRequestId(first, "request"),
                    "retry-2" to CommandRequestId(second, secondRequest),
                ),
            )
        )
        state.next(
            testSignal(
                CommandStage.PROCESSED,
                waitCommandId = "main",
                commandId = "original-1",
                aggregateId = first,
                requestId = "request",
            )
        ).completed.assert().isFalse()
        state.next(
            testSignal(
                CommandStage.PROCESSED,
                waitCommandId = "main",
                commandId = "duplicate-1",
                aggregateId = first,
                requestId = "request",
            )
        ).completed.assert().isFalse()
        state.next(
            testSignal(
                CommandStage.PROCESSED,
                waitCommandId = "main",
                commandId = "original-2",
                aggregateId = second,
                requestId = secondRequest,
            )
        ).completed.assert().isTrue()
    }

    @Test
    fun `multiple command ids for one request need only one outcome`() {
        val state = chain()
        state.next(testSignal(CommandStage.PROCESSED, waitCommandId = "main"))
        val request = CommandRequestId(testAggregateId(), "request")
        state.next(
            testSignal(
                CommandStage.SAGA_HANDLED,
                waitCommandId = "main",
                commands = listOf("retry-1", "retry-2"),
                commandRequests = mapOf("retry-1" to request, "retry-2" to request),
            )
        )
        state.next(
            testSignal(
                CommandStage.PROCESSED,
                waitCommandId = "main",
                commandId = "original",
                requestId = "request",
            )
        ).completed.assert().isTrue()
    }

    @Test
    fun `root progress does not complete a child sharing the root request id`() {
        val state = chain()
        state.next(
            testSignal(
                CommandStage.SAGA_HANDLED,
                waitCommandId = "main",
                requestId = "request",
                commands = listOf("retry"),
                commandRequests = mapOf("retry" to CommandRequestId(testAggregateId(), "request")),
            )
        )
        state.next(testSignal(CommandStage.PROCESSED, waitCommandId = "main", requestId = "request"))
            .completed.assert().isFalse()
        state.next(
            testSignal(
                CommandStage.PROCESSED,
                waitCommandId = "main",
                commandId = "original",
                requestId = "request",
            )
        ).completed.assert().isTrue()
    }

    private fun chain() = ChainWaitState(
        CommandWait.chain("main", testNamedFunction(), WaitingChainTail(CommandStage.PROCESSED, testNamedFunction())),
    )
}
